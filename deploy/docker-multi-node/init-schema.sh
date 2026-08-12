#!/usr/bin/env bash

set -Eeuo pipefail

readonly GSQL_BIN="/usr/local/opengauss/bin/gsql"
readonly EXPECTED_APP_USER="rbmh_ai"
readonly DOCKER_MARKER_TABLE="c_ai_docker_schema_state"
readonly EXPECTED_MARKER="docker_full_init_v1"

require_env() {
    local name="$1"
    if [[ -z "${!name:-}" ]]; then
        echo "[schema-init] required environment variable is empty: ${name}" >&2
        exit 1
    fi
}

require_env PCIE_DB_HOST
require_env PCIE_DB_PORT
require_env PCIE_DB_NAME
require_env PCIE_DB_USERNAME
require_env PCIE_DB_PASSWORD
require_env PCIE_SCHEMA_SQL

if [[ "$PCIE_DB_USERNAME" != "$EXPECTED_APP_USER" ]]; then
    echo "[schema-init] refusing unexpected application user: ${PCIE_DB_USERNAME}" >&2
    exit 1
fi

if [[ ! -x "$GSQL_BIN" ]]; then
    echo "[schema-init] gsql is not executable: ${GSQL_BIN}" >&2
    exit 1
fi

export GAUSSHOME="/usr/local/opengauss"
export PATH="${GAUSSHOME}/bin:${PATH}"
export LD_LIBRARY_PATH="${GAUSSHOME}/lib:${LD_LIBRARY_PATH:-}"

gsql_app() {
    "$GSQL_BIN" \
        -X \
        -h "$PCIE_DB_HOST" \
        -p "$PCIE_DB_PORT" \
        -d "$PCIE_DB_NAME" \
        -U "$PCIE_DB_USERNAME" \
        -W "$PCIE_DB_PASSWORD" \
        -v ON_ERROR_STOP=1 \
        "$@"
}

scalar_app() {
    gsql_app -qAtc "$1"
}

assert_equals() {
    local label="$1"
    local expected="$2"
    local actual="$3"

    if [[ "$actual" != "$expected" ]]; then
        echo "[schema-init] validation failed: ${label}; expected=${expected}, actual=${actual}" >&2
        exit 1
    fi
}

validate_prepared_owner() {
    local database_owner_count
    local schema_owner_count

    database_owner_count="$(scalar_app \
        "SELECT COUNT(1) FROM pg_database database_meta JOIN pg_roles owner_role ON owner_role.oid = database_meta.datdba WHERE database_meta.datname = current_database() AND owner_role.rolname = current_user")"
    schema_owner_count="$(scalar_app \
        "SELECT COUNT(1) FROM pg_namespace schema_meta JOIN pg_roles owner_role ON owner_role.oid = schema_meta.nspowner WHERE schema_meta.nspname = current_schema() AND owner_role.rolname = current_user")"

    assert_equals "prepared database owner" "1" "$database_owner_count"
    assert_equals "prepared current schema owner" "1" "$schema_owner_count"
}

