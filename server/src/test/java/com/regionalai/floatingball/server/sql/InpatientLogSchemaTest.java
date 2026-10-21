package com.regionalai.floatingball.server.sql;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import static org.assertj.core.api.Assertions.*;
class InpatientLogSchemaTest {
 @Test void bothBaselinesHaveRoundAndEventConstraintsAndCommentsWithoutNewUpgradeFiles() throws Exception {
  for(String dialect:new String[]{"oracle","gaussdb"}){
   Path dir=Paths.get("src/main/resources/sql",dialect);String sql=new String(Files.readAllBytes(dir.resolve("init.sql")),StandardCharsets.UTF_8);
   assertThat(sql).contains("CREATE TABLE c_ai_inpatient_emr_log (","CREATE TABLE c_ai_inpatient_emr_log_event (",
    "(id_device, generation_id)","(id_device, event_id)","(id_log, event_sequence)","REFERENCES c_ai_inpatient_emr_log(id_log)",
    "COMMENT ON COLUMN c_ai_inpatient_emr_log.template_hash", "COMMENT ON COLUMN c_ai_inpatient_emr_log_event.body_hash");
   assertThat(new String(Files.readAllBytes(dir.resolve("README.md")),StandardCharsets.UTF_8)).contains("c_ai_inpatient_emr_log_event");
   try(java.nio.file.DirectoryStream<Path> files=Files.newDirectoryStream(dir,"upgrade_*.sql")){assertThat(files.iterator().hasNext()).isFalse();}
  }
 }
 @Test void oneTimeMigrationsContainExactlyTheBaselineDdlAndNoExistingBusinessMutations() throws Exception {
  for(String dialect:new String[]{"oracle","gaussdb"}){
   Path dir=Paths.get("src/main/resources/sql",dialect);
   String baseline=read(dir.resolve("init.sql"));
   String migration=read(dir.resolve("update_inpatient_emr_logs.sql"));
   String ddl=between(migration,"-- BEGIN INPATIENT LOG DDL","-- END INPATIENT LOG DDL");
   assertThat(ddl).isEqualTo(baseline.substring(baseline.indexOf("CREATE TABLE c_ai_inpatient_emr_log ("),
    baseline.indexOf("CREATE TABLE c_ai_user_consultation_log (")).trim());
   assertThat(matches(ddl,"(?im)^CREATE TABLE (\\w+)"))
    .containsExactly("c_ai_inpatient_emr_log","c_ai_inpatient_emr_log_event");
   assertThat(matches(ddl,"(?im)^CREATE (?:UNIQUE )?INDEX (\\w+)"))
    .containsExactly("uk_c_ai_inp_log_round","idx_c_ai_inp_log_time","idx_c_ai_inp_log_org",
     "idx_c_ai_inp_log_patient","idx_c_ai_inp_log_template","uk_c_ai_inp_event_id","uk_c_ai_inp_event_seq");
   assertThat(matches(ddl,"(?im)^COMMENT ON COLUMN (\\S+)")).hasSize(35);
   assertThat(Pattern.compile("(?im)^\\s*(INSERT|UPDATE|DELETE|MERGE|DROP|ALTER|TRUNCATE)\\s").matcher(migration).find()).isFalse();
   assertThat(migration).doesNotContain("IF NOT EXISTS", "@init.sql", "\\i init.sql");
  }
 }
 @Test void migrationsFailClosedOnWrongSchemaAndExistingObjectsAndValidateBeforeSuccess() throws Exception {
  Path root=Paths.get("src/main/resources/sql");
  String oracle=read(root.resolve("oracle/update_inpatient_emr_logs.sql"));
  String gauss=read(root.resolve("gaussdb/update_inpatient_emr_logs.sql"));
  String oraclePreflight=oracle.substring(0,oracle.indexOf("-- BEGIN INPATIENT LOG DDL"));
  assertThat(oraclePreflight).startsWith("WHENEVER OSERROR EXIT FAILURE ROLLBACK\nWHENEVER SQLERROR EXIT SQL.SQLCODE ROLLBACK")
   .contains("CURRENT_SCHEMA", "C_AI_DEVICE", "C_AI_ORG", "FROM user_objects", "IF v_count <> 0 THEN", "RAISE_APPLICATION_ERROR");
  String gaussPreflight=gauss.substring(0,gauss.indexOf("-- BEGIN INPATIENT LOG DDL"));
  assertThat(gaussPreflight).startsWith("\\set ON_ERROR_STOP on")
   .contains("BEGIN;", "current_schema()", "'c_ai_device'", "'c_ai_org'", "FROM pg_class", "IF v_count <> 0 THEN", "RAISE EXCEPTION");
  for(String object:new String[]{"c_ai_inpatient_emr_log","c_ai_inpatient_emr_log_event","uk_c_ai_inp_log_round",
   "idx_c_ai_inp_log_time","idx_c_ai_inp_log_org","idx_c_ai_inp_log_patient","idx_c_ai_inp_log_template",
   "uk_c_ai_inp_event_id","uk_c_ai_inp_event_seq"}){
   assertThat(oraclePreflight).contains("'"+object.toUpperCase(Locale.ROOT)+"'");
   assertThat(gaussPreflight).contains("'"+object+"'");
  }
  for(String sql:new String[]{oracle,gauss}){
   String validation=sql.substring(sql.indexOf("-- END INPATIENT LOG DDL"),sql.indexOf("\nCOMMIT;"));
   assertThat(validation).contains("IF v_count <> 35 THEN", "IF v_count <> 3 THEN", "IF v_count <> 7 THEN");
  }
  assertThat(oracle.trim()).endsWith("EXIT SUCCESS");
  assertThat(gauss.lastIndexOf("COMMIT;")).isGreaterThan(gauss.lastIndexOf("RAISE EXCEPTION"));
 }
 private String read(Path path) throws Exception { return new String(Files.readAllBytes(path),StandardCharsets.UTF_8); }
 private String between(String text,String start,String end){
  int from=text.indexOf(start);assertThat(from).isGreaterThanOrEqualTo(0);
  int to=text.indexOf(end,from+start.length());assertThat(to).isGreaterThan(from);
  return text.substring(from+start.length(),to).trim();
 }
 private List<String> matches(String sql,String pattern){
  List<String> values=new ArrayList<>();Matcher matcher=Pattern.compile(pattern).matcher(sql);
  while(matcher.find())values.add(matcher.group(1));return values;
 }
}
