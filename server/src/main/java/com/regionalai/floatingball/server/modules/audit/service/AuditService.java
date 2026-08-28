package com.regionalai.floatingball.server.modules.audit.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.audit.dto.AuditBatchRequest;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.Map;
import java.util.Collections;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;

import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AiOpLogMapper aiOpLogMapper;
    private final AiOpLogService aiOpLogService;
    private final ObjectMapper objectMapper;
    private final AudioLogStorageService audioLogStorageService;
    private final AuditLogDisplayCatalog displayCatalog = new AuditLogDisplayCatalog();

    public AuditService(AiOpLogMapper aiOpLogMapper,
                        AiOpLogService aiOpLogService,
                        ObjectMapper objectMapper,
                        AudioLogStorageService audioLogStorageService) {
        this.aiOpLogMapper = aiOpLogMapper;
        this.aiOpLogService = aiOpLogService;
        this.objectMapper = objectMapper;
        this.audioLogStorageService = audioLogStorageService;
    }

    @Transactional
    public int saveBatch(AiDevice device, AuditBatchRequest request) {
        if (request.getEvents() == null || request.getEvents().isEmpty()) {
            return 0;
        }
        log.info("audit batch save. deviceId={}, eventCount={}", device == null ? null : device.getIdDevice(), request.getEvents().size());
        List<AiOpLog> batch = new ArrayList<AiOpLog>(request.getEvents().size());
        for (AuditBatchRequest.AuditEvent event : request.getEvents()) {
            Map<String, Object> payload = event.getPayload();
            AiOpLog opLog = new AiOpLog();
            opLog.setIdDevice(device.getIdDevice());
            opLog.setIdOrg(device.getIdOrg());
            opLog.setHisOrgId(trimToNull(event.getHisOrgId()));
            opLog.setHisOrgName(trimToNull(event.getHisOrgName()));
            opLog.setSdLogType(event.getEventType());
            String module = resolveModule(event.getEventType(), payload);
            String action = resolveAction(event.getEventType(), payload);
            String title = resolveTitle(payload, action);
            opLog.setNaModule(module);
            opLog.setOpAction(action);
            opLog.setOpTitle(title);
            opLog.setSourceModule(resolveSourceModule(payload));
            opLog.setSceneCode(resolveScene(payload));
            opLog.setTraceId(resolveTraceId(payload));
            opLog.setConsultationId(resolveConsultationId(payload));
            opLog.setDesOp(title);
            opLog.setOpResult(resolveResult(payload));
            opLog.setOperationTime(event.getTimestamp() == null
                ? LocalDateTime.now()
                : LocalDateTime.ofInstant(Instant.ofEpochMilli(event.getTimestamp()), ZoneId.systemDefault()));
            opLog.setFgActive("1");
            try {
                opLog.setPayloadJson(objectMapper.writeValueAsString(payload));
            } catch (JsonProcessingException ex) {
                throw new BusinessException("审计事件序列化失败");
            }
            batch.add(opLog);
        }
        aiOpLogService.saveBatch(batch);
        return batch.size();
    }

    private String resolveModule(String eventType, Map<String, Object> payload) {
        return firstNonBlank(
            extractText(payload, "module"),
            extractText(payload, "operationType"),
            extractText(payload, "metricType"),
            extractText(payload, "targetType"),
            extractText(payload, "recType"),
            extractText(payload, "sessionType"),
            trimToNull(eventType)
        );
    }

    private String resolveAction(String eventType, Map<String, Object> payload) {
        String action = firstNonBlank(
            extractText(payload, "action"),
            extractText(payload, "operationName"),
            extractText(payload, "feedbackType"),
            extractText(payload, "recType"),
            extractText(payload, "metricType")
        );
        return action != null ? action : trimToNull(eventType);
    }

    private String resolveTitle(Map<String, Object> payload, String fallbackAction) {
        return firstNonBlank(
            extractText(payload, "title"),
            extractText(payload, "operationTitle"),
            extractText(payload, "description"),
            fallbackAction
        );
    }

    private String resolveSourceModule(Map<String, Object> payload) {
        return firstNonBlank(
            extractText(payload, "sourceModule"),
            extractNestedText(payload, "details", "sourceModule")
        );
    }

    private String resolveScene(Map<String, Object> payload) {
        return firstNonBlank(
            extractText(payload, "scene"),
            extractNestedText(payload, "details", "scene")
        );
    }

    private String resolveTraceId(Map<String, Object> payload) {
        return firstNonBlank(
            extractText(payload, "traceId"),
            extractNestedText(payload, "details", "traceId")
        );
    }

    private String resolveConsultationId(Map<String, Object> payload) {
        return firstNonBlank(
            extractText(payload, "consultationId"),
            extractNestedText(payload, "details", "consultationId")
        );
    }

    private String resolveResult(Map<String, Object> payload) {
        String explicitResult = normalizeResultValue(payload == null ? null : payload.get("result"));
        if (explicitResult != null) {
            return explicitResult;
        }
        String successResult = normalizeResultValue(payload == null ? null : payload.get("success"));
        if (successResult != null) {
            return successResult;
        }
        return "1";
    }

    private String extractText(Map<String, Object> payload, String key) {
        if (payload == null || !payload.containsKey(key)) {
            return null;
        }
        return trimToNull(String.valueOf(payload.get(key)));
    }

    @SuppressWarnings("unchecked")
    private String extractNestedText(Map<String, Object> payload, String parentKey, String key) {
        if (payload == null) {
            return null;
        }
        Object parent = payload.get(parentKey);
        if (!(parent instanceof Map)) {
            return null;
        }
        Object value = ((Map<String, Object>) parent).get(key);
        return value == null ? null : trimToNull(String.valueOf(value));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asPayloadMap(Object payload) {
        if (!(payload instanceof Map)) {
            return null;
        }
        return (Map<String, Object>) payload;
    }

    private String firstNonBlank(String... candidates) {
        if (candidates == null) {
            return null;
        }
        for (String candidate : candidates) {
            String text = trimToNull(candidate);
            if (text != null) {
                return text;
            }
        }
        return null;
    }

    private String normalizeResultValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Boolean) {
            return Boolean.TRUE.equals(value) ? "1" : "0";
        }
        String text = trimToNull(String.valueOf(value));
        if (text == null) {
            return null;
        }
        String normalized = text.toLowerCase();
        if ("1".equals(normalized) || "true".equals(normalized) || "success".equals(normalized) || "ok".equals(normalized)) {
            return "1";
        }
        if ("0".equals(normalized) || "false".equals(normalized) || "fail".equals(normalized)
            || "failure".equals(normalized) || "error".equals(normalized)) {
            return "0";
        }
        return text;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    public PageResponse<AiOpLog> list(long current,
                                      long size,
                                      String keyword,
                                      String logType,
                                      String module,
                                      String action,
                                      String title,
                                      String sourceModule,
                                      String scene,
                                      String traceId,
                                      String consultationId,
                                      String result,
                                      String dateFrom,
                                      String dateTo) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiOpLog> page = new Page<AiOpLog>(pageRequest.getCurrent(), pageRequest.getSize());
        LocalDateTime startTime = parseDateTime(dateFrom, false);
        LocalDateTime endTime = parseDateTime(dateTo, true);
        LambdaQueryWrapper<AiOpLog> wrapper = new LambdaQueryWrapper<AiOpLog>()
            .eq(AiOpLog::getFgActive, "1")
            .orderByDesc(AiOpLog::getOperationTime);
        if (StringUtils.hasText(keyword)) {
            applyKeywordFilter(wrapper, keyword);
        }
        if (StringUtils.hasText(logType)) {
            wrapper.eq(AiOpLog::getSdLogType, logType.trim());
        }
        if (StringUtils.hasText(module)) {
            applyAliasTextFilter(wrapper, AiOpLog::getNaModule, module, displayCatalog.lookupModuleCodes(module));
        }
        if (StringUtils.hasText(action)) {
            applyAliasTextFilter(wrapper, AiOpLog::getOpAction, action, displayCatalog.lookupActionCodes(action));
        }
        if (StringUtils.hasText(title)) {
            applyTitleFilter(wrapper, title);
        }
        if (StringUtils.hasText(sourceModule)) {
            applyAliasTextFilter(wrapper, AiOpLog::getSourceModule, sourceModule, displayCatalog.lookupSourceModuleCodes(sourceModule));
        }
        if (StringUtils.hasText(scene)) {
            applyAliasTextFilter(wrapper, AiOpLog::getSceneCode, scene, displayCatalog.lookupSceneCodes(scene));
        }
        if (StringUtils.hasText(traceId)) {
            wrapper.eq(AiOpLog::getTraceId, traceId.trim());
        }
        if (StringUtils.hasText(consultationId)) {
            wrapper.eq(AiOpLog::getConsultationId, consultationId.trim());
        }
        if (StringUtils.hasText(result)) {
            applyResultFilter(wrapper, result);
        }
        if (startTime != null) {
            wrapper.ge(AiOpLog::getOperationTime, startTime);
        }
        if (endTime != null) {
            wrapper.le(AiOpLog::getOperationTime, endTime);
        }
        Page<AiOpLog> pageResult = aiOpLogMapper.selectPage(page, wrapper);
        enrichDisplayFields(pageResult.getRecords());
        return new PageResponse<AiOpLog>(
            pageResult.getCurrent(),
            pageResult.getSize(),
            pageResult.getTotal(),
            pageResult.getRecords()
        );
    }

    public void saveSystemLog(AiDevice device,
                              String logType,
                              String module,
                              String action,
                              Object payload,
                              boolean success) {
        saveSystemLogInternal(device, logType, module, action, payload, success, null, null);
    }

    public void saveSystemLogWithAudioFile(AiDevice device,
                                           String logType,
                                           String module,
                                           String action,
                                           Object payload,
                                           boolean success,
                                           byte[] audioBytes,
                                           String audioFileName) {
        saveSystemLogInternal(device, logType, module, action, payload, success, audioBytes, audioFileName);
    }

    private void saveSystemLogInternal(AiDevice device,
                                       String logType,
                                       String module,
                                       String action,
                                       Object payload,
                                       boolean success,
                                       byte[] audioBytes,
                                       String audioFileName) {
        AiOpLog opLog = new AiOpLog();
        opLog.setIdLog(UUID.randomUUID().toString().replace("-", ""));
        opLog.setIdDevice(device == null ? null : device.getIdDevice());
        opLog.setIdOrg(device == null ? null : device.getIdOrg());
        opLog.setSdLogType(logType);
        opLog.setNaModule(module);
        Map<String, Object> payloadMap = asPayloadMap(payload);
        opLog.setHisOrgId(firstNonBlank(
            extractText(payloadMap, "hisOrgId"),
            extractNestedText(payloadMap, "context", "hisOrgId")
        ));
        opLog.setHisOrgName(firstNonBlank(
            extractText(payloadMap, "hisOrgName"),
            extractText(payloadMap, "orgName"),
            extractNestedText(payloadMap, "context", "hisOrgName")
        ));
        opLog.setOpAction(action);
        opLog.setOpTitle(resolveTitle(payloadMap, action));
        opLog.setSourceModule(resolveSourceModule(payloadMap));
        opLog.setSceneCode(resolveScene(payloadMap));
        opLog.setTraceId(resolveTraceId(payloadMap));
        opLog.setConsultationId(resolveConsultationId(payloadMap));
        opLog.setDesOp(firstNonBlank(opLog.getOpTitle(), action));
        opLog.setOpResult(success ? "1" : "0");
        opLog.setOperationTime(LocalDateTime.now());
        opLog.setFgActive("1");
        String storedAudioPath = null;
        try {
            if (audioBytes != null && audioBytes.length > 0) {
                storedAudioPath = audioLogStorageService.store(audioBytes, audioFileName, opLog.getIdLog());
                opLog.setAudioFilePath(storedAudioPath);
            }
            opLog.setPayloadJson(objectMapper.writeValueAsString(payload == null ? Collections.emptyMap() : payload));
            aiOpLogMapper.insert(opLog);
        } catch (Exception ex) {
            audioLogStorageService.deleteQuietly(storedAudioPath);
            if (success) {
                log.error("audit system log save failed for successful operation. logType={}, module={}, action={}, deviceId={}",
                    logType, module, action, device == null ? null : device.getIdDevice(), ex);
                throw new BusinessException("AUDIT-PERSIST-FAILED", "审计日志写入失败，请稍后重试；如持续出现，请联系管理员查看日志");
            }
            log.error("audit system log save failed for failed operation. logType={}, module={}, action={}, deviceId={}",
                logType, module, action, device == null ? null : device.getIdDevice(), ex);
        }
    }

    private void applyResultFilter(LambdaQueryWrapper<AiOpLog> wrapper, String result) {
        String normalized = result.trim().toLowerCase();
        if ("success".equals(normalized) || "1".equals(normalized) || "true".equals(normalized)) {
            wrapper.and(q -> q.eq(AiOpLog::getOpResult, "1")
                .or()
                .eq(AiOpLog::getOpResult, "true")
                .or()
                .eq(AiOpLog::getOpResult, "TRUE")
                .or()
                .eq(AiOpLog::getOpResult, "success")
                .or()
                .eq(AiOpLog::getOpResult, "SUCCESS"));
            return;
        }
        if ("failure".equals(normalized) || "0".equals(normalized) || "false".equals(normalized)) {
            wrapper.and(q -> q.eq(AiOpLog::getOpResult, "0")
                .or()
                .eq(AiOpLog::getOpResult, "false")
                .or()
                .eq(AiOpLog::getOpResult, "FALSE")
                .or()
                .eq(AiOpLog::getOpResult, "fail")
                .or()
                .eq(AiOpLog::getOpResult, "FAIL")
                .or()
                .eq(AiOpLog::getOpResult, "failure")
                .or()
                .eq(AiOpLog::getOpResult, "FAILURE"));
            return;
        }
        wrapper.eq(AiOpLog::getOpResult, result.trim());
    }

    private void enrichDisplayFields(List<AiOpLog> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (AiOpLog record : records) {
            displayCatalog.enrich(record);
        }
    }

    private void applyAliasTextFilter(LambdaQueryWrapper<AiOpLog> wrapper,
                                      SFunction<AiOpLog, String> column,
                                      String input,
                                      Collection<String> aliases) {
        final String text = input.trim();
        if (aliases == null || aliases.isEmpty()) {
            wrapper.like(column, text);
            return;
        }
        wrapper.and(q -> {
            q.like(column, text);
            for (String alias : aliases) {
                q.or().eq(column, alias);
            }
        });
    }

    private void applyTitleFilter(LambdaQueryWrapper<AiOpLog> wrapper, String title) {
        final String text = title.trim();
        final Collection<String> titleCodes = displayCatalog.lookupTitleCodes(title);
        final Collection<String> actionCodes = displayCatalog.lookupActionCodes(title);
        wrapper.and(q -> {
            q.like(AiOpLog::getOpTitle, text)
                .or()
                .like(AiOpLog::getDesOp, text);
            for (String alias : titleCodes) {
                q.or().eq(AiOpLog::getOpTitle, alias);
            }
            for (String alias : actionCodes) {
                q.or().eq(AiOpLog::getOpAction, alias);
            }
        });
    }

    private void applyKeywordFilter(LambdaQueryWrapper<AiOpLog> wrapper, String keyword) {
        final String text = keyword.trim();
        final Collection<String> moduleCodes = displayCatalog.lookupModuleCodes(keyword);
        final Collection<String> actionCodes = displayCatalog.lookupActionCodes(keyword);
        final Collection<String> titleCodes = displayCatalog.lookupTitleCodes(keyword);
        final Collection<String> sourceCodes = displayCatalog.lookupSourceModuleCodes(keyword);
        final Collection<String> sceneCodes = displayCatalog.lookupSceneCodes(keyword);
        wrapper.and(q -> {
            q.like(AiOpLog::getNaModule, text)
                .or()
                .like(AiOpLog::getOpAction, text)
                .or()
                .like(AiOpLog::getOpTitle, text)
                .or()
                .like(AiOpLog::getSourceModule, text)
                .or()
                .like(AiOpLog::getSceneCode, text)
                .or()
                .like(AiOpLog::getTraceId, text)
                .or()
                .like(AiOpLog::getConsultationId, text)
                .or()
                .like(AiOpLog::getSdLogType, text)
                .or()
                .like(AiOpLog::getDesOp, text)
                .or()
                .like(AiOpLog::getPayloadJson, text)
                .or()
                .like(AiOpLog::getAudioFilePath, text)
                .or()
                .like(AiOpLog::getIdDevice, text)
                .or()
                .like(AiOpLog::getIdOrg, text);
            appendOrEquals(q, AiOpLog::getNaModule, moduleCodes);
            appendOrEquals(q, AiOpLog::getOpAction, actionCodes);
            appendOrEquals(q, AiOpLog::getOpTitle, titleCodes);
            appendOrEquals(q, AiOpLog::getSourceModule, sourceCodes);
            appendOrEquals(q, AiOpLog::getSceneCode, sceneCodes);
        });
    }

    private void appendOrEquals(LambdaQueryWrapper<AiOpLog> wrapper,
                                SFunction<AiOpLog, String> column,
                                Collection<String> aliases) {
        if (aliases == null || aliases.isEmpty()) {
            return;
        }
        for (String alias : aliases) {
            wrapper.or().eq(column, alias);
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
            throw new BusinessException("日志查询时间格式非法");
        }
    }
}
