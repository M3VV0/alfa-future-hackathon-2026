"""Tests for scoring and Java callback workers."""

import json
import sys
import unittest
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import AsyncMock
from uuid import UUID

import httpx
from sqlalchemy.dialects import postgresql


SCORER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCORER_DIR))

from scorer_service.callback_worker import (
    CallbackError,
    build_callback_payload,
    dispatch_one,
    encode_callback_payload,
    send_to_java,
)
from scorer_service.service import ScoringTask
from scorer_service.worker import ScoringResult, process_one


APPLICATION_ID = UUID("a051d4c4-6f76-4a56-a181-1ddcc6821177")
PARAMETERS = {
    "requested_amount": "1000000.50",
    "currency": "RUB",
    "term_months": 24,
}


class SessionStub:
    def __init__(self, task):
        self.task = task
        self.statement = None
        self.added = []

    async def scalar(self, statement):
        self.statement = statement
        return self.task

    def add(self, value):
        self.added.append(value)


class SessionFactoryStub:
    def __init__(self, session):
        self.session = session
        self.transaction_finished = False

    def begin(self):
        factory = self

        class Transaction:
            async def __aenter__(self):
                return factory.session

            async def __aexit__(self, exc_type, exc_value, traceback):
                factory.transaction_finished = True

        return Transaction()


class CallbackSessionStub:
    def __init__(self, stored, task=None):
        self.stored = stored
        self.task = task
        self.statement = None
        self.get_calls = []

    async def scalar(self, statement):
        self.statement = statement
        return self.stored

    async def get(self, model, application_id):
        self.get_calls.append((model, application_id))
        return self.task


class WorkerTests(unittest.IsolatedAsyncioTestCase):
    async def test_successful_task_is_locked_scored_and_saved(self):
        task = SimpleNamespace(
            id=APPLICATION_ID,
            parameters=PARAMETERS,
            status="PENDING",
        )
        session = SessionStub(task)
        sessions = SessionFactoryStub(session)
        scorer = SimpleNamespace(predict=AsyncMock(return_value=17.5))

        processed = await process_one(sessions, scorer)

        self.assertTrue(processed)
        scorer.predict.assert_awaited_once_with(PARAMETERS)
        self.assertEqual(task.status, "SUCCEEDED")
        self.assertTrue(sessions.transaction_finished)
        self.assertEqual(len(session.added), 1)
        result = session.added[0]
        self.assertIsInstance(result, ScoringResult)
        self.assertEqual(result.application_id, APPLICATION_ID)
        self.assertEqual(result.result, {"interest_rate": 17.5})

        sql = str(session.statement.compile(dialect=postgresql.dialect()))
        self.assertIn("FOR UPDATE SKIP LOCKED", sql)
        self.assertIn("scoring_queue.status", sql)

    async def test_empty_queue_does_not_call_scorer(self):
        session = SessionStub(task=None)
        sessions = SessionFactoryStub(session)
        scorer = SimpleNamespace(predict=AsyncMock())

        processed = await process_one(sessions, scorer)

        self.assertFalse(processed)
        scorer.predict.assert_not_awaited()
        self.assertEqual(session.added, [])
        self.assertTrue(sessions.transaction_finished)

    async def test_prediction_failure_marks_task_failed_without_result(self):
        task = SimpleNamespace(
            id=APPLICATION_ID,
            parameters=PARAMETERS,
            status="PENDING",
        )
        session = SessionStub(task)
        sessions = SessionFactoryStub(session)
        scorer = SimpleNamespace(
            predict=AsyncMock(side_effect=ValueError("invalid request"))
        )

        with self.assertLogs("scorer_service.worker", level="ERROR"):
            processed = await process_one(sessions, scorer)

        self.assertTrue(processed)
        self.assertEqual(task.status, "FAILED")
        self.assertEqual(session.added, [])
        self.assertTrue(sessions.transaction_finished)


