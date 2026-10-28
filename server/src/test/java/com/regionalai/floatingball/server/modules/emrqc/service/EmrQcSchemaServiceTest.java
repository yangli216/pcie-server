package com.regionalai.floatingball.server.modules.emrqc.service;

import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcFindingMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcRecordMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcReviewLogMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcTaskMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmrQcSchemaServiceTest {

    @Mock
    private AiEmrQcRecordMapper recordMapper;
    @Mock
    private AiEmrQcFindingMapper findingMapper;
    @Mock
    private AiEmrQcTaskMapper taskMapper;
    @Mock
    private AiEmrQcReviewLogMapper reviewLogMapper;

    @Test
    void missingTableReturnsStableSchemaErrorAndDoesNotCacheReady() {
        when(recordMapper.selectCount(any())).thenThrow(new DataAccessResourceFailureException("table missing"));
        EmrQcSchemaService service = service();

        ServiceUnavailableException first = assertThrows(ServiceUnavailableException.class, service::requireReady);
        ServiceUnavailableException second = assertThrows(ServiceUnavailableException.class, service::requireReady);

        assertEquals("EMR-QC-SCHEMA", first.getCode());
        assertEquals("EMR-QC-SCHEMA", second.getCode());
        verify(recordMapper, times(2)).selectCount(any());
    }

    @Test
    void successfulProbeIsCachedAcrossCalls() {
        when(recordMapper.selectCount(any())).thenReturn(0L);
        when(findingMapper.selectCount(any())).thenReturn(0L);
        when(taskMapper.selectCount(any())).thenReturn(0L);
        when(reviewLogMapper.selectCount(any())).thenReturn(0L);
        EmrQcSchemaService service = service();

        service.requireReady();
        service.requireReady();

        verify(recordMapper, times(1)).selectCount(any());
        verify(findingMapper, times(1)).selectCount(any());
        verify(taskMapper, times(1)).selectCount(any());
        verify(reviewLogMapper, times(1)).selectCount(any());
    }

    private EmrQcSchemaService service() {
        return new EmrQcSchemaService(recordMapper, findingMapper, taskMapper, reviewLogMapper);
    }
}
