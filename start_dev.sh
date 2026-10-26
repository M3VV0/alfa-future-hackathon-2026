#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

export SPRING_PROFILES_ACTIVE=dev
export POSTGRES_PORT=5433
export DB_URL=jdbc:postgresql://localhost:5433/smartcreditor
export DB_USER=smartcreditor
export DB_PASSWORD=localdev
export SCORER_ENABLED=true
export SCORER_BASE_URL="http://localhost:${SCORER_PORT:-8000}"
export JAVA_BASE_URL=http://host.docker.internal:8080
export SCORER_TOKEN="${SCORER_TOKEN:-local-review-scorer-token}"

docker compose --project-name smartcreditor-dev --file compose.yaml --profile scoring up --detach --build --wait --wait-timeout 120

cd code/app/backend
sh ./mvnw clean package

set -- target/*.jar
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    printf '%s\n' 'Expected exactly one application JAR in target/.' >&2
    exit 1
fi
printf '%s\n' 'Login: http://localhost:8080/login — synthetic accounts and verification steps are listed in README.md.'
exec java -jar "$1" --spring.profiles.active=dev
