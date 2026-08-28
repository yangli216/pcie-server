#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="${PROJECT_ROOT}/server"
ADMIN_DIR="${SERVER_DIR}/src/main/admin"
TARGET_DIR="${SERVER_DIR}/target"
APP_NAME="pcie-server"
DEFAULT_ENV="test"
ENVIRONMENT="${DEFAULT_ENV}"
PROFILE=""
PORT=""
JAR_FILE=""
PID_FILE=""
LOG_FILE=""

# 构建与运行标志
BUILD_FRONTEND_FLAG=0
BUILD_ALL_FLAG=0
FOREGROUND_FLAG=0
BUILD_TARGET="all"

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/manage-server.sh <command> [options]

Commands:
  start               Start server (default: background daemon)
  stop                Stop server for the specified environment
  restart             Restart server
  status              Check server running status
  logs                Tail live log output
  dev                 Quick local dev mode (build frontend + start backend in foreground)
  dev-frontend        Start frontend Vite dev server (HMR on port 5174)
  build [target]      Build components: all (default) | frontend | backend
  install-frontend    Install frontend dependencies (npm install)

Options:
  --env <env>         Environment profile (default: test)
                      Supported: test (8080), development (8088), xiaoshan-test (9090), product (8080)
  -b, --build-frontend Build frontend before starting (fast Vite build to resources/static/admin)
  -B, --build-all      Full Maven build before starting (includes frontend & jar packaging)
  -f, --foreground     Run server in foreground (output logs directly, Ctrl+C to stop)
  -h, --help           Show this help message

Examples:
  ./scripts/manage-server.sh start --env test
  ./scripts/manage-server.sh start -b --env test          # Build frontend then start backend daemon
  ./scripts/manage-server.sh dev --env development        # One-click dev mode (build frontend + foreground run)
  ./scripts/manage-server.sh dev-frontend                 # Start Vite dev server for admin UI
  ./scripts/manage-server.sh build frontend               # Only build frontend admin
  ./scripts/manage-server.sh build backend                # Only package backend jar
  ./scripts/manage-server.sh stop --env test
  ./scripts/manage-server.sh logs --env test
USAGE
}

resolve_environment() {
  case "${ENVIRONMENT}" in
    test)
      PROFILE="test"
      PORT="8080"
      ;;
    development|dev)
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

  JAR_FILE="$(find_latest_jar)"
  PID_FILE="${TARGET_DIR}/${APP_NAME}-${ENVIRONMENT}.pid"
  LOG_FILE="${TARGET_DIR}/${APP_NAME}-${ENVIRONMENT}.log"
}

find_latest_jar() {
  if [[ ! -d "${TARGET_DIR}" ]]; then
    return 0
  fi
  # Find latest non-source and non-javadoc jar
  local jar
  jar="$(find "${TARGET_DIR}" -maxdepth 1 -name "${APP_NAME}-*.jar" ! -name "*-sources.jar" ! -name "*-javadoc.jar" 2>/dev/null | sort -V | tail -n 1 || true)"
  echo "${jar}"
}

ensure_java() {
  if ! command -v java >/dev/null 2>&1; then
    echo "Error: Java runtime not found in PATH." >&2
    exit 1
  fi
}

ensure_maven() {
  if ! command -v mvn >/dev/null 2>&1; then
    echo "Error: Maven (mvn) not found in PATH." >&2
    exit 1
  fi
}

ensure_node() {
  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    echo "Error: Node.js / npm not found in PATH." >&2
    exit 1
  fi
}

ensure_frontend_deps() {
  if [[ ! -d "${ADMIN_DIR}/node_modules" ]]; then
    echo "Frontend node_modules not found. Installing dependencies in ${ADMIN_DIR}..."
    ensure_node
    npm --prefix "${ADMIN_DIR}" install
  fi
}

ensure_jar() {
  if [[ -z "${JAR_FILE}" || ! -f "${JAR_FILE}" ]]; then
    echo "Jar not found in ${TARGET_DIR}." >&2
    echo "Run './scripts/manage-server.sh build --env ${ENVIRONMENT}' first." >&2
    exit 1
  fi
}

is_running() {
  if [[ -f "${PID_FILE}" ]]; then
    local pid
    pid="$(cat "${PID_FILE}" 2>/dev/null || true)"
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      return 0
    fi
  fi
  return 1
}

build_frontend() {
  ensure_node
  ensure_frontend_deps
  echo ">>> Building frontend admin (pcie-admin via Vite)..."
  npm --prefix "${ADMIN_DIR}" run build
  echo ">>> Frontend build completed -> ${SERVER_DIR}/src/main/resources/static/admin"
}

