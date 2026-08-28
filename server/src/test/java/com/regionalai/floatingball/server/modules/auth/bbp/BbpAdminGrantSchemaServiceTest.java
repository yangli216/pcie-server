package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BbpAdminGrantSchemaServiceTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldReportReadyAfterSuccessfulProbe() {
        when(jdbcTemplate.query(eq(BbpAdminGrantSchemaService.SCHEMA_PROBE_SQL), any(ResultSetExtractor.class)))
            .thenReturn(null);
        BbpAdminGrantSchemaService service = new BbpAdminGrantSchemaService(jdbcTemplate);

        assertTrue(service.isReady());
        service.requireReady();
    }

    @Test
    void shouldUseStableErrorWithoutBlockingLegacyLoginFallback() {
        when(jdbcTemplate.query(eq(BbpAdminGrantSchemaService.SCHEMA_PROBE_SQL), any(ResultSetExtractor.class)))
            .thenThrow(new DataAccessResourceFailureException("table missing"));
        BbpAdminGrantSchemaService service = new BbpAdminGrantSchemaService(jdbcTemplate);

        assertFalse(service.isReady());
        ServiceUnavailableException exception = assertThrows(ServiceUnavailableException.class, service::requireReady);
        assertEquals("BBP-ADMIN-GRANT-SCHEMA-NOT-READY", exception.getCode());
    }
}
