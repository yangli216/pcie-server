package com.regionalai.floatingball.server.modules.knowledge.service;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.knowledge.config.DrugInstructionProperties;
import com.regionalai.floatingball.server.modules.knowledge.dto.*;
import com.regionalai.floatingball.server.modules.knowledge.repository.DrugInstructionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DrugInstructionServiceTest {
    private DrugInstructionProperties properties;
    private DrugInstructionRepository repository;
    private DrugInstructionService service;

    @BeforeEach
    void setup() {
        properties = new DrugInstructionProperties();
        repository = mock(DrugInstructionRepository.class);
        service = new DrugInstructionService(properties, repository);
    }

    private void enable() {
        properties.setEnabled(true);
        properties.setJdbcUrl("jdbc:mysql://synthetic.invalid/ipharmacare_knowledge");
        properties.setUsername("synthetic");
        properties.setPassword("synthetic");
    }

    private DrugInstructionSearchRequest request() {
        DrugInstructionSearchRequest request = new DrugInstructionSearchRequest();
        request.setKeyword(" 药品 ");
        return request;
    }

    @Test
    void disabledAndMissingConfigurationMustNotContactDatabase() {
        assertThrows(ServiceUnavailableException.class, () -> service.search(request()));
        properties.setEnabled(true);
        assertThrows(ServiceUnavailableException.class, () -> service.find("id"));
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsBlankOversizedAndUnboundedSearchesBeforeDatabaseAccess() {
        DrugInstructionSearchRequest request = request();
        request.setKeyword("  ");
        assertThrows(BusinessException.class, () -> service.search(request));
        request.setKeyword(String.join("", Collections.nCopies(129, "药")));
        assertThrows(BusinessException.class, () -> service.search(request));
        request.setKeyword("药");
        request.setCurrent(Integer.MAX_VALUE);
        assertThrows(BusinessException.class, () -> service.search(request));
        request.setCurrent(1);
        request.setSize(101);
        assertThrows(BusinessException.class, () -> service.search(request));
        assertThrows(BusinessException.class, () -> service.find(String.join("", Collections.nCopies(65, "x"))));
        verifyNoInteractions(repository);
    }

    @Test
    void usesExtraRowForPaginationWithoutReturningIt() throws Exception {
        enable();
        DrugInstructionSearchRequest request = request();
        request.setCurrent(2);
        request.setSize(2);
        DrugInstructionItem a = new DrugInstructionItem("A", "同名药", "", "1g", "甲厂", "", "");
        DrugInstructionItem b = new DrugInstructionItem("B", "同名药", "", "2g", "乙厂", "", "");
        when(repository.search("药品", 2, 3)).thenReturn(Arrays.asList(a, b, a));
        DrugInstructionSearchResponse response = service.search(request);
        assertTrue(response.isHasMore());
        assertEquals(Arrays.asList(a, b), response.getRecords());
        assertEquals(2, response.getCurrent());
        verify(repository).search("药品", 2, 3);
    }

    @Test
    void distinguishesZeroHitsFromDatabaseFailure() throws Exception {
        enable();
        when(repository.search("药品", 0, 21)).thenReturn(Collections.emptyList());
        assertTrue(service.search(request()).getRecords().isEmpty());
        assertFalse(service.search(request()).isHasMore());
        when(repository.search("药品", 0, 21)).thenThrow(new SQLException("sensitive JDBC details", "08001"));
        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class, () -> service.search(request()));
        assertEquals("DRUG_INSTRUCTIONS_UNAVAILABLE", error.getCode());
        assertFalse(error.getMessage().contains("JDBC"));
        when(repository.find("id")).thenThrow(new SQLException("sensitive", "08001"));
        assertThrows(ServiceUnavailableException.class, () -> service.find("id"));
    }
}
