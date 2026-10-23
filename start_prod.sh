#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

: "${JWT_PRIVATE_KEY:?Set JWT_PRIVATE_KEY to the private PEM resource path.}"
: "${JWT_PUBLIC_KEY:?Set JWT_PUBLIC_KEY to the public PEM resource path.}"

export SPRING_PROFILES_ACTIVE=prod
export POSTGRES_PORT=5432

docker compose --project-name smartcreditor-prod --file compose.yaml up --detach --force-recreate --wait --wait-timeout 90

cd code/app/backend
sh ./mvnw clean package

set -- target/*.jar
if [ "$#" -ne 1 ] || [ ! -f "$1" ]; then
    printf '%s\n' 'Expected exactly one application JAR in target/.' >&2
    exit 1
fi
exec java -jar "$1" --spring.profiles.active=prod