build_backend() {
  ensure_java
  ensure_maven
  echo ">>> Building backend jar (profile: ${PROFILE})..."
  mvn -f "${SERVER_DIR}/pom.xml" -DskipTests -Dspring.profiles.active="${PROFILE}" package
  JAR_FILE="$(find_latest_jar)"
  echo ">>> Backend jar ready -> ${JAR_FILE}"
}

build_all() {
  ensure_java
  ensure_maven
  echo ">>> Building entire project (frontend + backend jar)..."
  build_frontend
  build_backend
}

start_app() {
  ensure_java

  if [[ "${BUILD_ALL_FLAG}" -eq 1 ]]; then
    build_all
  elif [[ "${BUILD_FRONTEND_FLAG}" -eq 1 ]]; then
    build_frontend
    # If no jar exists yet, trigger backend build
    if [[ -z "${JAR_FILE}" || ! -f "${JAR_FILE}" ]]; then
      build_backend
    fi
  fi

  # Refresh jar reference in case it was just built
  JAR_FILE="$(find_latest_jar)"
  ensure_jar

  if is_running; then
    echo "Service is already running with PID $(cat "${PID_FILE}")."
    return 0
  fi

  mkdir -p "${TARGET_DIR}"

  if [[ "${FOREGROUND_FLAG}" -eq 1 ]]; then
    echo "Starting ${APP_NAME} (${ENVIRONMENT}) on port ${PORT} in foreground..."
    echo "Jar: ${JAR_FILE}"
    echo "Press Ctrl+C to stop."
    exec java -jar "${JAR_FILE}" --spring.profiles.active="${PROFILE}" --server.port="${PORT}"
  else
    echo "Starting ${APP_NAME} (${ENVIRONMENT}) on port ${PORT}..."
    nohup java -jar "${JAR_FILE}" --spring.profiles.active="${PROFILE}" --server.port="${PORT}" >"${LOG_FILE}" 2>&1 &
    echo $! > "${PID_FILE}"
    sleep 2

    if is_running; then
      echo "Started successfully. PID=$(cat "${PID_FILE}")"
      echo "Logs: ${LOG_FILE}"
      echo "Admin UI available at: http://localhost:${PORT}/admin/"
    else
      echo "Failed to start service. Check the log file: ${LOG_FILE}" >&2
      exit 1
    fi
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
    echo "Admin UI: http://localhost:${PORT}/admin/"
  else
    echo "Stopped (${ENVIRONMENT})"
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

dev_mode() {
  FOREGROUND_FLAG=1
  BUILD_FRONTEND_FLAG=1
  start_app
}

dev_frontend() {
  ensure_node
  ensure_frontend_deps
  echo ">>> Starting frontend Vite dev server on http://localhost:5174/admin/ ..."
  npm --prefix "${ADMIN_DIR}" run dev
}

parse_args() {
  COMMAND=""
  while [[ $# -gt 0 ]]; do
    case "$1" in
      start|stop|restart|status|logs|dev|dev-frontend|install-frontend)
        if [[ -n "${COMMAND}" ]]; then
          echo "Error: Only one command is supported." >&2
          exit 2
        fi
        COMMAND="$1"
        ;;
      build)
        if [[ -n "${COMMAND}" ]]; then
          echo "Error: Only one command is supported." >&2
          exit 2
        fi
        COMMAND="build"
        if [[ $# -ge 2 && ( "$2" == "all" || "$2" == "frontend" || "$2" == "backend" ) ]]; then
          BUILD_TARGET="$2"
          shift
        fi
        ;;
      --env)
        if [[ $# -lt 2 ]]; then
          echo "Error: Missing value for --env" >&2
          exit 2
        fi
        ENVIRONMENT="$2"
        shift
        ;;
      -b|--build-frontend)
        BUILD_FRONTEND_FLAG=1
        ;;
      -B|--build-all)
        BUILD_ALL_FLAG=1
        ;;
      -f|--foreground)
        FOREGROUND_FLAG=1
        ;;
      -h|--help)
        usage
        exit 0
        ;;
      *)
        echo "Error: Unknown argument: $1" >&2
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
    dev)
      dev_mode
      ;;
    dev-frontend)
      dev_frontend
      ;;
    install-frontend)
      ensure_node
      npm --prefix "${ADMIN_DIR}" install
      ;;
    build)
      case "${BUILD_TARGET}" in
        frontend)
          build_frontend
          ;;
        backend)
          build_backend
          ;;
        all|*)
          build_all
          ;;
      esac
      ;;
  esac
}

main "$@"
