#!/usr/bin/env bash
set -Eeuo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SERVER_DIR="${PROJECT_ROOT}/server"

TARGET_HOST="${TARGET_HOST:-192.168.204.122}"
TARGET_USER="${TARGET_USER:-root}"
SKIP_TESTS="${SKIP_TESTS:-0}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-90}"

ENVIRONMENT=""
CONFIRM_PRODUCTION=0
DRY_RUN=0

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/publish-xiaoshan.sh testing
  ./scripts/publish-xiaoshan.sh production --confirm-production

Options:
  --confirm-production  Required for an actual production deployment.
  --dry-run             Print the immutable environment mapping and exit.
  -h, --help            Show this help.

Optional environment variables:
  TARGET_HOST, TARGET_USER, SSH_PASS, SKIP_TESTS=1,
  HEALTH_TIMEOUT_SECONDS=90

The script reuses a per-run SSH control connection. If password authentication
is used, the remote password should normally be requested only once.
USAGE
}

for arg in "$@"; do
  case "${arg}" in
    testing|production)
      if [[ -n "${ENVIRONMENT}" ]]; then
        echo "Only one environment may be specified." >&2
        usage >&2
        exit 2
      fi
      ENVIRONMENT="${arg}"
      ;;
    --confirm-production)
      CONFIRM_PRODUCTION=1
      ;;
    --dry-run)
      DRY_RUN=1
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: ${arg}" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "${ENVIRONMENT}" ]]; then
  echo "An environment is required: testing or production." >&2
  usage >&2
  exit 2
fi

case "${ENVIRONMENT}" in
  testing)
    APP_NAME="floating-ball-server-testing"
    DEPLOY_DIR="/data/floating-ball-server-testing"
    JAR_FILE="${DEPLOY_DIR}/floating-ball-server.jar"
    START_SCRIPT="${DEPLOY_DIR}/start.sh"
    PID_FILE="${DEPLOY_DIR}/${APP_NAME}.pid"
    LOG_DIR="${DEPLOY_DIR}/logs"
    RELEASE_DIR="${DEPLOY_DIR}/releases"
    SPEECH_DIR="${DEPLOY_DIR}/speech-files"
    SPRING_PROFILE="xiaoshan-test"
    SERVER_PORT="9090"
    OTHER_PID_FILE="/data/floating-ball-server-production/floating-ball-server-production.pid"
    LEGACY_JAR_FILE=""
    LEGACY_START_SCRIPT=""
    LEGACY_PID_FILE=""
    LEGACY_RELEASE_DIR=""
    LEGACY_SPEECH_DIR=""
    ;;
  production)
    APP_NAME="floating-ball-server-production"
    DEPLOY_DIR="/data/floating-ball-server-production"
    JAR_FILE="${DEPLOY_DIR}/floating-ball-server.jar"
    START_SCRIPT="${DEPLOY_DIR}/start.sh"
    PID_FILE="${DEPLOY_DIR}/${APP_NAME}.pid"
    LOG_DIR="${DEPLOY_DIR}/logs"
    RELEASE_DIR="${DEPLOY_DIR}/releases"
    SPEECH_DIR="${DEPLOY_DIR}/speech-files"
    SPRING_PROFILE="xiaoshan"
    SERVER_PORT="8080"
    OTHER_PID_FILE="/data/floating-ball-server-testing/floating-ball-server-testing.pid"
    LEGACY_JAR_FILE="/data/floating-ball-server.jar"
    LEGACY_START_SCRIPT="/data/start-floating-ball-server.sh"
    LEGACY_PID_FILE="/data/floating-ball-server.pid"
    LEGACY_RELEASE_DIR="/tmp/floating-ball-server/releases"
    LEGACY_SPEECH_DIR="/data/floating-ball/speech-files"
    ;;
esac

print_plan() {
  cat <<PLAN
Environment : ${ENVIRONMENT}
Remote      : ${TARGET_USER}@${TARGET_HOST}
Jar         : ${JAR_FILE}
Start script: ${START_SCRIPT}
PID file    : ${PID_FILE}
Profile     : ${SPRING_PROFILE}
Port        : ${SERVER_PORT}
Logs        : ${LOG_DIR}
Releases    : ${RELEASE_DIR}
Speech files: ${SPEECH_DIR}
PLAN
}

