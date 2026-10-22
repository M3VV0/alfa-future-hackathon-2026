@echo off
setlocal

pushd "%~dp0"
if errorlevel 1 exit /b 1

set "SPRING_PROFILES_ACTIVE=dev"
set "POSTGRES_PORT=5433"
set "DB_URL=jdbc:postgresql://localhost:5433/smartcreditor"
set "DB_USER=smartcreditor"
set "DB_PASSWORD=localdev"

echo Resetting the development database: all dev data will be deleted.
docker compose --project-name smartcreditor-dev --file compose.yaml down --volumes --remove-orphans
if errorlevel 1 goto :failed

docker compose --project-name smartcreditor-dev --file compose.yaml up --detach --force-recreate --wait --wait-timeout 90
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
java -jar "%app_jar%" --spring.profiles.active=dev

:failed
set "script_exit_code=%errorlevel%"
popd
exit /b %script_exit_code%
