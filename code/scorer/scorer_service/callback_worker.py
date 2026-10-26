"""Deliver a committed result with bounded retries and crash recovery."""
import asyncio
import logging
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from uuid import uuid4
import httpx
from sqlalchemy import select
from scorer_service.config import Settings
from scorer_service.decision import make_callback
from scorer_service.storage import Base, ScoringTask, ScoringResult

log = logging.getLogger(__name__)


class CallbackError(RuntimeError):
    def __init__(self, status_code):
        self.status_code = status_code
        super().__init__(f"Java callback returned HTTP {status_code}")


def build_callback_payload(task, stored):
    # Freeze callback at inference time; legacy raw results can still be delivered.
    return deepcopy(stored.result.get("callback")) or make_callback(task.id, task.parameters, stored.result["interest_rate"])


def encode_callback_payload(payload):
    import json
    return json.dumps(payload, allow_nan=False, separators=(",", ":")).encode()


async def send_to_java(client: httpx.AsyncClient, payload):
    response = await client.post("/scored", content=encode_callback_payload(payload), headers={"Content-Type": "application/json"})
    if response.status_code != 204:
        raise CallbackError(response.status_code)


async def dispatch_one(sessions, sender, settings: Settings | None = None) -> bool:
    settings = settings or Settings.from_env()
    now = datetime.now(timezone.utc)
    async with sessions.begin() as session:
        stored = await session.scalar(select(ScoringResult)
            .where(ScoringResult.delivery_status.in_(["PENDING", "DELIVERING"]), ScoringResult.next_attempt_at <= now)
            .order_by(ScoringResult.next_attempt_at, ScoringResult.created_at, ScoringResult.application_id)
            .limit(1).with_for_update(skip_locked=True))
        if stored is None:
            return False
        if stored.attempts >= settings.max_attempts:
            stored.delivery_status, stored.last_error = "FAILED", "CALLBACK_ATTEMPTS_EXHAUSTED"
            return True
        task = await session.get(ScoringTask, stored.application_id)
        try:
            payload = build_callback_payload(task, stored)
        except (KeyError, ValueError, TypeError):
            stored.delivery_status, stored.last_error = "FAILED", "INVALID_CALLBACK"
            return True
        stored.delivery_status, stored.lease_token = "DELIVERING", uuid4()
        stored.attempts += 1
        stored.next_attempt_at = now + timedelta(seconds=settings.lease_seconds)
        application_id, token = stored.application_id, stored.lease_token
    code, permanent = None, False
    try:
        await asyncio.wait_for(sender(payload), timeout=settings.callback_timeout)
    except CallbackError as error:
        code = f"CALLBACK_HTTP_{error.status_code}"
        permanent = 400 <= error.status_code < 500 and error.status_code not in {408, 429}
    except (httpx.HTTPError, TimeoutError):
        code = "CALLBACK_UNAVAILABLE"
    except Exception:
        code = "CALLBACK_ERROR"
    async with sessions.begin() as session:
        stored = await session.get(ScoringResult, application_id, with_for_update=True)
        if stored.lease_token != token or stored.delivery_status != "DELIVERING":
            return True
        stored.lease_token = None
        if code:
            stored.last_error = code
            stored.delivery_status = "FAILED" if permanent or stored.attempts >= settings.max_attempts else "PENDING"
            stored.next_attempt_at = datetime.now(timezone.utc) + timedelta(seconds=settings.retry_seconds)
            log.warning("Callback failed: application=%s code=%s", application_id, code)
        else:
            stored.delivery_status, stored.last_error = "SENT", None
            stored.sent_at = stored.sent_at or datetime.now(timezone.utc)
    return True
