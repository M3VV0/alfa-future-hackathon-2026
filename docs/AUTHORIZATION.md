# Authentication and authorization

Local accounts use an email address and password. The backend uses Spring Security, JWTs in HttpOnly cookies, and an `AuthSession` record in PostgreSQL.

| Endpoint | Request | Successful response |
| --- | --- | ---  |
| `GET /login` | — | Render the `auth/login` view. |
| `GET /auth/csrf` | — | JSON with `headerName`, `parameterName`, and `token`; `Cache-Control: no-store`. An access JWT is not required. |
| `POST /login` | Form fields `email`, `password`, and CSRF token | Create a session and set cookies; `303 → /app`. |
| `POST /auth/refresh` | Refresh cookie and CSRF token | Replace both tokens; `204`. A valid access token is not required. |
| `POST /logout` | Refresh cookie and CSRF token | Revoke the session identified by a verified refresh token, clear cookies; `303 → /login`. Repeated logout succeeds. |

Login uses `application/x-www-form-urlencoded`. Invalid or missing fields return `422`; an incorrect email or password returns the same generic error and `401`. An invalid refresh token during renewal returns `401`; CSRF violations return `403`. Logout without a valid refresh token only clears cookies.

## Tokens and sessions

- Access JWTs are signed with RS256; `sub` is the user UUID and `sid` is the session UUID. Their lifetime is 15 minutes, capped by the session expiration.
- A session lasts 7 days from login. Refresh does not extend this absolute deadline; there is no separate inactivity timeout.
- Refresh tokens use the `sid.secret` format; the database stores the SHA-256 hash of the entire token. After successful rotation, the previous refresh token is no longer accepted.
- Every protected request validates the JWT and checks the active `sid/sub` pair in the database. Deleting the session revokes its JWTs for subsequent checks.
- HTTP sessions are not used. Backend nodes share the database and use consistent RSA keys and issuer configuration.

## Cookies and CSRF

Auth cookies are `access_token` and `refresh_token`, with HttpOnly, SameSite=Lax, Path=/, and Secure enabled by default. For local HTTP, set `AUTH_COOKIE_SECURE=false`. Configure keys through `JWT_PRIVATE_KEY` and `JWT_PUBLIC_KEY`.

Browser POST, PUT, PATCH and DELETE requests require CSRF protection: submit the `_csrf` field or the `X-XSRF-TOKEN` header containing the token obtained from server-rendered HTML. After login/logout, obtain a new CSRF token from the next page.

The service callback `POST /scored` uses a separate security chain with the `X-Scorer-Token` header, configured through `SCORER_TOKEN`. It does not use browser JWT cookies or CSRF; an absent or blank configured token rejects all callbacks. This exception applies only to that method and path. See [SCORER.md](SCORER.md) for the contract, configuration, and result persistence.


Auth endpoints and `/api/health` are accessible without an access JWT. For protected routes, authentication failure redirects ordinary requests to `/login`; HTMX receives `401` and `HX-Redirect: /login`.

The login template is served at `/login`; `/app` creates or edits a draft,
`/app/applications` lists accessible applications, `/app/applications/{id}` displays the workflow,
and `/app/companies` edits declared company information. Browser JSON endpoints use the same
JWT-cookie/CSRF policy. Before a mutation the frontend gets a fresh CSRF token from `/auth/csrf`.
An API 401 triggers one refresh attempt; uncertain network responses are not retried automatically.
Create retries reuse the same UUID `Idempotency-Key` until the input changes or creation is confirmed.

Companies come from `GET /api/credit/companies`, scoped to the current user's memberships.
OWNER and ADMIN may mutate; VIEWER may read only. Application and document access is checked
against the company's membership on every request; another company's resources return 404.
All lifecycle commands and callbacks serialize on the same application queue row.

The dev initializer adds missing synthetic accounts, companies and memberships without changing
existing passwords or roles, unless `DEMO_DATA_ENABLED=false`. It is excluded when `prod` is
active. The app does not create arbitrary user accounts. An accepted draft or submitted application
is not a credit approval. No local scoring provider is installed. See [MVP_JAVA.md](MVP_JAVA.md).
