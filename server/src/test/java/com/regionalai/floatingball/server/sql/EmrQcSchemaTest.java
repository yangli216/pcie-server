package com.regionalai.floatingball.server.sql;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class EmrQcSchemaTest {

    private static final String[] TABLES = new String[]{
        "c_ai_emr_qc_record", "c_ai_emr_qc_finding", "c_ai_emr_qc_task", "c_ai_emr_qc_review_log"
    };

    @Test
    void bothBaselinesContainTheFourEmrQcTablesWithoutUpgradeScripts() throws Exception {
        for (String dialect : new String[]{"oracle", "gaussdb"}) {
            Path dir = Paths.get("src/main/resources/sql", dialect);
            String sql = read(dir.resolve("init.sql"));
            for (String table : TABLES) {
                assertThat(sql).contains("CREATE TABLE " + table + " (");
                assertThat(sql).contains("COMMENT ON TABLE " + table + " ");
            }
            assertThat(sql).contains("CREATE INDEX idx_c_ai_emr_qc_record_org ON c_ai_emr_qc_record (id_org, analyzed_at)");
            assertThat(sql).contains("CREATE UNIQUE INDEX uk_c_ai_emr_qc_record_active ON c_ai_emr_qc_record (");
            assertThat(sql).contains("CREATE INDEX idx_c_ai_emr_qc_finding_record ON c_ai_emr_qc_finding (id_qc_record, id_analysis)");
            assertThat(sql).contains("CREATE UNIQUE INDEX uk_c_ai_emr_qc_finding_active ON c_ai_emr_qc_finding (");
            assertThat(sql).contains("CREATE INDEX idx_c_ai_emr_qc_task_creator ON c_ai_emr_qc_task (id_org, created_by, status, insert_time)");
            assertThat(sql).contains("CREATE INDEX idx_c_ai_emr_qc_review_org ON c_ai_emr_qc_review_log (id_org, insert_time)");
            assertThat(sql).contains(
                "VALUES ('ROLE004', 'EMR_QC_REVIEWER', '门诊病历质控员', " +
                    "'只读查询所属机构门诊病历并执行 AI 书写质控', '1', '1')");
            assertThat(read(dir.resolve("README.md")))
                .contains(TABLES[0], TABLES[1], TABLES[2], TABLES[3], "EMR_QC_REVIEWER");
            try (DirectoryStream<Path> upgrade = Files.newDirectoryStream(dir, "upgrade_*.sql")) {
                assertThat(upgrade.iterator().hasNext()).isFalse();
            }
        }
    }

    @Test
    void baselinesPersistOnlyMaskedIdentityAndProgressItems() throws Exception {
        for (String dialect : new String[]{"oracle", "gaussdb"}) {
            String sql = read(Paths.get("src/main/resources/sql", dialect).resolve("init.sql"));
            assertThat(sql).contains("patient_name_masked", "id_card_masked", "content_hash");
            assertThat(sql).contains("record_ids_json", "items_json", "processed_count", "succeeded_count", "failed_count");
            assertThat(sql).contains("operator_id", "request_id", "result_status");
            String qcSchema = sql.substring(sql.indexOf("CREATE TABLE c_ai_emr_qc_record ("), sql.indexOf("COMMIT;"));
            assertThat(qcSchema).doesNotContain("html_content", "xml_content", "patient_name ", "id_card ");
        }
    }

    @Test
    void gaussdbFollowsPostgresTypesAndOracleKeepsVarchar2() throws Exception {
        String oracle = read(Paths.get("src/main/resources/sql/oracle/init.sql"));
        String gaussdb = read(Paths.get("src/main/resources/sql/gaussdb/init.sql"));
        assertThat(oracle).contains("content_hash         VARCHAR2(64) NOT NULL", "dimensions_json      CLOB");
        assertThat(gaussdb).contains("content_hash         VARCHAR(64) NOT NULL", "dimensions_json      TEXT");
        assertThat(gaussdb).doesNotContain("VARCHAR2");
    }

    @Test
    void baselinesPersistFindingDeductionsAndIgnoreStateForNineDimensions() throws Exception {
        for (String dialect : new String[]{"oracle", "gaussdb"}) {
            Path dir = Paths.get("src/main/resources/sql", dialect);
            String sql = read(dir.resolve("init.sql"));
            assertThat(sql).contains("九维度评分JSON，score为维度得分、weight为维度满分，满分合计100");
            assertThat(sql).contains("COMMENT ON COLUMN c_ai_emr_qc_record.finding_count IS '未忽略问题数'");
            assertThat(sql).contains("COMMENT ON COLUMN c_ai_emr_qc_record.high_severity_count IS '未忽略高严重度问题数'");
            assertThat(sql).contains("COMMENT ON COLUMN c_ai_emr_qc_finding.deduction IS '单条问题扣分'");
            assertThat(sql).contains("COMMENT ON COLUMN c_ai_emr_qc_finding.ignored IS '人工忽略标记，忽略项保留明细但不计入有效问题统计'");
            assertThat(read(dir.resolve("README.md"))).contains("9 维度评分", "满分合计 100", "`deduction`", "`ignored`");
        }
        String oracle = read(Paths.get("src/main/resources/sql/oracle/init.sql"));
        String gaussdb = read(Paths.get("src/main/resources/sql/gaussdb/init.sql"));
        assertThat(oracle).contains("deduction       NUMBER(5) DEFAULT 0 NOT NULL", "ignored         NUMBER(1) DEFAULT 0 NOT NULL");
        assertThat(gaussdb).contains("deduction       INTEGER DEFAULT 0 NOT NULL", "ignored         BOOLEAN DEFAULT FALSE NOT NULL");
    }

    private String read(Path path) throws Exception {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
