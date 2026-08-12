#!/usr/bin/env bash

set -Eeuo pipefail

readonly GSQL_BIN="/usr/local/opengauss/bin/gsql"
readonly EXPECTED_APP_USER="rbmh_ai"

: "${GS_DB:?GS_DB is required}"
: "${GS_USERNAME:?GS_USERNAME is required}"
: "${GS_PASSWORD:?GS_PASSWORD is required}"

if [[ "$GS_USERNAME" != "$EXPECTED_APP_USER" ]]; then
    echo "[database-prepare] refusing unexpected application user: ${GS_USERNAME}" >&2
    exit 1
fi

export GAUSSHOME="/usr/local/opengauss"
export PATH="${GAUSSHOME}/bin:${PATH}"
export LD_LIBRARY_PATH="${GAUSSHOME}/lib:${LD_LIBRARY_PATH:-}"

echo "[database-prepare] assigning isolated test database and public schema to ${GS_USERNAME}"
"$GSQL_BIN" \
    -X \
    -d "$GS_DB" \
    -U omm \
    -W "$GS_PASSWORD" \
    -v ON_ERROR_STOP=1 \
    --set=db_name="$GS_DB" \
    --set=app_user="$GS_USERNAME" \
    -q <<'SQL'
ALTER DATABASE :"db_name" OWNER TO :"app_user";
ALTER SCHEMA public OWNER TO :"app_user";
GRANT ALL ON SCHEMA public TO :"app_user";
SQL

echo "[database-prepare] local owner preparation passed"
