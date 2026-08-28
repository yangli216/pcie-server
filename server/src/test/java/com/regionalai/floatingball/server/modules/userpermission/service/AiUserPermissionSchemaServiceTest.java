package com.regionalai.floatingball.server.modules.userpermission.service;

import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUserPermissionSchemaServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCacheSuccessfulSchemaProbe() {
        when(jdbcTemplate.query(eq(AiUserPermissionSchemaService.SCHEMA_PROBE_SQL), any(ResultSetExtractor.class)))
            .thenReturn(null);
        AiUserPermissionSchemaService service = new AiUserPermissionSchemaService(jdbcTemplate);

        service.requireReady();
        service.requireReady();

        verify(jdbcTemplate, times(1))
            .query(eq(AiUserPermissionSchemaService.SCHEMA_PROBE_SQL), any(ResultSetExtractor.class));
    }

    @Test
    void shouldReturnStableServiceUnavailableErrorUntilDbaMigration() {
        when(jdbcTemplate.query(eq(AiUserPermissionSchemaService.SCHEMA_PROBE_SQL), any(ResultSetExtractor.class)))
            .thenThrow(new DataAccessResourceFailureException("table missing"));
        AiUserPermissionSchemaService service = new AiUserPermissionSchemaService(jdbcTemplate);

        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class, service::requireReady);

        assertEquals("AI-PERMISSION-SCHEMA-NOT-READY", exception.getCode());
        assertEquals(AiUserPermissionSchemaService.REMEDIATION_MESSAGE, exception.getMessage());
        assertDoesNotThrow(service::afterSingletonsInstantiated);
    }
}