class CallbackWorkerTests(unittest.IsolatedAsyncioTestCase):
    def task(self):
        return SimpleNamespace(id=APPLICATION_ID, parameters=PARAMETERS)

    def stored(self, interest_rate=17.56789):
        return SimpleNamespace(
            application_id=APPLICATION_ID,
            result={"interest_rate": interest_rate},
            delivery_status="PENDING",
            sent_at=None,
        )

    def test_builds_exact_java_contract_and_numeric_json(self):
        payload = build_callback_payload(self.task(), self.stored(), "OFFER")

        self.assertEqual(
            payload,
            {
                "status": "OFFER",
                "months": 24,
                "percent": Decimal("17.5679"),
                "sum": Decimal("1000000.50"),
                "id": str(APPLICATION_ID),
            },
        )
        encoded = encode_callback_payload(payload)
        decoded = json.loads(encoded)
        self.assertEqual(
            set(decoded), {"status", "months", "percent", "sum", "id"}
        )
        self.assertIsInstance(decoded["percent"], float)
        self.assertIsInstance(decoded["sum"], float)

    def test_rejects_missing_business_status_and_invalid_values(self):
        with self.assertRaisesRegex(ValueError, "SCORER_SUCCESS_STATUS"):
            build_callback_payload(self.task(), self.stored(), "")
        with self.assertRaisesRegex(ValueError, "interest_rate"):
            build_callback_payload(self.task(), self.stored(-1), "OK")

    async def test_unsent_result_is_locked_delivered_and_marked_sent(self):
        stored = self.stored()
        task = self.task()
        session = CallbackSessionStub(stored, task)
        sessions = SessionFactoryStub(session)
        sender = AsyncMock()

        processed = await dispatch_one(sessions, sender, "OFFER")

        self.assertTrue(processed)
        self.assertTrue(sessions.transaction_finished)
        self.assertIsNotNone(stored.sent_at)
        self.assertEqual(stored.delivery_status, "SENT")
        self.assertEqual(
            session.get_calls,
            [(ScoringTask, APPLICATION_ID)],
        )
        sender.assert_awaited_once()
        sql = str(session.statement.compile(dialect=postgresql.dialect()))
        self.assertIn("scoring_result.delivery_status", sql)
        self.assertIn("FOR UPDATE SKIP LOCKED", sql)

    async def test_empty_result_queue_does_not_send(self):
        session = CallbackSessionStub(stored=None)
        sessions = SessionFactoryStub(session)
        sender = AsyncMock()

        processed = await dispatch_one(sessions, sender, "OFFER")

        self.assertFalse(processed)
        sender.assert_not_awaited()
        self.assertTrue(sessions.transaction_finished)

    async def test_failed_delivery_remains_unsent_for_retry(self):
        stored = self.stored()
        session = CallbackSessionStub(stored, self.task())
        sessions = SessionFactoryStub(session)
        sender = AsyncMock(side_effect=CallbackError("HTTP 503"))

        with self.assertRaises(CallbackError):
            await dispatch_one(sessions, sender, "OFFER")

        self.assertIsNone(stored.sent_at)
        self.assertEqual(stored.delivery_status, "PENDING")
        self.assertTrue(sessions.transaction_finished)

    async def test_invalid_stored_result_is_failed_without_http_request(self):
        stored = self.stored(-1)
        session = CallbackSessionStub(stored, self.task())
        sessions = SessionFactoryStub(session)
        sender = AsyncMock()

        with self.assertLogs("scorer_service.callback_worker", level="ERROR"):
            processed = await dispatch_one(sessions, sender, "OFFER")

        self.assertTrue(processed)
        self.assertEqual(stored.delivery_status, "FAILED")
        self.assertIsNone(stored.sent_at)
        sender.assert_not_awaited()

    async def test_http_sender_uses_token_client_and_requires_204(self):
        requests = []

        def accepted(request):
            requests.append(request)
            return httpx.Response(204)

        payload = build_callback_payload(self.task(), self.stored(), "OFFER")
        async with httpx.AsyncClient(
            base_url="http://java",
            headers={"X-Scorer-Token": "secret"},
            transport=httpx.MockTransport(accepted),
        ) as client:
            await send_to_java(client, payload)

        self.assertEqual(requests[0].url.path, "/scored")
        self.assertEqual(requests[0].headers["X-Scorer-Token"], "secret")
        self.assertEqual(
            json.loads(requests[0].content),
            json.loads(encode_callback_payload(payload)),
        )

        async with httpx.AsyncClient(
            base_url="http://java",
            transport=httpx.MockTransport(lambda request: httpx.Response(409)),
        ) as client:
            with self.assertRaisesRegex(CallbackError, "HTTP 409"):
                await send_to_java(client, payload)

if __name__ == "__main__":
    unittest.main()
