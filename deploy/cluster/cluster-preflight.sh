#!/usr/bin/env bash

set -euo pipefail

usage() {
  printf '%s\n' \
    'Usage:' \
    '  cluster-preflight.sh --node NODE_ID=MANAGEMENT_BASE_URL [--node ...] \' \
    '    --writer-upstream-node-id NODE_ID [--fixture-dir DIRECTORY]' \
    '' \
    'Exactly three --node arguments are required. MANAGEMENT_BASE_URL must reach' \
    'the node-local Actuator endpoint, normally through a controlled SSH tunnel.'
}

fail() {
  printf 'PREFLIGHT FAIL: %s\n' "$*" >&2
  exit 1
}

require_value() {
  local option="$1"
  local value="${2:-}"
  [[ -n "$value" ]] || fail "$option requires a value"
}

json_value() {
  local payload="$1"
  local filter="$2"
  local description="$3"
  local value
  if ! value=$(jq -er "$filter" <<<"$payload"); then
    fail "invalid $description response"
  fi
  printf '%s' "$value"
}

new_uuid() {
  local value
  if command -v uuidgen >/dev/null 2>&1; then
    value=$(uuidgen)
  elif [[ -r /proc/sys/kernel/random/uuid ]]; then
    value=$(</proc/sys/kernel/random/uuid)
  else
    fail "uuidgen or /proc/sys/kernel/random/uuid is required"
  fi
  printf '%s' "$value" | tr '[:upper:]' '[:lower:]'
}

fixture_storage_id() {
  local node_name="$1"
  local storage="$2"
  local mapping_path="$fixture_dir/$node_name-storage.json"
  local mapping_payload
  [[ -r "$mapping_path" ]] || fail "missing fixture: $mapping_path"
  mapping_payload=$(<"$mapping_path")
  json_value \
    "$mapping_payload" \
    ".${storage} | select(type == \"string\" and test(\"^[A-Za-z0-9._-]+$\"))" \
    "$node_name fixture $storage storage ID"
}

challenge_request() {
  local action="$1"
  local node_index="$2"
  local storage="$3"
  local token="$4"
  local content="${5:-}"
  local node_name="${node_names[$node_index]}"

  if [[ -n "$fixture_dir" ]]; then
    local storage_id
    local storage_directory
    local target
    storage_id=$(fixture_storage_id "$node_name" "$storage")
    storage_directory="$fixture_state_dir/$storage_id"
    target="$storage_directory/$token"
    case "$action" in
      write)
        mkdir -p "$storage_directory"
        [[ ! -e "$target" ]] || fail "fixture challenge already exists: $storage/$token"
        printf '%s' "$content" >"$target"
        jq -nc \
          --arg storage "$storage" --arg token "$token" --arg content "$content" --arg node "$node_name" \
          '{storage:$storage,token:$token,content:$content,exists:true,status:"CREATED",nodeId:$node}'
        ;;
      read)
        if [[ -f "$target" ]]; then
          local stored_content
          stored_content=$(<"$target")
          jq -nc \
            --arg storage "$storage" --arg token "$token" --arg content "$stored_content" --arg node "$node_name" \
            '{storage:$storage,token:$token,content:$content,exists:true,status:"FOUND",nodeId:$node}'
        else
          jq -nc \
            --arg storage "$storage" --arg token "$token" --arg node "$node_name" \
            '{storage:$storage,token:$token,exists:false,status:"ABSENT",nodeId:$node}'
        fi
        ;;
      delete)
        local deleted=false
        if [[ -f "$target" ]]; then
          rm -f "$target"
          deleted=true
        fi
        jq -nc \
          --arg storage "$storage" --arg token "$token" --arg node "$node_name" --argjson deleted "$deleted" \
          '{storage:$storage,token:$token,exists:false,status:"DELETED",nodeId:$node,deleted:$deleted}'
        ;;
      *)
        fail "unsupported fixture challenge action: $action"
        ;;
    esac
    return
  fi

  local node_url="${node_urls[$node_index]}"
  local endpoint="$node_url/actuator/clusterStorageChallenge/$storage"
  local curl_options=(
    --fail --silent --show-error
    --connect-timeout "${PCIE_PREFLIGHT_CONNECT_TIMEOUT_SECONDS:-3}"
    --max-time "${PCIE_PREFLIGHT_MAX_TIME_SECONDS:-10}"
  )
  case "$action" in
    write)
      local payload
      payload=$(jq -nc --arg token "$token" --arg content "$content" '{token:$token,content:$content}')
      curl "${curl_options[@]}" \
        --request POST --header 'Content-Type: application/json' --data "$payload" "$endpoint"
      ;;
    read)
      curl "${curl_options[@]}" "$endpoint?token=$token"
      ;;
    delete)
      curl "${curl_options[@]}" --request DELETE "$endpoint?token=$token"
      ;;
    *)
      fail "unsupported challenge action: $action"
      ;;
  esac
}

