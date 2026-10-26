# ADR-0003: Java MVP with a future external Python scorer

Status: accepted for branch mvpback.

Keep the existing Spring MVC/Thymeleaf application, JWT-cookie authorization and PostgreSQL.
Java owns intake, product limits, structured documents, draft revisions, submission snapshots,
affordability and offer actions. The existing HTTP scorer contract remains the service boundary.
Do not add a local model, demo scoring provider or generated callback. Disable dispatch by default;
complete submissions wait durably for the future Python service.

Persist the new dossier and audit data through JDBC in the same JPA transaction/connection as
applications and queue rows. Serialize workflow mutations, claims and callbacks using the queue-row
lock. Preserve original scorer output and store the separate Java decision. Keep small JSON reports
in PostgreSQL (1 MiB limit), avoiding an otherwise-unused object-storage runtime in this scope.

The target architecture in architecture/architecture.md described a Java RulesScorer, db-scheduler,
S3 and a web-only entrypoint. The implemented branch instead retains the existing scheduled durable
queue and browser JSON API. These are intentional MVP choices, not claims that the target stack has
been fully implemented. See ../MVP_JAVA.md for actual states, endpoints and limits.
