"""PostgreSQL durable tasks and result outbox, isolated from Java tables."""
from datetime import datetime
from typing import Any
from uuid import UUID
from sqlalchemy import DateTime, ForeignKey, Integer, String, func, text
from sqlalchemy.dialects.postgresql import JSONB
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import Uuid
from scorer_service.config import Settings


class Base(DeclarativeBase):
    pass


class ScoringTask(Base):
    __tablename__ = "scoring_queue"
    id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    parameters: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    status: Mapped[str] = mapped_column(String(16), nullable=False, server_default=text("'PENDING'"))
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    next_attempt_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    lease_token: Mapped[UUID | None] = mapped_column(Uuid(as_uuid=True))
    last_error: Mapped[str | None] = mapped_column(String(64))


class ScoringResult(Base):
    __tablename__ = "scoring_result"
    application_id: Mapped[UUID] = mapped_column(ForeignKey("scoring_queue.id", ondelete="CASCADE"), primary_key=True)
    result: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    delivery_status: Mapped[str] = mapped_column(String(16), nullable=False, server_default=text("'PENDING'"))
    sent_at: Mapped[datetime | None] = mapped_column(DateTime(timezone=True))
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    next_attempt_at: Mapped[datetime] = mapped_column(DateTime(timezone=True), nullable=False, server_default=func.now())
    lease_token: Mapped[UUID | None] = mapped_column(Uuid(as_uuid=True))
    last_error: Mapped[str | None] = mapped_column(String(64))


def engine_for(settings: Settings):
    return create_async_engine(settings.database_url, pool_pre_ping=True,
        connect_args={"server_settings": {"search_path": settings.schema}})


async def initialize(engine, schema):
    # Serialize first-start DDL, including simultaneous API/worker processes.
    async with engine.begin() as connection:
        await connection.execute(text("SELECT pg_advisory_xact_lock(714619883421)"))
        await connection.execute(text(f'CREATE SCHEMA IF NOT EXISTS "{schema}"'))
        await connection.run_sync(Base.metadata.create_all)
        for table in ("scoring_queue", "scoring_result"):
            for column in ("attempts INTEGER NOT NULL DEFAULT 0", "next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now()",
                           "lease_token UUID", "last_error VARCHAR(64)"):
                await connection.execute(text(f'ALTER TABLE "{table}" ADD COLUMN IF NOT EXISTS {column}'))
            await connection.execute(text(f'CREATE INDEX IF NOT EXISTS "{table}_ready" ON "{table}" (next_attempt_at)'))
