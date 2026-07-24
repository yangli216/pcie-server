package com.regionalai.floatingball.server.modules.chronicdisease.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotItemRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotResponse;
import com.regionalai.floatingball.server.modules.chronicdisease.entity.AiChronicArtifactSnapshot;
import com.regionalai.floatingball.server.modules.chronicdisease.mapper.AiChronicArtifactSnapshotMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ChronicArtifactSnapshotService {

    private static final String HYPERTENSION = "hypertension";
    private static final String TYPE2_DIABETES = "type2_diabetes";
    private static final String HEALTH_PRESCRIPTION = "health_prescription";
    private static final String ANNUAL_ASSESSMENT = "annual_assessment";

    private static final Set<String> DISEASE_TYPES = setOf(HYPERTENSION, TYPE2_DIABETES);
    private static final Set<String> ARTIFACT_TYPES = setOf(HEALTH_PRESCRIPTION, ANNUAL_ASSESSMENT);
    private static final Set<String> ITEM_CATEGORIES = setOf("test", "medicine-review", "lifestyle");

    private final AiChronicArtifactSnapshotMapper snapshotMapper;
    private final ChronicArtifactSnapshotWriter snapshotWriter;
    private final ObjectMapper objectMapper;

    public ChronicArtifactSnapshotService(
        AiChronicArtifactSnapshotMapper snapshotMapper,
        ChronicArtifactSnapshotWriter snapshotWriter,
        ObjectMapper objectMapper
    ) {
        this.snapshotMapper = snapshotMapper;
        this.snapshotWriter = snapshotWriter;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChronicArtifactSnapshotResponse save(
        AiDevice device,
        ChronicArtifactSnapshotRequest request
    ) {
        if (device == null || !StringUtils.hasText(device.getIdOrg())) {
            throw new BusinessException("CHRONIC-AUTH-001", "设备机构上下文缺失，请重新注册设备");
        }
        normalize(request);
        validate(request);

        String diseaseTypesJson = serialize(request.getDiseaseTypes(), "慢病类型");
        AiChronicArtifactSnapshot existing = findByRequestId(device.getIdOrg(), request.getRequestId());
        if (existing != null) {
            return resolveIdempotent(existing, request, diseaseTypesJson);
        }

        AiChronicArtifactSnapshot entity = toEntity(device, request, diseaseTypesJson);
        try {
            snapshotWriter.insert(entity);
        } catch (DuplicateKeyException ex) {
            AiChronicArtifactSnapshot concurrent = findByRequestId(
                device.getIdOrg(),
                request.getRequestId()
            );
            if (concurrent != null) {
                return resolveIdempotent(concurrent, request, diseaseTypesJson);
            }
            throw ex;
        }
        return toResponse(entity);
    }

    private void normalize(ChronicArtifactSnapshotRequest request) {
        request.setRequestId(trim(request.getRequestId()));
        request.setArtifactType(trim(request.getArtifactType()));
        request.setHisOrgId(trimToNull(request.getHisOrgId()));
        request.setHisOrgName(trimToNull(request.getHisOrgName()));
        request.setPatientId(trim(request.getPatientId()));
        request.setVisitId(trimToNull(request.getVisitId()));
        request.setPatientName(trim(request.getPatientName()));
        request.setDiseaseTypes(normalizeStrings(request.getDiseaseTypes()));
        request.setDataAsOf(trim(request.getDataAsOf()));
        request.setTemplateVersions(normalizeStrings(request.getTemplateVersions()));
        request.setPathVersions(normalizeStrings(request.getPathVersions()));
        request.setEvidenceVersions(normalizeStrings(request.getEvidenceVersions()));
        request.setRuleVersion(trim(request.getRuleVersion()));
        request.setSummaryText(trim(request.getSummaryText()));
        request.setDoctorNotes(trimToNull(request.getDoctorNotes()));
        request.setDoctorId(trimToNull(request.getDoctorId()));
        request.setDoctorName(trim(request.getDoctorName()));
        if (request.getAcceptedItems() == null) {
            request.setAcceptedItems(new ArrayList<ChronicArtifactSnapshotItemRequest>());
        }
        for (ChronicArtifactSnapshotItemRequest item : request.getAcceptedItems()) {
            item.setItemId(trim(item.getItemId()));
            item.setCategory(trim(item.getCategory()));
            item.setTitle(trim(item.getTitle()));
            item.setDetail(trim(item.getDetail()));
            item.setReason(trim(item.getReason()));
        }
    }

    private void validate(ChronicArtifactSnapshotRequest request) {
        requireEnum(ARTIFACT_TYPES, request.getArtifactType(), "快照类型");
        if (request.getDiseaseTypes().isEmpty()) {
            throw new BusinessException("慢病类型不能为空");
        }
        for (String diseaseType : request.getDiseaseTypes()) {
            requireEnum(DISEASE_TYPES, diseaseType, "慢病类型");
        }
        parseDataAsOf(request.getDataAsOf());
        requireVersion(request.getRuleVersion(), "CHRONIC-RULE-2026.1", "慢病规则");
        validatePublishedVersions(request);

        if (HEALTH_PRESCRIPTION.equals(request.getArtifactType())
            && request.getAcceptedItems().isEmpty()) {
            throw new BusinessException("健康处方至少需要一条医生确认建议");
        }
        if (ANNUAL_ASSESSMENT.equals(request.getArtifactType())) {
            Integer year = request.getAssessmentYear();
            if (year == null) {
                throw new BusinessException("年度评估必须提供评估年度");
            }
            if (year < 2000 || year > Year.now().getValue()) {
                throw new BusinessException("评估年度超出允许范围");
            }
        }
        for (ChronicArtifactSnapshotItemRequest item : request.getAcceptedItems()) {
            requireEnum(ITEM_CATEGORIES, item.getCategory(), "确认项分类");
        }

        validateRange(request.getSystolicPressure(), 60, 260, "收缩压");
        validateRange(request.getDiastolicPressure(), 30, 160, "舒张压");
        validateRange(request.getBloodGlucose(), new BigDecimal("1.0"), new BigDecimal("50.0"), "血糖");
        requireNonNegative(request.getBloodPressureRecordCount(), "血压记录数");
        requireNonNegative(request.getBloodGlucoseRecordCount(), "血糖记录数");
    }

    private void validatePublishedVersions(ChronicArtifactSnapshotRequest request) {
        Set<String> expectedTemplates = new HashSet<String>();
        Set<String> expectedPaths = new HashSet<String>();
        Set<String> expectedEvidence = new HashSet<String>();
        for (String diseaseType : request.getDiseaseTypes()) {
            if (HYPERTENSION.equals(diseaseType)) {
                expectedTemplates.add("HTN-FOLLOWUP-2026.1");
                expectedPaths.add("HTN-PATH-2024.1");
                expectedEvidence.add("中国高血压防治指南-2024");
            } else {
                expectedTemplates.add("T2DM-FOLLOWUP-2026.1");
                expectedPaths.add("T2DM-PATH-2022.1");
                expectedEvidence.add("国家基层糖尿病防治管理指南-2022");
            }
        }
        requireExactVersions(request.getTemplateVersions(), expectedTemplates, "表单模板");
        requireExactVersions(request.getPathVersions(), expectedPaths, "临床路径");
        requireExactVersions(request.getEvidenceVersions(), expectedEvidence, "依据");
    }

    private AiChronicArtifactSnapshot findByRequestId(String idOrg, String requestId) {
        return snapshotMapper.selectOne(new LambdaQueryWrapper<AiChronicArtifactSnapshot>()
            .eq(AiChronicArtifactSnapshot::getIdOrg, idOrg)
            .eq(AiChronicArtifactSnapshot::getRequestId, requestId)
            .eq(AiChronicArtifactSnapshot::getFgActive, "1"));
    }

    private ChronicArtifactSnapshotResponse resolveIdempotent(
        AiChronicArtifactSnapshot existing,
        ChronicArtifactSnapshotRequest request,
        String diseaseTypesJson
    ) {
        if (!request.getPatientId().equals(existing.getPatientId())
            || !request.getArtifactType().equals(existing.getArtifactType())
            || !safeEquals(request.getVisitId(), existing.getVisitId())
            || !diseaseTypesJson.equals(existing.getDiseaseTypesJson())) {
            throw new ConflictException(
                "CHRONIC-ARTIFACT-CONFLICT",
                "相同 requestId 已用于其他患者、就诊、病种或快照类型"
            );
        }
        return toResponse(existing);
    }

    private AiChronicArtifactSnapshot toEntity(
        AiDevice device,
        ChronicArtifactSnapshotRequest request,
        String diseaseTypesJson
    ) {
        AiChronicArtifactSnapshot entity = new AiChronicArtifactSnapshot();
        entity.setRequestId(request.getRequestId());
        entity.setArtifactType(request.getArtifactType());
        entity.setIdDevice(device.getIdDevice());
        entity.setIdOrg(device.getIdOrg());
        entity.setIdHisOrg(request.getHisOrgId());
        entity.setNaHisOrg(request.getHisOrgName());
        entity.setPatientId(request.getPatientId());
        entity.setVisitId(request.getVisitId());
        entity.setPatientName(request.getPatientName());
        entity.setDiseaseTypesJson(diseaseTypesJson);
        entity.setDataAsOf(parseDataAsOf(request.getDataAsOf()));
        entity.setAssessmentYear(request.getAssessmentYear());
        entity.setTemplateVersionsJson(serialize(request.getTemplateVersions(), "表单模板版本"));
        entity.setPathVersionsJson(serialize(request.getPathVersions(), "临床路径版本"));
        entity.setEvidenceVersionsJson(serialize(request.getEvidenceVersions(), "依据版本"));
        entity.setRuleVersion(request.getRuleVersion());
        entity.setSummaryText(request.getSummaryText());
        entity.setSystolicPressure(request.getSystolicPressure());
        entity.setDiastolicPressure(request.getDiastolicPressure());
        entity.setBloodGlucose(request.getBloodGlucose());
        entity.setBloodPressureRecordCount(request.getBloodPressureRecordCount());
        entity.setBloodGlucoseRecordCount(request.getBloodGlucoseRecordCount());
        entity.setAcceptedItemsJson(serialize(request.getAcceptedItems(), "医生确认项"));
        entity.setDoctorNotes(request.getDoctorNotes());
        entity.setIdDoctor(request.getDoctorId());
        entity.setNaDoctor(request.getDoctorName());
        entity.setSaveStatus("saved");
        entity.setFgActive("1");
        return entity;
    }

    private ChronicArtifactSnapshotResponse toResponse(AiChronicArtifactSnapshot entity) {
        LocalDateTime savedAt = entity.getInsertTime() == null
            ? LocalDateTime.now()
            : entity.getInsertTime();
        return new ChronicArtifactSnapshotResponse(
            entity.getIdSnapshot(),
            entity.getRequestId(),
            "saved",
            savedAt,
            entity.getArtifactType()
        );
    }

    private List<String> normalizeStrings(List<String> values) {
        if (values == null) {
            return Collections.emptyList();
        }
        return values.stream()
            .map(this::trim)
            .filter(StringUtils::hasText)
            .distinct()
            .sorted()
            .collect(Collectors.toList());
    }

    private LocalDateTime parseDataAsOf(String value) {
        try {
            return OffsetDateTime.parse(value).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException ex) {
                throw new BusinessException("数据截至时间格式无效");
            }
        }
    }

    private void requireEnum(Set<String> allowed, String value, String label) {
        if (!allowed.contains(value)) {
            throw new BusinessException(label + "不是受支持的值");
        }
    }

    private void requireVersion(String actual, String expected, String label) {
        if (!expected.equals(actual)) {
            throw new BusinessException(label + "版本未发布或已失效");
        }
    }

    private void requireExactVersions(List<String> actual, Set<String> expected, String label) {
        if (!new HashSet<String>(actual).equals(expected)) {
            throw new BusinessException(label + "版本未完整覆盖当前病种或包含未发布版本");
        }
    }

    private void validateRange(Integer value, int min, int max, String label) {
        if (value != null && (value < min || value > max)) {
            throw new BusinessException(label + "超出允许范围");
        }
    }

    private void validateRange(BigDecimal value, BigDecimal min, BigDecimal max, String label) {
        if (value != null && (value.compareTo(min) < 0 || value.compareTo(max) > 0)) {
            throw new BusinessException(label + "超出允许范围");
        }
    }

    private void requireNonNegative(Integer value, String label) {
        if (value == null || value < 0) {
            throw new BusinessException(label + "不能为空且不能为负数");
        }
    }

    private String serialize(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new BusinessException(label + "序列化失败");
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimToNull(String value) {
        String trimmed = trim(value);
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static Set<String> setOf(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }
}
