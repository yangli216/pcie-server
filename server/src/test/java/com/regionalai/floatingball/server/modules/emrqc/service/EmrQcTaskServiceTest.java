package com.regionalai.floatingball.server.modules.emrqc.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.NotFoundException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.emrqc.config.EmrQcProperties;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcTaskCreateRequest;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcTask;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcTaskMapper;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmrQcTaskServiceTest {

    @Mock
    private EmrQcSchemaService schemaService;
    @Mock
    private EmrQcService emrQcService;
    @Mock
    private EmrQcAuditService auditService;
    @Mock
    private AiEmrQcTaskMapper taskMapper;

    private EmrQcTaskService service;

    @BeforeEach
    void setUp() {
        EmrQcProperties properties = new EmrQcProperties();
        properties.setEnabled(true);
        properties.setMaxTaskRecords(50);
        service = new EmrQcTaskService(properties, schemaService, emrQcService, auditService,
            taskMapper, new ObjectMapper(), new SensitiveDataMasker());
    }

    @Test
    void createRejectsMoreThanFiftyRecordsWithoutInserting() {
        AdminCurrentUser user = user();
        when(emrQcService.context(user, "req")).thenReturn(context(user));

        EmrQcTaskCreateRequest request = new EmrQcTaskCreateRequest();
        request.setRecordIds(ids(51));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.create(user, request, "req"));

        assertEquals("EMR-QC-400", exception.getCode());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void createRejectsWholeBatchWhenAnyRecordIsOutsideSessionScope() {
        AdminCurrentUser user = user();
        when(emrQcService.context(user, "req")).thenReturn(context(user));
        when(emrQcService.requireRecord(any(), eq("A"))).thenReturn(record("A"));
        when(emrQcService.requireRecord(any(), eq("B")))
            .thenThrow(new NotFoundException("EMR-RECORD-NOT-FOUND", "病历不存在或不在当前查询范围内"));

        EmrQcTaskCreateRequest request = new EmrQcTaskCreateRequest();
        request.setRecordIds(Arrays.asList("A", "B"));

        NotFoundException exception = assertThrows(NotFoundException.class,
            () -> service.create(user, request, "req"));

        assertEquals("EMR-RECORD-NOT-FOUND", exception.getCode());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void createRejectsConcurrentTaskForSameOperator() {
        AdminCurrentUser user = user();
        when(emrQcService.context(user, "req")).thenReturn(context(user));
        when(emrQcService.requireRecord(any(), eq("A"))).thenReturn(record("A"));
        when(taskMapper.selectCount(any())).thenReturn(1L);

        EmrQcTaskCreateRequest request = new EmrQcTaskCreateRequest();
        request.setRecordIds(new ArrayList<String>(Arrays.asList("A")));

        ConflictException exception = assertThrows(ConflictException.class,
            () -> service.create(user, request, "req"));

        assertEquals("EMR-QC-TASK-BUSY", exception.getCode());
        verify(taskMapper, never()).insert(any());
    }

    @Test
    void batchProgressAccumulatesPerRecordFailuresWithoutAbortingBatch() throws Exception {
        AdminCurrentUser user = user();
        when(emrQcService.context(user, "req")).thenReturn(context(user));
        when(emrQcService.requireRecord(any(), eq("A"))).thenReturn(record("A"));
        when(emrQcService.requireRecord(any(), eq("B"))).thenReturn(record("B"));
        when(taskMapper.selectCount(any())).thenReturn(0L);

        final AtomicReference<AiEmrQcTask> saved = new AtomicReference<AiEmrQcTask>();
        doAnswer(invocation -> {
            AiEmrQcTask task = invocation.getArgument(0);
            task.setIdTask("TASK1");
            saved.set(task);
            return 1;
        }).when(taskMapper).insert(any());
        when(taskMapper.selectById("TASK1")).thenAnswer(invocation -> saved.get());

        final CountDownLatch finished = new CountDownLatch(1);
        doAnswer(invocation -> {
            AiEmrQcTask task = invocation.getArgument(0);
            if ("completed".equals(task.getStatus())
                || "partially_completed".equals(task.getStatus())
                || "failed".equals(task.getStatus())) {
                finished.countDown();
            }
            return 1;
        }).when(taskMapper).updateById(any());

        EmrQcAnalysisVO success = new EmrQcAnalysisVO();
        success.setScore(82);
        success.setFindingCount(5);
        when(emrQcService.analyzeForTask(any(), eq("A"), eq(false))).thenReturn(success);
        when(emrQcService.analyzeForTask(any(), eq("B"), eq(false)))
            .thenThrow(new ServiceUnavailableException("EMR-PHIS-UNAVAILABLE", "PHIS 服务读取超时"));

        EmrQcTaskCreateRequest request = new EmrQcTaskCreateRequest();
        request.setRecordIds(Arrays.asList("A", "B"));

        assertEquals("queued", service.create(user, request, "req").getStatus());
        assertTrue(finished.await(5, TimeUnit.SECONDS), "batch task should finish");

        AiEmrQcTask task = saved.get();
        assertEquals("partially_completed", task.getStatus());
        assertEquals(2, task.getProcessedCount());
        assertEquals(1, task.getSucceededCount());
        assertEquals(1, task.getFailedCount());
        assertTrue(task.getFinishedAt() != null);
        verify(auditService).record(any(), eq("batch_analyze"), isNull(), anyString(), eq("queued"));
    }

    @Test
    void taskLookupOnlyReturnsTasksOwnedByCurrentOperator() {
        AdminCurrentUser user = user();
        when(emrQcService.context(user, "req")).thenReturn(context(user));
        when(taskMapper.selectOne(any())).thenReturn(null);

        NotFoundException exception = assertThrows(NotFoundException.class,
            () -> service.get(user, "TASK-OTHER", "req"));

        assertEquals("EMR-RECORD-NOT-FOUND", exception.getCode());
    }

    private AdminCurrentUser user() {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP");
        user.setAuthSessionId("s1");
        user.setIdOrg("ORG001");
        user.setBbpUserId("BBP-USER-1");
        user.setNaUser("张三");
        return user;
    }

    private EmrQcContext context(AdminCurrentUser user) {
        return new EmrQcContext(user, null, "ORG001", "req");
    }

    private PhisEmrRecord record(String id) {
        PhisEmrRecord record = new PhisEmrRecord();
        record.setIdMedrecdoc(id);
        record.setPatientName("张三");
        return record;
    }

    private List<String> ids(int count) {
        List<String> ids = new ArrayList<String>();
        for (int index = 0; index < count; index++) {
            ids.add("R" + index);
        }
        return ids;
    }
}
