package com.regionalai.floatingball.server.sql;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class GbasePostgresqlSchemaScriptTest {
    private static final Path INIT = Paths.get("src/main/resources/sql/gbase-postgresql/init.sql");
    private static final Path UPDATES = Paths.get("src/main/resources/sql/gaussdb");
    private static final Path JAVA_ROOT = Paths.get("src/main/java");

    @Test
    void baselineShouldCoverCurrentPersistentEntitiesIncludingInheritedColumns() throws Exception {
        Map<String, String> tables = declarations(statements(INIT), "TABLE");
        int entities = 0;
        try (Stream<Path> paths = Files.walk(JAVA_ROOT)) {
            for (Path source : paths.filter(p -> p.toString().endsWith(".java")).collect(Collectors.toList())) {
                if (!read(source).contains("@TableName")) continue;
                String className = JAVA_ROOT.relativize(source).toString()
                    .replace(java.io.File.separatorChar, '.').replaceAll("\\.java$", "");
                Class<?> entity = Class.forName(className);
                TableName name = entity.getAnnotation(TableName.class);
                if (name == null) continue;
                entities++;
                String table = tables.get(normalize(name.value()));
                assertNotNull(table, "Missing entity table: " + name.value());
                Map<String, String> columns = columns(table);
                for (Class<?> type = entity; type != null && type != Object.class; type = type.getSuperclass()) {
                    for (Field field : type.getDeclaredFields()) {
                        if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers())
                            || field.isSynthetic()) continue;
                        TableField mapped = field.getAnnotation(TableField.class);
                        if (mapped != null && !mapped.exist()) continue;
                        TableId id = field.getAnnotation(TableId.class);
                        String column = mapped != null && !mapped.value().isEmpty() ? mapped.value()
                            : id != null && !id.value().isEmpty() ? id.value()
                            : field.getName().replaceAll("([a-z0-9])([A-Z])", "$1_$2");
                        String definition = columns.get(normalize(column));
                        assertNotNull(definition, "Missing mapped column: " + name.value() + "." + column);
                        if (id != null) assertTrue(definition.toUpperCase(Locale.ROOT).contains("PRIMARY KEY"),
                            "Missing primary key: " + name.value() + "." + column);
                    }
                }
            }
        }
        assertTrue(entities > 0, "No persistent entities discovered");
    }

    @Test
    void baselineShouldFoldDirectedUpdateColumnsTablesAndIndexes() throws Exception {
        List<String> baseline = statements(INIT);
        Map<String, String> tables = declarations(baseline, "TABLE");
        Map<String, String> indexes = declarations(baseline, "(?:UNIQUE )?INDEX");
        Pattern addedColumn = Pattern.compile(
            "(?i)ALTER TABLE\\s+(\\w+)\\s+ADD COLUMN(?:\\s+IF NOT EXISTS)?\\s+(\\w+)\\b");
        Pattern tableDeclaration = Pattern.compile("(?is)CREATE TABLE IF NOT EXISTS\\s+(\\w+)\\s*\\(.*?\\);");
        Pattern indexDeclaration = Pattern.compile("(?is)CREATE (?:UNIQUE )?INDEX(?: IF NOT EXISTS)?\\s+(\\w+)\\s+ON\\s+.*?;");
        try (Stream<Path> paths = Files.list(UPDATES)) {
            for (Path update : paths.filter(p -> p.getFileName().toString().startsWith("update_"))
                .collect(Collectors.toList())) {
                String sql = read(update);
                Matcher added = addedColumn.matcher(sql);
                while (added.find()) {
                    assertTrue(columns(tables.get(normalize(added.group(1)))).containsKey(normalize(added.group(2))),
                        "Missing update column: " + added.group(1) + "." + added.group(2));
                }
                Matcher table = tableDeclaration.matcher(sql);
                while (table.find()) {
                    Map<String, String> current = columns(tables.get(normalize(table.group(1))));
                    assertTrue(current.keySet().containsAll(columns(table.group()).keySet()),
                        "Missing update table columns: " + table.group(1));
                }
                Matcher index = indexDeclaration.matcher(sql);
                while (index.find()) {
                    String current = indexes.get(normalize(index.group(1)));
                    assertNotNull(current, "Missing update index: " + index.group(1));
                    assertEquals(canonical(index.group()), canonical(current), "Update index drift: " + index.group(1));
                }
            }
        }
    }

    @Test
    void baselineShouldKeepAllDdlBeforeSeedsAndAvoidDatabaseOrSchemaMutation() throws Exception {
        List<String> statements = statements(INIT);
        Map<String, String> tables = declarations(statements, "TABLE");
        declarations(statements, "(?:UNIQUE )?INDEX");
        boolean seedStarted = false;
        int seeds = 0;
        for (String sql : statements) {
            String upper = sql.toUpperCase(Locale.ROOT);
            if (upper.startsWith("INSERT INTO ")) {
                seedStarted = true;
                seeds++;
                assertTrue(tables.containsKey(normalize(sql.split("\\s+")[2])), "Seed precedes table: " + sql);
            } else if (upper.startsWith("CREATE ") || upper.startsWith("COMMENT ")) {
                assertFalse(seedStarted, "DDL after seed: " + sql);
                assertTrue(upper.startsWith("CREATE TABLE ") || upper.startsWith("CREATE INDEX ")
                    || upper.startsWith("CREATE UNIQUE INDEX ") || upper.startsWith("COMMENT ON "));
            } else {
                assertEquals("COMMIT", upper, "Unexpected initialization statement");
            }
        }
        assertTrue(seeds > 0);
        String sql = read(INIT);
        assertEquals(1, sql.split("VALUES \\('feature_event_minimization_v1'\\)", -1).length - 1);
        assertFalse(sql.contains("VARCHAR2"));
        assertFalse(sql.contains("CLOB"));
    }

    private Map<String, String> declarations(List<String> statements, String kind) {
        Map<String, String> result = new LinkedHashMap<>();
        Pattern declaration = Pattern.compile("^CREATE (?:" + kind + ") (\\w+)\\b", Pattern.CASE_INSENSITIVE);
        for (String sql : statements) {
            Matcher matcher = declaration.matcher(sql);
            if (matcher.find()) assertNull(result.put(normalize(matcher.group(1)), sql),
                "Duplicate object: " + matcher.group(1));
        }
        assertFalse(result.isEmpty(), "No " + kind + " declarations found");
        return result;
    }

    private Map<String, String> columns(String sql) {
        assertNotNull(sql, "Missing table declaration");
        String body = sql.substring(sql.indexOf('(') + 1, sql.lastIndexOf(')'));
        List<String> definitions = new ArrayList<>();
        int depth = 0, start = 0;
        boolean quoted = false;
        for (int i = 0; i < body.length(); i++) {
            char ch = body.charAt(i);
            if (ch == '\'') quoted = !quoted;
            if (quoted) continue;
            if (ch == '(') depth++;
            if (ch == ')') depth--;
            if (ch == ',' && depth == 0) {
                definitions.add(body.substring(start, i));
                start = i + 1;
            }
        }
        definitions.add(body.substring(start));
        Map<String, String> result = new LinkedHashMap<>();
        for (String definition : definitions) {
            String[] parts = definition.trim().split("\\s+", 2);
            if (parts.length == 2 && !parts[0].equalsIgnoreCase("CONSTRAINT")) {
                assertNull(result.put(normalize(parts[0]), parts[1]), "Duplicate column: " + parts[0]);
            }
        }
        return result;
    }

    private List<String> statements(Path path) throws Exception {
        List<String> result = new ArrayList<>();
        ScriptUtils.splitSqlScript(read(path), ';', result);
        return result;
    }

    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private String normalize(String identifier) {
        return identifier.replace("\"", "").toLowerCase(Locale.ROOT);
    }

    private String canonical(String sql) {
        return sql.replaceAll("(?i) IF NOT EXISTS", "").replaceAll("\\s+", "").replace(";", "")
            .replaceAll("\\(\\((CASE.*?END)\\)\\)", "($1)").toUpperCase(Locale.ROOT);
    }
}
