package com.regionalai.floatingball.server.common.db;

import com.regionalai.floatingball.server.common.config.MybatisPlusDatabaseProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class DatabaseDialect {

    public enum Kind {
        ORACLE,
        OPENGAUSS,
        POSTGRESQL,
        GBASE_POSTGRESQL
    }

    private final Kind kind;

    @Autowired
    public DatabaseDialect(MybatisPlusDatabaseProperties properties) {
        this.kind = resolve(properties.getDbType());
    }

    public DatabaseDialect(Kind kind) {
        this.kind = kind == null ? Kind.ORACLE : kind;
    }

    public Kind getKind() {
        return kind;
    }

    public boolean isPgCompatible() {
        return kind == Kind.OPENGAUSS || kind == Kind.POSTGRESQL || kind == Kind.GBASE_POSTGRESQL;
    }

    public String firstRows(int rows) {
        int safeRows = Math.max(1, rows);
        return isPgCompatible() ? "LIMIT " + safeRows : "FETCH FIRST " + safeRows + " ROWS ONLY";
    }

    public String dayText(String column) {
        if (isPgCompatible()) {
            return "TO_CHAR(" + column + "::date, 'YYYY-MM-DD')";
        }
        return "TO_CHAR(TRUNC(" + column + "), 'yyyy-MM-dd')";
    }

    public String dateStartParameter(String parameterPath) {
        if (isPgCompatible()) {
            return "TO_TIMESTAMP(#{" + parameterPath + "}, 'YYYY-MM-DD')";
        }
        return "TO_DATE(#{" + parameterPath + "}, 'yyyy-MM-dd')";
    }

    public String dateEndExclusiveParameter(String parameterPath) {
        if (isPgCompatible()) {
            return "TO_TIMESTAMP(#{" + parameterPath + "}, 'YYYY-MM-DD') + INTERVAL '1 day'";
        }
        return "TO_DATE(#{" + parameterPath + "}, 'yyyy-MM-dd') + 1";
    }

    public String nvl(String valueExpression, String fallbackExpression) {
        return isPgCompatible()
            ? "COALESCE(" + valueExpression + ", " + fallbackExpression + ")"
            : "NVL(" + valueExpression + ", " + fallbackExpression + ")";
    }

    public String jsonNumber(String column, String jsonPath) {
        if (isPgCompatible()) {
            String key = jsonPath;
            if (key != null && key.startsWith("$.")) {
                key = key.substring(2);
            }
            return "CAST(" + column + "::jsonb ->> '" + key + "' AS NUMERIC)";
        }
        return "JSON_VALUE(" + column + ", '" + jsonPath + "' RETURNING NUMBER)";
    }

    public String textContains(String column, String parameterPlaceholder) {
        if (isPgCompatible()) {
            return "POSITION(" + parameterPlaceholder + " IN " + column + ") > 0";
        }
        return "DBMS_LOB.INSTR(" + column + ", " + parameterPlaceholder + ") > 0";
    }

    private Kind resolve(String configured) {
        if (configured == null) {
            return Kind.ORACLE;
        }
        String value = configured.trim().toLowerCase(Locale.ROOT);
        if ("gbase-postgresql".equals(value)) {
            return Kind.GBASE_POSTGRESQL;
        }
        if ("gauss".equals(value) || "gaussdb".equals(value) || "opengauss".equals(value)) {
            return Kind.OPENGAUSS;
        }
        if ("postgres".equals(value) || "postgresql".equals(value) || "postgre_sql".equals(value)) {
            return Kind.POSTGRESQL;
        }
        return Kind.ORACLE;
    }
}
