package com.regionalai.floatingball.server.sql;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class PublicHealthSchemaTest {
    @Test void bothInitBaselinesProvideUniqueOriginAndRequestWithTerminalRunState() throws Exception {
        for(String dialect:new String[]{"oracle","gaussdb"}) {Path directory=Paths.get("src/main/resources/sql",dialect);String sql=new String(Files.readAllBytes(directory.resolve("init.sql")),StandardCharsets.UTF_8);assertTrue(sql.contains("CREATE TABLE c_ai_ph_origin"));assertTrue(sql.contains("CREATE TABLE c_ai_ph_run"));assertTrue(sql.contains("ON c_ai_ph_origin (id_org, id_device, origin_request_id)"));assertTrue(sql.contains("ON c_ai_ph_run (id_org, id_device, request_id)"));assertTrue(sql.contains("patient_sex"));assertTrue(sql.contains("expires_at_ms"));assertTrue(sql.contains("status IN ('RUNNING','COMPLETED','FAILED')"));assertTrue(new String(Files.readAllBytes(directory.resolve("README.md")),StandardCharsets.UTF_8).contains("c_ai_ph_run"));assertEquals(1,sql.split("CREATE TABLE c_ai_ph_run",-1).length-1);}
    }
}