print_plan

if [[ "${DRY_RUN}" == "1" ]]; then
  exit 0
fi

if [[ "${ENVIRONMENT}" == "production" && "${CONFIRM_PRODUCTION}" != "1" ]]; then
  echo "Production deployment refused: add --confirm-production." >&2
  exit 2
fi

need_cmd() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Missing required command: $1" >&2
    exit 1
  fi
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

verify_admin_ui_resources() {
  local jar_file="$1"
  local jar_entries="$2"
  local admin_index_entry="BOOT-INF/classes/static/admin/index.html"
  local verification_dir admin_index_file asset_path asset_entry
  local -a admin_asset_paths=()

  if ! grep -Fqx "${admin_index_entry}" <<< "${jar_entries}"; then
    echo "Packaged jar is missing the admin entry: ${admin_index_entry}" >&2
    return 1
  fi

  verification_dir="$(mktemp -d "${TMPDIR:-/tmp}/pcie-admin-verify.XXXXXX")"
  if ! (cd "${verification_dir}" && jar xf "${jar_file}" "${admin_index_entry}"); then
    rm -rf "${verification_dir}"
    echo "Failed to extract the admin entry from packaged jar." >&2
    return 1
  fi

  admin_index_file="${verification_dir}/${admin_index_entry}"
  while IFS= read -r asset_path; do
    admin_asset_paths+=("${asset_path}")
  done < <(LC_ALL=C grep -Eo '/admin/assets/[^"'"'"'?#[:space:]<>]+' "${admin_index_file}" | sort -u)
  rm -rf "${verification_dir}"

  if [[ "${#admin_asset_paths[@]}" -eq 0 ]]; then
    echo "Packaged admin index does not reference any /admin/assets/* resources." >&2
    return 1
  fi

  for asset_path in "${admin_asset_paths[@]}"; do
    asset_entry="BOOT-INF/classes/static${asset_path}"
    if ! grep -Fqx "${asset_entry}" <<< "${jar_entries}"; then
      echo "Packaged jar is missing an admin resource referenced by index.html: ${asset_entry}" >&2
      return 1
    fi
  done

  echo "Verified packaged admin UI: ${admin_index_entry} and ${#admin_asset_paths[@]} referenced assets."
}

TMP_START_SCRIPT=""
TMP_REMOTE_DEPLOYER=""
# OpenSSH limits Unix-domain socket paths to roughly 100 bytes. macOS commonly
# provides a long per-user TMPDIR, so keep the control socket under /tmp.
SSH_CONTROL_DIR="$(mktemp -d "/tmp/pcie-publish-ssh.XXXXXX")"
SSH_CONTROL_PATH="${SSH_CONTROL_DIR}/control-%C"
SSH_BASE_OPTIONS=(
  -o StrictHostKeyChecking=accept-new
  -o ControlMaster=auto
  -o ControlPersist=10m
  -o ControlPath="${SSH_CONTROL_PATH}"
)

cleanup_local() {
  local exit_code="$?"
  set +e

  rm -f "${TMP_START_SCRIPT:-}" "${TMP_REMOTE_DEPLOYER:-}"
  if [[ -n "${SSH_CONTROL_DIR:-}" && -d "${SSH_CONTROL_DIR}" ]]; then
    if command -v ssh >/dev/null 2>&1; then
      ssh "${SSH_BASE_OPTIONS[@]}" -O exit "${TARGET_USER}@${TARGET_HOST}" >/dev/null 2>&1 || true
    fi
    rm -rf "${SSH_CONTROL_DIR}"
  fi

  exit "${exit_code}"
}

trap cleanup_local EXIT

