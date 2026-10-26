@echo off
setlocal

pushd "%~dp0"
if errorlevel 1 exit /b 1

set "SPRING_PROFILES_ACTIVE=dev"
set "POSTGRES_PORT=5433"
set "DB_URL=jdbc:postgresql://localhost:5433/smartcreditor"
set "DB_USER=smartcreditor"
set "DB_PASSWORD=localdev"
set "SCORER_ENABLED=true"
if not defined SCORER_PORT set "SCORER_PORT=8000"
set "SCORER_BASE_URL=http://localhost:%SCORER_PORT%"
set "JAVA_BASE_URL=http://host.docker.internal:8080"
if not defined SCORER_TOKEN set "SCORER_TOKEN=local-review-scorer-token"

docker compose --project-name smartcreditor-dev --file compose.yaml --profile scoring up --detach --build --wait --wait-timeout 120
if errorlevel 1 goto :failed

cd /d "code\app\backend"
if errorlevel 1 goto :failed
call mvnw.cmd clean package
if errorlevel 1 goto :failed

set "app_jar="
for %%J in (target\*.jar) do (
    if defined app_jar (
        >&2 echo Expected exactly one application JAR in target/.
        popd
        exit /b 1
    )
    set "app_jar=%%~fJ"
)
if not exist "%app_jar%" (
    >&2 echo Application JAR was not found in target/.
    popd
    exit /b 1
)
echo Login: http://localhost:8080/login - demo accounts and company UUIDs are listed in README.md.
java -jar "%app_jar%" --spring.profiles.active=dev

:failed
set "script_exit_code=%errorlevel%"
popd
exit /b %script_exit_code%
