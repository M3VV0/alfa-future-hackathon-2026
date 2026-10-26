"""PostgreSQL worker that runs queued requests through the scoring model."""

import asyncio
import logging
import os
from datetime import datetime
from typing import Any
from uuid import UUID

from sqlalchemy import DateTime, ForeignKey, String, func, select, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import Mapped, mapped_column

from scorer import Scorer
from scorer_service.service import Base, ScoringTask


log = logging.getLogger(__name__)


class ScoringResult(Base):
    __tablename__ = "scoring_result"

    application_id: Mapped[UUID] = mapped_column(
        ForeignKey("scoring_queue.id", ondelete="CASCADE"), primary_key=True
    )
    result: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )
    delivery_status: Mapped[str] = mapped_column(
        String(16), nullable=False, server_default=text("'PENDING'")
    )
    sent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))


async def process_one(
    sessions: async_sessionmaker[AsyncSession], scorer: Scorer
) -> bool:
    """Process one available task and return whether a task was found."""
    statement = (
        select(ScoringTask)
        .where(ScoringTask.status == "PENDING")
        .order_by(ScoringTask.created_at, ScoringTask.id)
        .limit(1)
        .with_for_update(skip_locked=True)
    )

    async with sessions.begin() as session:
        task = await session.scalar(statement)
        if task is None:
            return False

        try:
            interest_rate = await scorer.predict(task.parameters)
        except Exception:
            task.status = "FAILED"
            log.exception("Scoring failed for application %s", task.id)
            return True

        session.add(
            ScoringResult(
                application_id=task.id,
                result={"interest_rate": interest_rate},
            )
        )
        task.status = "SUCCEEDED"
        return True


async def main() -> None:
    database_url = os.getenv(
        "SCORER_DATABASE_URL",
        "postgresql+asyncpg://smartcreditor:localdev@localhost:5432/smartcreditor",
    )
    if not database_url.startswith("postgresql+asyncpg://"):
        raise RuntimeError("SCORER_DATABASE_URL must use postgresql+asyncpg")

    poll_seconds = float(os.getenv("SCORER_WORKER_POLL_SECONDS", "1"))
    if poll_seconds <= 0:
        raise RuntimeError("SCORER_WORKER_POLL_SECONDS must be positive")

    engine = create_async_engine(database_url, pool_pre_ping=True)
    sessions = async_sessionmaker(engine, expire_on_commit=False)
    scorer = Scorer()

    try:
        async with engine.begin() as connection:
            await connection.run_sync(Base.metadata.create_all)

        while True:
            try:
                processed = await process_one(sessions, scorer)
            except SQLAlchemyError:
                log.exception("Scoring worker database error")
                processed = False
            if not processed:
                await asyncio.sleep(poll_seconds)
    finally:
        await engine.dispose()

if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    asyncio.run(main())
