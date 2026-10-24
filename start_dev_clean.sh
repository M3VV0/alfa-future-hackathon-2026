#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
cd "$script_dir"

printf '%s\n' 'Resetting the development database: all dev data, including applications, will be deleted.'
docker compose --project-name smartcreditor-dev --file compose.yaml down --volumes --remove-orphans

exec sh ./start_dev.sh
