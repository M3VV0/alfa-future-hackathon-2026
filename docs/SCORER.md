# Java–scorer integration

Java sends `POST /request` to an external scorer and receives results at `POST /scored`. The Python model and scorer server are outside this integration. [OpenAPI contract](openapi/scorer.yaml).

## Scoring request

```http
POST http://localhost:8000/request
Content-Type: application/json
X-Scorer-Token: <SCORER_TOKEN>
```

```json
{
  "id": "a051d4c4-6f76-4a56-a181-1ddcc6821177",
  "parameters": {
    "requested_amount": "1000000.50",
    "currency": "RUB",
    "term_months": 24,
    "payment_frequency": "monthly",
    "financing_purpose": "equipment",
    "declared_collateral_value": null,
    "applicant_entity_type": "legal_entity",
    "applicant_inn": "7700000000",
    "msp_category": "micro",
    "snapshot_on": "2026-10-25",
    "average_headcount": 10
  }
}
```

- `id`: existing application UUID.
- `parameters`: input to `Scorer.predict`, excluding `id` and `target`; names and categories follow the [model contract](../code/scorer/docs/INTERFACE.md). Combines application parameters with stored `Business` data, including available registry and nested data.
- Amounts: exact decimal strings in rubles, supported by the model. Dates: `YYYY-MM-DD`; `snapshot_on` is the application's UTC creation date.
- Unknown data: `null`, never fabricated values. A stub may accept missing `msp_category`, but real prediction requires all mandatory model fields or a technical error. Data enrichment is outside this integration.

The scorer must persist the request and return **`202 Accepted` after commit**, then asynchronously run prediction and send the callback. Repeated requests with the same `id` and parameters return `202` without creating another task; a completed result may be sent again. Different parameters for the same `id` return `409`. This enables retries after network failures or Java restarts.

## Result callback

The scorer uses its configured Java backend URL:

```http
POST http://localhost:8080/scored
Content-Type: application/json
X-Scorer-Token: <SCORER_TOKEN>
```

Until credit decision logic is implemented, a rejection stub can return:

```json
{
  "status": "DECLINED",
  "months": null,
  "percent": null,
  "sum": null,
  "id": "a051d4c4-6f76-4a56-a181-1ddcc6821177"
}
```

Zero terms are also valid for rejection. Java stores rejection as a business outcome, not a technical error.

| Field | Format |
| --- | --- |
| `id` | Required UUID of a submitted application |
| `status` | Required: `OK`, `DECLINED`, or `OFFER` |
| `months` | Non-negative integer; required and positive for `OK` / `OFFER` |
| `percent` | Non-negative annual percentage (`15.5` = 15.5%); up to 5 integer and 4 fractional digits; required for `OK` / `OFFER` |
| `sum` | Non-negative ruble amount; up to 15 integer and 2 fractional digits; required and positive for `OK` / `OFFER` |

| HTTP | Meaning |
| --- | --- |
| `204` | Result saved or identical result already accepted |
| `400` | Invalid JSON, status, or terms |
| `401` | Missing/invalid service token; an empty configured token also rejects callbacks |
| `404` | Application not found |
| `409` | Application not yet sent or a different result already stored |

Decimals compare by value: `15` and `15.0000` are equal. Duplicate callbacks preserve the first result timestamp; conflicting callbacks never overwrite it. Late results for sent tasks are accepted even after a technical timeout.

A separate Spring Security chain protects only `POST /scored` with the service token. Browser JWT and CSRF are not required; client POST protection is unchanged. The token is never logged.

## Java persistence and execution

- The application, immutable request snapshot, and `CreditApplicationTask` are saved atomically. Company edits and retries do not change the snapshot.
- Migration `V5` adds the JSONB snapshot, retry/error metadata, and terminal task states `COMPLETED` / `FAILED`. Existing tasks remain; missing snapshots are built from current company data on first dispatch.
- The worker claims one ready task using `FOR UPDATE SKIP LOCKED`, saves `PROCESSING` and lease expiry, then commits before HTTP. No database locks are held during HTTP.
- `202` moves the task to `AWAITING_RESULT`; transient errors return it to `PENDING`. Expired `PROCESSING` and `AWAITING_RESULT` tasks are retried, including after restarts.
- Network errors, unexpected ACKs, `5xx`, `408`, and `429` allow retries; other `4xx` are terminal technical failures. Exhausted attempts mark both application and task `FAILED`. Errors are stored as safe codes without payloads or tokens.
- An accepted callback atomically stores `CreditApplication.scorerResult` (`ScorerResult`, JSONB) and `scorerResultReceivedAt`, completing both application and task. The business outcome is `scorerResult.status`. Existing DB columns remain `scoredResult` / `scoredAt`, mapped via `@Column`.
- A callback before ACK or a response from an older attempt cannot move a completed application back to waiting.

## Configuration and startup

The Java worker is disabled by default. Enabling it requires a scorer URL and shared service token.

| Variable | Default | Purpose |
| --- | --- | --- |
| `SCORER_ENABLED` | `false` | Enable Java dispatch worker |
| `SCORER_BASE_URL` | `http://localhost:8000` | Scorer service URL |
| `SCORER_TOKEN` | Empty | Shared `X-Scorer-Token`; required when worker is enabled |
| `SCORER_CONNECT_TIMEOUT` | `PT3S` | Connection timeout |
| `SCORER_READ_TIMEOUT` | `PT10S` | HTTP response timeout |
| `SCORER_REQUEST_LEASE` | `PT30S` | Dispatch lease; must exceed combined HTTP timeouts |
| `SCORER_CALLBACK_TIMEOUT` | `PT60S` | Result wait after ACK |
| `SCORER_RETRY_DELAY` | `PT5S` | Delay after transient failure |
| `SCORER_MAX_ATTEMPTS` | `5` | Maximum sends per snapshot |
| `SCORER_POLL_DELAY_MS` | `1000` | Delay between task polls |
| `SCORER_INITIAL_DELAY_MS` | `1000` | Initial worker delay |

From the repository root, with the scorer already running:

```sh
POSTGRES_PORT=5433 docker compose --project-name smartcreditor-dev up -d --wait
cd code/app/backend
SPRING_PROFILES_ACTIVE=dev \
DB_URL=jdbc:postgresql://localhost:5433/smartcreditor \
SCORER_ENABLED=true \
SCORER_BASE_URL=http://localhost:8000 \
SCORER_TOKEN=local-scorer-demo-token \
sh ./mvnw spring-boot:run
```

This integration includes no web templates or automatic sample accounts. A user, business, and access rights must exist before submission. Simulate a callback using an already dispatched application's UUID and the same `SCORER_TOKEN`:

```sh
curl -i http://localhost:8080/scored \
  -H 'Content-Type: application/json' \
  -H 'X-Scorer-Token: local-scorer-demo-token' \
  --data '{"status":"DECLINED","months":null,"percent":null,"sum":null,"id":"a051d4c4-6f76-4a56-a181-1ddcc6821177"}'
```

## Tests

With PostgreSQL running, from `code/app/backend`:

```sh
SPRING_PROFILES_ACTIVE=dev \
DB_URL=jdbc:postgresql://localhost:5433/smartcreditor \
sh ./mvnw test
```

HTTP tests use a local `/request` stub. Integration tests create and delete only a temporary PostgreSQL schema; the test worker does not claim development tasks. Coverage includes:

- Snapshots and exact amounts; rejection before ACK; duplicates and conflicts.
- Service authorization and client-route CSRF.
- Transient/permanent failures, callback timeout, late results, expired-task recovery, and concurrent workers.
