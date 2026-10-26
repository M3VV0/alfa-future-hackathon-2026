"""Boundary tests: genuine rate interpretation, exact money and callback errors."""
import json
import sys
import unittest
from decimal import Decimal
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4

import httpx
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from scorer_service.callback_worker import CallbackError, build_callback_payload, encode_callback_payload, send_to_java
from scorer_service.config import Settings
from scorer_service.decision import make_callback, normalize_parameters, same_json

PARAMETERS = {"requested_amount": "1000000.50", "currency": "RUB", "term_months": 24,
    "payment_frequency": "monthly", "financing_purpose": "working_capital", "msp_category": "micro",
    "applicant_entity_type": "legal_entity", "snapshot_on": "2026-10-26"}


class BoundaryTests(unittest.IsolatedAsyncioTestCase):
    def test_rate_bands_compare_the_unrounded_real_prediction(self):
        for rate, status in [("11.99999", "DECLINED"), ("12", "OK"), ("24.99999", "OK"),
                ("25", "OFFER"), ("34.99999", "OFFER"), ("35", "DECLINED")]:
            with self.subTest(rate=rate):
                payload = make_callback(uuid4(), PARAMETERS, rate)
                self.assertEqual(payload["status"], status)
                self.assertEqual(payload["sum"], "1000000.50")
        for rate in ["nan", "inf", "-1", "100000", True]:
            with self.subTest(rate=rate), self.assertRaises(ValueError):
                make_callback(uuid4(), PARAMETERS, rate)

    def test_money_is_never_rounded_or_coerced_from_boolean_at_intake(self):
        result = normalize_parameters({**PARAMETERS, "requested_amount": "1000000.5"})
        self.assertEqual(result["requested_amount"], "1000000.50")
        for amount in [True, "0", "-1", "1.001", "nan", "1000000000000000"]:
            with self.subTest(amount=amount), self.assertRaises(ValueError):
                normalize_parameters({**PARAMETERS, "requested_amount": amount})
        with self.assertRaises(ValueError):
            normalize_parameters({**PARAMETERS, "term_months": True})
        self.assertFalse(same_json({"a": True}, {"a": 1}))
        self.assertTrue(same_json({"a": 1}, {"a": 1.0}))

    def test_callback_is_frozen_and_supports_legacy_raw_results(self):
        task = SimpleNamespace(id=uuid4(), parameters=PARAMETERS)
        result = SimpleNamespace(result={"interest_rate": 28.56789})
        payload = build_callback_payload(task, result)
        self.assertEqual(payload["status"], "OFFER")
        self.assertEqual(payload["percent"], "28.5679")
        stored = SimpleNamespace(result={"callback": payload, "interest_rate": 999})
        self.assertEqual(build_callback_payload(task, stored), payload)
        self.assertEqual(json.loads(encode_callback_payload(payload))["sum"], "1000000.50")

    async def test_http_sender_keeps_the_exact_callback_and_requires_204(self):
        requests = []
        async with httpx.AsyncClient(base_url="http://java", headers={"X-Scorer-Token": "secret"},
                transport=httpx.MockTransport(lambda req: requests.append(req) or httpx.Response(204))) as client:
            await send_to_java(client, make_callback(uuid4(), PARAMETERS, 20))
        self.assertEqual(requests[0].headers["X-Scorer-Token"], "secret")
        self.assertEqual(requests[0].url.path, "/scored")
        async with httpx.AsyncClient(base_url="http://java", transport=httpx.MockTransport(lambda req: httpx.Response(409))) as client:
            with self.assertRaises(CallbackError):
                await send_to_java(client, make_callback(uuid4(), PARAMETERS, 20))

    def test_invalid_worker_configuration_fails_before_start(self):
        for changes in [{"lease_seconds": 1}, {"max_attempts": 0}, {"poll_seconds": float("nan")},
                        {"schema": "unsafe;drop"}, {"java_base_url": "file:///tmp"}]:
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                Settings(**changes)
