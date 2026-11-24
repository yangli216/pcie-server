package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcReviewLogMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class EmrQcAuditServiceTest {
    @Test
    void mandatorySuccessAuditPropagatesDatabaseFailureWhileFailureLoggingRemainsBestEffort() {
        AiEmrQcReviewLogMapper logs = mock(AiEmrQcReviewLogMapper.class);
        when(logs.insert(any())).thenThrow(new IllegalStateException("insert failed"));
        EmrQcAuditService service = new EmrQcAuditService(logs);
        EmrQcContext context = new EmrQcContext(null, null, "ORG-FAKE", "REQUEST-FAKE");
        assertThrows(IllegalStateException.class,
            () -> service.recordRequired(context, "analyze", "A", null, "success"));
        assertDoesNotThrow(() -> service.record(context, "analyze", "A", null, "failed"));
        verify(logs, times(2)).insert(any());
    }
}
