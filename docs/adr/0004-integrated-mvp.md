# ADR-0004: Integrated Java/Python delivery

Status: accepted for scorer-service; extends ADR-0003.

Merge the Java MVP and supplied FastAPI service. The standard Compose launch starts PostgreSQL,
Python inference/delivery and Java, with matching internal URLs and shared development service token.
No runtime stub, fallback rate or forced success status is allowed.

Rebuild the selected CatBoost configuration on the same 8,000-row development split because the
old artifact does not match checked-in preprocessing. Preserve feature-hash checks and export a
manifest; do not claim historical notebook results as measurements of the rebuilt artifact.

Python persists a genuine rate, model version and frozen callback. Interpret the documented synthetic
price bands separately from Java's product and affordability policy. Inference and delivery use
leased durable jobs outside DB transactions, bounded retries, stale-owner guards and an isolated
schema. Java exposes explicit retry for technical FAILED, preserving the UUID/input and dispatched
history. External registries remain declared data; their execution is not simulated or claimed.

Use a universal hash-locked dependency file, pinned Python/Java/PostgreSQL image digests, LF Python
sources for cross-platform model hash stability, real PostgreSQL integration tests and real-service
E2E verification. Deployment/review is local; repository access and jury submission remain owner actions.
