FROM python:3.13.7-slim@sha256:5f55cdf0c5d9dc1a415637a5ccc4a9e18663ad203673173b8cda8f8dcacef689
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 libstdc++6 && rm -rf /var/lib/apt/lists/*
COPY requirements-service.lock ./
RUN pip install --no-cache-dir --require-hashes --only-binary=:all: -r requirements-service.lock \
    && python -c "from uvicorn import run; from fastapi import FastAPI; from catboost import CatBoostRegressor"
COPY features.py scorer.py ./
COPY scorer_service ./scorer_service
COPY artifacts/selected_model.joblib artifacts/selected_model.validation.json ./artifacts/
RUN python -c "from scorer import Scorer; print(Scorer().model_version)"
USER 10001:10001
EXPOSE 8000
HEALTHCHECK --interval=5s --timeout=3s --start-period=40s --retries=12 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/health', timeout=2)" || exit 1
CMD ["python", "-m", "scorer_service"]
