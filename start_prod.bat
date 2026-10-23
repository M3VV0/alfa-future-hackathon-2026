@echo off
setlocal

if not defined JWT_PRIVATE_KEY (
    >&2 echo Set JWT_PRIVATE_KEY to the private PEM resource path.
    exit /b 1
)
if not defined JWT_PUBLIC_KEY (
    >&2 echo Set JWT_PUBLIC_KEY to the public PEM resource path.
    exit /b 1
)

pushd "%~dp0"
if errorlevel 1 exit /b 1

set "SPRING_PROFILES_ACTIVE=prod"
set "POSTGRES_PORT=5432"

docker compose --project-name smartcreditor-prod --file compose.yaml up --detach --force-recreate --wait --wait-timeout 90
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
java -jar "%app_jar%" --spring.profiles.active=prod

:failed
set "script_exit_code=%errorlevel%"
popd
exit /b %script_exit_code%
