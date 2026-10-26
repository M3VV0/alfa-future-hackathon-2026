"""Bounded model worker: release database locks before actual inference."""
import asyncio
import logging
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from uuid import uuid4
from sqlalchemy import select
from scorer import Scorer
from scorer_service.config import Settings
from scorer_service.decision import VERSION, make_callback
from scorer_service.storage import Base, ScoringTask, ScoringResult

log = logging.getLogger(__name__)


async def process_one(sessions, scorer: Scorer, settings: Settings | None = None) -> bool:
    settings = settings or Settings.from_env()
    now = datetime.now(timezone.utc)
    async with sessions.begin() as session:
        task = await session.scalar(select(ScoringTask)
            .where(ScoringTask.status.in_(["PENDING", "PROCESSING"]), ScoringTask.next_attempt_at <= now)
            .order_by(ScoringTask.next_attempt_at, ScoringTask.created_at, ScoringTask.id)
            .limit(1).with_for_update(skip_locked=True))
        if task is None:
            return False
        if task.attempts >= settings.max_attempts:
            task.status, task.last_error = "FAILED", "PREDICTION_ATTEMPTS_EXHAUSTED"
            return True
        task.status, task.lease_token = "PROCESSING", uuid4()
        task.attempts += 1
        task.next_attempt_at = now + timedelta(seconds=settings.lease_seconds)
        application_id, token, parameters = task.id, task.lease_token, deepcopy(task.parameters)
    error, result = None, None
    try:
        prediction = await asyncio.wait_for(scorer.predict(parameters), timeout=settings.prediction_timeout)
        result = {"interest_rate": prediction, "callback": make_callback(application_id, parameters, prediction),
                  "model_version": getattr(scorer, "model_version", "unspecified"), "decision_version": VERSION}
    except (ValueError, TypeError):
        error = "INVALID_PREDICTION"
    except TimeoutError:
        error = "PREDICTION_TIMEOUT"
    except Exception:
        error = "PREDICTION_UNAVAILABLE"
    async with sessions.begin() as session:
        task = await session.get(ScoringTask, application_id, with_for_update=True)
        if task.lease_token != token or task.status != "PROCESSING":
            return True
        task.lease_token = None
        if error:
            task.last_error = error
            task.status = "FAILED" if error == "INVALID_PREDICTION" or task.attempts >= settings.max_attempts else "PENDING"
            task.next_attempt_at = datetime.now(timezone.utc) + timedelta(seconds=settings.retry_seconds)
            log.error("Scoring failed: application=%s code=%s", application_id, error)
        else:
            session.add(ScoringResult(application_id=application_id, result=result))
            task.status, task.last_error = "SUCCEEDED", None
    return True
