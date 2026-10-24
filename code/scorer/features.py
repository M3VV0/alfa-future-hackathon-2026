"""Общие преобразования заявок для обучения и применения регрессора."""

import json
import re

import numpy as np
import pandas as pd

FEATURE_VERSION = 3
BASE_CAT_COLUMNS = [
    "payment_frequency",
    "financing_purpose",
    "msp_category",
    "applicant_entity_type",
]
DEMOGRAPHIC_COLUMNS = ["sex", "country_code_raw", "citizenship_kind"]
REGISTRY_COLUMNS = [
    "formation_method_name",
    "region_code",
    "location_missing",
    "msp_tenure_days",
    "msp_included_missing",
    "report_age_years",
    "is_newly_registered",
    "is_social_enterprise",
    "has_predecessors",
    "has_successors",
    "participants_count",
    "legal_events_missing",
    "legal_events_count",
    "reorganization_count",
    "days_since_last_legal_event",
]
DISCLOSURE_COLUMNS = [
    "products_missing",
    "products_count",
    "product_sections_count",
    "product_section",
    "innovative_products_count",
    "partnerships_missing",
    "partnerships_count",
    "days_since_latest_partnership",
    "days_since_oldest_partnership",
]
CAT_COLUMNS = (
    BASE_CAT_COLUMNS
    + ["formation_method_name", "region_code", "product_section"]
    + DEMOGRAPHIC_COLUMNS
)
RAW_NUMERIC = [
    "requested_amount",
    "term_months",
    "declared_collateral_value",
    "average_headcount",
    "registration_age",
]
ENGINEERED_NUMERIC = [
    "coverage",
    "collateral_missing",
    "headcount_missing",
    "headcount_zero",
    "annual_principal",
    "period_principal",
    "amount_per_employee",
    "annual_principal_per_employee",
    "log_amount",
    "log_collateral",
]
FEATURE_SETS = {
    "raw": RAW_NUMERIC + BASE_CAT_COLUMNS,
    "engineered": RAW_NUMERIC + BASE_CAT_COLUMNS + ENGINEERED_NUMERIC,
}
FEATURE_SETS["registry"] = FEATURE_SETS["engineered"] + REGISTRY_COLUMNS
FEATURE_SETS["disclosures"] = FEATURE_SETS["engineered"] + DISCLOSURE_COLUMNS
FEATURE_SETS["engineered_demographics"] = (
    FEATURE_SETS["engineered"] + DEMOGRAPHIC_COLUMNS
)
FEATURE_SETS["all_features"] = (
    FEATURE_SETS["registry"] + DISCLOSURE_COLUMNS + DEMOGRAPHIC_COLUMNS
)
FEATURE_SETS["all_without_demographics"] = FEATURE_SETS["registry"] + DISCLOSURE_COLUMNS
REQUIRED_COLUMNS = [
    "requested_amount",
    "term_months",
    "currency",
    "payment_frequency",
    "financing_purpose",
    "msp_category",
    "applicant_entity_type",
    "snapshot_on",
]


def _missing(value):
    return (
        value is None
        or (isinstance(value, str) and not value.strip())
        or (not isinstance(value, (dict, list)) and bool(pd.isna(value)))
    )


def _category(value):
    if _missing(value):
        return "__MISSING__"
    if not isinstance(value, (str, int, np.integer)):
        raise ValueError("Категория должна быть строкой или целым кодом")
    return str(value).strip()


def _flag(value, field):
    if _missing(value):
        return np.nan
    normalized = str(value).strip().lower()
    if normalized in {"true", "1", "1.0"}:
        return 1.0
    if normalized in {"false", "0", "0.0"}:
        return 0.0
    raise ValueError(f"Некорректный логический признак {field}")


def _json_block(value, field, expected):
    if _missing(value):
        return None
    if isinstance(value, str):
        try:
            value = json.loads(value)
        except (ValueError, TypeError) as error:
            raise ValueError(f"Некорректный JSON {field}") from error
    if value is None:
        return None
    if not isinstance(value, expected):
        raise ValueError(f"Некорректный тип блока {field}")
    if expected is list and any(not isinstance(item, dict) for item in value):
        raise ValueError(f"Записи {field} должны быть объектами")
    return value


def _date(value, field):
    if _missing(value):
        raise ValueError(f"Не заполнена дата {field}")
    if not isinstance(value, str) or not re.fullmatch(
        r"\d{4}-\d{2}-\d{2}", value.strip()
    ):
        raise ValueError(f"Некорректная дата {field}")
    return pd.Timestamp(value.strip())


