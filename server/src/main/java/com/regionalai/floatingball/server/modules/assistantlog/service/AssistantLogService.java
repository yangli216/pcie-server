package com.regionalai.floatingball.server.modules.assistantlog.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.assistantlog.dto.*;
import com.regionalai.floatingball.server.modules.assistantlog.mapper.AssistantLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

@Service
public class AssistantLogService {
    private static final int EXPORT_LIMIT = 1000;
    private final AssistantLogMapper mapper;
    private final DatabaseDialect dialect;
    private final ObjectMapper objectMapper;

    public AssistantLogService(AssistantLogMapper mapper, DatabaseDialect dialect, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.dialect = dialect;
        this.objectMapper = objectMapper;
    }

    public PageResponse<AssistantLogItem> list(AssistantLogQuery query, long current, long size) {
        normalize(query);
        PageRequest page = PageRequest.of(current, size);
        long total = mapper.count(query, dialect.isPgCompatible());
        List<AssistantLogItem> records = new ArrayList<>();
        if (page.getOffset() < total) {
            for (AssistantLogRow row : mapper.list(query, dialect.isPgCompatible(), page.getOffset(), page.getSize())) {
                records.add(project(row, false));
            }
        }
        return new PageResponse<>(page.getCurrent(), page.getSize(), total, records);
    }

    public List<AssistantLogOrganization> organizations() {
        return mapper.organizations();
    }

    public AssistantLogDetail detail(String idLog) {
        AssistantLogRow row = mapper.detail(idLog);
        if (row == null) throw new BusinessException("医学助手日志不存在");
        return (AssistantLogDetail) project(row, true);
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public byte[] exportCsv(AssistantLogQuery query) {
        normalize(query);
        StringBuilder csv = new StringBuilder("\ufeff序号,机构,医生姓名,使用时间,查询内容输入\r\n");
        int index = 0;
        // One bounded statement keeps export order stable while a cursor avoids retaining image payloads.
        try (org.apache.ibatis.cursor.Cursor<AssistantLogRow> rows = mapper.exportRows(query, dialect.isPgCompatible())) {
            for (AssistantLogRow row : rows) {
                if (++index > EXPORT_LIMIT) throw new BusinessException("最多导出 1000 条，请缩小筛选范围");
                AssistantLogDetail item = (AssistantLogDetail) project(row, true);
                String input = item.getInputText();
                if (item.getImageCount() > 0) input += "\n[附图 " + item.getImageCount() + " 张]";
                csv.append(index).append(',').append(cell(item.getOrgName())).append(',')
                    .append(cell(item.getDoctorName())).append(',')
                    .append(cell(item.getUsageTime() == null ? "" : item.getUsageTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))))
                    .append(',').append(cell(input)).append("\r\n");
            }
        } catch (java.io.IOException ex) {
            throw new BusinessException("导出医学助手日志失败，请稍后重试");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String cell(String value) {
        String text = value == null ? "" : value;
        String trimmed = text.trim();
        if ((!trimmed.isEmpty() && "=+-@".indexOf(trimmed.charAt(0)) >= 0)
            || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n")) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private AssistantLogItem project(AssistantLogRow row, boolean full) {
        AssistantLogDetail item = new AssistantLogDetail();
        item.setIdLog(row.getIdLog());
        item.setIdOrg(row.getIdOrg());
        item.setHisOrgId(row.getHisOrgId());
        item.setOrgName(row.getOrganizationName());
        item.setUsageTime(row.getOperationTime());
        item.setSuccess("1".equals(row.getOpResult()));
        String input = "";
        String output = "";
        try {
            JsonNode payload = objectMapper.readTree(row.getPayloadJson());
            if (payload != null && payload.isObject()) {
                item.setContentAvailable(payload.path("requestBody").path("messages").isArray() || payload.path("responseText").isTextual());
                item.setDoctorId(text(payload.path("doctorId")));
                item.setDoctorName(text(payload.path("doctorName")));
                JsonNode messages = payload.path("requestBody").path("messages");
                if (messages.isArray()) {
                    for (int i = messages.size() - 1; i >= 0; i--) {
                        JsonNode message = messages.get(i);
                        if (!"user".equals(message.path("role").asText())) continue;
                        JsonNode content = message.path("content");
                        if (content.isTextual()) input = content.asText();
                        else if (content.isArray()) {
                            StringBuilder parts = new StringBuilder();
                            for (JsonNode part : content) {
                                if ("text".equals(part.path("type").asText()) && part.path("text").isTextual()) {
                                    if (parts.length() > 0) parts.append('\n');
                                    parts.append(part.path("text").asText());
                                } else if ("image_url".equals(part.path("type").asText())) {
                                    item.setImageCount(item.getImageCount() + 1);
                                }
                            }
                            input = parts.toString();
                        }
                        break;
                    }
                }
                if (payload.path("responseText").isTextual()) output = payload.path("responseText").asText();
            }
        } catch (Exception ignored) {
            // Historical damaged payloads remain visible as unavailable, never return raw payload.
        }
        int end = input.offsetByCodePoints(0, Math.min(160, input.codePointCount(0, input.length())));
        item.setInputPreview(input.substring(0, end) + (end < input.length() ? "…" : ""));
        if (full) {
            item.setInputText(input);
            item.setOutputText(output);
            return item;
        }
        AssistantLogItem summary = new AssistantLogItem();
        org.springframework.beans.BeanUtils.copyProperties(item, summary);
        return summary;
    }

    private String text(JsonNode value) {
        return value.isTextual() && StringUtils.hasText(value.asText()) ? value.asText().trim() : null;
    }

    private void normalize(AssistantLogQuery query) {
        query.setDoctorName(clean(query.getDoctorName()));
        query.setIdOrg(clean(query.getIdOrg()));
        query.setHisOrgId(clean(query.getHisOrgId()));
        String from = clean(query.getDateFrom());
        String to = clean(query.getDateTo());
        if ((from == null) != (to == null)) throw new BusinessException("请同时选择开始和结束日期");
        try {
            LocalDate start = from == null ? LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) : LocalDate.parse(from);
            LocalDate end = to == null ? start.plusDays(6) : LocalDate.parse(to);
            if (end.isBefore(start)) throw new BusinessException("开始日期不能晚于结束日期");
            query.setStartTime(start.atStartOfDay());
            query.setEndTime(end.plusDays(1).atStartOfDay());
        } catch (java.time.DateTimeException ex) {
            throw new BusinessException("日期格式必须为 yyyy-MM-dd");
        }
    }

    private String clean(String value) {
        if (!StringUtils.hasText(value)) return null;
        String text = value.trim();
        if (text.length() > 128) throw new BusinessException("筛选条件不能超过 128 个字符");
        return text;
    }
}
