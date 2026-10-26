# Java–Python scorer contract

Java owns the client workflow and final product/affordability policy. The supplied Python service runs the trained
CatBoost model. **There is no dummy scorer, Java fallback, generated scoring answer or demo mode.**
[Exchange OpenAPI](openapi/scorer.yaml) · [Java workflow and rules](MVP_JAVA.md).

## Request and acknowledgement

Java sends `POST /request` to `SCORER_BASE_URL` with `X-Scorer-Token: <SCORER_TOKEN>`:

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
    "snapshot_on": "2026-10-26",
    "average_headcount": 10,
    "product_id": "equipment",
    "input_revision": 2,
    "financials": {
      "monthlyRevenue": "1000000.00",
      "monthlyExpenses": "600000.00",
      "existingMonthlyPayment": "10000.00"
    }
  }
}
```

`id` is the existing Java application's UUID. Parameter names/categories of the existing model are
retained, with declared business fields and available registry/nested data. Unknown data stays null.
The model adapter must accept/ignore extra parameters it does not use; no ID or target is included
inside model parameters. Monthly financial values and requested money are decimal strings in RUB.
For mandatory input semantics see [the existing model interface](../code/scorer/docs/INTERFACE.md).

Python must persist the request and return **202 after commit**, then process it asynchronously.
Repeated UUID/equivalent input must return 202 without duplicate computation. Different input under
an existing UUID must return 409. Java freezes the payload on submission; profile edits and retries
cannot change it. The confirmed report reference/checksum, product and policy versions are retained
in the Java submission snapshot. Draft/NEEDS_INFO tasks are paused and never dispatched.

## Callback

Python sends `POST /scored` to its configured Java URL with the same service token:

```json
{
  "id": "a051d4c4-6f76-4a56-a181-1ddcc6821177",
  "status": "OFFER",
  "months": 24,
  "percent": "20.0000",
  "sum": "1000000.50"
}
```

| Field | Contract |
| --- | --- |
| `id` | Required UUID of a dispatched application |
| `status` | `OK`, `OFFER` or `DECLINED` |
| `months` | Positive integer for OK/OFFER; null or non-negative for DECLINED |
| `percent` | Annual percent, not a fraction; non-negative, ≤5 integer / 4 decimal digits; required for OK/OFFER |
| `sum` | RUB; positive for OK/OFFER, ≤15 integer / 2 decimal digits; optional/non-negative for DECLINED |

Exact decimal strings are preferred; JSON numbers are also accepted. Requested funds in a rejection
are not an offer. Model failure is a technical error, never `DECLINED`. Java preserves the original
callback separately from the final decision. Positive callbacks are subjected to the selected product
limits and affordability policy; a positive model outcome does not bypass those checks.

| HTTP | Meaning |
| --- | --- |
| 204 | Saved after commit, or an identical callback already stored |
| 400 | Malformed JSON/status/terms |
| 401 | Missing/incorrect shared token, or Java token not configured |
| 404 | Application not found |
| 409 | Unsent/cancelled application or conflicting previously stored result |
| 422 | Positive terms violate Java product/rate validation; technical incompatibility, not credit decline |

Duplicate decimals compare numerically: `15` and `15.0000` are equal. Duplicates preserve the first
result timestamp and do not recreate offers or events. A callback may arrive before the initial 202
ACK. A late result can recover technical FAILED, but cannot reopen CANCELLED. The service token is
not logged or exposed to browsers. Only `POST /scored` has the service-token security chain; browser
API mutations still require JWT-cookie authentication and CSRF.

## Durable execution

The Java worker claims `PENDING`, expired `PROCESSING` or expired `AWAITING_RESULT` rows with
`FOR UPDATE SKIP LOCKED`. It commits the lease before HTTP and holds no database lock while
waiting for Python. 202 moves the row to `AWAITING_RESULT`; a callback commits the original result,
final decision, event and task completion atomically. Late ACKs cannot overwrite a newer attempt or
completed/cancelled task.

Network failures, unexpected ACKs, 5xx, 408 and 429 retry with configured bounds. Other request 4xx
are permanent technical failures. Exhausted sends or callback timeouts become FAILED. Request
payloads and tokens are excluded from error logs. A restart recovers expired leases from PostgreSQL.
Legacy pre-MVP submissions retain their original result contract; their cabinet view is read-only.

## Configuration

| Variable | Default | Purpose |
| --- | --- | --- |
| `SCORER_ENABLED` | `true` in Compose; `false` for bare Java | Enable dispatch; true in the integrated Compose configuration |
| `SCORER_BASE_URL` | `http://localhost:8000` natively; `http://scorer:8000` in Compose | Python URL reachable from Java |
| `SCORER_TOKEN` | Empty | Shared token; enabled Java worker rejects an empty value |
| `SCORER_CONNECT_TIMEOUT` | `PT3S` | Connection timeout |
| `SCORER_READ_TIMEOUT` | `PT10S` | HTTP response timeout |
| `SCORER_REQUEST_LEASE` | `PT30S` | Must exceed combined HTTP timeouts |
| `SCORER_CALLBACK_TIMEOUT` | `PT60S` | Callback deadline after ACK |
| `SCORER_RETRY_DELAY` | `PT5S` | Retry delay |
| `SCORER_MAX_ATTEMPTS` | `5` | Send limit |
| `SCORER_POLL_DELAY_MS` | `1000` | Poll delay |
| `SCORER_INITIAL_DELAY_MS` | `1000` | Initial worker delay |