run_ssh() {
  local remote_cmd="$1"

  if [[ -n "${SSH_PASS:-}" ]]; then
    if command -v sshpass >/dev/null 2>&1; then
      SSHPASS="${SSH_PASS}" sshpass -e ssh "${SSH_BASE_OPTIONS[@]}" "${TARGET_USER}@${TARGET_HOST}" "${remote_cmd}"
    elif command -v expect >/dev/null 2>&1; then
      SSH_PASS="${SSH_PASS}" TARGET="${TARGET_USER}@${TARGET_HOST}" REMOTE_CMD="${remote_cmd}" expect <<'EXPECT'
set timeout -1
spawn ssh -o StrictHostKeyChecking=accept-new $env(TARGET) $env(REMOTE_CMD)
expect {
  -re "(?i)password:" {
    send "$env(SSH_PASS)\r"
    exp_continue
  }
  eof
}
catch wait result
exit [lindex $result 3]
EXPECT
    else
      echo "SSH_PASS is set, but neither sshpass nor expect is available." >&2
      exit 1
    fi
  else
    ssh "${SSH_BASE_OPTIONS[@]}" "${TARGET_USER}@${TARGET_HOST}" "${remote_cmd}"
  fi
}

run_scp() {
  local local_path="$1"
  local remote_path="$2"

  if [[ -n "${SSH_PASS:-}" ]]; then
    if command -v sshpass >/dev/null 2>&1; then
      SSHPASS="${SSH_PASS}" sshpass -e scp "${SSH_BASE_OPTIONS[@]}" "${local_path}" "${TARGET_USER}@${TARGET_HOST}:${remote_path}"
    elif command -v expect >/dev/null 2>&1; then
      SSH_PASS="${SSH_PASS}" LOCAL_PATH="${local_path}" REMOTE_TARGET="${TARGET_USER}@${TARGET_HOST}:${remote_path}" expect <<'EXPECT'
set timeout -1
spawn scp -o StrictHostKeyChecking=accept-new $env(LOCAL_PATH) $env(REMOTE_TARGET)
expect {
  -re "(?i)password:" {
    send "$env(SSH_PASS)\r"
    exp_continue
  }
  eof
}
catch wait result
exit [lindex $result 3]
EXPECT
    else
      echo "SSH_PASS is set, but neither sshpass nor expect is available." >&2
      exit 1
    fi
  else
    scp "${SSH_BASE_OPTIONS[@]}" "${local_path}" "${TARGET_USER}@${TARGET_HOST}:${remote_path}"
  fi
}

