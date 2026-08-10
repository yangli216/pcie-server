#!/usr/bin/env bash

set -euo pipefail

script_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
preflight="$script_dir/cluster-preflight.sh"
fixtures="$script_dir/preflight-fixtures"
test_root=$(mktemp -d "${TMPDIR:-/tmp}/pcie-cluster-preflight.XXXXXX")
trap 'rm -rf "$test_root"' EXIT

run_preflight() {
  writer_upstream_node_id="${2:-pcie-01}"
  "$preflight" \
    --fixture-dir "$1" \
    --node pcie-01=fixture \
    --node pcie-02=fixture \
    --node pcie-03=fixture \
    --writer-upstream-node-id "$writer_upstream_node_id"
}

run_preflight "$fixtures" >"$test_root/pass.out"
grep -q 'PREFLIGHT PASS' "$test_root/pass.out"

drift_fixtures="$test_root/writer-drift"
mkdir -p "$drift_fixtures"
cp "$fixtures"/*.json "$drift_fixtures/"
jq '.cluster.releaseWriterNodeId = "pcie-02"' \
  "$drift_fixtures/pcie-02-info.json" >"$test_root/pcie-02-info.json"
mv "$test_root/pcie-02-info.json" "$drift_fixtures/pcie-02-info.json"

if run_preflight "$drift_fixtures" >"$test_root/drift.out" 2>"$test_root/drift.err"; then
  printf '%s\n' 'expected writer drift fixture to fail' >&2
  exit 1
fi
grep -q 'releaseWriterNodeId differs across nodes' "$test_root/drift.err"

if run_preflight "$fixtures" pcie-02 >"$test_root/upstream.out" 2>"$test_root/upstream.err"; then
  printf '%s\n' 'expected writer upstream mismatch to fail' >&2
  exit 1
fi
grep -q 'LB writer upstream points to pcie-02, expected pcie-01' "$test_root/upstream.err"

down_fixtures="$test_root/readiness-down"
mkdir -p "$down_fixtures"
cp "$fixtures"/*.json "$down_fixtures/"
jq '.status = "DOWN"' \
  "$down_fixtures/pcie-03-readiness.json" >"$test_root/pcie-03-readiness.json"
mv "$test_root/pcie-03-readiness.json" "$down_fixtures/pcie-03-readiness.json"
if run_preflight "$down_fixtures" >"$test_root/down.out" 2>"$test_root/down.err"; then
  printf '%s\n' 'expected readiness DOWN fixture to fail' >&2
  exit 1
fi
grep -q 'pcie-03 readiness is DOWN' "$test_root/down.err"

split_storage_fixtures="$test_root/split-storage"
mkdir -p "$split_storage_fixtures"
cp "$fixtures"/*.json "$split_storage_fixtures/"
jq '.release = "node-03-local-release"' \
  "$split_storage_fixtures/pcie-03-storage.json" >"$test_root/pcie-03-storage.json"
mv "$test_root/pcie-03-storage.json" "$split_storage_fixtures/pcie-03-storage.json"
if run_preflight "$split_storage_fixtures" >"$test_root/split.out" 2>"$test_root/split.err"; then
  printf '%s\n' 'expected split release storage fixture to fail' >&2
  exit 1
fi
grep -q 'release challenge written by pcie-01 is not visible on pcie-03' "$test_root/split.err"

printf '%s\n' 'cluster preflight fixture tests passed'