verify_shared_storage() {
  local storage="$1"
  local writer_index="$2"
  local delete_index="$3"
  local token
  local content
  local response
  local status
  local exists
  local observed_content
  token=$(new_uuid)
  content=$(new_uuid)

  if ! response=$(challenge_request write "$writer_index" "$storage" "$token" "$content"); then
    fail "$storage challenge write failed on ${node_names[$writer_index]}"
  fi
  status=$(json_value "$response" '.status | select(. == "CREATED")' "$storage challenge write")
  [[ "$status" == 'CREATED' ]] || fail "$storage challenge was not created"

  for ((reader_index = 0; reader_index < 3; reader_index += 1)); do
    if [[ $reader_index -eq $writer_index ]]; then
      continue
    fi
    if ! response=$(challenge_request read "$reader_index" "$storage" "$token"); then
      fail "$storage challenge read failed on ${node_names[$reader_index]}"
    fi
    exists=$(json_value "$response" '.exists | select(type == "boolean") | tostring' "$storage challenge read")
    [[ "$exists" == 'true' ]] \
      || fail "$storage challenge written by ${node_names[$writer_index]} is not visible on ${node_names[$reader_index]}"
    observed_content=$(json_value "$response" '.content | select(type == "string")' "$storage challenge content")
    [[ "$observed_content" == "$content" ]] \
      || fail "$storage challenge content differs on ${node_names[$reader_index]}"
  done

  if ! response=$(challenge_request delete "$delete_index" "$storage" "$token"); then
    fail "$storage challenge delete failed on ${node_names[$delete_index]}"
  fi
  exists=$(json_value "$response" '.deleted | select(type == "boolean") | tostring' "$storage challenge delete")
  [[ "$exists" == 'true' ]] \
    || fail "$storage challenge could not be deleted by ${node_names[$delete_index]}"

  for ((reader_index = 0; reader_index < 3; reader_index += 1)); do
    if ! response=$(challenge_request read "$reader_index" "$storage" "$token"); then
      fail "$storage challenge absence check failed on ${node_names[$reader_index]}"
    fi
    exists=$(json_value "$response" '.exists | select(type == "boolean") | tostring' "$storage challenge absence")
    [[ "$exists" == 'false' ]] \
      || fail "$storage challenge deletion is not visible on ${node_names[$reader_index]}"
  done

  printf 'SHARED STORAGE PASS: %s (writer=%s, deleter=%s)\n' \
    "$storage" "${node_names[$writer_index]}" "${node_names[$delete_index]}"
}

node_names=()
node_urls=()
writer_upstream_node_id=''
fixture_dir=''
fixture_state_dir=''

while [[ $# -gt 0 ]]; do
  case "$1" in
    --node)
      require_value "$1" "${2:-}"
      spec="$2"
      [[ "$spec" == *=* ]] || fail "--node must use NODE_ID=MANAGEMENT_BASE_URL"
      node_name="${spec%%=*}"
      node_url="${spec#*=}"
      [[ -n "$node_name" && -n "$node_url" ]] || fail "--node contains an empty node ID or URL"
      node_names+=("$node_name")
      node_urls+=("${node_url%/}")
      shift 2
      ;;
    --writer-upstream-node-id)
      require_value "$1" "${2:-}"
      writer_upstream_node_id="$2"
      shift 2
      ;;
    --fixture-dir)
      require_value "$1" "${2:-}"
      fixture_dir="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ ${#node_names[@]} -eq 3 ]] || fail "exactly three --node arguments are required"
[[ -n "$writer_upstream_node_id" ]] || fail "--writer-upstream-node-id is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"
if [[ -z "$fixture_dir" ]]; then
  command -v curl >/dev/null 2>&1 || fail "curl is required"
else
  [[ -d "$fixture_dir" ]] || fail "fixture directory does not exist: $fixture_dir"
  fixture_state_dir=$(mktemp -d "${TMPDIR:-/tmp}/pcie-cluster-storage-fixture.XXXXXX")
  trap 'rm -rf "$fixture_state_dir"' EXIT
fi

cluster_ids=()
reported_node_ids=()
writer_node_ids=()
effective_writers=()

