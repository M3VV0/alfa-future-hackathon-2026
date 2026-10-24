"""Применение сохранённой обученной модели ставки к новым заявкам."""

import asyncio
import hashlib
import threading
from collections.abc import Mapping
from copy import deepcopy
from pathlib import Path

import joblib
import pandas as pd
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import FunctionTransformer

try:
    from .features import FEATURE_VERSION, make_features, model_input
except ImportError:
    from features import FEATURE_VERSION, make_features, model_input


class Scorer:
    def __init__(self, model_path=None):
        path = (
            Path(model_path)
            if model_path is not None
            else Path(__file__).parent / "artifacts" / "selected_model.joblib"
        )
        bundle = joblib.load(path)
        if bundle["feature_version"] != FEATURE_VERSION:
            raise ValueError("Версия признаков не соответствует модели")
        source_hash = hashlib.sha256(
            Path(__file__).with_name("features.py").read_bytes()
        ).hexdigest()
        if bundle["feature_source_sha256"] != source_hash:
            raise ValueError("Код признаков изменился после обучения модели")
        self.spec = bundle["spec"]
        self.feature_columns = bundle["feature_columns"]
        self.scoring_model = make_pipeline(
            FunctionTransformer(self._preprocess, validate=False),
            bundle["estimator"],
        )
        self._prediction_lock = threading.Lock()

    def _preprocess(self, frame):
        """Применить подготовку, согласованную с обученным estimator."""
        features = model_input(
            make_features(frame),
            self.spec["features"],
            self.spec["name"].startswith("ridge_"),
        )
        if list(features.columns) != self.feature_columns:
            raise ValueError("Схема признаков не соответствует модели")
        return features

    def _predict_one(self, row):
        """Выполнить весь pipeline в рабочем потоке с ограничением CPU."""
        frame = pd.DataFrame([row])
        parameters = {"thread_count": 2} if self.spec["kind"] == "catboost" else {}
        with self._prediction_lock:
            prediction = self.scoring_model.predict(frame, **parameters)
        return float(prediction[0])

    async def predict(self, row: Mapping | pd.Series) -> float:
        """Асинхронно вернуть ставку для одной записи без target."""
        if isinstance(row, pd.Series):
            if not row.index.is_unique:
                raise ValueError("Поля записи не должны повторяться")
            row = row.to_dict()
        if not isinstance(row, Mapping):
            raise TypeError("Ожидается одна запись: словарь или pandas.Series")
        if "target" in row:
            raise ValueError("Входная запись не должна содержать target")
        return await asyncio.to_thread(self._predict_one, deepcopy(dict(row)))
