# Business Credit Portal

Small-business credit portal prototype with sign-in, company access checks, and applications stored in PostgreSQL. Web UI: Spring Boot/Thymeleaf (RU/EN). Standalone Python rate estimator: `code/scorer`.

## Run locally

Requires JDK 21+ and running Docker with Compose. First build needs Maven Central access; Maven is downloaded by the wrapper. Run from the repository root.

| Platform | Start | Reset database and start |
| --- | --- | --- |
| macOS / Linux | `sh ./start_dev.sh` | `sh ./start_dev_clean.sh` |
| Windows (cmd) | `start_dev.bat` | `start_dev_clean.bat` |

Scripts start PostgreSQL (`5433`), build and test the backend, then run it on `8080` with the `dev` profile. Flyway applies migrations; missing sample accounts, businesses, and memberships are added. Normal startup preserves data.

[Sign in](http://localhost:8080/login) · [Application form](http://localhost:8080/app) (after sign-in) · [Health check](http://localhost:8080/api/health) (`OK`).

**Reset deletes all dev data, including applications, accounts, and password changes**, then runs normal startup. It affects only the `smartcreditor-dev` Docker Compose project.

Use `Ctrl+C` to stop the backend before restarting/resetting. Stop PostgreSQL without deleting data:

```sh
docker compose --project-name smartcreditor-dev stop
```

## Sample accounts

Initial shared password: **`DemoCredit2026!`**. All data is synthetic; `.test` emails and tax IDs are local fixtures. Tax IDs are not checked externally.

| Email | Role | Business | Can submit |
| --- | --- | --- | --- |
| `owner@demo.test` | `OWNER` | ООО «Демо Оборот» | Yes |
| `admin@demo.test` | `ADMIN` | ООО «Демо Оборот» | Yes |
| `viewer@demo.test` | `VIEWER` | ООО «Демо Оборот» | No (`403`) |
| `other@demo.test` | `OWNER` | ИП Демо Развитие | Own business only |

**Business ID** values:

| Business | UUID |
| --- | --- |
| ООО «Демо Оборот» | `10000000-0000-4000-8000-000000000001` |
| ИП Демо Развитие | `10000000-0000-4000-8000-000000000002` |

Existing records are matched by email or tax ID; passwords, business details, and roles stay unchanged. The password above applies to new accounts.

## Check application submission

1. As `owner@demo.test`, submit for ООО «Демо Оборот»: `1000000` RUB, `24` months, any purpose and payment frequency, optional collateral. Expect confirmation with an application UUID.
2. Sign out and repeat as `viewer@demo.test`: submission is denied (`403`).
3. As `other@demo.test`: the ООО «Демо Оборот» UUID returns `404`; the ИП Демо Развитие UUID allows submission.
4. Restart normally: verify accounts and applications persist without duplicate sample users or businesses.

## Current limits

Scorer integration is pending; background processing is disabled by default. Applications and queue tasks remain `PENDING`. Acceptance is not a credit decision. Retrying after a lost response may create a duplicate.

## Development settings

IDE or `spring-boot:run` settings:

| Variable | Local value |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` |
| `DB_URL` | `jdbc:postgresql://localhost:5433/smartcreditor` |
| `DB_USER` | `smartcreditor` |
| `DB_PASSWORD` | `localdev` |
| `DEMO_DATA_ENABLED` | `true` by default in `dev` |

Seeding requires `dev` and `app.demo-data.enabled=true`; any active `prod` profile disables it. Set `DEMO_DATA_ENABLED=false` to skip seeding and retain existing records.

See [authorization](docs/AUTHORIZATION.md) and [target architecture and planned work](docs/architecture/architecture.md).
