"""Проверки применения обученной модели без состояния ноутбука."""

import asyncio
import sys
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import joblib
import numpy as np
import pandas as pd

SCORER_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCORER_DIR))
from features import make_features, model_input
from scorer import Scorer


class ScorerTests(unittest.IsolatedAsyncioTestCase):
    @classmethod
    def setUpClass(cls):
        cls.rows = pd.read_csv(SCORER_DIR / "synthetic_credit_users.csv", nrows=5,
                               dtype=str, keep_default_na=False, skipinitialspace=True)
        cls.rows.columns = cls.rows.columns.str.strip()
        cls.rows = cls.rows.apply(lambda column: column.str.strip()).drop(columns="target")
        cls.model = Scorer()

    async def test_single_predictions_match_trained_estimator(self):
        bundle = joblib.load(SCORER_DIR / "artifacts" / "selected_model.joblib")
        features = model_input(make_features(self.rows), self.model.spec["features"],
                               self.model.spec["kind"] == "ridge")
        expected = bundle["estimator"].predict(features)
        single = await asyncio.gather(*(self.model.predict(row.to_dict()) for _, row in self.rows.iterrows()))
        self.assertTrue(all(isinstance(value, float) for value in single))
        np.testing.assert_allclose(expected, single)
        self.assertAlmostEqual(single[0], await Scorer().predict(self.rows.iloc[0]))
        self.assertTrue(np.isfinite(single).all())
        self.assertIn(self.model.spec["kind"], {"ridge", "catboost"})

    async def test_unused_identifiers_do_not_change_prediction(self):
        row = self.rows.iloc[0].to_dict()
        altered = {**row, "id": "другой", "applicant_inn": "другой", "legal_name": "другое"}
        self.assertAlmostEqual(await self.model.predict(altered), await self.model.predict(row))

    async def test_target_and_batches_are_rejected(self):
        row = self.rows.iloc[0].to_dict()
        with self.assertRaisesRegex(ValueError, "target"):
            await self.model.predict({**row, "target": 15.0})
        for value in [self.rows, self.rows.iloc[:1], [row], None, "заявка"]:
            with self.subTest(type=type(value).__name__), self.assertRaises(TypeError):
                await self.model.predict(value)

    async def test_prediction_does_not_block_event_loop(self):
        started, release = threading.Event(), threading.Event()
        event_loop_thread = threading.get_ident()

        def blocking_prediction(*args, **kwargs):
            self.assertNotEqual(threading.get_ident(), event_loop_thread)
            started.set()
            if not release.wait(timeout=3):
                raise AssertionError("Event loop не освободил рабочий поток")
            return [17.5]

        with patch.object(self.model.scoring_model, "predict", side_effect=blocking_prediction):
            task = asyncio.create_task(self.model.predict(self.rows.iloc[0].to_dict()))
            try:
                self.assertTrue(await asyncio.wait_for(asyncio.to_thread(started.wait, 1), timeout=2))
                self.assertFalse(task.done())
            finally:
                release.set()
                result = await task
        self.assertEqual(result, 17.5)

    async def test_missing_and_zero_are_distinct(self):
        missing = self.rows.iloc[:1].copy()
        missing[["average_headcount", "declared_collateral_value"]] = None
        zero = missing.copy()
        zero[["average_headcount", "declared_collateral_value"]] = 0
        a, b = make_features(missing), make_features(zero)
        self.assertTrue(a.coverage.isna().all())
        self.assertTrue(b.coverage.eq(0).all())
        self.assertTrue(a.headcount_missing.eq(1).all())
        self.assertTrue(b.headcount_zero.eq(1).all())
        self.assertTrue(a.amount_per_employee.isna().all())
        self.assertTrue(b.amount_per_employee.isna().all())
        self.assertTrue(np.isfinite(await self.model.predict(missing.iloc[0])))

    async def test_new_category_and_optional_fields(self):
        row = self.rows.iloc[0].to_dict()
        row["financing_purpose"] = "new_purpose"
        for field in ["average_headcount", "declared_collateral_value", "ogrn", "ogrnip"]:
            row.pop(field)
        self.assertTrue(np.isfinite(await self.model.predict(row)))

    async def test_invalid_amount_and_frequency_fail(self):
        for field, value in [("requested_amount", 0), ("term_months", None),
                             ("currency", "USD"), ("payment_frequency", "daily")]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                row = self.rows.iloc[0].to_dict()
                row[field] = value
                await self.model.predict(row)

    def test_feature_source_mismatch_fails(self):
        bundle = joblib.load(SCORER_DIR / "artifacts" / "selected_model.joblib")
        bundle["feature_source_sha256"] = "outdated"
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "model.joblib"
            joblib.dump(bundle, path)
            with self.assertRaisesRegex(ValueError, "Код признаков"):
                Scorer(path)


if __name__ == "__main__":
    unittest.main()
