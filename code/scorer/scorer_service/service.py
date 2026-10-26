"""FastAPI endpoint that stores scoring requests in a PostgreSQL queue."""

import hmac
import logging
import os
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Any
from uuid import UUID

from fastapi import FastAPI, HTTPException, Request, Response, Security, status
from fastapi.security import APIKeyHeader
from pydantic import BaseModel, ConfigDict, Field, JsonValue, field_validator
from sqlalchemy import DateTime, String, func, text
from sqlalchemy.dialects.postgresql import JSONB, insert
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from sqlalchemy.types import Uuid


log = logging.getLogger(__name__)
token_header = APIKeyHeader(
    name="X-Scorer-Token", scheme_name="scorerToken", auto_error=False
)


class Base(DeclarativeBase):
    pass


class ScoringTask(Base):
    __tablename__ = "scoring_queue"

    id: Mapped[UUID] = mapped_column(Uuid(as_uuid=True), primary_key=True)
    parameters: Mapped[dict[str, Any]] = mapped_column(JSONB, nullable=False)
    status: Mapped[str] = mapped_column(
        String(16), nullable=False, server_default=text("'PENDING'")
    )
    created_at: Mapped[datetime] = mapped_column(
        DateTime(timezone=True), nullable=False, server_default=func.now()
    )


class ScoringRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)

    id: UUID
    parameters: dict[str, JsonValue] = Field(min_length=1)

    @field_validator("parameters")
    @classmethod
    def target_is_not_an_input(cls, parameters):
        if "target" in parameters:
            raise ValueError("parameters must not contain target")
        return parameters


async def enqueue(
    sessions: async_sessionmaker[AsyncSession],
    application_id: UUID,
    parameters: dict[str, JsonValue],
) -> bool:
    """Store a task; return False only for a duplicate id with another payload."""
    statement = (
        insert(ScoringTask)
        .values(id=application_id, parameters=parameters)
        .on_conflict_do_nothing(index_elements=[ScoringTask.id])
        .returning(ScoringTask.id)
    )

    async with sessions.begin() as session:
        inserted = (await session.execute(statement)).scalar_one_or_none()
        if inserted is not None:
            return True

        existing = await session.get(ScoringTask, application_id)
        if existing is None:
            raise RuntimeError("Conflicting queue record disappeared")
        return existing.parameters == parameters


def create_app(
    database_url: str | None = None,
    scorer_token: str | None = None,
    session_factory: async_sessionmaker[AsyncSession] | None = None,
) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application: FastAPI):
        token = scorer_token if scorer_token is not None else os.getenv("SCORER_TOKEN")
        if not token:
            raise RuntimeError("SCORER_TOKEN must not be empty")
        application.state.scorer_token = token

        if session_factory is not None:
            application.state.sessions = session_factory
            yield
            return

        url = database_url or os.getenv(
            "SCORER_DATABASE_URL",
            "postgresql+asyncpg://smartcreditor:localdev@localhost:5432/smartcreditor",
        )
        if not url.startswith("postgresql+asyncpg://"):
            raise RuntimeError("SCORER_DATABASE_URL must use postgresql+asyncpg")

        engine = create_async_engine(url, pool_pre_ping=True)
        application.state.sessions = async_sessionmaker(
            engine, expire_on_commit=False
        )
        try:
            async with engine.begin() as connection:
                await connection.run_sync(Base.metadata.create_all)
            yield
        finally:
            await engine.dispose()

    application = FastAPI(
        title="SmartCreditor Scorer API", version="1.0.0", lifespan=lifespan
    )

    @application.post("/request", status_code=status.HTTP_202_ACCEPTED)
    async def accept_request(
        body: ScoringRequest,
        request: Request,
        supplied_token: str | None = Security(token_header),
    ):
        if supplied_token is None or not hmac.compare_digest(
            supplied_token, request.app.state.scorer_token
        ):
            raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED)

        try:
            accepted = await enqueue(
                request.app.state.sessions, body.id, body.parameters
            )
        except (SQLAlchemyError, RuntimeError):
            log.exception("Failed to persist scoring request %s", body.id)
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail="Scoring queue is unavailable",
            ) from None

        if not accepted:
            raise HTTPException(
                status_code=status.HTTP_409_CONFLICT,
                detail="A different request already exists for this id",
            )
        return Response(status_code=status.HTTP_202_ACCEPTED)

    return application


app = create_app()
