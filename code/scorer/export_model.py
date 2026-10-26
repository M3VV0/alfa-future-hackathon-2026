"""Rebuild the selected configuration with the checked-in feature code, without rerunning model search.

This trains on the same 8,000-row development split. The reused 2,000-row holdout
is diagnostic only, not independent evidence of real credit-risk quality.
"""
import asyncio
import hashlib
import importlib.metadata
import json
from pathlib import Path

import joblib
import numpy as np
import pandas as pd
from catboost import CatBoostRegressor
from sklearn.metrics import mean_absolute_error, mean_squared_error
from sklearn.model_selection import train_test_split

from features import CAT_COLUMNS, FEATURE_SETS, FEATURE_VERSION, make_features, model_input
from scorer import Scorer

ROOT = Path(__file__).parent
SEED = 20261022
SPEC = {"name": "anchor_engineered", "kind": "catboost", "features": "engineered", "params": {
    "iterations": 1000, "depth": 6, "learning_rate": 0.05, "l2_leaf_reg": 3.0,
    "random_strength": 1.0, "rsm": 1.0}}


def main():
    raw = pd.read_csv(ROOT / "synthetic_credit_users.csv", dtype=str, keep_default_na=False, skipinitialspace=True)
    raw.columns = raw.columns.str.strip()
    raw = raw.apply(lambda column: column.str.strip())
    target = pd.to_numeric(raw.target).astype(float)
    train, test = train_test_split(np.arange(len(raw)), test_size=.2, random_state=SEED,
        stratify=pd.cut(target, [-np.inf, 12, 35, np.inf], right=False))
    assert set(raw.iloc[train].applicant_inn).isdisjoint(raw.iloc[test].applicant_inn)
    prepared = model_input(make_features(raw.drop(columns="target")), SPEC["features"])
    cats = [name for name in CAT_COLUMNS if name in FEATURE_SETS[SPEC["features"]]]
    estimator = CatBoostRegressor(**SPEC["params"], loss_function="RMSE", random_seed=SEED,
        thread_count=2, task_type="CPU", used_ram_limit="1gb", border_count=128,
        one_hot_max_size=10, allow_writing_files=False, verbose=False, cat_features=cats)
    estimator.fit(prepared.iloc[train], target.iloc[train])
    prediction = estimator.predict(prepared.iloc[test], thread_count=2)
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest()
    bundle = {"estimator": estimator, "spec": SPEC, "feature_columns": list(prepared.columns),
        "feature_version": FEATURE_VERSION, "feature_source_sha256": sha(ROOT / "features.py"),
        "data_sha256": sha(ROOT / "synthetic_credit_users.csv"), "training_scope": "8000 development rows; selected-configuration rebuild"}
    path = ROOT / "artifacts" / "selected_model.joblib"
    joblib.dump(bundle, path)
    loaded = Scorer(path)
    actual = asyncio.run(loaded.predict(raw.iloc[test[0]].drop(labels="target").to_dict()))
    np.testing.assert_allclose(actual, prediction[0])
    manifest = {key: value for key, value in bundle.items() if key != "estimator"}
    manifest.update({"seed": SEED, "train_rows": len(train), "diagnostic_rows": len(test),
        "diagnostic_status": "reused synthetic holdout; no independent risk validation", "artifact_sha256": sha(path),
        "rmse_percentage_points": float(mean_squared_error(target.iloc[test], prediction) ** .5),
        "mae_percentage_points": float(mean_absolute_error(target.iloc[test], prediction)),
        "versions": {name: importlib.metadata.version(name) for name in ["numpy", "pandas", "scikit-learn", "catboost", "joblib"]}})
    (ROOT / "artifacts" / "selected_model.validation.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(json.dumps(manifest, indent=2))


if __name__ == "__main__":
    main()
