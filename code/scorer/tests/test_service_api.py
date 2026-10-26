"""Tests for accepting scoring requests into the PostgreSQL queue."""

import sys
import unittest
from pathlib import Path
from types import SimpleNamespace
from uuid import UUID

from fastapi.testclient import TestClient
from sqlalchemy.exc import OperationalError


SCORER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCORER_DIR))

from scorer_service.service import ScoringTask, create_app, enqueue


APPLICATION_ID = "a051d4c4-6f76-4a56-a181-1ddcc6821177"
TOKEN = "test-scorer-token"
PARAMETERS = {"requested_amount": "1000000.50", "currency": "RUB", "term_months": 24,
    "payment_frequency": "monthly", "financing_purpose": "working_capital", "msp_category": "micro",
    "applicant_entity_type": "legal_entity", "snapshot_on": "2026-10-26"}


class ResultStub:
    def __init__(self, value):
        self.value = value

    def scalar_one_or_none(self):
        return self.value


class SessionStub:
    def __init__(self, inserted=UUID(APPLICATION_ID), existing=None, error=None):
        self.inserted = inserted
        self.existing = existing
        self.error = error
        self.statement = None
        self.get_calls = []

    async def execute(self, statement):
        if self.error is not None:
            raise self.error
        self.statement = statement
        return ResultStub(self.inserted)

    async def get(self, model, application_id):
        self.get_calls.append((model, application_id))
        return self.existing


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


def client(session):
    factory = SessionFactoryStub(session)
    api = create_app(scorer_token=TOKEN, session_factory=factory)
    return TestClient(api), factory


class EnqueueTests(unittest.IsolatedAsyncioTestCase):
    async def test_new_task_is_inserted_without_reading_an_existing_row(self):
        session = SessionStub()
        factory = SessionFactoryStub(session)

        accepted = await enqueue(factory, UUID(APPLICATION_ID), PARAMETERS)

        self.assertTrue(accepted)
        self.assertTrue(factory.transaction_finished)
        self.assertEqual(session.get_calls, [])
        statement = str(session.statement)
        self.assertIn("ON CONFLICT (id) DO NOTHING", statement)
        self.assertIn("RETURNING scoring_queue.id", statement)

    async def test_duplicate_id_with_equal_json_is_accepted(self):
        existing_parameters = {
            "currency": "RUB",
            "nested": {"a": 1, "b": [2, 3]},
        }
        requested_parameters = {
            "nested": {"b": [2, 3], "a": 1},
            "currency": "RUB",
        }
        session = SessionStub(
            inserted=None,
            existing=SimpleNamespace(status="PENDING", parameters=existing_parameters),
        )
        factory = SessionFactoryStub(session)

        accepted = await enqueue(
            factory, UUID(APPLICATION_ID), requested_parameters
        )

        self.assertTrue(accepted)
        self.assertTrue(factory.transaction_finished)
        self.assertEqual(
            session.get_calls,
            [(ScoringTask, UUID(APPLICATION_ID))],
        )

    async def test_duplicate_id_with_different_json_is_rejected(self):
        session = SessionStub(
            inserted=None,
            existing=SimpleNamespace(status="PENDING", parameters={"currency": "USD"}),
        )
        factory = SessionFactoryStub(session)

        accepted = await enqueue(factory, UUID(APPLICATION_ID), PARAMETERS)

        self.assertFalse(accepted)
        self.assertTrue(factory.transaction_finished)
        self.assertEqual(
            session.get_calls,
            [(ScoringTask, UUID(APPLICATION_ID))],
        )

    async def test_conflict_without_visible_existing_row_is_an_error(self):
        session = SessionStub(inserted=None, existing=None)
        factory = SessionFactoryStub(session)

        with self.assertRaisesRegex(
            RuntimeError, "Conflicting queue record disappeared"
        ):
            await enqueue(factory, UUID(APPLICATION_ID), PARAMETERS)

        self.assertTrue(factory.transaction_finished)
        self.assertEqual(
            session.get_calls,
            [(ScoringTask, UUID(APPLICATION_ID))],
        )


class ScorerApiTests(unittest.TestCase):
    def test_accepts_and_persists_request(self):
        api, factory = client(SessionStub())

        with api:
            response = api.post(
                "/request",
                json={"id": APPLICATION_ID, "parameters": PARAMETERS},
                headers={"X-Scorer-Token": TOKEN},
            )

        self.assertEqual(response.status_code, 202)
        self.assertTrue(factory.transaction_finished)
        self.assertIn("ON CONFLICT", str(factory.session.statement))

    def test_duplicate_payload_is_accepted_but_different_payload_conflicts(self):
        parameters = PARAMETERS.copy()
        same, _ = client(
            SessionStub(
                inserted=None, existing=SimpleNamespace(status="PENDING", parameters=parameters)
            )
        )
        different, _ = client(
            SessionStub(
                inserted=None,
                existing=SimpleNamespace(status="PENDING", parameters={"currency": "USD"}),
            )
        )

        with same, different:
            duplicate = same.post(
                "/request",
                json={"id": APPLICATION_ID, "parameters": parameters},
                headers={"X-Scorer-Token": TOKEN},
            )
            conflict = different.post(
                "/request",
                json={"id": APPLICATION_ID, "parameters": parameters},
                headers={"X-Scorer-Token": TOKEN},
            )

        self.assertEqual(duplicate.status_code, 202)
        self.assertEqual(conflict.status_code, 409)

    def test_token_is_required(self):
        api, factory = client(SessionStub())
        with api:
            response = api.post(
                "/request",
                json={"id": APPLICATION_ID, "parameters": {"currency": "RUB"}},
            )

        self.assertEqual(response.status_code, 401)
        self.assertIsNone(factory.session.statement)

    def test_invalid_request_formats_are_rejected_with_422(self):
        api, factory = client(SessionStub())
        invalid_payloads = [
            {"id": "not-a-uuid", "parameters": {"currency": "RUB"}},
            {"id": APPLICATION_ID},
            {"id": APPLICATION_ID, "parameters": []},
            {"id": APPLICATION_ID, "parameters": {}},
            {"id": APPLICATION_ID, "parameters": {"target": 20.0}},
            {
                "id": APPLICATION_ID,
                "parameters": {"currency": "RUB"},
                "unexpected": True,
            },
        ]

        with api:
            responses = [
                api.post(
                    "/request",
                    json=payload,
                    headers={"X-Scorer-Token": TOKEN},
                )
                for payload in invalid_payloads
            ]

        self.assertTrue(all(response.status_code == 422 for response in responses))
        self.assertIsNone(factory.session.statement)

    def test_database_failure_returns_503(self):
        error = OperationalError("statement", {}, Exception())
        api, _ = client(SessionStub(error=error))

        with self.assertLogs("scorer_service.service", level="ERROR"), api:
            response = api.post(
                "/request",
                json={"id": APPLICATION_ID, "parameters": PARAMETERS},
                headers={"X-Scorer-Token": TOKEN},
            )

        self.assertEqual(response.status_code, 503)


if __name__ == "__main__":
    unittest.main()
