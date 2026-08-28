package com.regionalai.floatingball.server.modules.symptom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.symptom.dto.SymptomTemplateChangeLogVO;
import com.regionalai.floatingball.server.modules.symptom.dto.SymptomTemplateVO;
import com.regionalai.floatingball.server.modules.symptom.entity.AiSymptomTemplateChangeLog;
import com.regionalai.floatingball.server.modules.symptom.mapper.AiSymptomTemplateChangeLogMapper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class SymptomTemplateChangeLogService {

    public static final String OPERATION_CREATE = "create";
    public static final String OPERATION_UPDATE = "update";
    public static final String OPERATION_DELETE = "delete";
    public static final String OPERATION_IMPORT_BUILTIN = "import_builtin";
    public static final String OPERATION_IMPORT_JSON = "import_json";

    private static final String ACTIVE_ENABLED = "1";
    private static final int SUMMARY_MAX_LENGTH = 1000;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");
    private static final Set<String> OPERATION_TYPES = new LinkedHashSet<String>(Arrays.asList(
        OPERATION_CREATE,
        OPERATION_UPDATE,
        OPERATION_DELETE,
        OPERATION_IMPORT_BUILTIN,
        OPERATION_IMPORT_JSON
    ));

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };

    private final AiSymptomTemplateChangeLogMapper changeLogMapper;
    private final ObjectMapper objectMapper;

    public SymptomTemplateChangeLogService(AiSymptomTemplateChangeLogMapper changeLogMapper,
                                           ObjectMapper objectMapper) {
        this.changeLogMapper = changeLogMapper;
        this.objectMapper = objectMapper;
    }

    public void record(String operationType,
                       SymptomTemplateVO before,
                       SymptomTemplateVO after,
                       AdminCurrentUser operator) {
        String normalizedOperationType = normalizeOperationType(operationType);
        SymptomTemplateVO anchor = after != null ? after : before;
        if (anchor == null) {
            return;
        }

        Map<String, Object> beforeSnapshot = toSnapshot(before);
        Map<String, Object> afterSnapshot = toSnapshot(after);
        Map<String, Object> diff = buildDiff(beforeSnapshot, afterSnapshot);

        AiSymptomTemplateChangeLog log = new AiSymptomTemplateChangeLog();
        log.setIdTemplate(anchor.getId());
        log.setCdSymptom(anchor.getKey());
        log.setNaSymptom(anchor.getName());
        log.setSdMedicalMode(anchor.getMedicalMode());
        log.setIdOrg(trimToNull(anchor.getIdOrg()));
        log.setIdRegion(trimToNull(anchor.getIdRegion()));
        log.setOperationType(normalizedOperationType);
        log.setIdOperator(operator == null ? null : operator.getIdUser());
        log.setCdOperator(operator == null ? null : operator.getCdUser());
        log.setNaOperator(operator == null ? null : operator.getNaUser());
        log.setChangeSummary(buildSummary(normalizedOperationType, diff, anchor));
        log.setBeforeJson(writeJson(beforeSnapshot));
        log.setAfterJson(writeJson(afterSnapshot));
        log.setDiffJson(writeJson(diff));
        log.setOperationTime(LocalDateTime.now());
        log.setFgActive(ACTIVE_ENABLED);
        changeLogMapper.insert(log);
    }

    public PageResponse<SymptomTemplateChangeLogVO> list(long current,
                                                         long size,
                                                         String idTemplate,
                                                         String keyword,
                                                         String medicalMode,
                                                         String operationType,
                                                         String operatorKeyword,
                                                         String dateFrom,
                                                         String dateTo) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiSymptomTemplateChangeLog> page = new Page<AiSymptomTemplateChangeLog>(pageRequest.getCurrent(), pageRequest.getSize());
        LocalDateTime startTime = parseDateTime(dateFrom, false);
        LocalDateTime endTime = parseDateTime(dateTo, true);
        LambdaQueryWrapper<AiSymptomTemplateChangeLog> wrapper = new LambdaQueryWrapper<AiSymptomTemplateChangeLog>()
            .eq(AiSymptomTemplateChangeLog::getFgActive, ACTIVE_ENABLED)
            .orderByDesc(AiSymptomTemplateChangeLog::getOperationTime);
        if (StringUtils.hasText(idTemplate)) {
            wrapper.eq(AiSymptomTemplateChangeLog::getIdTemplate, idTemplate.trim());
        }
        if (StringUtils.hasText(keyword)) {
            final String text = keyword.trim();
            wrapper.and(q -> q.like(AiSymptomTemplateChangeLog::getNaSymptom, text)
                .or()
                .like(AiSymptomTemplateChangeLog::getCdSymptom, text)
                .or()
                .like(AiSymptomTemplateChangeLog::getChangeSummary, text)
                .or()
                .like(AiSymptomTemplateChangeLog::getCdOperator, text)
                .or()
                .like(AiSymptomTemplateChangeLog::getNaOperator, text));
        }
        if (StringUtils.hasText(medicalMode)) {
            wrapper.eq(AiSymptomTemplateChangeLog::getSdMedicalMode, medicalMode.trim());
        }
        if (StringUtils.hasText(operationType)) {
            wrapper.eq(AiSymptomTemplateChangeLog::getOperationType, normalizeOperationType(operationType));
        }
        if (StringUtils.hasText(operatorKeyword)) {
            final String text = operatorKeyword.trim();
            wrapper.and(q -> q.like(AiSymptomTemplateChangeLog::getCdOperator, text)
                .or()
                .like(AiSymptomTemplateChangeLog::getNaOperator, text)
                .or()
                .like(AiSymptomTemplateChangeLog::getIdOperator, text));
        }
        if (startTime != null) {
            wrapper.ge(AiSymptomTemplateChangeLog::getOperationTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(AiSymptomTemplateChangeLog::getOperationTime, endTime);
        }
        Page<AiSymptomTemplateChangeLog> result = changeLogMapper.selectPage(page, wrapper);
        List<SymptomTemplateChangeLogVO> records = new ArrayList<SymptomTemplateChangeLogVO>();
        for (AiSymptomTemplateChangeLog item : result.getRecords()) {
            records.add(toView(item));
        }
        return new PageResponse<SymptomTemplateChangeLogVO>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    private String normalizeOperationType(String value) {
        if (!StringUtils.hasText(value) || !OPERATION_TYPES.contains(value.trim())) {
            throw new BusinessException("症状模板日志操作类型不支持");
        }
        return value.trim();
    }

    private Map<String, Object> toSnapshot(SymptomTemplateVO value) {
        if (value == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> snapshot = objectMapper.convertValue(value, MAP_TYPE);
        snapshot.remove("createdAt");
        snapshot.remove("updatedAt");
        return normalizeMap(snapshot);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeMap(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (source == null || source.isEmpty()) {
            return result;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                result.put(entry.getKey(), normalizeMap((Map<String, Object>) value));
            } else if (value instanceof List) {
                result.put(entry.getKey(), normalizeList((List<Object>) value));
            } else {
                result.put(entry.getKey(), value);
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<Object> normalizeList(List<Object> source) {
        List<Object> result = new ArrayList<Object>();
        if (source == null) {
            return result;
        }
        for (Object value : source) {
            if (value instanceof Map) {
                result.add(normalizeMap((Map<String, Object>) value));
            } else if (value instanceof List) {
                result.add(normalizeList((List<Object>) value));
            } else {
                result.add(value);
            }
        }
        return result;
    }

    private Map<String, Object> buildDiff(Map<String, Object> beforeSnapshot, Map<String, Object> afterSnapshot) {
        Map<String, Object> diff = new LinkedHashMap<String, Object>();
        LinkedHashSet<String> keys = new LinkedHashSet<String>();
        keys.addAll(beforeSnapshot == null ? Collections.<String>emptySet() : beforeSnapshot.keySet());
        keys.addAll(afterSnapshot == null ? Collections.<String>emptySet() : afterSnapshot.keySet());
        for (String key : keys) {
            Object beforeValue = beforeSnapshot == null ? null : beforeSnapshot.get(key);
            Object afterValue = afterSnapshot == null ? null : afterSnapshot.get(key);
            if (!jsonEquals(beforeValue, afterValue)) {
                Map<String, Object> fieldDiff = new LinkedHashMap<String, Object>();
                fieldDiff.put("before", beforeValue);
                fieldDiff.put("after", afterValue);
                diff.put(key, fieldDiff);
            }
        }
        return diff;
    }

    private boolean jsonEquals(Object left, Object right) {
        JsonNode leftNode = objectMapper.valueToTree(left);
        JsonNode rightNode = objectMapper.valueToTree(right);
        return Objects.equals(leftNode, rightNode);
    }

    private String buildSummary(String operationType, Map<String, Object> diff, SymptomTemplateVO anchor) {
        if (OPERATION_CREATE.equals(operationType)) {
            return "新增症状模板：" + displayName(anchor);
        }
        if (OPERATION_DELETE.equals(operationType)) {
            return "删除症状模板：" + displayName(anchor);
        }
        if (OPERATION_IMPORT_BUILTIN.equals(operationType)) {
            return buildImportSummary("导入内置模板", diff, anchor);
        }
        if (OPERATION_IMPORT_JSON.equals(operationType)) {
            return buildImportSummary("导入配置文件", diff, anchor);
        }
        if (diff == null || diff.isEmpty()) {
            return "保存症状模板：" + displayName(anchor) + "，无字段变化";
        }
        return truncate("修改症状模板：" + displayName(anchor) + "，变更字段：" + String.join("、", diff.keySet()));
    }

    private String buildImportSummary(String prefix, Map<String, Object> diff, SymptomTemplateVO anchor) {
        if (diff == null || diff.isEmpty()) {
            return prefix + "：" + displayName(anchor);
        }
        return truncate(prefix + "：" + displayName(anchor) + "，变更字段：" + String.join("、", diff.keySet()));
    }

    private String displayName(SymptomTemplateVO anchor) {
        if (anchor == null) {
            return "--";
        }
        if (StringUtils.hasText(anchor.getName())) {
            return anchor.getName();
        }
        return StringUtils.hasText(anchor.getKey()) ? anchor.getKey() : "--";
    }

    private String truncate(String value) {
        if (value == null || value.length() <= SUMMARY_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, SUMMARY_MAX_LENGTH - 3) + "...";
    }

    private SymptomTemplateChangeLogVO toView(AiSymptomTemplateChangeLog item) {
        SymptomTemplateChangeLogVO vo = new SymptomTemplateChangeLogVO();
        vo.setIdLog(item.getIdLog());
        vo.setIdTemplate(item.getIdTemplate());
        vo.setSymptomKey(item.getCdSymptom());
        vo.setSymptomName(item.getNaSymptom());
        vo.setMedicalMode(item.getSdMedicalMode());
        vo.setIdOrg(item.getIdOrg());
        vo.setIdRegion(item.getIdRegion());
        vo.setOperationType(item.getOperationType());
        vo.setOperatorId(item.getIdOperator());
        vo.setOperatorCode(item.getCdOperator());
        vo.setOperatorName(item.getNaOperator());
        vo.setChangeSummary(item.getChangeSummary());
        vo.setBeforeSnapshot(readMap(item.getBeforeJson()));
        vo.setAfterSnapshot(readMap(item.getAfterJson()));
        vo.setDiff(readMap(item.getDiffJson()));
        vo.setOperationTime(item.getOperationTime());
        vo.setCreatedAt(Long.valueOf(toEpochMillis(item.getInsertTime())));
        vo.setUpdatedAt(Long.valueOf(toEpochMillis(item.getUpdateTime())));
        return vo;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Collections.emptyMap() : value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("症状模板修改日志序列化失败");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (IOException ex) {
            throw new BusinessException("症状模板修改日志解析失败");
        }
    }

    private LocalDateTime parseDateTime(String value, boolean endOfDay) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String candidate = value.trim();
        try {
            if (candidate.length() == 10) {
                LocalDate date = LocalDate.parse(candidate, DateTimeFormatter.ISO_LOCAL_DATE);
                return endOfDay ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
            }
            return LocalDateTime.parse(candidate, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(candidate, ISO_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return OffsetDateTime.parse(candidate).toLocalDateTime();
        } catch (DateTimeParseException ex) {
            throw new BusinessException("症状模板修改日志查询时间格式非法");
        }
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private long toEpochMillis(LocalDateTime value) {
        if (value == null) {
            return 0L;
        }
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
