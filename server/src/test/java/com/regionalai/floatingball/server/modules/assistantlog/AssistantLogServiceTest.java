package com.regionalai.floatingball.server.modules.assistantlog;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.assistantlog.dto.*;
import com.regionalai.floatingball.server.modules.assistantlog.mapper.AssistantLogMapper;
import com.regionalai.floatingball.server.modules.assistantlog.service.AssistantLogService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AssistantLogServiceTest {
    private final AssistantLogMapper mapper = mock(AssistantLogMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AssistantLogService service = new AssistantLogService(mapper, new DatabaseDialect(DatabaseDialect.Kind.OPENGAUSS), objectMapper);

    @Test
    void paginatesInDatabaseAndDoesNotExposeRawPayloadOrFullContent() throws Exception {
        when(mapper.count(any(), eq(true))).thenReturn(50L);
        when(mapper.list(any(), eq(true), eq(0L), eq(100L))).thenReturn(Collections.singletonList(row(
            "{\"doctorName\":\"张医生\",\"requestBody\":{\"messages\":[{\"role\":\"system\",\"content\":\"SECRET\"},{\"role\":\"user\",\"content\":\"本次提问\"}]},\"responseText\":\"回答\",\"upstreamBody\":\"SECRET\"}")));
        PageResponse<AssistantLogItem> page = service.list(query(), 0, 1000);
        assertThat(page.getCurrent()).isEqualTo(1);
        assertThat(page.getSize()).isEqualTo(100);
        assertThat(page.getRecords().get(0).getInputPreview()).isEqualTo("本次提问");
        assertThat(page.getRecords().get(0).getDoctorName()).isEqualTo("张医生");
        assertThat(objectMapper.writeValueAsString(page.getRecords())).doesNotContain("SECRET", "outputText", "inputText", "payloadJson");
        ArgumentCaptor<AssistantLogQuery> captor = ArgumentCaptor.forClass(AssistantLogQuery.class);
        verify(mapper).count(captor.capture(), eq(true));
        assertThat(captor.getValue().getStartTime()).isEqualTo(LocalDateTime.of(2026, 10, 1, 0, 0));
        assertThat(captor.getValue().getEndTime()).isEqualTo(LocalDateTime.of(2026, 11, 1, 0, 0));
    }

    @Test
    void detailUsesOnlyLastUserMessageAndCountsImagesWithoutReturningTheirContents() throws Exception {
        when(mapper.detail("id")).thenReturn(row("{\"requestBody\":{\"messages\":[{\"role\":\"user\",\"content\":\"OLD\"},{\"role\":\"assistant\",\"content\":\"OLD ANSWER\"},{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"问题\"},{\"type\":\"image_url\",\"image_url\":{\"url\":\"BASE64_SECRET\"}}]}]},\"responseText\":\"<script>alert(1)</script>\"}"));
        AssistantLogDetail detail = service.detail("id");
        assertThat(detail.getInputText()).isEqualTo("问题");
        assertThat(detail.getImageCount()).isEqualTo(1);
        assertThat(detail.getOutputText()).isEqualTo("<script>alert(1)</script>");
        assertThat(detail.isContentAvailable()).isTrue();
        assertThat(detail.getDoctorName()).isNull();
        assertThat(objectMapper.writeValueAsString(detail)).doesNotContain("BASE64_SECRET", "OLD", "upstreamBody");
    }

    @Test
    void damagedHistoricalPayloadRemainsUnavailableAndFailedPartialAnswerIsPreserved() {
        when(mapper.detail("damaged")).thenReturn(row("not-json"));
        AssistantLogDetail damaged = service.detail("damaged");
        assertThat(damaged.isContentAvailable()).isFalse();
        assertThat(damaged.getInputText()).isEmpty();
        AssistantLogRow failed = row("{\"responseText\":\"部分回答\"}"); failed.setOpResult("0");
        when(mapper.detail("failed")).thenReturn(failed);
        assertThat(service.detail("failed").isSuccess()).isFalse();
        assertThat(service.detail("failed").getOutputText()).isEqualTo("部分回答");
        assertThatThrownBy(() -> service.detail("other")).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsInvalidRangesBeforeQueryingAndSkipsOutOfRangePages() {
        AssistantLogQuery reversed = query(); reversed.setDateFrom("2026-11-01");
        assertThatThrownBy(() -> service.list(reversed, 1, 10)).hasMessageContaining("开始日期");
        AssistantLogQuery missing = query(); missing.setDateTo(null);
        assertThatThrownBy(() -> service.exportCsv(missing)).hasMessageContaining("同时选择");
        AssistantLogQuery invalid = query(); invalid.setDateTo("2026-02-30");
        assertThatThrownBy(() -> service.list(invalid, 1, 10)).hasMessageContaining("日期格式");
        verifyNoInteractions(mapper);
        when(mapper.count(any(), eq(true))).thenReturn(1L);
        assertThat(service.list(query(), Long.MAX_VALUE, 100).getRecords()).isEmpty();
        verify(mapper, never()).list(any(), anyBoolean(), anyLong(), anyLong());
    }

    @Test
    void csvIncludesAllFilteredRowsAndEscapesFormulaCommaQuotesAndNewlines() {
        AssistantLogRow row = row("{\"doctorName\":\"=FORMULA()\",\"requestBody\":{\"messages\":[{\"role\":\"user\",\"content\":\"逗号,引号\\\"\\n换行\"}]}}");
        org.apache.ibatis.cursor.Cursor<AssistantLogRow> oneRow = cursor(Collections.singletonList(row));
        when(mapper.exportRows(any(), eq(true))).thenReturn(oneRow);
        String csv = new String(service.exportCsv(query()), StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\ufeff序号,机构,医生姓名,使用时间,查询内容输入\r\n");
        assertThat(csv).contains("\"'=FORMULA()\"", "\"逗号,引号\"\"\n换行\"");
        org.apache.ibatis.cursor.Cursor<AssistantLogRow> overLimit = cursor(Collections.nCopies(1001, row));
        when(mapper.exportRows(any(), eq(true))).thenReturn(overLimit);
        assertThatThrownBy(() -> service.exportCsv(query())).hasMessageContaining("1000");
    }

    @SuppressWarnings("unchecked")
    private org.apache.ibatis.cursor.Cursor<AssistantLogRow> cursor(java.util.List<AssistantLogRow> rows) {
        org.apache.ibatis.cursor.Cursor<AssistantLogRow> cursor = mock(org.apache.ibatis.cursor.Cursor.class);
        when(cursor.iterator()).thenReturn(rows.iterator());
        return cursor;
    }

    private AssistantLogQuery query() {
        AssistantLogQuery query = new AssistantLogQuery(); query.setDateFrom("2026-10-01"); query.setDateTo("2026-10-31"); return query;
    }
    private AssistantLogRow row(String payload) {
        AssistantLogRow row = new AssistantLogRow(); row.setIdLog("id"); row.setOrganizationName("测试机构"); row.setOpResult("1"); row.setPayloadJson(payload); return row;
    }
}