def _registry_features(row, snapshot):
    """Признаки реестра доступны на дату среза; длительности измеряются в днях."""
    location = _json_block(row.get("location"), "location", dict)
    features = {
        "location_missing": float(location is None),
        "region_code": _category(
            location.get("region_code") if location is not None else None
        ),
        "formation_method_name": _category(row.get("formation_method_name")),
    }
    for field in [
        "is_newly_registered",
        "is_social_enterprise",
        "has_predecessors",
        "has_successors",
    ]:
        features[field] = _flag(row.get(field), field)
    participants = row.get("participants_count")
    features["participants_count"] = (
        np.nan if _missing(participants) else float(participants)
    )
    count = features["participants_count"]
    if not np.isnan(count) and (
        not np.isfinite(count) or count < 0 or count != int(count)
    ):
        raise ValueError("Некорректное participants_count")
    included = row.get("msp_included_on")
    features["msp_included_missing"] = float(_missing(included))
    features["msp_tenure_days"] = np.nan
    if not _missing(included):
        days = (snapshot - _date(included, "msp_included_on")).days
        if days < 0:
            raise ValueError("Дата включения в МСП позже среза")
        features["msp_tenure_days"] = float(days)
    year = row.get("report_year")
    features["report_age_years"] = np.nan
    if not _missing(year):
        year = float(year)
        # Проверяем завершённость года; фактическая доступность требует retrieved_at.
        if (
            not np.isfinite(year)
            or year != int(year)
            or year <= 0
            or year >= snapshot.year
        ):
            raise ValueError("Отчётный год недоступен на дату среза")
        features["report_age_years"] = float(snapshot.year - year)
    events = _json_block(row.get("legal_events"), "legal_events", list)
    features["legal_events_missing"] = float(events is None)
    visible = []
    for event in events or []:
        occurred = _date(event.get("event_date"), "legal_events.event_date")
        recorded = _date(event.get("record_date"), "legal_events.record_date")
        kind = _category(event.get("kind"))
        if kind == "__MISSING__":
            raise ValueError("Не заполнен вид правового события")
        # Поздняя запись события исключается даже при его исторической дате.
        if occurred <= snapshot and recorded <= snapshot:
            visible.append((occurred, kind))
    features["legal_events_count"] = np.nan if events is None else float(len(visible))
    features["reorganization_count"] = (
        np.nan
        if events is None
        else float(sum(kind == "reorganization_completed" for _, kind in visible))
    )
    features["days_since_last_legal_event"] = (
        float((snapshot - max(date for date, _ in visible)).days) if visible else np.nan
    )
    return features


def _disclosure_features(row, snapshot):
    """Добровольные раскрытия: неизвестный блок отличается от пустого списка."""
    products = _json_block(row.get("products"), "products", list)
    sections, innovative = [], []
    for product in products or []:
        code = product.get("okpd2_code")
        if not isinstance(code, str) or not re.fullmatch(
            r"\d{2}(?:\.\d{1,3})*", code.strip()
        ):
            raise ValueError("Некорректный код ОКПД2")
        sections.append(code.strip().split(".")[0])
        innovative.append(
            _flag(product.get("innovative_or_hightech"), "innovative_or_hightech")
        )
    # Сортировка делает категорию независимой от порядка записей в источнике.
    features = {
        "products_missing": float(products is None),
        "products_count": np.nan if products is None else float(len(products)),
        "product_sections_count": (
            np.nan if products is None else float(len(set(sections)))
        ),
        "product_section": (
            "__MISSING__"
            if products is None
            else ("__EMPTY__" if not sections else "|".join(sorted(set(sections))))
        ),
        "innovative_products_count": (
            np.nan
            if products is None or any(np.isnan(innovative))
            else float(sum(innovative))
        ),
    }
    partnerships = _json_block(row.get("partnerships"), "partnerships", list)
    dates = [
        _date(item.get("agreement_date"), "partnerships.agreement_date")
        for item in partnerships or []
    ]
    dates = [date for date in dates if date <= snapshot]
    features.update(
        {
            "partnerships_missing": float(partnerships is None),
            "partnerships_count": np.nan if partnerships is None else float(len(dates)),
            "days_since_latest_partnership": (
                float((snapshot - max(dates)).days) if dates else np.nan
            ),
            "days_since_oldest_partnership": (
                float((snapshot - min(dates)).days) if dates else np.nan
            ),
        }
    )
    return features


