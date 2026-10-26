"""Deliver persisted scoring results to the Java callback endpoint."""

import asyncio
import json
import logging
import os
from collections.abc import Awaitable, Callable
from datetime import datetime, timezone
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
from typing import TypedDict

import httpx
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine

from scorer_service.service import Base, ScoringTask
from scorer_service.worker import ScoringResult


log = logging.getLogger(__name__)


class CallbackPayload(TypedDict):
    status: str
    months: int
    percent: Decimal
    sum: Decimal
    id: str


class CallbackError(RuntimeError):
    pass


def build_callback_payload(
    task: ScoringTask, stored: ScoringResult, success_status: str
) -> CallbackPayload:
    """Convert a raw model result and its original request to Java's contract."""
    if success_status not in {"OK", "OFFER"}:
        raise ValueError("SCORER_SUCCESS_STATUS must be OK or OFFER")

    months = task.parameters.get("term_months")
    if isinstance(months, bool) or not isinstance(months, int) or months <= 0:
        raise ValueError("term_months must be a positive integer")

    try:
        percent = Decimal(str(stored.result["interest_rate"]))
        amount = Decimal(str(task.parameters["requested_amount"]))
    except (KeyError, InvalidOperation, TypeError, ValueError) as error:
        raise ValueError("Stored scoring data cannot form a callback") from error

    if not percent.is_finite() or percent < 0:
        raise ValueError("interest_rate must be finite and non-negative")
    if not amount.is_finite() or amount <= 0:
        raise ValueError("requested_amount must be finite and positive")

    percent = percent.quantize(Decimal("0.0001"), rounding=ROUND_HALF_UP)
    amount = amount.quantize(Decimal("0.01"), rounding=ROUND_HALF_UP)
    if percent > Decimal("99999.9999"):
        raise ValueError("interest_rate exceeds Java contract limits")
    if amount > Decimal("999999999999999.99"):
        raise ValueError("requested_amount exceeds Java contract limits")

    return {
        "status": success_status,
        "months": months,
        "percent": percent,
        "sum": amount,
        "id": str(task.id),
    }


def encode_callback_payload(payload: CallbackPayload) -> bytes:
    """Encode Decimal values as JSON numbers without losing monetary precision."""
    return (
        "{"
        f'"status":{json.dumps(payload["status"])},'
        f'"months":{payload["months"]},'
        f'"percent":{format(payload["percent"], "f")},'
        f'"sum":{format(payload["sum"], "f")},'
        f'"id":{json.dumps(payload["id"])}'
        "}"
    ).encode("utf-8")


async def send_to_java(
    client: httpx.AsyncClient, payload: CallbackPayload
) -> None:
    response = await client.post(
        "/scored",
        content=encode_callback_payload(payload),
        headers={"Content-Type": "application/json"},
    )
    if response.status_code != 204:
        raise CallbackError(
            f"Java callback returned HTTP {response.status_code}"
        )


async def dispatch_one(
    sessions: async_sessionmaker[AsyncSession],
    sender: Callable[[CallbackPayload], Awaitable[None]],
    success_status: str,
) -> bool:
    """Send one unsent result and return whether a result was found."""
    statement = (
        select(ScoringResult)
        .where(ScoringResult.delivery_status == "PENDING")
        .order_by(ScoringResult.created_at, ScoringResult.application_id)
        .limit(1)
        .with_for_update(skip_locked=True)
    )

    async with sessions.begin() as session:
        stored = await session.scalar(statement)
        if stored is None:
            return False

        task = await session.get(ScoringTask, stored.application_id)
        if task is None:
            raise RuntimeError("Scoring task for persisted result disappeared")

        try:
            payload = build_callback_payload(task, stored, success_status)
        except ValueError:
            stored.delivery_status = "FAILED"
            log.exception(
                "Stored result cannot be delivered for application %s",
                stored.application_id,
            )
            return True

        await sender(payload)
        stored.delivery_status = "SENT"
        stored.sent_at = datetime.now(timezone.utc)
        return True


async def main() -> None:
    database_url = os.getenv(
        "SCORER_DATABASE_URL",
        "postgresql+asyncpg://smartcreditor:localdev@localhost:5432/smartcreditor",
    )
    if not database_url.startswith("postgresql+asyncpg://"):
        raise RuntimeError("SCORER_DATABASE_URL must use postgresql+asyncpg")

    java_base_url = os.getenv("JAVA_BASE_URL", "http://localhost:8080")
    if not java_base_url.startswith(("http://", "https://")):
        raise RuntimeError("JAVA_BASE_URL must use http or https")

    token = os.getenv("SCORER_TOKEN", "")
    if not token:
        raise RuntimeError("SCORER_TOKEN must not be empty")

    poll_seconds = float(os.getenv("SCORER_CALLBACK_POLL_SECONDS", "1"))
    timeout_seconds = float(os.getenv("SCORER_CALLBACK_TIMEOUT_SECONDS", "10"))
    if poll_seconds <= 0 or timeout_seconds <= 0:
        raise RuntimeError("Callback polling and timeout must be positive")

    engine = create_async_engine(database_url, pool_pre_ping=True)
    sessions = async_sessionmaker(engine, expire_on_commit=False)

    try:
        async with engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)

        async with httpx.AsyncClient(
            base_url=java_base_url,
            headers={"X-Scorer-Token": token},
            timeout=timeout_seconds,
        ) as client:
            async def sender(payload: CallbackPayload) -> None:
                await send_to_java(client, payload)

            while True:
                try:
                    processed = await dispatch_one(
                        sessions, sender, "OK"
                    )
                except (SQLAlchemyError, httpx.HTTPError, CallbackError, RuntimeError):
                    log.exception("Failed to deliver a scoring result")
                    processed = False
                if not processed:
                    await asyncio.sleep(poll_seconds)
    finally:
        await engine.dispose()


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(main())
