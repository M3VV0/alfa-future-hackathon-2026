"""Проверки доступности, схемы и независимости признаков заявки."""

import json
import sys
import unittest
from copy import deepcopy
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from features import CAT_COLUMNS, FEATURE_SETS, make_features, model_input


def application():
    """Минимальная заявка с проверяемыми раскрытиями на дату среза."""
    return {
        "requested_amount": 1000000,
        "term_months": 24,
        "currency": "RUB",
        "payment_frequency": "monthly",
        "financing_purpose": "equipment",
        "msp_category": "small",
        "applicant_entity_type": "legal_entity",
        "snapshot_on": "2026-10-10",
        "report_year": 2025,
        "msp_included_on": "2025-10-10",
        "participants_count": 2,
        "is_newly_registered": False,
        "has_predecessors": 0,
        "products": [
            {"okpd2_code": "62.01.11", "name": "Услуга", "innovative_or_hightech": True}
        ],
        "partnerships": [
            {
                "customer_name": "Заказчик",
                "agreement_number": "123",
                "agreement_date": "2026-10-01",
            }
        ],
        "legal_events": [
            {
                "kind": "reorganization_completed",
                "event_date": "2026-09-01",
                "record_date": "2026-09-02",
            }
        ],
        "location": {"region_code": "01", "city": "Город"},
        "sex": "female",
        "country_code_raw": "051",
        "citizenship_kind": "foreign",
    }


class FeatureTests(unittest.TestCase):
    def features(self, row):
        return make_features(pd.DataFrame([row]))

    def test_json_and_typed_blocks_are_equivalent(self):
        typed = application()
        serialized = deepcopy(typed)
        for field in ["products", "partnerships", "legal_events", "location"]:
            serialized[field] = json.dumps(typed[field], ensure_ascii=False)
        pd.testing.assert_frame_equal(self.features(typed), self.features(serialized))
        result = self.features(typed).iloc[0]
        self.assertEqual(result["region_code"], "01")
        self.assertEqual(result["country_code_raw"], "051")
        self.assertEqual(result["days_since_latest_partnership"], 9)
        self.assertEqual(result["msp_tenure_days"], 365)

    def test_missing_null_and_empty_blocks_are_distinct(self):
        for field in ["products", "partnerships", "legal_events"]:
            with self.subTest(field=field):
                row = application()
                row.pop(field)
                absent = self.features(row)
                for value in [None, "null", "", float("nan")]:
                    row[field] = value
                    pd.testing.assert_frame_equal(absent, self.features(row))
                row[field] = []
                empty = self.features(row)
                self.assertEqual(absent.iloc[0][f"{field}_missing"], 1)
                self.assertTrue(pd.isna(absent.iloc[0][f"{field}_count"]))
                self.assertEqual(empty.iloc[0][f"{field}_missing"], 0)
                self.assertEqual(empty.iloc[0][f"{field}_count"], 0)

    def test_future_records_never_change_available_features(self):
        row = application()
        expected = self.features(row)
        row["partnerships"].append({"agreement_date": "2026-10-11"})
        row["legal_events"].extend(
            [
                {
                    "kind": "reorganization_completed",
                    "event_date": "2026-10-01",
                    "record_date": "2026-10-11",
                },
                {
                    "kind": "reorganization_completed",
                    "event_date": "2026-10-11",
                    "record_date": "2026-10-01",
                },
            ]
        )
        pd.testing.assert_frame_equal(expected, self.features(row))
        for field, value in [("msp_included_on", "2026-10-11"), ("report_year", 2026)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.features({**application(), field: value})

    def test_completed_report_year_does_not_assume_release_calendar(self):
        row = {
            "requested_amount": 1000000,
            "term_months": 24,
            "currency": "RUB",
            "payment_frequency": "monthly",
            "financing_purpose": "equipment",
            "msp_category": "small",
            "applicant_entity_type": "legal_entity",
            "snapshot_on": "2026-06-10",
            "report_year": 2025,
        }
        self.assertEqual(self.features(row).iloc[0]["report_age_years"], 1)
        for year in [2026, 2027, 2025.5, float("inf"), 0]:
            with self.subTest(year=year), self.assertRaises(ValueError):
                self.features({**row, "report_year": year})

    def test_flags_zero_false_and_unknown(self):
        for value in [False, 0, "0", "False", 0.0]:
            row = application()
            row["is_newly_registered"] = value
            row["products"][0]["innovative_or_hightech"] = value
            result = self.features(row).iloc[0]
            self.assertEqual(result["is_newly_registered"], 0)
            self.assertEqual(result["innovative_products_count"], 0)
        self.assertTrue(
            pd.isna(
                self.features({**application(), "is_newly_registered": None}).iloc[0][
                    "is_newly_registered"
                ]
            )
        )
        with self.assertRaises(ValueError):
            self.features({**application(), "is_newly_registered": "2"})

    def test_malformed_blocks_fail_explicitly(self):
        invalid = [
            ("products", "[broken"),
            ("products", {}),
            ("partnerships", [1]),
            ("location", []),
            ("products", [{"okpd2_code": "invalid"}]),
            (
                "legal_events",
                [{"kind": "reorganization_completed", "event_date": "2026-01-01"}],
            ),
            ("partnerships", [{"agreement_date": "2026-02-30"}]),
            ("participants_count", -1),
            ("participants_count", 1.5),
        ]
        for field, value in invalid:
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                self.features({**application(), field: value})

    def test_identifiers_names_and_target_are_excluded(self):
        original = application()
        altered = deepcopy(original)
        for field in [
            "id",
            "target",
            "applicant_inn",
            "kpp",
            "legal_name",
            "source_xml",
            "annual_debt_budget",
        ]:
            altered[field] = "Запрещённый источник"
        altered["products"][0]["name"] = "Другое название"
        altered["partnerships"][0].update(
            customer_name="Иной заказчик", agreement_number="999"
        )
        altered["legal_events"][0].update(
            description="Служебный текст", source_status_code="999"
        )
        pd.testing.assert_frame_equal(self.features(original), self.features(altered))

    def test_row_locality_unknown_categories_and_stable_schema(self):
        first = application()
        other = {
            **application(),
            "requested_amount": 99,
            "sex": "unseen",
            "msp_category": "new",
        }
        batch = make_features(pd.DataFrame([first, other], index=[9, 4]))
        pd.testing.assert_frame_equal(
            batch.loc[[9]].reset_index(drop=True), self.features(first)
        )
        pd.testing.assert_frame_equal(
            batch.loc[[4]].reset_index(drop=True), self.features(other)
        )
        reversed_rows = make_features(pd.DataFrame([other, first], index=[4, 9]))
        pd.testing.assert_frame_equal(batch, reversed_rows.loc[batch.index])
        self.assertLess(batch.shape[1], 65)
        for name, columns in FEATURE_SETS.items():
            self.assertEqual(model_input(batch, name).columns.tolist(), columns)
            self.assertEqual(len(columns), len(set(columns)))
        self.assertTrue(set(CAT_COLUMNS).issubset(batch.columns))
        self.assertNotIn("sex", FEATURE_SETS["all_without_demographics"])
        self.assertIn("sex", FEATURE_SETS["all_features"])
        self.assertEqual(len({tuple(columns) for columns in FEATURE_SETS.values()}), 7)


if __name__ == "__main__":
    unittest.main()
