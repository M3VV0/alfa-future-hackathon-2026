# Authentication and authorization

Local accounts use an email address and password. The backend uses Spring Security, JWTs in HttpOnly cookies, and an `AuthSession` record in PostgreSQL.

| Endpoint | Request | Successful response |
| --- | --- | ---  |
| `GET /login` | — | Render the `auth/login` view. |
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

Every POST requires CSRF protection: submit the `_csrf` field or the `X-XSRF-TOKEN` header containing the token obtained from server-rendered HTML. After login/logout, obtain a new CSRF token from the next page.

Auth endpoints and `/api/health` are accessible without an access JWT. For protected routes, authentication failure redirects ordinary requests to `/login`; HTMX receives `401` and `HX-Redirect: /login`.

The login template is served at `/login`; after login, `/app` displays the credit application form. Static styles and scripts under `/assets/` are available without authentication. The form uses the existing JSON submission endpoint. Before submission, refresh, and logout, the frontend reads a fresh CSRF token from the existing public login form and sends it in the CSRF header. This accommodates the current JWT filter clearing the CSRF cookie after authentication; no new token endpoint or authentication rules are introduced. If submission is rejected with `401`, it attempts refresh once before retrying; network failures and other responses are never retried automatically.

The company UUID must be entered manually because a company-list endpoint is not available. In the `dev` profile, the application adds missing synthetic users, companies, and memberships at startup unless `DEMO_DATA_ENABLED=false`; existing passwords and roles are preserved. The initializer is excluded when `prod` is active. Credentials and company UUIDs are listed in [README.md](../README.md). The frontend does not create accounts. A successful submission displays the accepted application UUID, without implying a credit decision or ongoing processing.
