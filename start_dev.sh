#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

export SPRING_PROFILES_ACTIVE=dev
export POSTGRES_PORT=5433
export DB_URL=jdbc:postgresql://localhost:5433/smartcreditor
export DB_USER=smartcreditor
export DB_PASSWORD=localdev

printf '%s\n' 'Resetting the development database: all dev data will be deleted.'
docker compose --project-name smartcreditor-dev --file compose.yaml down --volumes --remove-orphans

docker compose --project-name smartcreditor-dev --file compose.yaml up --detach --force-recreate --wait --wait-timeout 90

cd code/app/backend
sh ./mvnw clean package

set -- target/*.jar
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    printf '%s\n' 'Expected exactly one application JAR in target/.' >&2
    exit 1
fi
exec java -jar "$1" --spring.profiles.active=dev
