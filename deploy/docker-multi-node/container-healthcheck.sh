#!/usr/bin/env bash
set -euo pipefail

readonly health_host="${FB_MANAGEMENT_HEALTH_HOST:-127.0.0.1}"
readonly health_port="${FB_MANAGEMENT_PORT:-8081}"
readonly health_path="/actuator/health/readiness"
readonly read_timeout_seconds="${FB_CONTAINER_HEALTH_TIMEOUT_SECONDS:-3}"

exec 3<>"/dev/tcp/${health_host}/${health_port}"
printf 'GET %s HTTP/1.1\r\nHost: %s:%s\r\nConnection: close\r\n\r\n' \
    "${health_path}" "${health_host}" "${health_port}" >&3

IFS= read -r -t "${read_timeout_seconds}" status_line <&3
status_line="${status_line%$'\r'}"

case "${status_line}" in
    'HTTP/1.0 200 '*|'HTTP/1.1 200 '*)
        exit 0
        ;;
    *)
        printf 'readiness check failed: %s\n' "${status_line:-no HTTP status}" >&2
        exit 1
        ;;
esac
