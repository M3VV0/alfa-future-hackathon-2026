"""Runtime configuration; no alternate/local scoring provider."""
import math
import os
import re
from dataclasses import dataclass, field
from urllib.parse import urlparse


@dataclass(frozen=True)
class Settings:
    database_url: str = field(default="postgresql+asyncpg://smartcreditor:localdev@localhost:5432/smartcreditor", repr=False)
    token: str = field(default="", repr=False)
    schema: str = "scorer"
    java_base_url: str = "http://localhost:8080"
    poll_seconds: float = .5
    retry_seconds: float = 5
    max_attempts: int = 5
    lease_seconds: float = 60
    prediction_timeout: float = 30
    callback_timeout: float = 10

    def __post_init__(self):
        if not self.database_url.startswith("postgresql+asyncpg://"):
            raise ValueError("SCORER_DATABASE_URL must use postgresql+asyncpg")
        if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", self.schema):
            raise ValueError("Invalid SCORER_DB_SCHEMA")
        address = urlparse(self.java_base_url)
        if address.scheme not in {"http", "https"} or not address.netloc or address.query or address.fragment:
            raise ValueError("JAVA_BASE_URL must be an HTTP base URL")
        values = [self.poll_seconds, self.retry_seconds, self.lease_seconds, self.prediction_timeout, self.callback_timeout]
        if any(not math.isfinite(value) or value <= 0 for value in values) or self.max_attempts < 1:
            raise ValueError("Worker intervals and limits must be positive and finite")
        if self.lease_seconds <= max(self.prediction_timeout, self.callback_timeout) + 5:
            raise ValueError("Lease must exceed processing/HTTP timeouts by more than 5 seconds")

    @classmethod
    def from_env(cls):
        return cls(database_url=os.getenv("SCORER_DATABASE_URL", cls.__dataclass_fields__["database_url"].default),
            token=os.getenv("SCORER_TOKEN", ""), schema=os.getenv("SCORER_DB_SCHEMA", "scorer"),
            java_base_url=os.getenv("JAVA_BASE_URL", "http://localhost:8080"),
            poll_seconds=float(os.getenv("SCORER_WORKER_POLL_SECONDS", ".5")),
            retry_seconds=float(os.getenv("SCORER_RETRY_SECONDS", "5")),
            max_attempts=int(os.getenv("SCORER_MAX_ATTEMPTS", "5")),
            lease_seconds=float(os.getenv("SCORER_LEASE_SECONDS", "60")),
            prediction_timeout=float(os.getenv("SCORER_PREDICTION_TIMEOUT_SECONDS", "30")),
            callback_timeout=float(os.getenv("SCORER_CALLBACK_TIMEOUT_SECONDS", "10")))
