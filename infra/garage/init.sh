#!/bin/sh
set -eu
# CLI reads the node identity from the shared, read-only metadata volume.
unset GARAGE_RPC_HOST
attempt=0
until node_address=$(garage -c /etc/garage.toml node id 2>/dev/null | head -n 1) && [ -n "$node_address" ]; do
  attempt=$((attempt+1)); [ "$attempt" -le 60 ] || exit 1; sleep 1
done
export GARAGE_RPC_HOST="$node_address"
g() { garage -c /etc/garage.toml "$@"; }
attempt=0
until g status >/tmp/garage-status 2>/dev/null; do
  attempt=$((attempt+1)); [ "$attempt" -le 60 ] || exit 1; sleep 1
done
node=$(awk '/HEALTHY NODES/{f=1;next} f && /^[0-9a-f]/{print $1;exit}' /tmp/garage-status)
if ! g layout show | grep -q 'demo'; then
  g layout assign -z demo -c 1G "$node"
  g layout apply --version 1
fi
if ! g key info "$S3_ACCESS_KEY" >/dev/null 2>&1; then
  g key import "$S3_ACCESS_KEY" "$S3_SECRET_KEY" -n credit-demo --yes >/dev/null
fi
if ! g bucket info "$S3_BUCKET" >/dev/null 2>&1; then g bucket create "$S3_BUCKET"; fi
g bucket allow --read --write --owner "$S3_BUCKET" --key "$S3_ACCESS_KEY"
