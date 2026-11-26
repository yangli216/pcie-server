package com.regionalai.floatingball.server.modules.emrqc.service;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcRecord;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcFindingMapper;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcRecordMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class EmrQcWriterSourceTest {
    private final AiEmrQcRecordMapper records = mock(AiEmrQcRecordMapper.class);
    private final AiEmrQcFindingMapper findings = mock(AiEmrQcFindingMapper.class);
    private final EmrQcWriter writer = new EmrQcWriter(records, findings);

    @ParameterizedTest
    @ValueSource(strings = {"ai", "manual", "proxy_refill"})
    void updatingToProxyClearsStoredAndReturnedAiAndReviewMetadata(String oldSource) {
        AiEmrQcRecord previous = previous(oldSource);
        when(records.selectList(any())).thenReturn(Collections.singletonList(previous));
        EmrQcDraft draft = draft("proxy_refill");
        AiEmrQcRecord result = writer.save(draft);
        assertEquals(100, result.getScore());
        assertEquals("NEW", result.getLatestAnalysisId());
        assertEquals("proxy_refill", result.getResultSource());
        assertNull(result.getModel()); assertNull(result.getAiScore()); assertNull(result.getAiAnalysisId());
        assertNull(result.getReviewedBy()); assertNull(result.getReviewedByName());
        assertNull(result.getReviewedAt()); assertNull(result.getReviewReason());
        assertEquals(0, result.getFindingCount()); assertEquals(0, result.getHighSeverityCount());
        ArgumentCaptor<UpdateWrapper<AiEmrQcRecord>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(records).update(isNull(), update.capture());
        String sql = update.getValue().getSqlSet();
        for (String column : new String[]{"model", "ai_score", "ai_analysis_id", "reviewed_by", "reviewed_by_name", "reviewed_at", "review_reason"}) {
            assertTrue(sql.contains(column + "="), sql);
        }
        assertEquals(7, update.getValue().getParamNameValuePairs().values().stream().filter(Objects::isNull).count());
        verify(records).updateById(result);
        verifyNoInteractions(findings); // Prior findings are retained by analysis ID, never deleted.
    }

    @Test
    void proxyToAiWritesFreshAiMetadataAndClearsOldReviewState() {
        when(records.selectList(any())).thenReturn(Collections.singletonList(previous("proxy_refill")));
        EmrQcDraft draft = draft("ai"); draft.setScore(97); draft.setModel("new-model");
        AiEmrQcRecord result = writer.save(draft);
        assertEquals("ai", result.getResultSource());
        assertEquals("new-model", result.getModel());
        assertEquals(97, result.getAiScore()); assertEquals("NEW", result.getAiAnalysisId());
        assertNull(result.getReviewReason()); assertNull(result.getReviewedBy());
        ArgumentCaptor<UpdateWrapper<AiEmrQcRecord>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(records).update(isNull(), update.capture());
        assertFalse(update.getValue().getSqlSet().contains("ai_score="));
        assertEquals(4, update.getValue().getParamNameValuePairs().values().stream().filter(Objects::isNull).count());
    }

    @Test
    void newProxyResultNeverRecordsAnAiModelOrOriginalScore() {
        when(records.selectList(any())).thenReturn(Collections.emptyList());
        EmrQcDraft draft = draft("proxy_refill"); draft.setModel("must-not-survive");
        AiEmrQcRecord result = writer.save(draft);
        assertNull(result.getModel()); assertNull(result.getAiScore()); assertNull(result.getAiAnalysisId());
        verify(records).insert(result);
        verify(records, never()).update(any(), any());
    }

    @Test
    void analysisCannotWriteAnUnknownOrManualSource() {
        assertThrows(IllegalArgumentException.class, () -> writer.save(draft("manual")));
        assertThrows(IllegalArgumentException.class, () -> writer.save(draft("invented")));
        verifyNoInteractions(records, findings);
    }

    private EmrQcDraft draft(String source) {
        EmrQcDraft draft = new EmrQcDraft();
        draft.setIdOrg("FAKE-ORG"); draft.setIdMedrecdoc("FAKE-RECORD");
        draft.setAnalysisId("NEW"); draft.setResultSource(source); draft.setScore(100);
        return draft;
    }

    private AiEmrQcRecord previous(String source) {
        AiEmrQcRecord entity = new AiEmrQcRecord(); entity.setIdQcRecord("QC");
        entity.setResultSource(source); entity.setLatestAnalysisId("OLD");
        entity.setModel("old-model"); entity.setAiScore(63); entity.setAiAnalysisId("OLD-AI");
        entity.setReviewedBy("old-user"); entity.setReviewedByName("old-name");
        entity.setReviewedAt(LocalDateTime.of(2026, 1, 1, 0, 0)); entity.setReviewReason("old reason");
        entity.setFindingCount(4); entity.setHighSeverityCount(4);
        return entity;
    }
}