for ((index = 0; index < ${#node_names[@]}; index += 1)); do
  node_name="${node_names[$index]}"
  node_url="${node_urls[$index]}"

  for ((previous = 0; previous < index; previous += 1)); do
    [[ "$node_name" != "${node_names[$previous]}" ]] || fail "duplicate --node label: $node_name"
  done

  if [[ -n "$fixture_dir" ]]; then
    info_path="$fixture_dir/$node_name-info.json"
    readiness_path="$fixture_dir/$node_name-readiness.json"
    [[ -r "$info_path" ]] || fail "missing fixture: $info_path"
    [[ -r "$readiness_path" ]] || fail "missing fixture: $readiness_path"
    info_payload=$(<"$info_path")
    readiness_payload=$(<"$readiness_path")
  else
    if ! info_payload=$(curl --fail --silent --show-error \
      --connect-timeout "${PCIE_PREFLIGHT_CONNECT_TIMEOUT_SECONDS:-3}" \
      --max-time "${PCIE_PREFLIGHT_MAX_TIME_SECONDS:-10}" \
      "$node_url/actuator/info"); then
      fail "$node_name /actuator/info is unreachable"
    fi
    if ! readiness_payload=$(curl --fail --silent --show-error \
      --connect-timeout "${PCIE_PREFLIGHT_CONNECT_TIMEOUT_SECONDS:-3}" \
      --max-time "${PCIE_PREFLIGHT_MAX_TIME_SECONDS:-10}" \
      "$node_url/actuator/health/readiness"); then
      fail "$node_name readiness is unreachable"
    fi
  fi

  enabled=$(json_value "$info_payload" '.cluster.enabled | select(. == true)' "$node_name info.cluster.enabled")
  cluster_id=$(json_value "$info_payload" '.cluster.clusterId | select(type == "string" and length > 0)' "$node_name info.cluster.clusterId")
  reported_node_id=$(json_value "$info_payload" '.cluster.nodeId | select(type == "string" and length > 0)' "$node_name info.cluster.nodeId")
  writer_node_id=$(json_value "$info_payload" '.cluster.releaseWriterNodeId | select(type == "string" and length > 0)' "$node_name info.cluster.releaseWriterNodeId")
  effective_writer=$(json_value "$info_payload" '.cluster.releaseWriter | select(type == "boolean") | tostring' "$node_name info.cluster.releaseWriter")
  readiness=$(json_value "$readiness_payload" '.status | select(type == "string" and length > 0)' "$node_name readiness.status")

  [[ "$enabled" == 'true' ]] || fail "$node_name does not report cluster mode enabled"
  [[ "$reported_node_id" == "$node_name" ]] || fail "$node_name reports nodeId=$reported_node_id"
  [[ "$readiness" == 'UP' ]] || fail "$node_name readiness is $readiness"

  cluster_ids+=("$cluster_id")
  reported_node_ids+=("$reported_node_id")
  writer_node_ids+=("$writer_node_id")
  effective_writers+=("$effective_writer")
done

for ((index = 1; index < 3; index += 1)); do
  [[ "${cluster_ids[$index]}" == "${cluster_ids[0]}" ]] \
    || fail "clusterId differs across nodes"
  [[ "${writer_node_ids[$index]}" == "${writer_node_ids[0]}" ]] \
    || fail "releaseWriterNodeId differs across nodes"
done

for ((left = 0; left < 3; left += 1)); do
  for ((right = left + 1; right < 3; right += 1)); do
    [[ "${reported_node_ids[$left]}" != "${reported_node_ids[$right]}" ]] \
      || fail "nodeId is not unique: ${reported_node_ids[$left]}"
  done
done

configured_writer_node_id="${writer_node_ids[0]}"
writer_count=0
effective_writer_node_id=''
configured_writer_exists=false
for ((index = 0; index < 3; index += 1)); do
  if [[ "${reported_node_ids[$index]}" == "$configured_writer_node_id" ]]; then
    configured_writer_exists=true
  fi
  if [[ "${effective_writers[$index]}" == 'true' ]]; then
    writer_count=$((writer_count + 1))
    effective_writer_node_id="${reported_node_ids[$index]}"
  fi
done

[[ "$configured_writer_exists" == 'true' ]] \
  || fail "configured release writer is not one of the three nodes: $configured_writer_node_id"
[[ $writer_count -eq 1 ]] || fail "expected exactly one effective release writer, observed $writer_count"
[[ "$effective_writer_node_id" == "$configured_writer_node_id" ]] \
  || fail "effective writer $effective_writer_node_id does not match configured writer $configured_writer_node_id"
[[ "$writer_upstream_node_id" == "$configured_writer_node_id" ]] \
  || fail "LB writer upstream points to $writer_upstream_node_id, expected $configured_writer_node_id"

verify_shared_storage release 0 1
verify_shared_storage speech 1 2

printf '%-12s %-12s %-16s %-12s\n' 'NODE' 'READINESS' 'CLUSTER' 'WRITER'
for ((index = 0; index < 3; index += 1)); do
  printf '%-12s %-12s %-16s %-12s\n' \
    "${reported_node_ids[$index]}" 'UP' "${cluster_ids[$index]}" "${effective_writers[$index]}"
done
printf 'PREFLIGHT PASS: writer=%s, LB writer upstream=%s\n' \
  "$configured_writer_node_id" "$writer_upstream_node_id"
