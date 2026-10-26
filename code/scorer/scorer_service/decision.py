"""Validate the model boundary and map its genuine prediction to the existing Java contract."""
import json
from copy import deepcopy
from decimal import Decimal, InvalidOperation, ROUND_HALF_UP
import pandas as pd
from features import make_features

VERSION = "rate-bands-v1"


def normalize_parameters(parameters):
    parameters = deepcopy(parameters)
    if "target" in parameters or "id" in parameters:
        raise ValueError("Identifiers and targets are not model inputs")
    for key in ["requested_amount", "declared_collateral_value"]:
        if key == "declared_collateral_value" and parameters.get(key) is None:
            continue
        if isinstance(parameters.get(key), bool):
            raise ValueError("Invalid monetary value")
        try:
            value = Decimal(str(parameters[key]))
            if (not value.is_finite() or value < 0 or (key == "requested_amount" and value <= 0)
                    or value >= Decimal("1000000000000000") or value != value.quantize(Decimal(".01"))):
                raise ValueError("Invalid monetary value")
        except (KeyError, InvalidOperation) as error:
            raise ValueError("Invalid monetary value") from error
        parameters[key] = format(value, ".2f")
    term = parameters.get("term_months")
    if isinstance(term, bool) or not isinstance(term, int) or not 1 <= term <= 2147483647:
        raise ValueError("term_months must be a positive int32")
    json.dumps(parameters, allow_nan=False)
    make_features(pd.DataFrame([parameters]))
    return parameters


def same_json(left, right):
    if isinstance(left, bool) or isinstance(right, bool):
        return type(left) is type(right) and left == right
    if isinstance(left, dict) and isinstance(right, dict):
        return left.keys() == right.keys() and all(same_json(left[key], right[key]) for key in left)
    if isinstance(left, list) and isinstance(right, list):
        return len(left) == len(right) and all(same_json(a, b) for a, b in zip(left, right))
    return left == right


def make_callback(application_id, parameters, prediction):
    try:
        rate = Decimal(str(prediction))
        amount = Decimal(str(parameters["requested_amount"]))
    except (KeyError, ValueError, InvalidOperation) as error:
        raise ValueError("Invalid scoring output") from error
    if not rate.is_finite() or not 0 <= rate <= Decimal("99999.9999") or not amount.is_finite() or amount <= 0 or amount >= Decimal("1000000000000000"):
        raise ValueError("Invalid scoring output")
    # Compare before rounding; price boundaries are prototype policy, not a real risk probability.
    status = "DECLINED" if rate < 12 or rate >= 35 else "OK" if rate < 25 else "OFFER"
    return {"id": str(application_id), "status": status, "months": parameters["term_months"],
            "percent": format(rate.quantize(Decimal(".0001"), rounding=ROUND_HALF_UP), ".4f"),
            "sum": format(amount, ".2f")}
