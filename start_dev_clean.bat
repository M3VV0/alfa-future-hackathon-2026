@echo off
setlocal

pushd "%~dp0"
if errorlevel 1 exit /b 1

echo Resetting the development database: all dev data, including applications, will be deleted.
docker compose --project-name smartcreditor-dev --file compose.yaml down --volumes --remove-orphans
if errorlevel 1 goto :failed

call "%~dp0start_dev.bat"

:failed
set "script_exit_code=%errorlevel%"
popd
exit /b %script_exit_code%