build_start_script() {
  local output="$1"

  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -Eeuo pipefail' ''
    printf 'APP_NAME="%s"\n' "${APP_NAME}"
    printf 'BASE_DIR="%s"\n' "${DEPLOY_DIR}"
    printf 'JAR_FILE="%s"\n' "${JAR_FILE}"
    printf 'PID_FILE="%s"\n' "${PID_FILE}"
    printf 'LOG_DIR="%s"\n' "${LOG_DIR}"
    printf 'RELEASE_DIR="%s"\n' "${RELEASE_DIR}"
    printf 'SPEECH_DIR="%s"\n' "${SPEECH_DIR}"
    printf 'SERVER_PORT="%s"\n' "${SERVER_PORT}"
    printf 'SPRING_PROFILES_ACTIVE="%s"\n' "${SPRING_PROFILE}"
    cat <<'REMOTE_START'
CONSOLE_LOG="${LOG_DIR}/console.log"

JAVA_BIN="${JAVA_BIN:-java}"
JAVA_OPTS="${JAVA_OPTS:--Xms512m -Xmx1024m}"
EXTRA_ARGS="${EXTRA_ARGS:-}"

export FB_LOG_PATH="${LOG_DIR}"
export FB_RELEASE_STORAGE_DIR="${RELEASE_DIR}"
export FB_AUDIT_SPEECH_FILE_DIR="${SPEECH_DIR}"

mkdir -p "${LOG_DIR}" "${RELEASE_DIR}" "${SPEECH_DIR}"

pid_is_alive() {
  [[ -f "${PID_FILE}" ]] && kill -0 "$(cat "${PID_FILE}")" >/dev/null 2>&1
}

pid_matches_service() {
  local pid cmdline
  pid="$(cat "${PID_FILE}")"
  [[ -r "/proc/${pid}/cmdline" ]] || return 1
  cmdline="$(tr '\000' ' ' < "/proc/${pid}/cmdline")"
  [[ "${cmdline}" == *"-jar ${JAR_FILE}"* ]]
}

is_running() {
  pid_is_alive && pid_matches_service
}

start() {
  if pid_is_alive; then
    if pid_matches_service; then
      echo "${APP_NAME} is already running, pid=$(cat "${PID_FILE}"), port=${SERVER_PORT}, profile=${SPRING_PROFILES_ACTIVE}"
      return 0
    fi
    echo "Refusing to start: ${PID_FILE} belongs to another process." >&2
    exit 1
  fi

  rm -f "${PID_FILE}"
  if [[ ! -f "${JAR_FILE}" ]]; then
    echo "Jar file not found: ${JAR_FILE}" >&2
    exit 1
  fi

  cd "${BASE_DIR}"
  nohup "${JAVA_BIN}" ${JAVA_OPTS} \
    -Dspring.profiles.active="${SPRING_PROFILES_ACTIVE}" \
    -DLOG_PATH="${LOG_DIR}" \
    -jar "${JAR_FILE}" \
    --server.port="${SERVER_PORT}" \
    --logging.file.path="${LOG_DIR}" \
    ${EXTRA_ARGS} >> "${CONSOLE_LOG}" 2>&1 &

  echo $! > "${PID_FILE}"
  sleep 5
  if ! is_running; then
    rm -f "${PID_FILE}"
    echo "${APP_NAME} failed to start. Recent console log:" >&2
    tail -n 80 "${CONSOLE_LOG}" >&2 || true
    exit 1
  fi

  echo "${APP_NAME} started, pid=$(cat "${PID_FILE}"), port=${SERVER_PORT}, profile=${SPRING_PROFILES_ACTIVE}"
  echo "Log file: ${LOG_DIR}/floating-ball-server.log"
  echo "Console log: ${CONSOLE_LOG}"
}

stop() {
  if ! pid_is_alive; then
    echo "${APP_NAME} is not running"
    rm -f "${PID_FILE}"
    return 0
  fi
  if ! pid_matches_service; then
    echo "Refusing to stop: ${PID_FILE} belongs to another process." >&2
    exit 1
  fi

  local pid
  pid="$(cat "${PID_FILE}")"
  kill "${pid}"
  for _ in $(seq 1 30); do
    if ! kill -0 "${pid}" >/dev/null 2>&1; then
      rm -f "${PID_FILE}"
      echo "${APP_NAME} stopped"
      return 0
    fi
    sleep 1
  done

  echo "Force stopping ${APP_NAME}, pid=${pid}"
  kill -9 "${pid}" >/dev/null 2>&1 || true
  rm -f "${PID_FILE}"
}

status() {
  if is_running; then
    echo "${APP_NAME} is running, pid=$(cat "${PID_FILE}"), port=${SERVER_PORT}, profile=${SPRING_PROFILES_ACTIVE}"
  elif pid_is_alive; then
    echo "${APP_NAME} PID file belongs to another process: ${PID_FILE}" >&2
    return 1
  else
    echo "${APP_NAME} is not running, port=${SERVER_PORT}, profile=${SPRING_PROFILES_ACTIVE}"
    return 1
  fi
}

logs() {
  local app_log="${LOG_DIR}/floating-ball-server.log"
  if [[ -f "${app_log}" ]]; then
    tail -n "${TAIL_LINES:-200}" -f "${app_log}"
  else
    tail -n "${TAIL_LINES:-200}" -f "${CONSOLE_LOG}"
  fi
}

case "${1:-start}" in
  start) start ;;
  stop) stop ;;
  restart) stop; start ;;
  status) status ;;
  logs) logs ;;
  *) echo "Usage: $0 {start|stop|restart|status|logs}" >&2; exit 1 ;;
esac
REMOTE_START
  } > "${output}"
}

