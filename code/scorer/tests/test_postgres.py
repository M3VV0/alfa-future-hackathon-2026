"""Real PostgreSQL queue/recovery tests. Each case creates and drops its own schema."""
import asyncio
import os
import sys
import unittest
from dataclasses import replace
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import uuid4

from sqlalchemy import func, select, text
from sqlalchemy.ext.asyncio import async_sessionmaker
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scorer import Scorer
from scorer_service.callback_worker import CallbackError, dispatch_one
from scorer_service.config import Settings
from scorer_service.decision import normalize_parameters
from scorer_service.service import enqueue
from scorer_service.storage import ScoringResult, ScoringTask, engine_for, initialize
from scorer_service.worker import process_one

DATABASE = os.getenv("SCORER_TEST_DATABASE_URL")
PARAMETERS = {"requested_amount": "1000000.50", "currency": "RUB", "term_months": 24,
    "payment_frequency": "monthly", "financing_purpose": "working_capital", "msp_category": "micro",
    "applicant_entity_type": "legal_entity", "snapshot_on": "2026-10-26", "average_headcount": 10}


@unittest.skipUnless(DATABASE, "Set SCORER_TEST_DATABASE_URL for real PostgreSQL integration tests")
class PostgresTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.config = Settings(database_url=DATABASE, schema="scorer_test_" + uuid4().hex,
            retry_seconds=.01, poll_seconds=.01, max_attempts=2)
        self.engine = engine_for(self.config)
        await initialize(self.engine, self.config.schema)
        self.sessions = async_sessionmaker(self.engine, expire_on_commit=False)

    async def asyncTearDown(self):
        async with self.engine.begin() as connection:
            await connection.execute(text(f'DROP SCHEMA "{self.config.schema}" CASCADE'))
        await self.engine.dispose()

    async def task(self):
        identity = uuid4()
        self.assertTrue(await enqueue(self.sessions, identity, normalize_parameters(PARAMETERS)))
        return identity

    async def fetch(self, model, identity):
        async with self.sessions() as session:
            return await session.get(model, identity)

    async def due(self, model, identity):
        async with self.sessions.begin() as session:
            value = await session.get(model, identity, with_for_update=True)
            value.next_attempt_at = datetime.now(timezone.utc) - timedelta(seconds=1)

    async def result(self):
        identity = await self.task()
        await process_one(self.sessions, SimpleNamespace(predict=AsyncMock(return_value=20.5)), self.config)
        return identity

    async def test_real_model_is_persisted_before_callback_and_replay_does_not_score_again(self):
        identity = await self.task()
        model = Scorer()
        await process_one(self.sessions, model, self.config)
        stored = await self.fetch(ScoringResult, identity)
        self.assertEqual(stored.result["model_version"], model.model_version)
        self.assertEqual(stored.result["callback"]["id"], str(identity))
        sender = AsyncMock()
        await dispatch_one(self.sessions, sender, self.config)
        sent = await self.fetch(ScoringResult, identity)
        self.assertEqual(sent.delivery_status, "SENT")
        self.assertTrue(await enqueue(self.sessions, identity, normalize_parameters(PARAMETERS)))
        self.assertFalse(await process_one(self.sessions, model, self.config))
        await dispatch_one(self.sessions, sender, self.config)
        replayed = await self.fetch(ScoringResult, identity)
        self.assertEqual(replayed.sent_at, sent.sent_at)
        self.assertEqual(replayed.result, stored.result)
        self.assertEqual(sender.await_count, 2)

    async def test_concurrent_intake_and_workers_compute_one_result(self):
        identity = uuid4()
        normalized = normalize_parameters(PARAMETERS)
        results = await asyncio.gather(*(enqueue(self.sessions, identity, normalized) for _ in range(4)))
        self.assertTrue(all(results))
        self.assertFalse(await enqueue(self.sessions, identity, {**normalized, "requested_amount": "999999.00"}))
        predictor = AsyncMock(return_value=20)
        await asyncio.gather(*(process_one(self.sessions, SimpleNamespace(predict=predictor), self.config) for _ in range(4)))
        self.assertEqual(predictor.await_count, 1)
        async with self.sessions() as session:
            self.assertEqual(await session.scalar(select(func.count()).select_from(ScoringResult)), 1)

    async def test_prediction_does_not_hold_a_database_row_lock(self):
        identity = await self.task()
        entered, release = asyncio.Event(), asyncio.Event()
        async def predict(parameters):
            entered.set()
            await release.wait()
            return 20
        job = asyncio.create_task(process_one(self.sessions, SimpleNamespace(predict=predict), self.config))
        try:
            await asyncio.wait_for(entered.wait(), 2)
            async with self.sessions.begin() as session:
                await session.execute(select(ScoringTask).where(ScoringTask.id == identity).with_for_update(nowait=True))
            self.assertTrue(await enqueue(self.sessions, identity, normalize_parameters(PARAMETERS)))
        finally:
            release.set()
            await job

    async def test_expired_prediction_lease_recovers_and_stale_owner_cannot_overwrite(self):
        identity = await self.task()
        entered, release = asyncio.Event(), asyncio.Event()
        async def old_prediction(parameters):
            entered.set()
            await release.wait()
            return 40
        old = asyncio.create_task(process_one(self.sessions, SimpleNamespace(predict=old_prediction), self.config))
        try:
            await asyncio.wait_for(entered.wait(), 2)
            await self.due(ScoringTask, identity)
            await process_one(self.sessions, SimpleNamespace(predict=AsyncMock(return_value=20)), self.config)
        finally:
            release.set()
            await old
        stored = await self.fetch(ScoringResult, identity)
        self.assertEqual(stored.result["interest_rate"], 20)
        self.assertEqual(stored.result["callback"]["status"], "OK")

    async def test_invalid_prediction_and_exhausted_errors_are_technical_failures(self):
        invalid = await self.task()
        await process_one(self.sessions, SimpleNamespace(predict=AsyncMock(return_value=float("nan"))), self.config)
        self.assertEqual((await self.fetch(ScoringTask, invalid)).status, "FAILED")
        self.assertIsNone(await self.fetch(ScoringResult, invalid))
        failed = await self.task()
        model = SimpleNamespace(predict=AsyncMock(side_effect=RuntimeError("unavailable")))
        await process_one(self.sessions, model, self.config)
        self.assertEqual((await self.fetch(ScoringTask, failed)).status, "PENDING")
        await self.due(ScoringTask, failed)
        await process_one(self.sessions, model, self.config)
        self.assertEqual((await self.fetch(ScoringTask, failed)).status, "FAILED")
        self.assertIsNone(await self.fetch(ScoringResult, failed))

    async def test_prediction_timeout_is_bounded_and_never_delivers_decline(self):
        identity = await self.task()
        async def predict(parameters):
            await asyncio.sleep(10)
        await process_one(self.sessions, SimpleNamespace(predict=predict), replace(self.config, prediction_timeout=.01))
        self.assertEqual((await self.fetch(ScoringTask, identity)).last_error, "PREDICTION_TIMEOUT")
        self.assertIsNone(await self.fetch(ScoringResult, identity))

    async def test_transient_delivery_retries_saved_callback_then_stops(self):
        identity = await self.result()
        sender = AsyncMock(side_effect=CallbackError(503))
        await dispatch_one(self.sessions, sender, self.config)
        self.assertEqual((await self.fetch(ScoringResult, identity)).delivery_status, "PENDING")
        await self.due(ScoringResult, identity)
        await dispatch_one(self.sessions, sender, self.config)
        stored = await self.fetch(ScoringResult, identity)
        self.assertEqual(stored.delivery_status, "FAILED")
        self.assertEqual(stored.attempts, 2)
        self.assertEqual(sender.await_args_list[0], sender.await_args_list[1])
        self.assertTrue(await enqueue(self.sessions, identity, normalize_parameters(PARAMETERS)))
        await dispatch_one(self.sessions, AsyncMock(), self.config)
        self.assertEqual((await self.fetch(ScoringResult, identity)).delivery_status, "SENT")

    async def test_permanent_callback_error_does_not_block_other_applications(self):
        first = await self.result()
        second = await self.result()
        await dispatch_one(self.sessions, AsyncMock(side_effect=CallbackError(409)), self.config)
        await dispatch_one(self.sessions, AsyncMock(), self.config)
        statuses = {(await self.fetch(ScoringResult, value)).delivery_status for value in [first, second]}
        self.assertEqual(statuses, {"FAILED", "SENT"})

    async def test_callback_does_not_hold_a_lock_and_recovers_after_expired_lease(self):
        identity = await self.result()
        entered, release = asyncio.Event(), asyncio.Event()
        async def old_sender(payload):
            entered.set()
            await release.wait()
            raise CallbackError(503)
        old = asyncio.create_task(dispatch_one(self.sessions, old_sender, self.config))
        try:
            await asyncio.wait_for(entered.wait(), 2)
            async with self.sessions.begin() as session:
                await session.execute(select(ScoringResult).where(ScoringResult.application_id == identity).with_for_update(nowait=True))
            await self.due(ScoringResult, identity)
            await dispatch_one(self.sessions, AsyncMock(), self.config)
        finally:
            release.set()
            await old
        self.assertEqual((await self.fetch(ScoringResult, identity)).delivery_status, "SENT")
