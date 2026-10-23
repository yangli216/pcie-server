package com.regionalai.floatingball.server.modules.analytics.service;

import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.AnalyticsSummaryVO;
import com.regionalai.floatingball.server.modules.analytics.mapper.AnalyticsMapper;
import com.regionalai.floatingball.server.modules.audit.service.AuditLogDisplayCatalog;
import org.apache.ibatis.executor.result.DefaultResultContext;
import org.apache.ibatis.session.ResultHandler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AnalyticsPgCompatibleServiceTest {
    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.Kind.class, names = {"OPENGAUSS", "POSTGRESQL", "GBASE_POSTGRESQL"})
    void malformedTextShouldNotBreakEitherPeriodOrCountAsMatching(DatabaseDialect.Kind kind) {
        AnalyticsMapper mapper = mock(AnalyticsMapper.class);
        AnalyticsService service = new AnalyticsService(mapper, new AuditLogDisplayCatalog(),
            new DatabaseDialect(kind));
        when(mapper.countFinalizedConsultations(any())).thenReturn(10L, 2L);
        char[] padding = new char[50000];
        Arrays.fill(padding, '界');
        String largeBefore = "{\"padding\":\"" + new String(padding) + "\",\"diagnosisChanges\":0}";
        String largeAfter = "{\"diagnosisChanges\":0,\"padding\":\"" + new String(padding) + "\"}";
        doAnswer(invocation -> {
            AnalyticsQueryDTO query = invocation.getArgument(0);
            ResultHandler<String> handler = invocation.getArgument(1);
            String[] rows = "2026-10-20".equals(query.getDateFrom())
                ? new String[]{"{\"diagnosisChanges\":0}", "{\"diagnosisChanges\":\"0\"}",
                    "{\"diagnosisChanges\":1}", "{}", null, "{broken", largeBefore,
                    "{\"diagnosisChanges\":false}", largeAfter, "{\"diagnosisChanges\":\"bad\"}"}
                : new String[]{"{\"diagnosisChanges\":0}", "{\"diagnosisChanges\":1}"};
            DefaultResultContext<String> context = new DefaultResultContext<String>();
            for (String row : rows) {
                context.nextResultObject(row);
                handler.handleResult(context);
            }
            return null;
        }).when(mapper).visitFinalizedChangeSummaries(any(), any());
        AnalyticsQueryDTO query = new AnalyticsQueryDTO();
        query.setDateFrom("2026-10-20");
        query.setDateTo("2026-10-20");
        query.setIdOrg("ORG_SYN");
        query.setIdRegion("REG_SYN");
        query.setHisOrgId("HIS_SYN");

        AnalyticsSummaryVO summary = service.getSummary(query);

        assertEquals("40", summary.getDiagnosisMatchRate());
        assertEquals("-10", summary.getMatchRateGrowth());
        verify(mapper, never()).countDiagnosisMatchedConsultations(any());
        ArgumentCaptor<AnalyticsQueryDTO> filters = ArgumentCaptor.forClass(AnalyticsQueryDTO.class);
        verify(mapper, times(2)).visitFinalizedChangeSummaries(filters.capture(), any());
        for (AnalyticsQueryDTO period : filters.getAllValues()) {
            assertEquals("ORG_SYN", period.getIdOrg());
            assertEquals("REG_SYN", period.getIdRegion());
            assertEquals("HIS_SYN", period.getHisOrgId());
            assertNotNull(period.getDateFromTime());
            assertNotNull(period.getDateToExclusiveTime());
        }
    }

    @ParameterizedTest
    @EnumSource(value = DatabaseDialect.Kind.class, names = {"OPENGAUSS", "POSTGRESQL", "GBASE_POSTGRESQL"})
    void emptySummaryShouldKeepZeroRate(DatabaseDialect.Kind kind) {
        AnalyticsMapper mapper = mock(AnalyticsMapper.class);
        AnalyticsSummaryVO summary = new AnalyticsService(mapper, new AuditLogDisplayCatalog(),
            new DatabaseDialect(kind)).getSummary(new AnalyticsQueryDTO());
        assertEquals("0", summary.getDiagnosisMatchRate());
        verify(mapper, never()).countDiagnosisMatchedConsultations(any());
        verify(mapper, times(2)).visitFinalizedChangeSummaries(any(), any());
    }
}
