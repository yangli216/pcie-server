package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientFieldPolicy;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientPolicyReceipt;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientPolicyResolution;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.ForbiddenException;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.audit.service.AuditService;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.util.Objects;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateDictionaryItemSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateFieldSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateMappingOverrideVO;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateMappingRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateParseSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotReceipt;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotResolution;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotResolveRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotVO;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.StrictOutpatientEmrSnapshotDto;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateDecodedResponse;
import com.regionalai.floatingball.server.modules.emrtemplate.entity.AiOutpatientEmrTemplateMapping;
import com.regionalai.floatingball.server.modules.emrtemplate.entity.AiOutpatientEmrTemplateSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.mapper.AiOutpatientEmrTemplateMappingMapper;
import com.regionalai.floatingball.server.modules.emrtemplate.mapper.AiOutpatientEmrTemplateSnapshotMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
public class OutpatientEmrTemplateSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(OutpatientEmrTemplateSnapshotService.class);

    private static final String ACTIVE = "1";
    private static final String RESOLVE_SCHEMA = "outpatient-emr-template-pair-resolve.v1";
    private static final String RESOLUTION_SCHEMA = "outpatient-emr-template-pair-resolution.v1";
    private static final String SNAPSHOT_SCHEMA = "outpatient-emr-template-pair-snapshot.v1";
    private static final String PARSE_SCHEMA = "outpatient-emr-template-pair.v1";
    private static final String MAPPING_STATUS_MAPPED = "mapped";
    private static final String MAPPING_STATUS_UNMAPPED = "unmapped";
    private static final int MAX_TEMPLATE_SOURCE_BYTES = 1024 * 1024;
    private static final int MAX_PARSE_RESULT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_FIELD_COUNT = 2000;
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[a-f0-9]{64}$");

    private static final Set<String> RECORD_FIELDS = unmodifiableSet(
        "chiefComplaint",
        "historyOfPresentIllness",
        "pastMedicalHistory",
        "personalHistory",
        "menstrualHistory",
        "maritalReproductiveHistory",
        "familyHistory",
        "physicalExam",
        "precautions"
    );
    private static final String MANUAL_OVERRIDE_SOURCE = "manual-override";
    /** Sources a client-side automatic parse may register; manual-override is an overlay only. */
    private static final Set<String> MAPPING_SOURCES = unmodifiableSet(
        "definition-record-field",
        "definition-article-record-field",
        "canonical-id",
        "deterministic-alias",
        "deterministic-article",
        "unmapped"
    );
    private static final Set<String> PROJECTION_MODES = unmodifiableSet("direct", "section-compose");

    private final AiOutpatientEmrTemplateSnapshotMapper snapshotMapper;
    private final AiOutpatientEmrTemplateMappingMapper mappingMapper;
    private final ObjectMapper objectMapper;
    private final OutpatientEmrTemplateDecodeService decodeService;
    private final AuditService auditService;

    public OutpatientEmrTemplateSnapshotService(AiOutpatientEmrTemplateSnapshotMapper snapshotMapper,
                                                 AiOutpatientEmrTemplateMappingMapper mappingMapper,
                                                 ObjectMapper objectMapper,
                                                 OutpatientEmrTemplateDecodeService decodeService,
                                                 AuditService auditService) {
        this.snapshotMapper = snapshotMapper;
        this.mappingMapper = mappingMapper;
        this.objectMapper = objectMapper;
        this.decodeService = decodeService;
        this.auditService = auditService;
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotResolution resolve(
        AiDevice device,
        OutpatientEmrTemplateSnapshotResolveRequest request
    ) {
        ValidatedResolve validated = validateResolve(device, request);
        AiOutpatientEmrTemplateSnapshot entity = findExistingVersion(
            validated.idOrg,
            validated.templateId,
            validated.templateHash
        );
        boolean v2 = "outpatient-emr-template-pair-resolve.v2".equals(request.getSchemaVersion());
        if (entity == null) {
            log.info(
                "outpatient emr template snapshot resolved. templateId={}, templateHash={}, hit=false",
                validated.templateId,
                validated.templateHash
            );
            OutpatientEmrTemplateSnapshotResolution miss = new OutpatientEmrTemplateSnapshotResolution(
                RESOLUTION_SCHEMA,
                Boolean.FALSE,
                null,
                validated.templateId,
                validated.templateHash,
                null,
                null
            );
            return v2 ? new OutpatientPolicyResolution(miss, null) : miss;
        }

        snapshotMapper.lockVersion(entity.getIdSnapshot());
        // A locking read must precede the policy read: a writer may have committed since the identity lookup.
        AiOutpatientEmrTemplateSnapshot locked = snapshotMapper.selectById(entity.getIdSnapshot());
        if (locked != null) entity = locked;
        if (!v2) requireLegacyCompatible(entity);
        String id = requireText(entity.getIdSnapshot(), "历史解析.id", 32);
        String storedTemplateId = requireText(entity.getTemplateId(), "历史解析.templateId", 128);
        String storedTemplateHash = requireSha256(entity.getTemplateHash(), "历史解析.templateHash");
        if (!validated.idOrg.equals(entity.getIdOrg()) || !validated.templateId.equals(storedTemplateId)
            || !validated.templateHash.equals(storedTemplateHash)) {
            throw new BusinessException("历史模板解析身份与查询不一致");
        }
        OutpatientEmrTemplateParseSnapshot parseResult = readHistoricalFields(entity);
        Long receivedAt = toEpochMillis(entity.getLastReceivedAt());
        if (receivedAt == null) {
            throw new BusinessException("历史模板解析接收时间缺失");
        }
        log.info(
            "outpatient emr template snapshot resolved. idSnapshot={}, templateId={}, templateHash={}, hit=true",
            id,
            storedTemplateId,
            storedTemplateHash
        );
        OutpatientEmrTemplateSnapshotResolution result = new OutpatientEmrTemplateSnapshotResolution(
            RESOLUTION_SCHEMA,
            Boolean.TRUE,
            id,
            storedTemplateId,
            storedTemplateHash,
            parseResult,
            receivedAt
        );
        return v2 ? new OutpatientPolicyResolution(result, fieldPolicy(entity, parseResult)) : result;
    }

    public OutpatientEmrTemplateSnapshotReceipt save(AiDevice device,
                                                      OutpatientEmrTemplateSnapshotRequest request) {
        request = decodeSnapshotSources(request);
        ValidatedSnapshot validated = validate(device, request);
        LocalDateTime receivedAt = LocalDateTime.now();
        AiOutpatientEmrTemplateSnapshot existing = findExistingVersion(
            validated.idOrg,
            validated.templateId,
            validated.templateHash
        );
        if (existing != null) {
            return versionReceipt(device, request, existing, existingReceipt(existing, validated));
        }

        AiOutpatientEmrTemplateSnapshot entity = new AiOutpatientEmrTemplateSnapshot();
        entity.setIdSnapshot(UUID.randomUUID().toString().replace("-", ""));
        entity.setIdOrg(validated.idOrg);
        entity.setTemplateHash(validated.templateHash);
        entity.setFgActive(ACTIVE);
        applySnapshot(entity, device, request, validated, receivedAt);
        entity.setTemplateState("enabled");
        entity.setPolicyRevision(0L);
        setCounts(entity, describeFields(entity, request.getParseResult()));
        try {
            snapshotMapper.insert(entity);
        } catch (DuplicateKeyException duplicateKeyException) {
            AiOutpatientEmrTemplateSnapshot concurrent = findExistingVersion(
                validated.idOrg,
                validated.templateId,
                validated.templateHash
            );
            if (concurrent == null) {
                throw duplicateKeyException;
            }
            return versionReceipt(device, request, concurrent, existingReceipt(concurrent, validated));
        }

        log.info(
            "outpatient emr template snapshot received. idSnapshot={}, templateHash={}, deduplicated={}",
            entity.getIdSnapshot(),
            validated.templateHash,
            false
        );
        return versionReceipt(device, request, entity, new OutpatientEmrTemplateSnapshotReceipt(
            entity.getIdSnapshot(),
            validated.templateHash,
            Boolean.FALSE,
            toEpochMillis(receivedAt)
        ));
    }

    private OutpatientEmrTemplateSnapshotRequest decodeSnapshotSources(OutpatientEmrTemplateSnapshotRequest request) {
        if (request == null || (!OutpatientEmrTemplateDecodeService.hasDataUriSource(request.getTemplateHtml())
            && !OutpatientEmrTemplateDecodeService.hasDataUriSource(request.getTemplateDefinition()))) {
            return request;
        }
        OutpatientEmrTemplateDecodedResponse decoded = decodeService.decodePair(
            request.getTemplateHtml(), request.getTemplateDefinition()
        );
        OutpatientEmrTemplateSnapshotRequest normalized = new OutpatientEmrTemplateSnapshotRequest();
        normalized.setSchemaVersion(request.getSchemaVersion());
        normalized.setTemplateId(request.getTemplateId());
        normalized.setTemplateName(request.getTemplateName());
        normalized.setTemplateHash(request.getTemplateHash());
        normalized.setTemplateHtml(decoded.getTemplateHtml());
        normalized.setTemplateDefinition(decoded.getTemplateDefinition());
        normalized.setParseResult(request.getParseResult());
        normalized.getUnknownFields().putAll(request.getUnknownFields());
        return normalized;
    }

    private OutpatientEmrTemplateSnapshotReceipt existingReceipt(
        AiOutpatientEmrTemplateSnapshot entity,
        ValidatedSnapshot validated
    ) {
        String id = requireText(entity.getIdSnapshot(), "历史解析.id", 32);
        String storedTemplateId = requireText(entity.getTemplateId(), "历史解析.templateId", 128);
        String storedTemplateHash = requireSha256(entity.getTemplateHash(), "历史解析.templateHash");
        if (!validated.templateId.equals(storedTemplateId)
            || !validated.templateHash.equals(storedTemplateHash)) {
            throw new BusinessException("历史模板解析身份与登记请求不一致");
        }
        Long receivedAt = toEpochMillis(entity.getLastReceivedAt());
        if (receivedAt == null) {
            throw new BusinessException("历史模板解析接收时间缺失");
        }
        log.info(
            "outpatient emr template snapshot registration deduplicated without write. "
                + "idSnapshot={}, templateId={}, templateHash={}",
            id,
            storedTemplateId,
            storedTemplateHash
        );
        return new OutpatientEmrTemplateSnapshotReceipt(
            id,
            storedTemplateHash,
            Boolean.TRUE,
            receivedAt
        );
    }

    public PageResponse<OutpatientEmrTemplateSnapshotVO> list(long current,
                                                               long size,
                                                               String keyword) {
        return list(current, size, keyword, null);
    }

    public PageResponse<OutpatientEmrTemplateSnapshotVO> list(long current, long size, String keyword, String state) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiOutpatientEmrTemplateSnapshot> page = new Page<AiOutpatientEmrTemplateSnapshot>(
            pageRequest.getCurrent(),
            pageRequest.getSize()
        );
        LambdaQueryWrapper<AiOutpatientEmrTemplateSnapshot> wrapper =
            new LambdaQueryWrapper<AiOutpatientEmrTemplateSnapshot>()
                .select(
                    AiOutpatientEmrTemplateSnapshot::getIdSnapshot,
                    AiOutpatientEmrTemplateSnapshot::getIdOrg,
                    AiOutpatientEmrTemplateSnapshot::getIdRegion,
                    AiOutpatientEmrTemplateSnapshot::getIdDevice,
                    AiOutpatientEmrTemplateSnapshot::getCdDevice,
                    AiOutpatientEmrTemplateSnapshot::getTemplateId,
                    AiOutpatientEmrTemplateSnapshot::getTemplateName,
                    AiOutpatientEmrTemplateSnapshot::getTemplateHash,
                    AiOutpatientEmrTemplateSnapshot::getTemplateState,
                    AiOutpatientEmrTemplateSnapshot::getPolicyRevision,
                    AiOutpatientEmrTemplateSnapshot::getAiFieldCount,
                    AiOutpatientEmrTemplateSnapshot::getCustomPromptCount,
                    AiOutpatientEmrTemplateSnapshot::getFieldCount,
                    AiOutpatientEmrTemplateSnapshot::getWritableFieldCount,
                    AiOutpatientEmrTemplateSnapshot::getDictionaryFieldCount,
                    AiOutpatientEmrTemplateSnapshot::getMappedFieldCount,
                    AiOutpatientEmrTemplateSnapshot::getLastReceivedAt,
                    AiOutpatientEmrTemplateSnapshot::getFgActive,
                    AiOutpatientEmrTemplateSnapshot::getInsertTime,
                    AiOutpatientEmrTemplateSnapshot::getUpdateTime
                )
                .eq(AiOutpatientEmrTemplateSnapshot::getFgActive, ACTIVE)
                .orderByDesc(AiOutpatientEmrTemplateSnapshot::getLastReceivedAt);

        if (state != null && !Arrays.asList("enabled", "disabled", "deleted").contains(state))
            throw new BusinessException("模板状态不支持");
        if (state == null) wrapper.ne(AiOutpatientEmrTemplateSnapshot::getTemplateState, "deleted");
        else wrapper.eq(AiOutpatientEmrTemplateSnapshot::getTemplateState, state);
        String org = adminOrgScope();
        if (org != null) wrapper.eq(AiOutpatientEmrTemplateSnapshot::getIdOrg, org);
        if (StringUtils.hasText(keyword)) {
            String normalizedKeyword = keyword.trim();
            wrapper.and(q -> q.like(AiOutpatientEmrTemplateSnapshot::getTemplateId, normalizedKeyword)
                .or()
                .like(AiOutpatientEmrTemplateSnapshot::getTemplateName, normalizedKeyword)
                .or()
                .like(AiOutpatientEmrTemplateSnapshot::getTemplateHash, normalizedKeyword));
        }
        Page<AiOutpatientEmrTemplateSnapshot> result = snapshotMapper.selectPage(page, wrapper);
        List<String> legacyIds = result.getRecords().stream()
            .filter(row -> row.getAiFieldCount() == null || row.getCustomPromptCount() == null)
            .map(AiOutpatientEmrTemplateSnapshot::getIdSnapshot).collect(Collectors.toList());
        Map<String, AiOutpatientEmrTemplateSnapshot> legacy = new LinkedHashMap<>();
        if (!legacyIds.isEmpty()) {
            for (AiOutpatientEmrTemplateSnapshot row : snapshotMapper.selectList(
                    new LambdaQueryWrapper<AiOutpatientEmrTemplateSnapshot>()
                        .select(AiOutpatientEmrTemplateSnapshot::getIdSnapshot, AiOutpatientEmrTemplateSnapshot::getTemplateDefinition,
                            AiOutpatientEmrTemplateSnapshot::getParseResultJson, AiOutpatientEmrTemplateSnapshot::getFieldRulesJson)
                        .in(AiOutpatientEmrTemplateSnapshot::getIdSnapshot, legacyIds))) {
                setCounts(row, describeFields(row, readParseResult(row.getParseResultJson())));
                legacy.put(row.getIdSnapshot(), row);
            }
        }
        List<OutpatientEmrTemplateSnapshotVO> records = new ArrayList<OutpatientEmrTemplateSnapshotVO>();
        for (AiOutpatientEmrTemplateSnapshot item : result.getRecords()) {
            AiOutpatientEmrTemplateSnapshot historical = legacy.get(item.getIdSnapshot());
            if (historical != null) {
                item.setAiFieldCount(historical.getAiFieldCount());
                item.setCustomPromptCount(historical.getCustomPromptCount());
            }
            records.add(toView(item, false));
        }
        return new PageResponse<OutpatientEmrTemplateSnapshotVO>(
            result.getCurrent(),
            result.getSize(),
            result.getTotal(),
            records
        );
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotVO get(String idSnapshot) {
        snapshotMapper.lockVersion(idSnapshot);
        return toView(requireSnapshot(idSnapshot), true);
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotVO updateMapping(
        String idSnapshot,
        OutpatientEmrTemplateMappingRequest request
    ) {
        AiOutpatientEmrTemplateSnapshot snapshot = lockForEdit(idSnapshot, request == null ? null : request.getExpectedRevision());
        ValidatedMappingUpdate validated = validateMappingUpdate(snapshot, request);
        AiOutpatientEmrTemplateMapping existing = findMapping(snapshot.getIdSnapshot(), validated.fieldId);
        if (existing != null && Objects.equals(existing.getRecordField(), validated.recordField)
            && Objects.equals(existing.getProjectionMode(), validated.projectionMode)) return toView(snapshot, true);
        if (existing == null) {
            AiOutpatientEmrTemplateMapping mapping = new AiOutpatientEmrTemplateMapping();
            mapping.setIdMapping(UUID.randomUUID().toString().replace("-", ""));
            mapping.setIdSnapshot(snapshot.getIdSnapshot());
            mapping.setFieldId(validated.fieldId);
            mapping.setRecordField(validated.recordField);
            mapping.setProjectionMode(validated.projectionMode);
            mapping.setFgActive(ACTIVE);
            try {
                mappingMapper.insert(mapping);
            } catch (DuplicateKeyException duplicateKeyException) {
                AiOutpatientEmrTemplateMapping concurrent = findMapping(
                    snapshot.getIdSnapshot(),
                    validated.fieldId
                );
                if (concurrent == null) {
                    throw duplicateKeyException;
                }
                concurrent.setRecordField(validated.recordField);
                concurrent.setProjectionMode(validated.projectionMode);
                concurrent.setFgActive(ACTIVE);
                mappingMapper.updateById(concurrent);
            }
        } else {
            existing.setRecordField(validated.recordField);
            existing.setProjectionMode(validated.projectionMode);
            existing.setFgActive(ACTIVE);
            mappingMapper.update(null, new UpdateWrapper<AiOutpatientEmrTemplateMapping>()
                .eq("id_mapping", existing.getIdMapping()).set("record_field", validated.recordField)
                .set("projection_mode", validated.projectionMode).set("fg_active", ACTIVE));
        }
        saveVersion(snapshot, validated.fieldId, "mapping");
        return toView(snapshot, true);
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotVO clearMapping(String idSnapshot, String fieldId) {
        return clearMapping(idSnapshot, fieldId, null);
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotVO clearMapping(String idSnapshot, String fieldId, Long expectedRevision) {
        AiOutpatientEmrTemplateSnapshot snapshot = lockForEdit(idSnapshot, expectedRevision);
        String normalizedFieldId = requireText(fieldId, "fieldId", 512);
        OutpatientEmrTemplateParseSnapshot parseResult = readParseResult(snapshot.getParseResultJson());
        validateParseResult(parseResult);
        requireField(parseResult, normalizedFieldId);
        AiOutpatientEmrTemplateMapping existing = findMapping(
            snapshot.getIdSnapshot(),
            normalizedFieldId
        );
        if (existing != null) {
            mappingMapper.deleteById(existing.getIdMapping());
            saveVersion(snapshot, normalizedFieldId, "restore-mapping");
        }
        return toView(snapshot, true);
    }

    private OutpatientEmrTemplateSnapshotReceipt versionReceipt(AiDevice device, OutpatientEmrTemplateSnapshotRequest request,
            AiOutpatientEmrTemplateSnapshot snapshot, OutpatientEmrTemplateSnapshotReceipt receipt) {
        if (!"outpatient-emr-template-pair-snapshot.v2".equals(request.getSchemaVersion())) {
            requireLegacyCompatible(snapshot);
            return receipt;
        }
        // Registration uses a committed insert (a competing unique-key insert can abort a PostgreSQL transaction).
        // Read the revision again after the mapping overlay so both parts describe one committed configuration.
        for (int attempt = 0; attempt < 3; attempt++) {
            if (!device.getIdOrg().equals(snapshot.getIdOrg()) || !request.getTemplateId().equals(snapshot.getTemplateId())
                || !request.getTemplateHash().equals(snapshot.getTemplateHash())) throw new BusinessException("登记模板身份不一致");
            OutpatientEmrTemplateParseSnapshot parsed = readHistoricalFields(snapshot);
            OutpatientFieldPolicy policy = fieldPolicy(snapshot, parsed);
            Long observed = snapshotMapper.readRevision(snapshot.getIdSnapshot());
            if (observed != null && observed == policy.getRevision())
                return new OutpatientPolicyReceipt(receipt, snapshot.getTemplateId(), parsed, policy);
            snapshot = snapshotMapper.selectById(receipt.getId());
            if (snapshot == null) throw new BusinessException("登记快照不存在");
        }
        throw revisionConflict();
    }

    private void requireLegacyCompatible(AiOutpatientEmrTemplateSnapshot snapshot) {
        if (!"enabled".equals(state(snapshot)) || !settings(snapshot).isEmpty())
            throw new ConflictException("OUTPATIENT_TEMPLATE_RULES_CLIENT_UPGRADE_REQUIRED", "此版本使用门诊字段规则，请升级客户端后重试");
    }

    private static String state(AiOutpatientEmrTemplateSnapshot snapshot) {
        return snapshot.getTemplateState() == null ? "enabled" : snapshot.getTemplateState();
    }

    private static long revision(AiOutpatientEmrTemplateSnapshot snapshot) {
        return snapshot.getPolicyRevision() == null ? 0L : snapshot.getPolicyRevision();
    }

    private Map<String,OutpatientFieldPolicy.Settings> settings(AiOutpatientEmrTemplateSnapshot snapshot) {
        if (!StringUtils.hasText(snapshot.getFieldRulesJson())) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(snapshot.getFieldRulesJson(), new TypeReference<LinkedHashMap<String,OutpatientFieldPolicy.Settings>>() {});
        } catch (JsonProcessingException ex) {
            throw new BusinessException("门诊字段规则存储格式错误");
        }
    }

    private List<OutpatientFieldPolicy.Detail> describeFields(AiOutpatientEmrTemplateSnapshot snapshot, OutpatientEmrTemplateParseSnapshot parsed) {
        List<OutpatientEmrTemplateFieldSnapshot> automatic = parsed.getFields();
        if (automatic.stream().anyMatch(field -> MANUAL_OVERRIDE_SOURCE.equals(field.getMappingSource()))) {
            automatic = readParseResult(snapshot.getParseResultJson()).getFields();
        }
        OutpatientFieldRuleCompiler compiler = new OutpatientFieldRuleCompiler(objectMapper, snapshot.getTemplateDefinition(), automatic, settings(snapshot));
        Map<String, OutpatientEmrTemplateFieldSnapshot> automaticById = automatic.stream()
            .collect(Collectors.toMap(OutpatientEmrTemplateFieldSnapshot::getId, field -> field));
        List<OutpatientFieldPolicy.Detail> details = new ArrayList<>();
        for (OutpatientEmrTemplateFieldSnapshot effective : parsed.getFields()) {
            OutpatientFieldPolicy.Detail detail = compiler.describe(automaticById.get(effective.getId()));
            detail.setOriginalField(effective);
            details.add(detail);
        }
        return details;
    }

    private OutpatientFieldPolicy fieldPolicy(AiOutpatientEmrTemplateSnapshot snapshot, OutpatientEmrTemplateParseSnapshot parsed) {
        OutpatientFieldPolicy result = new OutpatientFieldPolicy();
        result.setTemplateState(state(snapshot));
        result.setRevision(revision(snapshot));
        if ("enabled".equals(result.getTemplateState())) result.setFields(describeFields(snapshot, parsed).stream()
            .map(OutpatientFieldPolicy.Detail::getEffective).collect(Collectors.toList()));
        return result;
    }

    private static void setCounts(AiOutpatientEmrTemplateSnapshot snapshot, List<OutpatientFieldPolicy.Detail> details) {
        snapshot.setAiFieldCount((int) details.stream().filter(d -> d.getEffective().isAiSuitable()).count());
        snapshot.setCustomPromptCount((int) details.stream().filter(d -> "custom".equals(d.getEffective().getPromptSource())).count());
    }

    private static ConflictException revisionConflict() {
        return new ConflictException("OUTPATIENT_TEMPLATE_REVISION_CONFLICT", "版本配置已更新，请重新读取并核对草稿");
    }

    private AiOutpatientEmrTemplateSnapshot lockForEdit(String id, Long expected) {
        snapshotMapper.lockVersion(id);
        AiOutpatientEmrTemplateSnapshot snapshot = requireSnapshot(id);
        if ("deleted".equals(state(snapshot))) throw new BusinessException("已删除版本仅供查看");
        if (expected != null && expected != revision(snapshot)) throw revisionConflict();
        return snapshot;
    }

    private void saveVersion(AiOutpatientEmrTemplateSnapshot snapshot, String field, String action) {
        setCounts(snapshot, describeFields(snapshot, readHistoricalFields(snapshot)));
        snapshot.setPolicyRevision(revision(snapshot) + 1);
        snapshot.setUpdateTime(LocalDateTime.now());
        snapshotMapper.update(null, new UpdateWrapper<AiOutpatientEmrTemplateSnapshot>().eq("id_snapshot", snapshot.getIdSnapshot())
            .set("policy_revision", snapshot.getPolicyRevision()).set("template_state", state(snapshot))
            .set("field_rules_json", snapshot.getFieldRulesJson()).set("ai_field_count", snapshot.getAiFieldCount())
            .set("custom_prompt_count", snapshot.getCustomPromptCount()).set("update_time", snapshot.getUpdateTime()));
        AdminCurrentUser actor = AdminContextHolder.get();
        Map<String, Object> audit = new LinkedHashMap<>();
        audit.put("title", "门诊模板字段维护");
        audit.put("snapshotId", snapshot.getIdSnapshot());
        audit.put("templateId", snapshot.getTemplateId());
        audit.put("fieldId", field);
        audit.put("operatorId", actor.getIdUser());
        audit.put("revision", snapshot.getPolicyRevision());
        auditService.saveAdminOperation(snapshot.getIdOrg(), "outpatient-emr-template", action, audit);
    }

    public static long expectedRevision(JsonNode body) {
        JsonNode value = body == null ? null : body.get("expectedRevision");
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0)
            throw new BusinessException("expectedRevision 必须为非负整数");
        return value.longValue();
    }

    private OutpatientFieldPolicy.Detail candidate(AiOutpatientEmrTemplateSnapshot snapshot, JsonNode input,
                                                   Map<String,OutpatientFieldPolicy.Settings> values) {
        String id = requireText(OutpatientFieldRuleCompiler.nullableText(input, "fieldId", 512), "fieldId", 512);
        OutpatientFieldPolicy.Settings config = OutpatientFieldRuleCompiler.settings(input);
        OutpatientEmrTemplateParseSnapshot parsed = readParseResult(snapshot.getParseResultJson());
        OutpatientEmrTemplateFieldSnapshot field = requireField(parsed, id);
        if (config.equals(new OutpatientFieldPolicy.Settings())) values.remove(id);
        else values.put(id, config);
        OutpatientFieldPolicy.Detail detail = new OutpatientFieldRuleCompiler(objectMapper, snapshot.getTemplateDefinition(), parsed.getFields(), values).describe(field);
        if ("ai".equals(config.getGenerationMode()) && detail.getRestriction() != null)
            throw new BusinessException(detail.getRestriction() + "不能开启 AI");
        return detail;
    }

    @Transactional
    public Map<String,Object> draftRule(String id, JsonNode input) {
        AiOutpatientEmrTemplateSnapshot snapshot = lockForEdit(id, expectedRevision(input));
        OutpatientFieldPolicy.Detail detail = candidate(snapshot, input, settings(snapshot));
        Map<String,Object> draft = new LinkedHashMap<>();
        draft.put("fieldId", detail.getEffective().getFieldId());
        draft.put("revision", revision(snapshot));
        draft.put("bodyDraft", detail.getDefaultBody());
        draft.put("resolvedPrompt", OutpatientFieldRuleCompiler.combine(detail.getDefaultBody(), detail.getSettings().getAdditionalConstraint(), detail.getFormatRule()));
        return draft;
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotVO saveRule(String id, JsonNode input) {
        AiOutpatientEmrTemplateSnapshot snapshot = lockForEdit(id, expectedRevision(input));
        Map<String,OutpatientFieldPolicy.Settings> previous = settings(snapshot);
        Map<String,OutpatientFieldPolicy.Settings> values = new LinkedHashMap<>(previous);
        OutpatientFieldPolicy.Detail detail = candidate(snapshot, input, values);
        if (!values.equals(previous)) {
            try { snapshot.setFieldRulesJson(values.isEmpty() ? null : objectMapper.writeValueAsString(values)); }
            catch (JsonProcessingException ex) { throw new BusinessException("字段配置序列化失败"); }
            saveVersion(snapshot, detail.getEffective().getFieldId(), "writing-rule");
        }
        return toView(snapshot, true);
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotVO changeState(String id, long expected, String target) {
        if (!Arrays.asList("enabled","disabled","deleted").contains(target)) throw new BusinessException("模板状态不支持");
        snapshotMapper.lockVersion(id);
        AiOutpatientEmrTemplateSnapshot snapshot = requireSnapshot(id);
        if (!target.equals(state(snapshot))) {
            if ("deleted".equals(state(snapshot))) throw new BusinessException("已删除版本仅供查看");
            if (expected != revision(snapshot)) throw revisionConflict();
            snapshot.setTemplateState(target);
            saveVersion(snapshot, null, target);
        }
        return toView(snapshot, true);
    }

    private ValidatedSnapshot validate(AiDevice device,
                                       OutpatientEmrTemplateSnapshotRequest request) {
        if (device == null || !StringUtils.hasText(device.getIdDevice()) || !StringUtils.hasText(device.getIdOrg())) {
            throw new BusinessException("设备认证上下文缺失");
        }
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        assertNoUnknownFields(request, "请求");
        if (!"outpatient-emr-template-pair-snapshot.v2".equals(request.getSchemaVersion())) requireEquals(request.getSchemaVersion(), SNAPSHOT_SCHEMA, "schemaVersion");
        String templateId = requireText(request.getTemplateId(), "templateId", 128);
        String templateName = requireText(request.getTemplateName(), "templateName", 200);
        String templateHash = requireSha256(request.getTemplateHash(), "templateHash");
        if (request.getTemplateHtml() == null || request.getTemplateHtml().trim().isEmpty()) {
            throw new BusinessException("templateHtml 不能为空");
        }
        if (request.getTemplateDefinition() == null
            || request.getTemplateDefinition().trim().isEmpty()) {
            throw new BusinessException("templateDefinition 不能为空");
        }
        if (utf8Length(request.getTemplateHtml()) > MAX_TEMPLATE_SOURCE_BYTES) {
            throw new BusinessException("templateHtml 超过 1 MiB");
        }
        if (utf8Length(request.getTemplateDefinition()) > MAX_TEMPLATE_SOURCE_BYTES) {
            throw new BusinessException("templateDefinition 超过 1 MiB");
        }
        String actualHash = sha256(
            "outpatient-emr-template-pair.v1:"
                + sha256(request.getTemplateHtml())
                + ":"
                + sha256(request.getTemplateDefinition())
        );
        if (!templateHash.equals(actualHash)) {
            throw new BusinessException("templateHash 与模板对不匹配");
        }

        ValidatedParseResult parseResult = validateParseResult(request.getParseResult());

        return new ValidatedSnapshot(
            requireText(device.getIdOrg(), "idOrg", 32),
            templateId,
            templateName,
            templateHash,
            parseResult.json,
            parseResult.fieldCount,
            parseResult.writableFieldCount,
            parseResult.dictionaryFieldCount,
            parseResult.mappedFieldCount
        );
    }

    private ValidatedResolve validateResolve(
        AiDevice device,
        OutpatientEmrTemplateSnapshotResolveRequest request
    ) {
        if (device == null
            || !StringUtils.hasText(device.getIdDevice())
            || !StringUtils.hasText(device.getIdOrg())) {
            throw new BusinessException("设备认证上下文缺失");
        }
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        assertNoUnknownFields(request, "请求");
        if (!"outpatient-emr-template-pair-resolve.v2".equals(request.getSchemaVersion())) requireEquals(request.getSchemaVersion(), RESOLVE_SCHEMA, "schemaVersion");
        return new ValidatedResolve(
            requireText(device.getIdOrg(), "idOrg", 32),
            requireText(request.getTemplateId(), "templateId", 128),
            requireSha256(request.getTemplateHash(), "templateHash")
        );
    }

    /** Validates an immutable automatic parse as registered by the client. */
    private ValidatedParseResult validateParseResult(
        OutpatientEmrTemplateParseSnapshot parseResult
    ) {
        return validateParseResult(parseResult, false);
    }

    /** Validates a parse after manual mapping overrides were overlaid in memory. */
    private ValidatedParseResult validateEffectiveParseResult(
        OutpatientEmrTemplateParseSnapshot parseResult
    ) {
        return validateParseResult(parseResult, true);
    }

    private ValidatedParseResult validateParseResult(
        OutpatientEmrTemplateParseSnapshot parseResult,
        boolean allowManualOverride
    ) {
        if (parseResult == null) {
            throw new BusinessException("parseResult 不能为空");
        }
        assertNoUnknownFields(parseResult, "parseResult");
        requireEquals(parseResult.getSchemaVersion(), PARSE_SCHEMA, "parseResult.schemaVersion");
        List<OutpatientEmrTemplateFieldSnapshot> fields = parseResult.getFields();
        if (fields == null || fields.isEmpty()) {
            throw new BusinessException("parseResult.fields 不能为空");
        }
        if (fields.size() > MAX_FIELD_COUNT) {
            throw new BusinessException("parseResult.fields 超过 2000 个");
        }

        int writableFieldCount = 0;
        int dictionaryFieldCount = 0;
        int mappedFieldCount = 0;
        Set<String> fieldIds = new HashSet<String>();
        for (int index = 0; index < fields.size(); index += 1) {
            OutpatientEmrTemplateFieldSnapshot field = fields.get(index);
            String path = "parseResult.fields[" + index + "]";
            validateField(field, path, allowManualOverride);
            if (!fieldIds.add(field.getId())) {
                throw new BusinessException("parseResult.fields 存在重复 id: " + field.getId());
            }
            if (!Boolean.TRUE.equals(field.getReadonly())) writableFieldCount++;
            if (!field.getDictionaryItems().isEmpty()) dictionaryFieldCount++;
            if (field.getRecordField() != null) mappedFieldCount++;
        }
        validateRecordFieldOwnership(fields);

        String parseResultJson = writeParseResult(parseResult);
        if (utf8Length(parseResultJson) > MAX_PARSE_RESULT_BYTES) {
            throw new BusinessException("parseResult 超过 2 MiB");
        }

        return new ValidatedParseResult(
            parseResultJson,
            fields.size(),
            writableFieldCount,
            dictionaryFieldCount,
            mappedFieldCount
        );
    }

    private void validateField(OutpatientEmrTemplateFieldSnapshot field, String path, boolean allowManualOverride) {
        if (field == null) {
            throw new BusinessException(path + " 不能为空");
        }
        assertNoUnknownFields(field, path);
        requireText(field.getId(), path + ".id", 512);
        requireText(field.getName(), path + ".name", 512);
        requireText(field.getType(), path + ".type", 64);
        requireText(field.getArticleTemplateId(), path + ".articleTemplateId", 512);
        requireText(field.getArticleId(), path + ".articleId", 512);
        requireText(field.getArticleName(), path + ".articleName", 512);
        // A chapter label is display text. Keep its original whitespace in the stored snapshot.
        if (!StringUtils.hasText(field.getArticleDefinitionName())) {
            throw new BusinessException(path + ".articleDefinitionName 不能为空");
        }
        if (field.getReadonly() == null || field.getAiSuitable() == null) {
            throw new BusinessException(path + ".readonly/aiSuitable 不能为空");
        }
        requireTextIncludingEmpty(field.getBaselineValue(), path + ".baselineValue", 262144);
        requireTextIncludingEmpty(
            field.getBaselineDictionaryValue(),
            path + ".baselineDictionaryValue",
            262144
        );
        List<OutpatientEmrTemplateDictionaryItemSnapshot> dictionaryItems = field.getDictionaryItems();
        if (dictionaryItems == null) {
            throw new BusinessException(path + ".dictionaryItems 不能为空");
        }
        validateDictionaryItems(dictionaryItems, path);

        String mappingSource = requireText(field.getMappingSource(), path + ".mappingSource", 64);
        boolean manualOverride = MANUAL_OVERRIDE_SOURCE.equals(mappingSource);
        if (manualOverride ? !allowManualOverride : !MAPPING_SOURCES.contains(mappingSource)) {
            throw new BusinessException(path + ".mappingSource 不支持");
        }
        String recordField = requireNullableText(
            field.getRecordField(),
            path + ".recordField",
            64
        );
        String projectionMode = requireNullableText(
            field.getProjectionMode(),
            path + ".projectionMode",
            64
        );
        if (recordField == null) {
            if (!"unmapped".equals(mappingSource) || projectionMode != null) {
                throw new BusinessException(path + " 未映射字段的 mappingSource/projectionMode 不一致");
            }
        } else {
            if (!RECORD_FIELDS.contains(recordField)) {
                throw new BusinessException(path + ".recordField 不支持");
            }
            if ("unmapped".equals(mappingSource)) {
                throw new BusinessException(path + ".mappingSource 与 recordField 不一致");
            }
            if (!PROJECTION_MODES.contains(projectionMode)) {
                throw new BusinessException(path + ".projectionMode 不支持");
            }
        }
    }

    private ValidatedMappingUpdate validateMappingUpdate(
        AiOutpatientEmrTemplateSnapshot snapshot,
        OutpatientEmrTemplateMappingRequest request
    ) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        assertNoUnknownFields(request, "请求");
        String fieldId = requireText(request.getFieldId(), "fieldId", 512);
        String mappingStatus = requireText(request.getMappingStatus(), "mappingStatus", 16);
        String recordField;
        String projectionMode;
        if (MAPPING_STATUS_MAPPED.equals(mappingStatus)) {
            recordField = requireText(request.getRecordField(), "recordField", 64);
            projectionMode = requireText(request.getProjectionMode(), "projectionMode", 64);
            if (!RECORD_FIELDS.contains(recordField)) {
                throw new BusinessException("recordField 不支持");
            }
            if (!PROJECTION_MODES.contains(projectionMode)) {
                throw new BusinessException("projectionMode 不支持");
            }
        } else if (MAPPING_STATUS_UNMAPPED.equals(mappingStatus)) {
            if (request.getRecordField() != null || request.getProjectionMode() != null) {
                throw new BusinessException("unmapped 映射的 recordField/projectionMode 必须为 null");
            }
            recordField = null;
            projectionMode = null;
        } else {
            throw new BusinessException("mappingStatus 只支持 mapped 或 unmapped");
        }

        OutpatientEmrTemplateParseSnapshot parseResult = readParseResult(snapshot.getParseResultJson());
        validateParseResult(parseResult);
        applyMappingOverrides(snapshot.getIdSnapshot(), parseResult);
        OutpatientEmrTemplateFieldSnapshot field = requireField(parseResult, fieldId);
        applyMappingValue(field, recordField, projectionMode);
        validateEffectiveParseResult(parseResult);
        return new ValidatedMappingUpdate(fieldId, recordField, projectionMode);
    }

    private void validateRecordFieldOwnership(List<OutpatientEmrTemplateFieldSnapshot> fields) {
        for (Map.Entry<String, List<String>> conflict : conflictingOwners(fields).entrySet()) {
            throw new BusinessException("标准字段 " + conflict.getKey() + " 存在重复映射: " + String.join("、", conflict.getValue()));
        }
    }

    private Map<String, List<String>> conflictingOwners(Iterable<OutpatientEmrTemplateFieldSnapshot> fields) {
        Map<String, List<String>> conflicts = new LinkedHashMap<>();

        Map<String, List<OutpatientEmrTemplateFieldSnapshot>> owners =
            new LinkedHashMap<String, List<OutpatientEmrTemplateFieldSnapshot>>();
        for (OutpatientEmrTemplateFieldSnapshot field : fields) {
            if (field.getRecordField() == null) {
                continue;
            }
            List<OutpatientEmrTemplateFieldSnapshot> mappedFields = owners.get(field.getRecordField());
            if (mappedFields == null) {
                mappedFields = new ArrayList<OutpatientEmrTemplateFieldSnapshot>();
                owners.put(field.getRecordField(), mappedFields);
            }
            mappedFields.add(field);
        }
        for (Map.Entry<String, List<OutpatientEmrTemplateFieldSnapshot>> entry : owners.entrySet()) {
            List<OutpatientEmrTemplateFieldSnapshot> mappedFields = entry.getValue();
            if (mappedFields.size() <= 1) {
                continue;
            }
            String articleId = mappedFields.get(0).getArticleId();
            boolean validComposition = StringUtils.hasText(articleId);
            for (OutpatientEmrTemplateFieldSnapshot field : mappedFields) {
                if (!articleId.equals(field.getArticleId())
                    || !"section-compose".equals(field.getProjectionMode())) {
                    validComposition = false;
                    break;
                }
            }
            if (!validComposition) {
                List<String> fieldIds = new ArrayList<String>();
                for (OutpatientEmrTemplateFieldSnapshot field : mappedFields) {
                    fieldIds.add(field.getId());
                }
                conflicts.put(entry.getKey(), fieldIds);
            }
        }
        return conflicts;
    }

    private List<OutpatientEmrTemplateMappingOverrideVO> applyMappingOverrides(
        String idSnapshot,
        OutpatientEmrTemplateParseSnapshot parseResult
    ) {
        Map<String, OutpatientEmrTemplateFieldSnapshot> fieldsById =
            new LinkedHashMap<String, OutpatientEmrTemplateFieldSnapshot>();
        for (OutpatientEmrTemplateFieldSnapshot field : parseResult.getFields()) {
            fieldsById.put(field.getId(), field);
        }
        List<OutpatientEmrTemplateMappingOverrideVO> views =
            new ArrayList<OutpatientEmrTemplateMappingOverrideVO>();
        for (AiOutpatientEmrTemplateMapping mapping : listMappings(idSnapshot)) {
            OutpatientEmrTemplateFieldSnapshot field = fieldsById.get(mapping.getFieldId());
            if (field == null) {
                throw new BusinessException("门诊模板字段映射引用了不存在的字段: " + mapping.getFieldId());
            }
            OutpatientEmrTemplateMappingOverrideVO view =
                new OutpatientEmrTemplateMappingOverrideVO();
            view.setFieldId(mapping.getFieldId());
            view.setMappingStatus(
                mapping.getRecordField() == null ? MAPPING_STATUS_UNMAPPED : MAPPING_STATUS_MAPPED
            );
            view.setRecordField(mapping.getRecordField());
            view.setProjectionMode(mapping.getProjectionMode());
            view.setAutomaticRecordField(field.getRecordField());
            view.setAutomaticMappingSource(field.getMappingSource());
            view.setAutomaticProjectionMode(field.getProjectionMode());
            view.setUpdatedAt(toEpochMillis(
                mapping.getUpdateTime() == null ? mapping.getInsertTime() : mapping.getUpdateTime()
            ));
            views.add(view);
            applyMappingValue(field, mapping.getRecordField(), mapping.getProjectionMode());
        }
        return views;
    }

    private void applyMappingValue(OutpatientEmrTemplateFieldSnapshot field,
                                   String recordField,
                                   String projectionMode) {
        field.setRecordField(recordField);
        field.setProjectionMode(projectionMode);
        if (recordField == null) {
            field.setMappingSource("unmapped");
        } else {
            field.setMappingSource(MANUAL_OVERRIDE_SOURCE);
        }
    }

    private OutpatientEmrTemplateFieldSnapshot requireField(
        OutpatientEmrTemplateParseSnapshot parseResult,
        String fieldId
    ) {
        for (OutpatientEmrTemplateFieldSnapshot field : parseResult.getFields()) {
            if (fieldId.equals(field.getId())) {
                return field;
            }
        }
        throw new BusinessException("门诊模板字段不存在: " + fieldId);
    }

    private List<AiOutpatientEmrTemplateMapping> listMappings(String idSnapshot) {
        List<AiOutpatientEmrTemplateMapping> mappings = mappingMapper.selectList(
            new LambdaQueryWrapper<AiOutpatientEmrTemplateMapping>()
                .eq(AiOutpatientEmrTemplateMapping::getIdSnapshot, idSnapshot)
                .eq(AiOutpatientEmrTemplateMapping::getFgActive, ACTIVE)
                .orderByAsc(AiOutpatientEmrTemplateMapping::getFieldId)
        );
        return mappings == null ? Collections.<AiOutpatientEmrTemplateMapping>emptyList() : mappings;
    }

    private AiOutpatientEmrTemplateMapping findMapping(String idSnapshot, String fieldId) {
        List<AiOutpatientEmrTemplateMapping> mappings = mappingMapper.selectList(
            new LambdaQueryWrapper<AiOutpatientEmrTemplateMapping>()
                .eq(AiOutpatientEmrTemplateMapping::getIdSnapshot, idSnapshot)
                .eq(AiOutpatientEmrTemplateMapping::getFieldId, fieldId)
                .eq(AiOutpatientEmrTemplateMapping::getFgActive, ACTIVE)
        );
        return mappings == null || mappings.isEmpty() ? null : mappings.get(0);
    }

    private String adminOrgScope() {
        AdminCurrentUser actor = AdminContextHolder.get();
        if (actor == null) throw new ForbiddenException("ADMIN_REQUIRED", "请先登录管理端");
        if (!"BBP".equals(actor.getAuthProvider()) && actor.getRoles() != null
                && actor.getRoles().contains("SYSTEM_ADMIN")) return null;
        if (StringUtils.hasText(actor.getIdOrg())) return actor.getIdOrg();
        throw new ForbiddenException("OUTPATIENT_TEMPLATE_ORG_FORBIDDEN", "当前账号缺少模板访问机构");
    }

    private AiOutpatientEmrTemplateSnapshot requireSnapshot(String idSnapshot) {
        String normalizedId = requireText(idSnapshot, "idSnapshot", 32);
        AiOutpatientEmrTemplateSnapshot entity = snapshotMapper.selectById(normalizedId);
        if (entity == null || !ACTIVE.equals(entity.getFgActive())) {
            throw new BusinessException("门诊模板解析记录不存在");
        }
        String org = adminOrgScope();
        if (org != null && !org.equals(entity.getIdOrg()))
            throw new ForbiddenException("OUTPATIENT_TEMPLATE_ORG_FORBIDDEN", "无权访问其他机构的模板");
        return entity;
    }

    private void validateDictionaryItems(List<OutpatientEmrTemplateDictionaryItemSnapshot> items,
                                         String fieldPath) {
        Set<String> tokens = new HashSet<String>();
        for (int index = 0; index < items.size(); index += 1) {
            OutpatientEmrTemplateDictionaryItemSnapshot item = items.get(index);
            String path = fieldPath + ".dictionaryItems[" + index + "]";
            if (item == null) {
                throw new BusinessException(path + " 不能为空");
            }
            assertNoUnknownFields(item, path);
            requireTextIncludingEmpty(item.getValue(), path + ".value", 2048);
            String text = requireText(item.getText(), path + ".text", 2048);
            for (String token : new HashSet<String>(Arrays.asList(item.getValue(), text))) {
                if (!tokens.add(token)) {
                    throw new BusinessException(fieldPath + ".dictionaryItems 存在重复匹配项: " + token);
                }
            }
        }
    }

    private void applySnapshot(AiOutpatientEmrTemplateSnapshot entity,
                               AiDevice device,
                               OutpatientEmrTemplateSnapshotRequest request,
                               ValidatedSnapshot validated,
                               LocalDateTime receivedAt) {
        entity.setIdRegion(trimToNull(device.getIdRegion()));
        entity.setIdDevice(device.getIdDevice().trim());
        entity.setCdDevice(truncate(trimToNull(device.getCdDevice()), 128));
        entity.setTemplateId(validated.templateId);
        entity.setTemplateName(validated.templateName);
        entity.setTemplateHtml(request.getTemplateHtml());
        entity.setTemplateDefinition(request.getTemplateDefinition());
        entity.setParseResultJson(validated.parseResultJson);
        entity.setFieldCount(Integer.valueOf(validated.fieldCount));
        entity.setWritableFieldCount(Integer.valueOf(validated.writableFieldCount));
        entity.setDictionaryFieldCount(Integer.valueOf(validated.dictionaryFieldCount));
        entity.setMappedFieldCount(Integer.valueOf(validated.mappedFieldCount));
        entity.setLastReceivedAt(receivedAt);
        entity.setFgActive(ACTIVE);
    }

    private AiOutpatientEmrTemplateSnapshot findExistingVersion(String idOrg,
                                                                 String templateId,
                                                                 String templateHash) {
        List<AiOutpatientEmrTemplateSnapshot> records = snapshotMapper.selectList(
            new LambdaQueryWrapper<AiOutpatientEmrTemplateSnapshot>()
                .eq(AiOutpatientEmrTemplateSnapshot::getIdOrg, idOrg)
                .eq(AiOutpatientEmrTemplateSnapshot::getTemplateId, templateId)
                .eq(AiOutpatientEmrTemplateSnapshot::getTemplateHash, templateHash)
                .eq(AiOutpatientEmrTemplateSnapshot::getFgActive, ACTIVE)
                .orderByDesc(AiOutpatientEmrTemplateSnapshot::getLastReceivedAt)
        );
        return records.isEmpty() ? null : records.get(0);
    }

    private OutpatientEmrTemplateSnapshotVO toView(AiOutpatientEmrTemplateSnapshot entity,
                                                    boolean includeDetail) {
        OutpatientEmrTemplateSnapshotVO view = new OutpatientEmrTemplateSnapshotVO();
        view.setId(entity.getIdSnapshot());
        view.setRevision(revision(entity));
        view.setTemplateState(state(entity));
        view.setRuleSetVersion(OutpatientFieldPolicy.RULE_SET);
        view.setAiFieldCount(entity.getAiFieldCount());
        view.setCustomPromptCount(entity.getCustomPromptCount());
        view.setIdOrg(entity.getIdOrg());
        view.setIdRegion(entity.getIdRegion());
        view.setIdDevice(entity.getIdDevice());
        view.setCdDevice(entity.getCdDevice());
        view.setTemplateId(entity.getTemplateId());
        view.setTemplateName(entity.getTemplateName());
        view.setTemplateHash(entity.getTemplateHash());
        view.setFieldCount(entity.getFieldCount());
        view.setWritableFieldCount(entity.getWritableFieldCount());
        view.setDictionaryFieldCount(entity.getDictionaryFieldCount());
        view.setMappedFieldCount(entity.getMappedFieldCount());
        view.setReceivedAt(toEpochMillis(entity.getLastReceivedAt()));
        view.setCreatedAt(toEpochMillis(entity.getInsertTime()));
        view.setUpdatedAt(toEpochMillis(entity.getUpdateTime()));
        if (includeDetail) {
            view.setTemplateHtml(entity.getTemplateHtml());
            view.setTemplateDefinition(entity.getTemplateDefinition());
            OutpatientEmrTemplateParseSnapshot parseResult = readParseResult(entity.getParseResultJson());
            validateParseResult(parseResult);
            List<OutpatientEmrTemplateMappingOverrideVO> overrides = applyMappingOverrides(
                entity.getIdSnapshot(),
                parseResult
            );
            ValidatedParseResult validated = validateEffectiveParseResult(parseResult);
            view.setParseResult(parseResult);
            List<OutpatientFieldPolicy.Detail> details = describeFields(entity, parseResult);
            setCounts(entity, details);
            view.setAiFieldCount(entity.getAiFieldCount());
            view.setCustomPromptCount(entity.getCustomPromptCount());
            view.setFields(details);
            view.setMappingOverrides(overrides);
            view.setMappedFieldCount(Integer.valueOf(validated.mappedFieldCount));
        }
        return view;
    }

    private OutpatientEmrTemplateParseSnapshot readParseResult(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException("门诊模板解析快照缺失");
        }
        try {
            return objectMapper.readValue(value, OutpatientEmrTemplateParseSnapshot.class);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("门诊模板解析快照损坏");
        }
    }

    /** Resolve an immutable version without repairing or resaving its stored source. */
    private OutpatientEmrTemplateParseSnapshot readHistoricalFields(AiOutpatientEmrTemplateSnapshot entity) {
        String json = entity.getParseResultJson();
        if (!StringUtils.hasText(json)) throw new BusinessException("门诊模板解析快照缺失");
        if (utf8Length(json) > MAX_PARSE_RESULT_BYTES) throw new BusinessException("parseResult 超过 2 MiB");
        JsonNode root;
        try {
            root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(json);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("门诊模板解析快照损坏");
        }
        if (!root.isObject() || !PARSE_SCHEMA.equals(root.path("schemaVersion").textValue())) {
            throw new BusinessException("历史模板解析 schemaVersion 不支持");
        }
        JsonNode rows = root.path("fields");
        if (!rows.isArray() || rows.size() == 0 || rows.size() > MAX_FIELD_COUNT || root.size() != 2) {
            throw new BusinessException("parseResult.fields 或快照结构损坏");
        }
        Set<String> seen = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (JsonNode row : rows) {
            String id = row.path("id").textValue();
            if (id != null && !seen.add(id)) duplicates.add(id);
        }
        Map<String, OutpatientEmrTemplateFieldSnapshot> fields = new LinkedHashMap<>();
        Set<String> disputedTargets = new HashSet<>();
        for (JsonNode row : rows) {
            String id = row.path("id").textValue();
            if (id != null && !duplicates.contains(id) && row.path("readonly").isBoolean()
                && (!row.has("aiSuitable") || row.path("aiSuitable").isBoolean())) {
                try {
                    OutpatientEmrTemplateFieldSnapshot field = objectMapper.treeToValue(row, OutpatientEmrTemplateFieldSnapshot.class);
                    // Older snapshots can omit this advisory annotation. The HTML and readonly flag govern editability.
                    if (field.getAiSuitable() == null) field.setAiSuitable(false);
                    validateField(field, "parseResult.fields", false);
                    fields.put(id, field);
                    continue;
                } catch (JsonProcessingException | BusinessException ex) {
                    // A malformed stored field cannot grant another row ownership of its target.
                }
            }
            if (row.path("recordField").isTextual()) disputedTargets.add(row.get("recordField").textValue());
        }

        Set<String> overridden = new HashSet<>();
        Set<String> ambiguousMappings = new HashSet<>();
        for (AiOutpatientEmrTemplateMapping mapping : listMappings(entity.getIdSnapshot())) {
            String id = mapping.getFieldId();
            OutpatientEmrTemplateFieldSnapshot field = fields.get(id);
            if (field == null || !overridden.add(id)) {
                if (field != null) {
                    disputedTargets.add(field.getRecordField());
                    ambiguousMappings.add(id);
                }
                disputedTargets.add(mapping.getRecordField());
                continue;
            }
            applyMappingValue(field, mapping.getRecordField(), mapping.getProjectionMode());
            try {
                validateField(field, "parseResult.fields", true);
            } catch (BusinessException ex) {
                disputedTargets.add(mapping.getRecordField());
                ambiguousMappings.add(id);
            }
        }
        for (String id : ambiguousMappings) applyMappingValue(fields.get(id), null, null);
        disputedTargets.addAll(conflictingOwners(fields.values()).keySet());
        for (OutpatientEmrTemplateFieldSnapshot field : fields.values()) {
            if (field.getRecordField() != null && disputedTargets.contains(field.getRecordField())) {
                applyMappingValue(field, null, null);
            }
        }
        boolean partial = fields.size() != rows.size() || !disputedTargets.isEmpty() || !ambiguousMappings.isEmpty();
        if (partial && fields.values().stream().noneMatch(field -> !field.getReadonly())) {
            throw new BusinessException("历史模板解析无可用生成字段");
        }
        OutpatientEmrTemplateParseSnapshot result = new OutpatientEmrTemplateParseSnapshot();
        result.setSchemaVersion(PARSE_SCHEMA);
        result.setFields(new ArrayList<>(fields.values()));
        return result;
    }

    private String writeParseResult(OutpatientEmrTemplateParseSnapshot value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("parseResult 无法序列化");
        }
    }

    private void assertNoUnknownFields(StrictOutpatientEmrSnapshotDto value, String path) {
        if (value != null && !value.getUnknownFields().isEmpty()) {
            throw new BusinessException(path + " 包含未支持字段: " + value.getUnknownFields().keySet());
        }
    }

    private String requireText(String value, String fieldName, int maxLength) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw new BusinessException(fieldName + " 不能为空");
        }
        if (!normalized.equals(value)) {
            throw new BusinessException(fieldName + " 不能包含首尾空白");
        }
        if (normalized.length() > maxLength) {
            throw new BusinessException(fieldName + " 超过长度限制");
        }
        return normalized;
    }

    private void requireEquals(String actual, String expected, String fieldName) {
        if (!expected.equals(actual)) {
            throw new BusinessException(fieldName + " 只支持 " + expected);
        }
    }

    private String requireSha256(String value, String fieldName) {
        String normalized = requireText(value, fieldName, 64).toLowerCase(Locale.ROOT);
        if (!SHA256_PATTERN.matcher(normalized).matches() || !normalized.equals(value)) {
            throw new BusinessException(fieldName + " 必须是 64 位小写 SHA-256");
        }
        return normalized;
    }

    private void requireTextIncludingEmpty(String value, String fieldName, int maxLength) {
        if (value == null) {
            throw new BusinessException(fieldName + " 不能为空，空值请使用空字符串");
        }
        if (!value.equals(value.trim())) {
            throw new BusinessException(fieldName + " 不能包含首尾空白");
        }
        if (value.length() > maxLength) {
            throw new BusinessException(fieldName + " 超过长度限制");
        }
    }

    private String requireNullableText(String value, String fieldName, int maxLength) {
        if (value == null) {
            return null;
        }
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(fieldName + " 空值必须使用 null");
        }
        if (!value.equals(value.trim())) {
            throw new BusinessException(fieldName + " 不能包含首尾空白");
        }
        if (value.length() > maxLength) {
            throw new BusinessException(fieldName + " 超过长度限制");
        }
        return value;
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private int utf8Length(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(bytes.length * 2);
            for (byte item : bytes) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private Long toEpochMillis(LocalDateTime value) {
        return value == null ? null : Long.valueOf(value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }

    private static Set<String> unmodifiableSet(String... values) {
        return Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(values)));
    }

    private static final class ValidatedSnapshot {
        private final String idOrg;
        private final String templateId;
        private final String templateName;
        private final String templateHash;
        private final String parseResultJson;
        private final int fieldCount;
        private final int writableFieldCount;
        private final int dictionaryFieldCount;
        private final int mappedFieldCount;

        private ValidatedSnapshot(String idOrg,
                                  String templateId,
                                  String templateName,
                                  String templateHash,
                                  String parseResultJson,
                                  int fieldCount,
                                  int writableFieldCount,
                                  int dictionaryFieldCount,
                                  int mappedFieldCount) {
            this.idOrg = idOrg;
            this.templateId = templateId;
            this.templateName = templateName;
            this.templateHash = templateHash;
            this.parseResultJson = parseResultJson;
            this.fieldCount = fieldCount;
            this.writableFieldCount = writableFieldCount;
            this.dictionaryFieldCount = dictionaryFieldCount;
            this.mappedFieldCount = mappedFieldCount;
        }
    }

    private static final class ValidatedResolve {
        private final String idOrg;
        private final String templateId;
        private final String templateHash;

        private ValidatedResolve(String idOrg,
                                 String templateId,
                                 String templateHash) {
            this.idOrg = idOrg;
            this.templateId = templateId;
            this.templateHash = templateHash;
        }
    }

    private static final class ValidatedParseResult {
        private final String json;
        private final int fieldCount;
        private final int writableFieldCount;
        private final int dictionaryFieldCount;
        private final int mappedFieldCount;

        private ValidatedParseResult(String json,
                                     int fieldCount,
                                     int writableFieldCount,
                                     int dictionaryFieldCount,
                                     int mappedFieldCount) {
            this.json = json;
            this.fieldCount = fieldCount;
            this.writableFieldCount = writableFieldCount;
            this.dictionaryFieldCount = dictionaryFieldCount;
            this.mappedFieldCount = mappedFieldCount;
        }
    }

    private static final class ValidatedMappingUpdate {
        private final String fieldId;
        private final String recordField;
        private final String projectionMode;

        private ValidatedMappingUpdate(String fieldId,
                                       String recordField,
                                       String projectionMode) {
            this.fieldId = fieldId;
            this.recordField = recordField;
            this.projectionMode = projectionMode;
        }
    }
}
