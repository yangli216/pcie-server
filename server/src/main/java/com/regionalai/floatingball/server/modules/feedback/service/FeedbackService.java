package com.regionalai.floatingball.server.modules.feedback.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.audit.service.AuditLogDisplayCatalog;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.feedback.dto.AdminFeedbackDetailResponse;
import com.regionalai.floatingball.server.modules.feedback.dto.AdminFeedbackListItem;
import com.regionalai.floatingball.server.modules.feedback.dto.ClientFeedbackSubmitRequest;
import com.regionalai.floatingball.server.modules.feedback.dto.ClientFeedbackSubmitResponse;
import com.regionalai.floatingball.server.modules.feedback.dto.FeedbackListQuery;
import com.regionalai.floatingball.server.modules.feedback.dto.FeedbackTimelineItem;
import com.regionalai.floatingball.server.modules.feedback.entity.AiFeedback;
import com.regionalai.floatingball.server.modules.feedback.mapper.AiFeedbackMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter ISO_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final AiFeedbackMapper aiFeedbackMapper;
    private final AiOpLogMapper aiOpLogMapper;
    private final ObjectMapper objectMapper;
    private final DatabaseDialect databaseDialect;
    private final AuditLogDisplayCatalog displayCatalog = new AuditLogDisplayCatalog();

    public FeedbackService(AiFeedbackMapper aiFeedbackMapper,
                           AiOpLogMapper aiOpLogMapper,
                           ObjectMapper objectMapper,
                           DatabaseDialect databaseDialect) {
        this.aiFeedbackMapper = aiFeedbackMapper;
        this.aiOpLogMapper = aiOpLogMapper;
        this.objectMapper = objectMapper;
        this.databaseDialect = databaseDialect;
    }

    @Transactional
    public ClientFeedbackSubmitResponse submit(AiDevice device, ClientFeedbackSubmitRequest request) {
        validateRequest(request);
        return submitResolved(device, request, false);
    }

    private ClientFeedbackSubmitResponse submitResolved(AiDevice device,
                                                        ClientFeedbackSubmitRequest request,
                                                        boolean retryingAfterDuplicate) {
        Map<String, Object> chainContext = request.getChainContext() == null
            ? Collections.<String, Object>emptyMap()
            : request.getChainContext();
        String scopeKey = trimToNull(asString(chainContext.get("feedbackScopeKey")));
        String previousFeedbackId = trimToNull(asString(chainContext.get("previousFeedbackId")));
        AiFeedback latestFeedback = findLatestFeedback(device == null ? null : device.getIdDevice(), scopeKey);
        FeedbackVersionInfo versionInfo = resolveVersionInfo(latestFeedback, previousFeedbackId);

        AiFeedback feedback = new AiFeedback();
        feedback.setIdDevice(device == null ? null : device.getIdDevice());
        feedback.setIdOrg(device == null ? null : device.getIdOrg());
        feedback.setSessionId(trimToNull(request.getSessionId()));
        feedback.setTraceId(trimToNull(request.getTraceId()));
        feedback.setSourceModule(firstNonBlank(request.getSourceModule(), "settings_feedback"));
        feedback.setKind(normalizeKind(request.getKind()));
        feedback.setSeverity(normalizeSeverity(request.getSeverity()));
        feedback.setIdDoctor(trimToNull(request.getDoctorId()));
        feedback.setNaDoctor(trimToNull(request.getDoctorName()));
        feedback.setIdDept(trimToNull(request.getDeptId()));
        feedback.setNaDept(trimToNull(request.getDeptName()));
        feedback.setNaOrg(trimToNull(request.getOrgName()));

        List<String> tags = request.getTags();
        if (tags != null && !tags.isEmpty()) {
            List<String> cleaned = tags.stream()
                .map(this::trimToNull)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .limit(20)
                .collect(Collectors.toList());
            if (!cleaned.isEmpty()) {
                try {
                    feedback.setTagsJson(objectMapper.writeValueAsString(cleaned));
                } catch (Exception ex) {
                    throw new BusinessException("反馈标签序列化失败");
                }
            }
        }

        feedback.setHasCorrection(Boolean.TRUE.equals(request.getHasCorrection()) ? "1" : "0");
        feedback.setHasTrace(StringUtils.hasText(feedback.getTraceId()) ? "1" : "0");
        feedback.setScore(request.getScore());
        feedback.setCommentText(request.getComment().trim());
        feedback.setFeedbackTime(LocalDateTime.now());
        feedback.setFgActive("1");

        ClientFeedbackSubmitRequest.ScreenshotPayload screenshot = request.getScreenshot();
        if (screenshot != null) {
            feedback.setScreenshotFileName(trimToNull(screenshot.getFileName()));
            feedback.setScreenshotMimeType(trimToNull(screenshot.getMimeType()));
            feedback.setScreenshotDataUrl(trimToNull(screenshot.getDataUrl()));
        }

        feedback.setFeedbackScopeKey(scopeKey);
        feedback.setIdFeedbackRoot(versionInfo.rootFeedbackId);
        feedback.setPreviousFeedbackId(versionInfo.previousFeedbackId);
        feedback.setRevisionNo(versionInfo.revisionNo);
        feedback.setFgLatest("1");

        try {
            feedback.setChainContextJson(objectMapper.writeValueAsString(
                chainContext
            ));
        } catch (Exception ex) {
            throw new BusinessException("反馈链路上下文序列化失败");
        }

        try {
            downgradePreviousLatest(device == null ? null : device.getIdDevice(), scopeKey, feedback.getPreviousFeedbackId());
            aiFeedbackMapper.insert(feedback);
            if (!StringUtils.hasText(feedback.getIdFeedbackRoot())) {
                feedback.setIdFeedbackRoot(feedback.getIdFeedback());
                aiFeedbackMapper.updateById(feedback);
            }
        } catch (DuplicateKeyException ex) {
            if (!retryingAfterDuplicate) {
                return submitResolved(device, request, true);
            }
            throw new BusinessException("反馈提交冲突，请稍后重试");
        }
        log.info("feedback submitted. idFeedback={}, kind={}, score={}, deviceId={}", feedback.getIdFeedback(), feedback.getKind(), feedback.getScore(), device == null ? null : device.getIdDevice());
        return new ClientFeedbackSubmitResponse(feedback.getIdFeedback(), "accepted");
    }

    public PageResponse<AdminFeedbackListItem> list(FeedbackListQuery query) {
        PageRequest pageRequest = PageRequest.of(query.getCurrent(), query.getSize());
        query.setCurrent(pageRequest.getCurrent());
        query.setSize(pageRequest.getSize());
        Page<AiFeedback> page = new Page<AiFeedback>(pageRequest.getCurrent(), pageRequest.getSize());
        QueryWrapper<AiFeedback> wrapper = new QueryWrapper<AiFeedback>()
            .eq("fg_active", "1")
            .orderByDesc("feedback_time");
        if (!Boolean.TRUE.equals(query.getIncludeHistory())) {
            wrapper.eq("fg_latest", "1");
        }

        if (StringUtils.hasText(query.getKeyword())) {
            String trimmed = query.getKeyword().trim();
            wrapper.and(q -> q
                .like("comment_text", trimmed)
                .or().like("trace_id", trimmed)
                .or().like("session_id", trimmed)
                .or().like("id_device", trimmed)
                .or().like("id_org", trimmed)
                .or().like("na_org", trimmed)
                .or().like("na_doctor", trimmed)
                .or().like("na_dept", trimmed));
        }
        List<Integer> scores = normalizeScores(query);
        if (!scores.isEmpty()) {
            if (scores.size() == 1) {
                wrapper.eq("score", scores.get(0));
            } else {
                wrapper.in("score", scores);
            }
        }
        if (StringUtils.hasText(query.getSourceModule())) {
            applySourceModuleFilter(wrapper, query.getSourceModule());
        }
        if (StringUtils.hasText(query.getKind())) {
            wrapper.eq("kind", query.getKind().trim());
        }
        if (StringUtils.hasText(query.getSeverity())) {
            wrapper.eq("severity", query.getSeverity().trim());
        }
        if (StringUtils.hasText(query.getDoctor())) {
            String trimmed = query.getDoctor().trim();
            wrapper.and(q -> q.like("na_doctor", trimmed).or().eq("id_doctor", trimmed));
        }
        if (StringUtils.hasText(query.getDept())) {
            String trimmed = query.getDept().trim();
            wrapper.and(q -> q.like("na_dept", trimmed).or().eq("id_dept", trimmed));
        }
        if (StringUtils.hasText(query.getOrg())) {
            String trimmed = query.getOrg().trim();
            wrapper.and(q -> q.like("na_org", trimmed).or().eq("id_org", trimmed));
        }
        if (query.getHasCorrection() != null) {
            wrapper.eq("has_correction", query.getHasCorrection() ? "1" : "0");
        }
        if (query.getHasTrace() != null) {
            wrapper.eq("has_trace", query.getHasTrace() ? "1" : "0");
        }

        LocalDateTime startTime = parseDateTime(query.getDateFrom(), false);
        LocalDateTime endTime = parseDateTime(query.getDateTo(), true);
        if (startTime != null) {
            wrapper.ge("feedback_time", startTime);
        }
        if (endTime != null) {
            wrapper.le("feedback_time", endTime);
        }

        Page<AiFeedback> result = aiFeedbackMapper.selectPage(page, wrapper);
        List<AdminFeedbackListItem> records = result.getRecords().stream()
            .map(this::toListItem)
            .collect(Collectors.toList());
        return new PageResponse<AdminFeedbackListItem>(
            result.getCurrent(),
            result.getSize(),
            result.getTotal(),
            records
        );
    }

    public AdminFeedbackDetailResponse detail(String feedbackId) {
        AiFeedback feedback = aiFeedbackMapper.selectOne(new QueryWrapper<AiFeedback>()
            .eq("id_feedback", feedbackId)
            .eq("fg_active", "1")
            .last(databaseDialect.firstRows(1)));
        if (feedback == null) {
            throw new BusinessException("反馈记录不存在");
        }

        AdminFeedbackDetailResponse response = new AdminFeedbackDetailResponse();
        response.setFeedback(toDetail(feedback));
        response.setTimeline(buildTimeline(feedback));
        return response;
    }

    private void validateRequest(ClientFeedbackSubmitRequest request) {
        if (request.getScore() == null || request.getScore() < 1 || request.getScore() > 5) {
            throw new BusinessException("评分必须在 1 到 5 分之间");
        }
        if (!StringUtils.hasText(request.getComment())) {
            throw new BusinessException("反馈说明不能为空");
        }
        ClientFeedbackSubmitRequest.ScreenshotPayload screenshot = request.getScreenshot();
        if (screenshot != null && StringUtils.hasText(screenshot.getDataUrl())) {
            String dataUrl = screenshot.getDataUrl().trim();
            if (!dataUrl.startsWith("data:image/")) {
                throw new BusinessException("截图必须为图片 dataUrl");
            }
        }
    }

    private AdminFeedbackListItem toListItem(AiFeedback feedback) {
        AdminFeedbackListItem item = new AdminFeedbackListItem();
        item.setFeedbackId(feedback.getIdFeedback());
        item.setScore(feedback.getScore());
        item.setComment(feedback.getCommentText());
        item.setSourceModule(feedback.getSourceModule());
        item.setDisplaySourceModule(displayCatalog.resolveSourceModuleLabel(feedback.getSourceModule()));
        item.setKind(normalizeKind(feedback.getKind()));
        item.setSeverity(normalizeSeverity(feedback.getSeverity()));
        item.setTags(parseTags(feedback.getTagsJson()));
        item.setHasCorrection("1".equals(feedback.getHasCorrection()));
        item.setHasTrace("1".equals(feedback.getHasTrace()) || StringUtils.hasText(feedback.getTraceId()));
        item.setHasScreenshot(StringUtils.hasText(feedback.getScreenshotDataUrl()));
        Map<String, Object> chainCtx = parseJsonMap(feedback.getChainContextJson());
        item.setTargetSummary(extractTargetSummary(item.getKind(), chainCtx));
        item.setTargetType(extractTargetType(item.getKind(), chainCtx));
        item.setIdDoctor(feedback.getIdDoctor());
        item.setNaDoctor(feedback.getNaDoctor());
        item.setIdDept(feedback.getIdDept());
        item.setNaDept(feedback.getNaDept());
        item.setIdOrg(feedback.getIdOrg());
        item.setNaOrg(feedback.getNaOrg());
        item.setTraceId(feedback.getTraceId());
        item.setSessionId(feedback.getSessionId());
        item.setIdDevice(feedback.getIdDevice());
        item.setRevisionNo(feedback.getRevisionNo());
        item.setLatest("1".equals(feedback.getFgLatest()));
        item.setCreatedAt(feedback.getFeedbackTime());
        return item;
    }

    private AdminFeedbackDetailResponse.FeedbackDetail toDetail(AiFeedback feedback) {
        AdminFeedbackDetailResponse.FeedbackDetail detail = new AdminFeedbackDetailResponse.FeedbackDetail();
        detail.setFeedbackId(feedback.getIdFeedback());
        detail.setScore(feedback.getScore());
        detail.setComment(feedback.getCommentText());
        detail.setSourceModule(feedback.getSourceModule());
        detail.setDisplaySourceModule(displayCatalog.resolveSourceModuleLabel(feedback.getSourceModule()));
        detail.setKind(normalizeKind(feedback.getKind()));
        detail.setSeverity(normalizeSeverity(feedback.getSeverity()));
        detail.setTags(parseTags(feedback.getTagsJson()));
        detail.setHasCorrection("1".equals(feedback.getHasCorrection()));
        detail.setHasTrace("1".equals(feedback.getHasTrace()) || StringUtils.hasText(feedback.getTraceId()));
        detail.setTraceId(feedback.getTraceId());
        detail.setSessionId(feedback.getSessionId());
        detail.setRevisionNo(feedback.getRevisionNo());
        detail.setLatest("1".equals(feedback.getFgLatest()));
        detail.setRootFeedbackId(feedback.getIdFeedbackRoot());
        detail.setPreviousFeedbackId(feedback.getPreviousFeedbackId());
        Map<String, Object> chainCtx = parseJsonMap(feedback.getChainContextJson());
        detail.setTargetSummary(extractTargetSummary(detail.getKind(), chainCtx));
        detail.setTargetType(extractTargetType(detail.getKind(), chainCtx));
        detail.setIdDoctor(feedback.getIdDoctor());
        detail.setNaDoctor(feedback.getNaDoctor());
        detail.setIdDept(feedback.getIdDept());
        detail.setNaDept(feedback.getNaDept());
        detail.setIdOrg(feedback.getIdOrg());
        detail.setNaOrg(feedback.getNaOrg());
        detail.setIdDevice(feedback.getIdDevice());
        detail.setScreenshotFileName(feedback.getScreenshotFileName());
        detail.setScreenshotMimeType(feedback.getScreenshotMimeType());
        detail.setScreenshotDataUrl(feedback.getScreenshotDataUrl());
        detail.setChainContext(chainCtx);
        detail.setCreatedAt(feedback.getFeedbackTime());
        return detail;
    }

    @SuppressWarnings("unchecked")
    private String extractTargetSummary(String kind, Map<String, Object> chainCtx) {
        if (chainCtx == null || chainCtx.isEmpty() || kind == null) {
            return null;
        }
        try {
            if ("recommendation".equals(kind)) {
                Object node = chainCtx.get("recommendation");
                if (node instanceof Map) {
                    Map<String, Object> rec = (Map<String, Object>) node;
                    String title = trimToNull(asString(rec.get("recommendationTitle")));
                    String type = trimToNull(asString(rec.get("targetType")));
                    String typeLabel = labelOfTargetType(type);
                    if (title != null) {
                        return typeLabel != null ? typeLabel + "：" + title : title;
                    }
                    return typeLabel;
                }
            } else if ("record_field".equals(kind)) {
                Object node = chainCtx.get("recordField");
                if (node instanceof Map) {
                    Map<String, Object> rf = (Map<String, Object>) node;
                    String label = trimToNull(asString(rf.get("fieldLabel")));
                    String key = trimToNull(asString(rf.get("fieldKey")));
                    String resolved = label != null ? label : labelOfRecordField(key);
                    return resolved != null ? "病例字段：" + resolved : null;
                }
            } else if ("session".equals(kind)) {
                return "整页评分";
            }
        } catch (Exception ex) {
            log.debug("feedback target summary extraction failed. kind={}, error={}", kind, ex.getMessage());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String extractTargetType(String kind, Map<String, Object> chainCtx) {
        if (chainCtx == null || chainCtx.isEmpty() || kind == null) {
            return null;
        }
        try {
            if ("recommendation".equals(kind)) {
                Object node = chainCtx.get("recommendation");
                if (node instanceof Map) {
                    return trimToNull(asString(((Map<String, Object>) node).get("targetType")));
                }
            } else if ("record_field".equals(kind)) {
                Object node = chainCtx.get("recordField");
                if (node instanceof Map) {
                    return trimToNull(asString(((Map<String, Object>) node).get("fieldKey")));
                }
            } else if ("session".equals(kind)) {
                return "session";
            }
        } catch (Exception ex) {
            log.debug("feedback target type extraction failed. kind={}, error={}", kind, ex.getMessage());
        }
        return null;
    }

    private String asString(Object value) {
        return value == null ? null : value.toString();
    }

    private List<Integer> normalizeScores(FeedbackListQuery query) {
        if (query.getScores() != null && !query.getScores().isEmpty()) {
            return query.getScores().stream()
                .filter(java.util.Objects::nonNull)
                .filter(score -> score >= 1 && score <= 5)
                .distinct()
                .collect(Collectors.toList());
        }
        if (query.getScore() != null && query.getScore() >= 1 && query.getScore() <= 5) {
            return Collections.singletonList(query.getScore());
        }
        return Collections.emptyList();
    }

    private AiFeedback findLatestFeedback(String deviceId, String scopeKey) {
        if (!StringUtils.hasText(deviceId) || !StringUtils.hasText(scopeKey)) {
            return null;
        }
        return aiFeedbackMapper.selectOne(new QueryWrapper<AiFeedback>()
            .eq("fg_active", "1")
            .eq("fg_latest", "1")
            .eq("id_device", deviceId)
            .eq("feedback_scope_key", scopeKey)
            .orderByDesc("revision_no")
            .orderByDesc("feedback_time")
            .last(databaseDialect.firstRows(1)));
    }

    private FeedbackVersionInfo resolveVersionInfo(AiFeedback latestFeedback, String requestedPreviousFeedbackId) {
        FeedbackVersionInfo info = new FeedbackVersionInfo();
        if (latestFeedback == null) {
            info.rootFeedbackId = null;
            info.previousFeedbackId = trimToNull(requestedPreviousFeedbackId);
            info.revisionNo = 1;
            return info;
        }
        info.rootFeedbackId = firstNonBlank(latestFeedback.getIdFeedbackRoot(), latestFeedback.getIdFeedback());
        info.previousFeedbackId = firstNonBlank(latestFeedback.getIdFeedback(), requestedPreviousFeedbackId);
        info.revisionNo = Math.max(1, latestFeedback.getRevisionNo() == null ? 1 : latestFeedback.getRevisionNo() + 1);
        return info;
    }

    private void downgradePreviousLatest(String deviceId, String scopeKey, String previousFeedbackId) {
        if (!StringUtils.hasText(deviceId) || !StringUtils.hasText(scopeKey)) {
            return;
        }
        UpdateWrapper<AiFeedback> wrapper = new UpdateWrapper<AiFeedback>()
            .eq("fg_active", "1")
            .eq("fg_latest", "1")
            .eq("id_device", deviceId)
            .eq("feedback_scope_key", scopeKey)
            .set("fg_latest", "0");
        if (StringUtils.hasText(previousFeedbackId)) {
            wrapper.ne("id_feedback", previousFeedbackId.trim());
        }
        aiFeedbackMapper.update(null, wrapper);

        if (StringUtils.hasText(previousFeedbackId)) {
            aiFeedbackMapper.update(null, new UpdateWrapper<AiFeedback>()
                .eq("fg_active", "1")
                .eq("id_feedback", previousFeedbackId.trim())
                .set("fg_latest", "0"));
        }
    }

    private static final class FeedbackVersionInfo {
        private String rootFeedbackId;
        private String previousFeedbackId;
        private Integer revisionNo;
    }

    private String labelOfTargetType(String type) {
        if (type == null) return null;
        switch (type) {
            case "diagnosis": return "推荐诊断";
            case "medication":
            case "medicine": return "推荐用药";
            case "exam": return "推荐检查";
            case "lab": return "推荐检验";
            case "procedure": return "推荐处置";
            case "treatment": return "推荐处置";
            default: return "推荐项";
        }
    }

    private String labelOfRecordField(String key) {
        if (key == null) return null;
        switch (key) {
            case "chiefComplaint": return "主诉";
            case "historyOfPresentIllness": return "现病史";
            case "pastHistory": return "既往史";
            case "personalHistory": return "个人史";
            case "familyHistory": return "家族史";
            case "physicalExamination": return "查体";
            case "diagnosis": return "诊断";
            default: return key;
        }
    }

    private static final java.util.Set<String> KIND_VALUES = new java.util.HashSet<>(java.util.Arrays.asList(
        "general", "recommendation", "record_field", "session"));
    private static final java.util.Set<String> SEVERITY_VALUES = new java.util.HashSet<>(java.util.Arrays.asList(
        "low", "medium", "high"));

    private String normalizeKind(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return "general";
        }
        String lower = trimmed.toLowerCase();
        return KIND_VALUES.contains(lower) ? lower : "general";
    }

    private String normalizeSeverity(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return "medium";
        }
        String lower = trimmed.toLowerCase();
        return SEVERITY_VALUES.contains(lower) ? lower : "medium";
    }

    private List<String> parseTags(String value) {
        if (!StringUtils.hasText(value)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<List<String>>() {});
        } catch (Exception ex) {
            return Collections.emptyList();
        }
    }

    private List<FeedbackTimelineItem> buildTimeline(AiFeedback feedback) {
        List<FeedbackTimelineItem> timeline = new ArrayList<FeedbackTimelineItem>();
        timeline.add(buildFeedbackTimelineItem(feedback));

        QueryWrapper<AiOpLog> wrapper = new QueryWrapper<AiOpLog>()
            .eq("fg_active", "1")
            .orderByAsc("operation_time");
        if (StringUtils.hasText(feedback.getIdDevice())) {
            wrapper.eq("id_device", feedback.getIdDevice());
        }
        if (StringUtils.hasText(feedback.getTraceId())) {
            wrapper.apply(databaseDialect.textContains("payload_json", "{0}"), feedback.getTraceId().trim());
        } else if (StringUtils.hasText(feedback.getSessionId())) {
            wrapper.apply(databaseDialect.textContains("payload_json", "{0}"), feedback.getSessionId().trim());
            if (feedback.getFeedbackTime() != null) {
                wrapper.ge("operation_time", feedback.getFeedbackTime().minusMinutes(30));
                wrapper.le("operation_time", feedback.getFeedbackTime().plusMinutes(5));
            }
        } else {
            if (feedback.getFeedbackTime() != null) {
                wrapper.ge("operation_time", feedback.getFeedbackTime().minusMinutes(10));
                wrapper.le("operation_time", feedback.getFeedbackTime().plusMinutes(1));
            }
        }

        List<AiOpLog> logs = aiOpLogMapper.selectList(wrapper);
        for (AiOpLog log : logs) {
            timeline.add(buildLogTimelineItem(log));
        }

        timeline.sort(Comparator.comparing(FeedbackTimelineItem::getTime, Comparator.nullsLast(Comparator.naturalOrder())));
        return timeline;
    }

    private FeedbackTimelineItem buildFeedbackTimelineItem(AiFeedback feedback) {
        FeedbackTimelineItem item = new FeedbackTimelineItem();
        item.setType("feedback");
        item.setTime(feedback.getFeedbackTime());
        item.setTitle("用户提交反馈");
        item.setDisplaySourceModule(displayCatalog.resolveSourceModuleLabel(feedback.getSourceModule()));
        item.setResult("success");
        item.setPayload(parseJsonMap(feedback.getChainContextJson()));
        return item;
    }

    private FeedbackTimelineItem buildLogTimelineItem(AiOpLog log) {
        displayCatalog.enrich(log);
        FeedbackTimelineItem item = new FeedbackTimelineItem();
        item.setType(log.getSdLogType());
        item.setTime(log.getOperationTime());
        item.setTitle(firstNonBlank(log.getDisplayTitle(), log.getDesOp(), log.getDisplayAction(), log.getDisplayModule(), log.getSdLogType()));
        item.setDisplaySourceModule(log.getDisplaySourceModule());
        item.setResult(resolveResult(log.getOpResult()));
        Map<String, Object> payload = new LinkedHashMap<String, Object>(parseJsonMap(log.getPayloadJson()));
        if (StringUtils.hasText(log.getAudioFilePath())) {
            payload.put("audioFilePath", log.getAudioFilePath());
        }
        item.setPayload(payload);
        return item;
    }

    private void applySourceModuleFilter(QueryWrapper<AiFeedback> wrapper, String sourceModule) {
        String text = sourceModule.trim();
        Collection<String> aliases = displayCatalog.lookupSourceModuleCodes(sourceModule);
        if (aliases == null || aliases.isEmpty()) {
            wrapper.like("source_module", text);
            return;
        }
        wrapper.and(q -> {
            q.like("source_module", text);
            for (String alias : aliases) {
                q.or().eq("source_module", alias);
            }
        });
    }

    private Map<String, Object> parseJsonMap(String value) {
        if (!StringUtils.hasText(value)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(value, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception ex) {
            return Collections.singletonMap("raw", value);
        }
    }

    private String resolveResult(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            return "unknown";
        }
        String lower = normalized.toLowerCase();
        if ("1".equals(lower) || "true".equals(lower) || "success".equals(lower) || "ok".equals(lower)) {
            return "success";
        }
        if ("0".equals(lower) || "false".equals(lower) || "fail".equals(lower) || "failure".equals(lower) || "error".equals(lower)) {
            return "failure";
        }
        return lower;
    }

    private LocalDateTime parseDateTime(String value, boolean endOfDay) {
        String text = trimToNull(value);
        if (text == null) {
            return null;
        }
        try {
            return LocalDateTime.parse(text, DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDateTime.parse(text, ISO_DATE_TIME_FORMATTER);
        } catch (DateTimeParseException ignored) {
        }
        try {
            LocalDate date = LocalDate.parse(text);
            return endOfDay ? date.atTime(LocalTime.MAX) : date.atStartOfDay();
        } catch (DateTimeParseException ignored) {
        }
        throw new BusinessException("时间格式不正确，应为 yyyy-MM-dd HH:mm:ss");
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = trimToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