Configure Python's callback URL separately: `http://app:8080/scored` on the same Compose network,
or the Java host URL. Compose supplies both services and shared token configuration. The Python image includes the trained artifact;
the Java image remains independent of Python packages. Native Java-only debugging may disable dispatch.

## Verification

Java tests exercise HTTP callbacks and mock the outgoing client strictly inside tests. They cover
snapshot freezing, callbacks before ACK, duplicate/conflicting results, authorization, decimal
precision, bounded retries/timeouts, concurrent workers, late results and cancelled applications.
No testing stub is installed as a runtime application mode. Commands are in [MVP_JAVA.md](MVP_JAVA.md).

## Included Python runtime

Run `python -m scorer_service` from `code/scorer`, or use the integrated Docker command. FastAPI starts
one inference loop and one delivery loop and loads the model once. `/health` checks DB connectivity,
model loading and live workers; `/docs` and `/openapi.json` describe the running Python API.

Its own PostgreSQL schema defaults to `scorer` (`SCORER_DB_SCHEMA`). `scoring_queue` and `scoring_result`
are independent of Java migrations. Queues persist attempts, ready/lease deadlines and ownership tokens.
No DB lock is held while predicting or sending HTTP. Expired leases recover after a crash; stale owners
cannot overwrite newer work. All failures are bounded; permanent 4xx do not block other applications.

`SCORER_WORKER_POLL_SECONDS=.5`, `SCORER_RETRY_SECONDS=5`, `SCORER_MAX_ATTEMPTS=5`,
`SCORER_LEASE_SECONDS=60`, `SCORER_PREDICTION_TIMEOUT_SECONDS=30`,
`SCORER_CALLBACK_TIMEOUT_SECONDS=10`. A lease must exceed the timeouts by more than five seconds.
A duplicate request for a failed computation may start a new bounded cycle. A saved result is always
redelivered without recomputation. `sent_at` preserves the first successful delivery time.

The model returns a rate, not a default probability. Adapter `rate-bands-v1` compares its unrounded
value: below 12% or at least 35% gives DECLINED; [12,25) OK; [25,35) OFFER. The lower threshold is a
prototype pricing bound, not evidence of insolvency. Java then applies separate product/affordability
rules. The raw rate, callback, model SHA-256 and adapter version are stored before delivery.

The old artifact failed the feature-code hash check. `export_model.py` rebuilt the selected CatBoost
configuration on the same 8,000-row development split using the checked-in preprocessing. It did not
rerun model search or fit on the reused 2,000-row diagnostic holdout. The version/metrics manifest is
`artifacts/selected_model.validation.json`. Startup does not train and does not bypass hash validation.