build_remote_deployer() {
  local output="$1"
  local release_id="$2"
  local expected_sha256="$3"
  local upload_jar="$4"
  local upload_start="$5"

  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -Eeuo pipefail' ''
    printf 'APP_NAME="%s"\n' "${APP_NAME}"
    printf 'JAR_FILE="%s"\n' "${JAR_FILE}"
    printf 'START_SCRIPT="%s"\n' "${START_SCRIPT}"
    printf 'PID_FILE="%s"\n' "${PID_FILE}"
    printf 'LOG_DIR="%s"\n' "${LOG_DIR}"
    printf 'RELEASE_DIR="%s"\n' "${RELEASE_DIR}"
    printf 'SPEECH_DIR="%s"\n' "${SPEECH_DIR}"
    printf 'SPRING_PROFILE="%s"\n' "${SPRING_PROFILE}"
    printf 'SERVER_PORT="%s"\n' "${SERVER_PORT}"
    printf 'OTHER_PID_FILE="%s"\n' "${OTHER_PID_FILE}"
    printf 'LEGACY_JAR_FILE="%s"\n' "${LEGACY_JAR_FILE}"
    printf 'LEGACY_START_SCRIPT="%s"\n' "${LEGACY_START_SCRIPT}"
    printf 'LEGACY_PID_FILE="%s"\n' "${LEGACY_PID_FILE}"
    printf 'LEGACY_RELEASE_DIR="%s"\n' "${LEGACY_RELEASE_DIR}"
    printf 'LEGACY_SPEECH_DIR="%s"\n' "${LEGACY_SPEECH_DIR}"
    printf 'RELEASE_ID="%s"\n' "${release_id}"
    printf 'EXPECTED_SHA256="%s"\n' "${expected_sha256}"
    printf 'UPLOAD_JAR="%s"\n' "${upload_jar}"
    printf 'UPLOAD_START="%s"\n' "${upload_start}"
    printf 'HEALTH_TIMEOUT_SECONDS="%s"\n' "${HEALTH_TIMEOUT_SECONDS}"
    cat <<'REMOTE_DEPLOY'

JAR_BACKUP="${JAR_FILE}.backup-${RELEASE_ID}"
SCRIPT_BACKUP="${START_SCRIPT}.backup-${RELEASE_ID}"
LOG_ARCHIVE="${LOG_DIR}/archive/${RELEASE_ID}"
ORIGINAL_RUNNING=0
LEGACY_RUNNING=0
OTHER_PID_BEFORE=""
MUTATION_STARTED=0
DEPLOY_SUCCEEDED=0

cleanup() {
  rm -f "${UPLOAD_JAR}" "${UPLOAD_START}" "$0"
}

rollback() {
  local exit_code="$?"
  trap - ERR
  set +e

  if [[ "${MUTATION_STARTED}" == "1" && "${DEPLOY_SUCCEEDED}" != "1" ]]; then
    echo "Deployment failed; rolling back ${APP_NAME}." >&2
    if [[ -x "${START_SCRIPT}" ]]; then
      "${START_SCRIPT}" stop >/dev/null 2>&1 || true
    fi
    if [[ -f "${JAR_BACKUP}" ]]; then
      cp -p "${JAR_BACKUP}" "${JAR_FILE}"
    else
      rm -f "${JAR_FILE}"
    fi
    if [[ -f "${SCRIPT_BACKUP}" ]]; then
      cp -p "${SCRIPT_BACKUP}" "${START_SCRIPT}"
      chmod +x "${START_SCRIPT}"
    else
      rm -f "${START_SCRIPT}"
    fi
    if [[ "${ORIGINAL_RUNNING}" == "1" && -x "${START_SCRIPT}" ]]; then
      "${START_SCRIPT}" start || true
    elif [[ "${LEGACY_RUNNING}" == "1" && -x "${LEGACY_START_SCRIPT}" ]]; then
      "${LEGACY_START_SCRIPT}" start || true
    fi
  fi

  cleanup
  exit "${exit_code}"
}

trap cleanup EXIT
trap rollback ERR

if command -v jar >/dev/null 2>&1; then
  JAR_TOOL="$(command -v jar)"
elif [[ -x /usr/local/jdk8/bin/jar ]]; then
  JAR_TOOL="/usr/local/jdk8/bin/jar"
else
  echo "Remote jar command not found." >&2
  exit 1
fi

actual_sha256="$(sha256sum "${UPLOAD_JAR}" | awk '{print $1}')"
if [[ "${actual_sha256}" != "${EXPECTED_SHA256}" ]]; then
  echo "Uploaded jar checksum mismatch." >&2
  exit 1
fi

jar_entries="$("${JAR_TOOL}" tf "${UPLOAD_JAR}")"
grep -Fqx "BOOT-INF/classes/application-${SPRING_PROFILE}.yml" <<< "${jar_entries}"
grep -Fqx 'BOOT-INF/classes/logback-spring.xml' <<< "${jar_entries}"
bash -n "${UPLOAD_START}"
grep -Fqx "APP_NAME=\"${APP_NAME}\"" "${UPLOAD_START}"
grep -Fqx "JAR_FILE=\"${JAR_FILE}\"" "${UPLOAD_START}"
grep -Fqx "PID_FILE=\"${PID_FILE}\"" "${UPLOAD_START}"
grep -Fqx "SERVER_PORT=\"${SERVER_PORT}\"" "${UPLOAD_START}"
grep -Fqx "SPRING_PROFILES_ACTIVE=\"${SPRING_PROFILE}\"" "${UPLOAD_START}"

current_pid=""
if [[ -f "${PID_FILE}" ]]; then
  current_pid="$(cat "${PID_FILE}")"
  if kill -0 "${current_pid}" >/dev/null 2>&1; then
    current_cmdline="$(tr '\000' ' ' < "/proc/${current_pid}/cmdline")"
    if [[ "${current_cmdline}" != *"-jar ${JAR_FILE}"* || "${current_cmdline}" != *"spring.profiles.active=${SPRING_PROFILE}"* ]]; then
      echo "Target PID does not belong to ${APP_NAME}; refusing deployment." >&2
      exit 1
    fi
    ORIGINAL_RUNNING=1
  else
    current_pid=""
  fi
fi

legacy_pid=""
if [[ -n "${LEGACY_PID_FILE}" && -f "${LEGACY_PID_FILE}" ]]; then
  legacy_pid="$(cat "${LEGACY_PID_FILE}")"
  if kill -0 "${legacy_pid}" >/dev/null 2>&1; then
    legacy_cmdline="$(tr '\000' ' ' < "/proc/${legacy_pid}/cmdline")"
    if [[ "${legacy_cmdline}" != *"-jar ${LEGACY_JAR_FILE}"* || "${legacy_cmdline}" != *"spring.profiles.active=${SPRING_PROFILE}"* ]]; then
      echo "Legacy production PID does not match the expected service; refusing deployment." >&2
      exit 1
    fi
    LEGACY_RUNNING=1
  else
    legacy_pid=""
  fi
fi

if [[ "${ORIGINAL_RUNNING}" == "1" && "${LEGACY_RUNNING}" == "1" ]]; then
  echo "Both current and legacy production PID files are active; refusing deployment." >&2
  exit 1
fi

listener="$(ss -ltnp 2>/dev/null | grep -E ":${SERVER_PORT}[[:space:]]" || true)"
if [[ -n "${listener}" ]]; then
  expected_listener_pid="${current_pid:-${legacy_pid}}"
  if [[ -z "${expected_listener_pid}" || "${listener}" != *"pid=${expected_listener_pid},"* ]]; then
    echo "Port ${SERVER_PORT} is owned by another process; refusing deployment." >&2
    exit 1
  fi
fi

if [[ -f "${OTHER_PID_FILE}" ]]; then
  other_pid="$(cat "${OTHER_PID_FILE}")"
  if kill -0 "${other_pid}" >/dev/null 2>&1; then
    OTHER_PID_BEFORE="${other_pid}"
  fi
fi

if [[ -f "${JAR_FILE}" ]]; then
  cp -p "${JAR_FILE}" "${JAR_BACKUP}"
fi
if [[ -f "${START_SCRIPT}" ]]; then
  cp -p "${START_SCRIPT}" "${SCRIPT_BACKUP}"
fi

if [[ "${LEGACY_RUNNING}" == "1" ]]; then
  mkdir -p "${RELEASE_DIR}" "${SPEECH_DIR}"
  if [[ -d "${LEGACY_RELEASE_DIR}" ]]; then
    cp -a -n "${LEGACY_RELEASE_DIR}/." "${RELEASE_DIR}/"
  fi
  if [[ -d "${LEGACY_SPEECH_DIR}" ]]; then
    cp -a -n "${LEGACY_SPEECH_DIR}/." "${SPEECH_DIR}/"
  fi
fi

MUTATION_STARTED=1
if [[ "${ORIGINAL_RUNNING}" == "1" ]]; then
  "${START_SCRIPT}" stop
elif [[ "${LEGACY_RUNNING}" == "1" ]]; then
  "${LEGACY_START_SCRIPT}" stop
fi

if ss -ltnp 2>/dev/null | grep -qE ":${SERVER_PORT}[[:space:]]"; then
  echo "Port ${SERVER_PORT} is still occupied after stopping ${APP_NAME}." >&2
  exit 1
fi

mkdir -p "${LOG_ARCHIVE}"
for log_file in "${LOG_DIR}/console.log" "${LOG_DIR}/floating-ball-server.log"; do
  if [[ -f "${log_file}" ]]; then
    mv "${log_file}" "${LOG_ARCHIVE}/"
  fi
done

mv -f "${UPLOAD_JAR}" "${JAR_FILE}"
mv -f "${UPLOAD_START}" "${START_SCRIPT}"
chmod +x "${START_SCRIPT}"
"${START_SCRIPT}" start

ready=0
for _ in $(seq 1 "${HEALTH_TIMEOUT_SECONDS}"); do
  if curl -fsS --max-time 2 "http://127.0.0.1:${SERVER_PORT}/admin/" >/dev/null; then
    ready=1
    break
  fi
  if ! "${START_SCRIPT}" status >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if [[ "${ready}" != "1" ]]; then
  echo "Health check timed out for ${APP_NAME}." >&2
  exit 1
fi

new_pid="$(cat "${PID_FILE}")"
new_cmdline="$(tr '\000' ' ' < "/proc/${new_pid}/cmdline")"
[[ "${new_cmdline}" == *"-jar ${JAR_FILE}"* ]]
[[ "${new_cmdline}" == *"spring.profiles.active=${SPRING_PROFILE}"* ]]
[[ "${new_cmdline}" == *"--server.port=${SERVER_PORT}"* ]]

profile_log="$(grep -h "profile is active: \"${SPRING_PROFILE}\"" "${LOG_DIR}/console.log" "${LOG_DIR}/floating-ball-server.log" 2>/dev/null | tail -n 1 || true)"
if [[ -z "${profile_log}" ]]; then
  echo "Active profile was not confirmed in the new logs." >&2
  exit 1
