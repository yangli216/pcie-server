package com.regionalai.floatingball.server.modules.knowledge.repository;

import com.regionalai.floatingball.server.modules.knowledge.config.DrugInstructionProperties;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionDetail;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in read-only smoke test against an explicitly configured external view. */
@EnabledIfEnvironmentVariable(named = "FB_DRUG_INSTRUCTIONS_INTEGRATION", matches = "true")
class DrugInstructionMySqlIntegrationTest {
    @Test
    void readsActualViewWithProductionJdbcCode() throws Exception {
        DrugInstructionProperties properties = new DrugInstructionProperties();
        properties.setJdbcUrl(System.getenv("FB_DRUG_INSTRUCTIONS_JDBC_URL"));
        properties.setUsername(System.getenv("FB_DRUG_INSTRUCTIONS_USERNAME"));
        properties.setPassword(System.getenv("FB_DRUG_INSTRUCTIONS_PASSWORD"));
        DrugInstructionRepository repository = new DrugInstructionRepository(properties);
        try {
            List<DrugInstructionItem> rows = repository.search("痔疮胶囊", 0, 3);
            assertFalse(rows.isEmpty());
            DrugInstructionDetail detail = repository.find(rows.get(0).getProductId()).get();
            assertEquals(rows.get(0).getProductName(), detail.getProductName());
            assertTrue(detail.getSummary().contains("【用法用量】"));
            assertTrue(detail.getSummary().contains("【禁忌】"));
            assertTrue(detail.getSummary().length() > 100);
            List<DrugInstructionItem> pinyin = repository.search("ZC", 0, 21);
            assertFalse(pinyin.isEmpty());
            assertEquals(pinyin.size(), pinyin.stream().map(DrugInstructionItem::getProductId).collect(Collectors.toSet()).size());
            assertFalse(repository.find("PCIE_SYNTHETIC_NONEXISTENT_ID").isPresent());
            assertTrue(repository.search("PCIE_SYNTHETIC_%_!_NONEXISTENT", 0, 2).isEmpty());
        } finally {
            repository.close();
        }
    }
}