validate_schema() {
    local validation_output
    local -a checks

    validation_output="$(gsql_app -qAt <<'SQL'
SELECT COUNT(1)
  FROM c_ai_docker_schema_state
 WHERE marker_key = 'docker_full_init_v1';

SELECT COUNT(1)
  FROM pg_database database_meta
  JOIN pg_roles owner_role ON owner_role.oid = database_meta.datdba
 WHERE database_meta.datname = current_database()
   AND owner_role.rolname = current_user;

SELECT COUNT(1)
  FROM pg_namespace schema_meta
  JOIN pg_roles owner_role ON owner_role.oid = schema_meta.nspowner
 WHERE schema_meta.nspname = current_schema()
   AND owner_role.rolname = current_user;

SELECT COUNT(1)
  FROM pg_class table_meta
  JOIN pg_namespace schema_meta ON schema_meta.oid = table_meta.relnamespace
 WHERE schema_meta.nspname = current_schema()
   AND table_meta.relkind = 'r'
   AND table_meta.relname IN (
       'c_ai_device',
       'c_ai_config',
       'c_ai_prompt',
       'c_ai_data_package',
       'c_ai_request_nonce',
       'c_ai_user'
   );

SELECT COUNT(1) || '|' ||
       SUM(
           CASE
               WHEN column_name = 'id_device'
                    AND data_type = 'character varying'
                    AND character_maximum_length = 32
                    AND is_nullable = 'NO' THEN 1
               WHEN column_name = 'nonce_hash'
                    AND data_type = 'character varying'
                    AND character_maximum_length = 64
                    AND is_nullable = 'NO' THEN 1
               WHEN column_name = 'expires_at'
                    AND data_type = 'bigint'
                    AND is_nullable = 'NO' THEN 1
               WHEN column_name = 'insert_time'
                    AND data_type = 'timestamp without time zone'
                    AND is_nullable = 'NO'
                    AND column_default IS NOT NULL THEN 1
               ELSE 0
           END
       )
  FROM information_schema.columns
 WHERE table_schema = current_schema()
   AND table_name = 'c_ai_request_nonce';

SELECT COUNT(1)
  FROM (
        SELECT constraint_meta.constraint_name
          FROM information_schema.table_constraints constraint_meta
          JOIN information_schema.key_column_usage key_column
            ON key_column.constraint_schema = constraint_meta.constraint_schema
           AND key_column.constraint_name = constraint_meta.constraint_name
           AND key_column.table_schema = constraint_meta.table_schema
           AND key_column.table_name = constraint_meta.table_name
         WHERE constraint_meta.table_schema = current_schema()
           AND constraint_meta.table_name = 'c_ai_request_nonce'
           AND constraint_meta.constraint_type = 'PRIMARY KEY'
         GROUP BY constraint_meta.constraint_name
        HAVING COUNT(1) = 2
           AND SUM(
                   CASE
                       WHEN key_column.ordinal_position = 1
                            AND key_column.column_name = 'id_device' THEN 1
                       ELSE 0
                   END
               ) = 1
           AND SUM(
                   CASE
                       WHEN key_column.ordinal_position = 2
                            AND key_column.column_name = 'nonce_hash' THEN 1
                       ELSE 0
                   END
               ) = 1
       ) valid_primary_key;

SELECT COUNT(1)
  FROM pg_indexes
 WHERE schemaname = current_schema()
   AND tablename = 'c_ai_request_nonce'
   AND indexname = 'idx_c_ai_request_nonce_exp'
   AND indexdef LIKE 'CREATE INDEX %'
   AND (
        indexdef LIKE '% (expires_at)'
        OR indexdef LIKE '% (expires_at) TABLESPACE %'
   );
SQL
)"

    mapfile -t checks <<< "$validation_output"
    assert_equals "validation result count" "7" "${#checks[@]}"
    assert_equals "Docker schema marker ${EXPECTED_MARKER}" "1" "${checks[0]}"
    assert_equals "database owner" "1" "${checks[1]}"
    assert_equals "current schema owner" "1" "${checks[2]}"
    assert_equals "core business tables" "6" "${checks[3]}"
    assert_equals "nonce column contract" "4|4" "${checks[4]}"
    assert_equals "nonce composite primary key" "1" "${checks[5]}"
    assert_equals "nonce expires_at index" "1" "${checks[6]}"

    gsql_app -qAtc \
        "SELECT id_device, nonce_hash, expires_at, insert_time FROM c_ai_request_nonce WHERE 1 = 0" \
        >/dev/null
}

marker_table_count="$(scalar_app \
    "SELECT COUNT(1) FROM pg_class table_meta JOIN pg_namespace schema_meta ON schema_meta.oid = table_meta.relnamespace WHERE schema_meta.nspname = current_schema() AND table_meta.relkind = 'r' AND table_meta.relname = '${DOCKER_MARKER_TABLE}'")"

if [[ "$marker_table_count" == "1" ]]; then
    echo "[schema-init] schema marker table exists; validating without replaying init.sql"
    validate_schema
    echo "[schema-init] existing schema validation passed"
    exit 0
fi

assert_equals "Docker schema marker table count" "0" "$marker_table_count"

existing_business_table_count="$(scalar_app \
    "SELECT COUNT(1) FROM pg_class table_meta JOIN pg_namespace schema_meta ON schema_meta.oid = table_meta.relnamespace WHERE schema_meta.nspname = current_schema() AND table_meta.relkind = 'r' AND (LEFT(table_meta.relname, 5) = 'c_ai_' OR LEFT(table_meta.relname, 7) = 'hi_ods_')")"

if [[ "$existing_business_table_count" != "0" ]]; then
    echo "[schema-init] partial PCIE schema detected without ${EXPECTED_MARKER}; refusing initialization" >&2
    exit 1
fi

if [[ ! -r "$PCIE_SCHEMA_SQL" ]]; then
    echo "[schema-init] init.sql is not readable: ${PCIE_SCHEMA_SQL}" >&2
    exit 1
fi

validate_prepared_owner

echo "[schema-init] running complete GaussDB/openGauss init.sql as ${PCIE_DB_USERNAME}"
gsql_app -q -1 -f "$PCIE_SCHEMA_SQL"

gsql_app -q <<'SQL'
BEGIN;

CREATE TABLE c_ai_docker_schema_state (
    marker_key VARCHAR(64) PRIMARY KEY,
    initialized_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP NOT NULL
);

INSERT INTO c_ai_docker_schema_state (marker_key)
VALUES ('docker_full_init_v1');

COMMIT;
SQL

validate_schema
echo "[schema-init] schema initialization and validation passed"