fi

if [[ -n "${OTHER_PID_BEFORE}" ]]; then
  if ! kill -0 "${OTHER_PID_BEFORE}" >/dev/null 2>&1; then
    echo "The other environment process changed during deployment." >&2
    exit 1
  fi
  other_pid_after="$(cat "${OTHER_PID_FILE}")"
  if [[ "${other_pid_after}" != "${OTHER_PID_BEFORE}" ]]; then
    echo "The other environment PID changed during deployment." >&2
    exit 1
  fi
fi

final_sha256="$(sha256sum "${JAR_FILE}" | awk '{print $1}')"
[[ "${final_sha256}" == "${EXPECTED_SHA256}" ]]

DEPLOY_SUCCEEDED=1
echo "Deployment succeeded."
echo "environment=${APP_NAME}"
echo "pid=${new_pid}"
echo "profile=${SPRING_PROFILE}"
echo "port=${SERVER_PORT}"
echo "sha256=${final_sha256}"
echo "profile_log=${profile_log}"
echo "jar_backup=${JAR_BACKUP}"
echo "script_backup=${SCRIPT_BACKUP}"
echo "log_archive=${LOG_ARCHIVE}"
if [[ -n "${OTHER_PID_BEFORE}" ]]; then
  echo "other_environment_pid=${OTHER_PID_BEFORE} (unchanged)"
