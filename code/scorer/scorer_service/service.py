"""Authenticated FastAPI queue with supervised inference and callback workers."""
import asyncio
import hmac
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone
from typing import Any
from uuid import UUID

import httpx
from fastapi import FastAPI, HTTPException, Request, Response, Security
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from fastapi.security import APIKeyHeader
from pydantic import BaseModel, ConfigDict, Field, JsonValue, field_validator
from sqlalchemy import text
from sqlalchemy.dialects.postgresql import insert
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import async_sessionmaker

from scorer_service.config import Settings
from scorer_service.decision import normalize_parameters, same_json
from scorer_service.storage import Base, ScoringTask, ScoringResult, engine_for, initialize

log = logging.getLogger(__name__)
token_header = APIKeyHeader(name="X-Scorer-Token", scheme_name="scorerToken", auto_error=False)


class ScoringRequest(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)
    id: UUID
    parameters: dict[str, JsonValue] = Field(min_length=1)

    @field_validator("parameters")
    @classmethod
    def target_is_not_an_input(cls, parameters):
        if "target" in parameters or "id" in parameters:
            raise ValueError("Identifiers and target are not model parameters")
        return parameters


async def enqueue(sessions, application_id: UUID, parameters) -> bool:
    statement = insert(ScoringTask).values(id=application_id, parameters=parameters).on_conflict_do_nothing(
        index_elements=[ScoringTask.id]).returning(ScoringTask.id)
    async with sessions.begin() as session:
        if (await session.execute(statement)).scalar_one_or_none() is not None:
            return True
        existing = await session.get(ScoringTask, application_id)
        if existing is None:
            raise RuntimeError("Conflicting queue record disappeared")
        if not same_json(existing.parameters, parameters):
            return False
        # A Java callback timeout requests redelivery of the saved result, never another prediction.
        if existing.status == "FAILED":
            existing.status, existing.attempts, existing.last_error = "PENDING", 0, None
            existing.next_attempt_at = datetime.now(timezone.utc)
        if existing.status == "SUCCEEDED":
            result = await session.get(ScoringResult, application_id, with_for_update=True)
            if result is not None and result.delivery_status in {"SENT", "FAILED"}:
                result.delivery_status, result.attempts, result.last_error = "PENDING", 0, None
                result.next_attempt_at = datetime.now(timezone.utc)
        return True


async def worker_loop(work, poll_seconds):
    while True:
        try:
            processed = await work()
        except SQLAlchemyError:
            log.error("Worker database unavailable")
            processed = False
        if not processed:
            await asyncio.sleep(poll_seconds)


def create_app(database_url: str | None = None, scorer_token: str | None = None,
               session_factory=None, settings: Settings | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(application):
        from dataclasses import replace
        config = settings or Settings.from_env()
        if database_url is not None:
            config = replace(config, database_url=database_url)
        token = scorer_token if scorer_token is not None else config.token
        if not token.strip():
            raise RuntimeError("SCORER_TOKEN must not be empty")
        application.state.scorer_token = token
        application.state.workers = []
        if session_factory is not None:
            application.state.sessions = session_factory
            yield
            return
        from scorer import Scorer
        from scorer_service.worker import process_one
        from scorer_service.callback_worker import dispatch_one, send_to_java
        model = await asyncio.to_thread(Scorer)
        engine = engine_for(config)
        sessions = async_sessionmaker(engine, expire_on_commit=False)
        application.state.sessions = sessions
        application.state.model_version = model.model_version
        try:
            await initialize(engine, config.schema)
            async with httpx.AsyncClient(base_url=config.java_base_url, headers={"X-Scorer-Token": token},
                                         timeout=config.callback_timeout) as client:
                workers = [asyncio.create_task(worker_loop(lambda: process_one(sessions, model, config), config.poll_seconds)),
                    asyncio.create_task(worker_loop(lambda: dispatch_one(sessions, lambda payload: send_to_java(client, payload), config), config.poll_seconds))]
                application.state.workers = workers
                try:
                    yield
                finally:
                    for worker in workers:
                        worker.cancel()
                    await asyncio.gather(*workers, return_exceptions=True)
        finally:
            await engine.dispose()

    application = FastAPI(title="SmartCreditor Scorer API", version="1.1.0", lifespan=lifespan)

    @application.exception_handler(RequestValidationError)
    async def invalid_request(request, error):
        return JSONResponse(status_code=422, content={"detail": "Invalid scoring request"})

    @application.get("/health")
    async def health(request: Request):
        if any(worker.done() for worker in request.app.state.workers):
            return JSONResponse(status_code=503, content={"status": "worker_unavailable"})
        try:
            async with request.app.state.sessions() as session:
                await session.execute(text("SELECT 1"))
        except (SQLAlchemyError, RuntimeError):
            return JSONResponse(status_code=503, content={"status": "database_unavailable"})
        return {"status": "ok", "model_version": request.app.state.model_version}

    @application.post("/request", status_code=202)
    async def accept_request(body: ScoringRequest, request: Request, supplied_token: str | None = Security(token_header)):
        if supplied_token is None or not hmac.compare_digest(supplied_token.encode(), request.app.state.scorer_token.encode()):
            raise HTTPException(status_code=401)
        try:
            parameters = await asyncio.to_thread(normalize_parameters, body.parameters)
        except (ValueError, TypeError, KeyError, OverflowError):
            raise HTTPException(status_code=422, detail="Invalid model parameters") from None
        try:
            accepted = await enqueue(request.app.state.sessions, body.id, parameters)
        except (SQLAlchemyError, RuntimeError):
            log.error("Failed to persist scoring request id=%s", body.id)
            raise HTTPException(status_code=503, detail="Scoring queue unavailable") from None
        if not accepted:
            raise HTTPException(status_code=409, detail="A different request already exists for this id")
        return Response(status_code=202)

    return application


app = create_app()
