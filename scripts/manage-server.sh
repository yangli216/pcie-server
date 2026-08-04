#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="${PROJECT_ROOT}/server"
TARGET_DIR="${SERVER_DIR}/target"
APP_NAME="pcie-server"
DEFAULT_ENV="test"
ENVIRONMENT="${DEFAULT_ENV}"
PROFILE=""
PORT=""
JAR_FILE=""
PID_FILE=""
LOG_FILE=""

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/manage-server.sh start [--env test|development|xiaoshan-test|product]
  ./scripts/manage-server.sh stop [--env ...]
  ./scripts/manage-server.sh restart [--env ...]
  ./scripts/manage-server.sh status [--env ...]
  ./scripts/manage-server.sh logs [--env ...]
  ./scripts/manage-server.sh build [--env ...]

Examples:
  ./scripts/manage-server.sh start --env test
  ./scripts/manage-server.sh restart --env test
  ./scripts/manage-server.sh status
  ./scripts/manage-server.sh logs

Environment defaults:
  test          -> profile test, port 8080
  development   -> profile development, port 8088
  xiaoshan-test -> profile xiaoshan-test, port 9090
  product       -> profile product, port 8080
USAGE
}

resolve_environment() {
  case "${ENVIRONMENT}" in
    test)
      PROFILE="test"
      PORT="8080"
      ;;
    development)
      PROFILE="development"
      PORT="8088"
      ;;
    xiaoshan-test)
      PROFILE="xiaoshan-test"
      PORT="9090"
      ;;
    product|prod)
      PROFILE="product"
      PORT="8080"
      ;;
    *)
      echo "Unsupported environment: ${ENVIRONMENT}" >&2
      exit 2
      ;;
  esac

  JAR_FILE="${TARGET_DIR}/${APP_NAME}-0.2.0-SNAPSHOT.jar"
  PID_FILE="${TARGET_DIR}/${APP_NAME}-${ENVIRONMENT}.pid"
  LOG_FILE="${TARGET_DIR}/${APP_NAME}-${ENVIRONMENT}.log"
}

ensure_java() {
  if ! command -v java >/dev/null 2>&1; then
    echo "Java runtime not found in PATH." >&2
    exit 1
  fi
}

ensure_jar() {
  if [[ ! -f "${JAR_FILE}" ]]; then
    echo "Jar not found: ${JAR_FILE}" >&2
    echo "Run './scripts/manage-server.sh build --env ${ENVIRONMENT}' first." >&2
    exit 1
  fi
}

is_running() {
  if [[ -f "${PID_FILE}" ]]; then
    local pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      return 0
    fi
  fi
  return 1
}

start_app() {
  ensure_java
  ensure_jar

  if is_running; then
    echo "Service already running with PID $(cat "${PID_FILE}")."
    return 0
  fi

  mkdir -p "${TARGET_DIR}"
  echo "Starting ${APP_NAME} (${ENVIRONMENT}) on port ${PORT}..."
  nohup java -jar "${JAR_FILE}" --spring.profiles.active="${PROFILE}" --server.port="${PORT}" >"${LOG_FILE}" 2>&1 &
  echo $! > "${PID_FILE}"
  sleep 2

  if is_running; then
    echo "Started successfully. PID=$(cat "${PID_FILE}")"
    echo "Logs: ${LOG_FILE}"
  else
    echo "Failed to start service. Check the log file: ${LOG_FILE}" >&2
    exit 1
  fi
}

stop_app() {
  if ! is_running; then
    echo "Service is not running."
    rm -f "${PID_FILE}"
    return 0
  fi

  local pid
  pid="$(cat "${PID_FILE}")"
  echo "Stopping ${APP_NAME} (${ENVIRONMENT}) PID=${pid}..."
  kill "${pid}" 2>/dev/null || true
  for _ in {1..10}; do
    if ! kill -0 "${pid}" 2>/dev/null; then
      rm -f "${PID_FILE}"
      echo "Stopped."
      return 0
    fi
    sleep 1
  done

  kill -9 "${pid}" 2>/dev/null || true
  rm -f "${PID_FILE}"
  echo "Stopped forcefully."
}

status_app() {
  if is_running; then
    echo "Running: PID=$(cat "${PID_FILE}") | Profile=${PROFILE} | Port=${PORT}"
  else
    echo "Stopped"
  fi
}

logs_app() {
  if [[ -f "${LOG_FILE}" ]]; then
    tail -f "${LOG_FILE}"
  else
    echo "Log file not found: ${LOG_FILE}"
    exit 1
  fi
}

build_app() {
  ensure_java
  echo "Building ${APP_NAME} with profile ${PROFILE}..."
  mvn -f "${SERVER_DIR}/pom.xml" -DskipTests -Dspring.profiles.active="${PROFILE}" package
}

parse_args() {
  COMMAND=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      start|stop|restart|status|logs|build)
        if [[ -n "${COMMAND}" ]]; then
          echo "Only one command is supported." >&2
          exit 2
        fi
        COMMAND="$1"
        ;;
      --env)
        if [[ $# -lt 2 ]]; then
          echo "Missing value for --env" >&2
          exit 2
        fi
        ENVIRONMENT="$2"
        shift
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        echo "Unknown argument: $1" >&2
        usage >&2
        exit 2
        ;;
    esac
    shift
  done

  if [[ -z "${COMMAND}" ]]; then
    usage
    exit 2
  fi
}

main() {
  parse_args "$@"
  resolve_environment

  case "${COMMAND}" in
    start)
      start_app
      ;;
    stop)
      stop_app
      ;;
    restart)
      stop_app || true
      start_app
      ;;
    status)
      status_app
      ;;
    logs)
      logs_app
      ;;
    build)
      build_app
      ;;
  esac
}

main "$@"