fi
if [[ "${LEGACY_RUNNING}" == "1" ]]; then
  echo "legacy_production_migrated=${LEGACY_JAR_FILE}"
fi
REMOTE_DEPLOY
  } > "${output}"
}

need_cmd mvn
need_cmd ssh
need_cmd scp
need_cmd jar
need_cmd curl

MAVEN_ARGS=(clean package)
if [[ "${SKIP_TESTS}" == "1" ]]; then
  MAVEN_ARGS+=(-DskipTests)
fi
echo "Building server and current admin UI: mvn -f ${SERVER_DIR}/pom.xml ${MAVEN_ARGS[*]}"
mvn -f "${SERVER_DIR}/pom.xml" "${MAVEN_ARGS[@]}"

JAR_FILES=()
while IFS= read -r packaged_jar; do
  JAR_FILES+=("${packaged_jar}")
done < <(find "${SERVER_DIR}/target" -maxdepth 1 -type f -name 'pcie-server-*.jar' ! -name '*.original' | sort)

if [[ "${#JAR_FILES[@]}" -ne 1 ]]; then
  echo "Expected exactly one packaged jar under ${SERVER_DIR}/target; found ${#JAR_FILES[@]}." >&2
  exit 1
fi

LOCAL_JAR="${JAR_FILES[0]}"
LOCAL_JAR_ENTRIES="$(jar tf "${LOCAL_JAR}")"
grep -Fqx "BOOT-INF/classes/application-${SPRING_PROFILE}.yml" <<< "${LOCAL_JAR_ENTRIES}"
grep -Fqx 'BOOT-INF/classes/logback-spring.xml' <<< "${LOCAL_JAR_ENTRIES}"
verify_admin_ui_resources "${LOCAL_JAR}" "${LOCAL_JAR_ENTRIES}"
LOCAL_SHA256="$(sha256_file "${LOCAL_JAR}")"
RELEASE_ID="$(date +%Y%m%d%H%M%S)-$$"
REMOTE_UPLOAD_JAR="${JAR_FILE}.upload-${RELEASE_ID}"
REMOTE_UPLOAD_START="${START_SCRIPT}.upload-${RELEASE_ID}"
REMOTE_DEPLOYER="${DEPLOY_DIR}/.publish-xiaoshan-${ENVIRONMENT}-${RELEASE_ID}.sh"