def make_features(frame):
    """Получить признаки из заявки без target и коэффициентов генератора."""
    frame = frame.copy()
    missing = set(REQUIRED_COLUMNS) - set(frame.columns)
    if missing:
        raise ValueError(f"Нет обязательных полей: {sorted(missing)}")
    if frame.empty:
        raise ValueError("Пустой набор заявок")
    x = pd.DataFrame(index=frame.index)
    for column in RAW_NUMERIC[:-1]:
        values = (
            frame[column] if column in frame else pd.Series(np.nan, index=frame.index)
        )
        values = values.replace(r"^\s*$", np.nan, regex=True)
        x[column] = pd.to_numeric(values, errors="raise").astype(float)
        if not np.isfinite(x[column].dropna()).all() or x[column].dropna().lt(0).any():
            raise ValueError(f"Недопустимое значение {column}")
    for column in ["requested_amount", "term_months"]:
        if x[column].isna().any() or x[column].le(0).any():
            raise ValueError(f"{column} должен быть положительным")
    for column in BASE_CAT_COLUMNS:
        x[column] = (
            frame[column]
            .astype("string")
            .str.strip()
            .fillna("__MISSING__")
            .replace("", "__MISSING__")
            .astype(str)
        )
    if not frame.currency.astype("string").str.strip().eq("RUB").fillna(False).all():
        raise ValueError("Поддерживаются заявки только в RUB")
    periods = x.payment_frequency.map({"monthly": 12, "quarterly": 4})
    if periods.isna().any():
        raise ValueError("Неизвестная частота платежей")
    snapshot = pd.to_datetime(frame.snapshot_on, format="%Y-%m-%d", errors="raise")
    if snapshot.isna().any():
        raise ValueError("Не заполнена дата среза")
    identifiers = []
    for column, length in [("ogrn", 13), ("ogrnip", 15)]:
        values = (
            frame[column] if column in frame else pd.Series(pd.NA, index=frame.index)
        )
        values = values.astype("string").str.strip().replace("", pd.NA)
        if not values.dropna().str.fullmatch(rf"\d{{{length}}}").all():
            raise ValueError(f"Некорректный формат {column}")
        identifiers.append(values)
    register = identifiers[0].fillna(identifiers[1])
    registration_year = 2000 + pd.to_numeric(register.str.slice(1, 3)).astype(float)
    x["registration_age"] = snapshot.dt.year - registration_year
    if x.registration_age.dropna().lt(0).any():
        raise ValueError("Год регистрации позже даты среза")
    x["coverage"] = x.declared_collateral_value / x.requested_amount
    x["collateral_missing"] = x.declared_collateral_value.isna().astype(float)
    x["headcount_missing"] = x.average_headcount.isna().astype(float)
    x["headcount_zero"] = x.average_headcount.eq(0).astype(float)
    # Это интенсивность погашения основного долга без предположения о ставке.
    x["annual_principal"] = x.requested_amount * 12 / x.term_months
    x["period_principal"] = x.annual_principal / periods
    employees = x.average_headcount.where(x.average_headcount.gt(0))
    x["amount_per_employee"] = x.requested_amount / employees
    x["annual_principal_per_employee"] = x.annual_principal / employees
    x["log_amount"] = np.log1p(x.requested_amount)
    x["log_collateral"] = np.log1p(x.declared_collateral_value)
    # Преобразования не обучаются на соседних строках и одинаковы внутри каждого фолда.
    extra = []
    for row, date in zip(frame.to_dict("records"), snapshot, strict=True):
        values = _registry_features(row, date)
        values.update(_disclosure_features(row, date))
        values.update(
            {column: _category(row.get(column)) for column in DEMOGRAPHIC_COLUMNS}
        )
        extra.append(values)
    x = pd.concat([x, pd.DataFrame(extra, index=frame.index)], axis=1)
    if np.isinf(x.select_dtypes(include="number").to_numpy()).any():
        raise ValueError("Переполнение при вычислении признаков")
    return x


def model_input(x, feature_set, logarithms=False):
    """Сохранить одинаковый порядок признаков при обучении и прогнозе."""
    result = x[FEATURE_SETS[feature_set]].copy()
    if logarithms:
        for column in ["requested_amount", "declared_collateral_value"]:
            result[column] = np.log1p(result[column])
    return result
