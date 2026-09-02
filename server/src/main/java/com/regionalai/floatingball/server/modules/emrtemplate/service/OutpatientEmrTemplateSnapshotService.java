package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
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
        "familyHistory",
        "physicalExam",
        "precautions"
    );
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

    public OutpatientEmrTemplateSnapshotService(AiOutpatientEmrTemplateSnapshotMapper snapshotMapper,
                                                 AiOutpatientEmrTemplateMappingMapper mappingMapper,
                                                 ObjectMapper objectMapper) {
        this.snapshotMapper = snapshotMapper;
        this.mappingMapper = mappingMapper;
        this.objectMapper = objectMapper;
    }

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
        if (entity == null) {
            log.info(
                "outpatient emr template snapshot resolved. templateId={}, templateHash={}, hit=false",
                validated.templateId,
                validated.templateHash
            );
            return new OutpatientEmrTemplateSnapshotResolution(
                RESOLUTION_SCHEMA,
                Boolean.FALSE,
                null,
                validated.templateId,
                validated.templateHash,
                null,
                null
            );
        }

        String id = requireText(entity.getIdSnapshot(), "历史解析.id", 32);
        String storedTemplateId = requireText(entity.getTemplateId(), "历史解析.templateId", 128);
        String storedTemplateHash = requireSha256(entity.getTemplateHash(), "历史解析.templateHash");
        if (!validated.templateId.equals(storedTemplateId)
            || !validated.templateHash.equals(storedTemplateHash)) {
            throw new BusinessException("历史模板解析身份与查询不一致");
        }
        OutpatientEmrTemplateParseSnapshot parseResult = readParseResult(entity.getParseResultJson());
        validateParseResult(parseResult);
        applyMappingOverrides(entity.getIdSnapshot(), parseResult);
        validateParseResult(parseResult);
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
        return new OutpatientEmrTemplateSnapshotResolution(
            RESOLUTION_SCHEMA,
            Boolean.TRUE,
            id,
            storedTemplateId,
            storedTemplateHash,
            parseResult,
            receivedAt
        );
    }

    public OutpatientEmrTemplateSnapshotReceipt save(AiDevice device,
                                                      OutpatientEmrTemplateSnapshotRequest request) {
        ValidatedSnapshot validated = validate(device, request);
        LocalDateTime receivedAt = LocalDateTime.now();
        AiOutpatientEmrTemplateSnapshot existing = findExistingVersion(
            validated.idOrg,
            validated.templateId,
            validated.templateHash
        );
        if (existing != null) {
            return existingReceipt(existing, validated);
        }

        AiOutpatientEmrTemplateSnapshot entity = new AiOutpatientEmrTemplateSnapshot();
        entity.setIdSnapshot(UUID.randomUUID().toString().replace("-", ""));
        entity.setIdOrg(validated.idOrg);
        entity.setTemplateHash(validated.templateHash);
        entity.setFgActive(ACTIVE);
        applySnapshot(entity, device, request, validated, receivedAt);
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
            return existingReceipt(concurrent, validated);
        }

        log.info(
            "outpatient emr template snapshot received. idSnapshot={}, templateHash={}, deduplicated={}",
            entity.getIdSnapshot(),
            validated.templateHash,
            false
        );
        return new OutpatientEmrTemplateSnapshotReceipt(
            entity.getIdSnapshot(),
            validated.templateHash,
            Boolean.FALSE,
            toEpochMillis(receivedAt)
        );
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

        if (StringUtils.hasText(keyword)) {
            String normalizedKeyword = keyword.trim();
            wrapper.and(q -> q.like(AiOutpatientEmrTemplateSnapshot::getTemplateId, normalizedKeyword)
                .or()
                .like(AiOutpatientEmrTemplateSnapshot::getTemplateName, normalizedKeyword)
                .or()
                .like(AiOutpatientEmrTemplateSnapshot::getTemplateHash, normalizedKeyword));
        }
        Page<AiOutpatientEmrTemplateSnapshot> result = snapshotMapper.selectPage(page, wrapper);
        List<OutpatientEmrTemplateSnapshotVO> records = new ArrayList<OutpatientEmrTemplateSnapshotVO>();
        for (AiOutpatientEmrTemplateSnapshot item : result.getRecords()) {
            records.add(toView(item, false));
        }
        return new PageResponse<OutpatientEmrTemplateSnapshotVO>(
            result.getCurrent(),
            result.getSize(),
            result.getTotal(),
            records
        );
    }

    public OutpatientEmrTemplateSnapshotVO get(String idSnapshot) {
        return toView(requireSnapshot(idSnapshot), true);
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotVO updateMapping(
        String idSnapshot,
        OutpatientEmrTemplateMappingRequest request
    ) {
        AiOutpatientEmrTemplateSnapshot snapshot = requireSnapshot(idSnapshot);
        ValidatedMappingUpdate validated = validateMappingUpdate(snapshot, request);
        AiOutpatientEmrTemplateMapping existing = findMapping(snapshot.getIdSnapshot(), validated.fieldId);
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
            mappingMapper.updateById(existing);
        }
        return toView(snapshot, true);
    }

    @Transactional
    public OutpatientEmrTemplateSnapshotVO clearMapping(String idSnapshot, String fieldId) {
        AiOutpatientEmrTemplateSnapshot snapshot = requireSnapshot(idSnapshot);
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
        requireEquals(request.getSchemaVersion(), SNAPSHOT_SCHEMA, "schemaVersion");
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
        requireEquals(request.getSchemaVersion(), RESOLVE_SCHEMA, "schemaVersion");
        return new ValidatedResolve(
            requireText(device.getIdOrg(), "idOrg", 32),
            requireText(request.getTemplateId(), "templateId", 128),
            requireSha256(request.getTemplateHash(), "templateHash")
        );
    }

    private ValidatedParseResult validateParseResult(
        OutpatientEmrTemplateParseSnapshot parseResult
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
            if (field == null) {
                throw new BusinessException(path + " 不能为空");
            }
            assertNoUnknownFields(field, path);
            String fieldId = requireText(field.getId(), path + ".id", 512);
            if (!fieldIds.add(fieldId)) {
                throw new BusinessException("parseResult.fields 存在重复 id: " + fieldId);
            }
            requireText(field.getName(), path + ".name", 512);
            requireText(field.getType(), path + ".type", 64);
            requireText(field.getArticleTemplateId(), path + ".articleTemplateId", 512);
            requireText(field.getArticleId(), path + ".articleId", 512);
            requireText(field.getArticleName(), path + ".articleName", 512);
            requireText(field.getArticleDefinitionName(), path + ".articleDefinitionName", 512);
            if (field.getReadonly() == null || field.getAiSuitable() == null) {
                throw new BusinessException(path + ".readonly/aiSuitable 不能为空");
            }
            requireTextIncludingEmpty(field.getBaselineValue(), path + ".baselineValue", 262144);
            requireTextIncludingEmpty(
                field.getBaselineDictionaryValue(),
                path + ".baselineDictionaryValue",
                262144
            );
            if (!Boolean.TRUE.equals(field.getReadonly())) {
                writableFieldCount += 1;
            }
            List<OutpatientEmrTemplateDictionaryItemSnapshot> dictionaryItems = field.getDictionaryItems();
            if (dictionaryItems == null) {
                throw new BusinessException(path + ".dictionaryItems 不能为空");
            }
            if (!dictionaryItems.isEmpty()) {
                dictionaryFieldCount += 1;
            }
            validateDictionaryItems(dictionaryItems, path);

            String mappingSource = requireText(field.getMappingSource(), path + ".mappingSource", 64);
            if (!MAPPING_SOURCES.contains(mappingSource)) {
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
                mappedFieldCount += 1;
            }
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
        validateParseResult(parseResult);
        return new ValidatedMappingUpdate(fieldId, recordField, projectionMode);
    }

    private void validateRecordFieldOwnership(List<OutpatientEmrTemplateFieldSnapshot> fields) {
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
                throw new BusinessException(
                    "标准字段 " + entry.getKey() + " 存在重复映射: " + String.join("、", fieldIds)
                );
            }
        }
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
        } else if ("section-compose".equals(projectionMode)) {
            field.setMappingSource("definition-article-record-field");
        } else {
            field.setMappingSource("definition-record-field");
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

    private AiOutpatientEmrTemplateSnapshot requireSnapshot(String idSnapshot) {
        String normalizedId = requireText(idSnapshot, "idSnapshot", 32);
        AiOutpatientEmrTemplateSnapshot entity = snapshotMapper.selectById(normalizedId);
        if (entity == null || !ACTIVE.equals(entity.getFgActive())) {
            throw new BusinessException("门诊模板解析记录不存在");
        }
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
            ValidatedParseResult validated = validateParseResult(parseResult);
            view.setParseResult(parseResult);
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