TMP_START_SCRIPT="$(mktemp "${TMPDIR:-/tmp}/pcie-publish-start.XXXXXX")"
TMP_REMOTE_DEPLOYER="$(mktemp "${TMPDIR:-/tmp}/pcie-publish-deployer.XXXXXX")"

build_start_script "${TMP_START_SCRIPT}"
build_remote_deployer "${TMP_REMOTE_DEPLOYER}" "${RELEASE_ID}" "${LOCAL_SHA256}" "${REMOTE_UPLOAD_JAR}" "${REMOTE_UPLOAD_START}"
chmod +x "${TMP_START_SCRIPT}" "${TMP_REMOTE_DEPLOYER}"
bash -n "${TMP_START_SCRIPT}"
bash -n "${TMP_REMOTE_DEPLOYER}"

echo "Remote SSH connection will be reused for this publish; password authentication should prompt at most once."
echo "Preparing remote directories."
run_ssh "mkdir -p '${DEPLOY_DIR}' '${LOG_DIR}' '${RELEASE_DIR}' '${SPEECH_DIR}'"

echo "Uploading jar to temporary path: ${REMOTE_UPLOAD_JAR}"
run_scp "${LOCAL_JAR}" "${REMOTE_UPLOAD_JAR}"
echo "Uploading start script to temporary path: ${REMOTE_UPLOAD_START}"
run_scp "${TMP_START_SCRIPT}" "${REMOTE_UPLOAD_START}"
echo "Uploading guarded remote deployer."
run_scp "${TMP_REMOTE_DEPLOYER}" "${REMOTE_DEPLOYER}"

echo "Deploying ${ENVIRONMENT}; only ${JAR_FILE} and ${START_SCRIPT} may be replaced."
run_ssh "chmod +x '${REMOTE_DEPLOYER}' && bash '${REMOTE_DEPLOYER}'"

echo "Verifying external admin endpoint."
curl -fsS --max-time 10 "http://${TARGET_HOST}:${SERVER_PORT}/admin/" >/dev/null
echo "Publish complete: http://${TARGET_HOST}:${SERVER_PORT}/admin/"
