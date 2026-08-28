package com.regionalai.floatingball.server.modules.symptom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.datapackage.dto.TemplateDeltaVO;
import com.regionalai.floatingball.server.modules.datapackage.service.BuiltinTemplateSeedService;
import com.regionalai.floatingball.server.modules.datapackage.service.DataPackageService;
import com.regionalai.floatingball.server.modules.symptom.dto.BuiltinSymptomImportRequest;
import com.regionalai.floatingball.server.modules.symptom.dto.BuiltinSymptomImportResultVO;
import com.regionalai.floatingball.server.modules.symptom.dto.JsonSymptomImportRequest;
import com.regionalai.floatingball.server.modules.symptom.dto.SymptomTemplateVO;
import com.regionalai.floatingball.server.modules.symptom.entity.AiSymptomTemplate;
import com.regionalai.floatingball.server.modules.symptom.mapper.AiSymptomTemplateMapper;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SymptomTemplateService {

    private static final Logger log = LoggerFactory.getLogger(SymptomTemplateService.class);

    private static final String ACTIVE_ENABLED = "1";
    private static final String ACTIVE_DISABLED = "0";
    private static final String STATUS_ENABLED = "1";
    private static final String STATUS_DISABLED = "0";
    private static final String MODE_WESTERN = "western";
    private static final String MODE_TCM = "tcm";
    private static final Set<String> SUPPORTED_MODES = new LinkedHashSet<String>(Arrays.asList(MODE_WESTERN, MODE_TCM));
    private static final Set<String> SUPPORTED_FIELD_TYPES = new LinkedHashSet<String>(Arrays.asList(
        "radio",
        "checkbox",
        "input",
        "input_radio",
        "number",
        "degree_slider",
        "preference_pair"
    ));

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<Map<String, Object>>() {
    };
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<List<String>>() {
    };

    private final AiSymptomTemplateMapper aiSymptomTemplateMapper;
    private final ObjectMapper objectMapper;
    private final BuiltinTemplateSeedService builtinTemplateSeedService;
    private final DataPackageService dataPackageService;
    private final SymptomTemplateChangeLogService changeLogService;
    private final DatabaseDialect databaseDialect;

    public SymptomTemplateService(AiSymptomTemplateMapper aiSymptomTemplateMapper,
                                  ObjectMapper objectMapper,
                                  BuiltinTemplateSeedService builtinTemplateSeedService,
                                  DataPackageService dataPackageService,
                                  SymptomTemplateChangeLogService changeLogService,
                                  DatabaseDialect databaseDialect) {
        this.aiSymptomTemplateMapper = aiSymptomTemplateMapper;
        this.objectMapper = objectMapper;
        this.builtinTemplateSeedService = builtinTemplateSeedService;
        this.dataPackageService = dataPackageService;
        this.changeLogService = changeLogService;
        this.databaseDialect = databaseDialect;
    }

    public PageResponse<SymptomTemplateVO> list(long current,
                                                long size,
                                                String keyword,
                                                String medicalMode,
                                                String systemCategory,
                                                String sdStatus,
                                                String idRegion,
                                                String idOrg) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiSymptomTemplate> page = new Page<AiSymptomTemplate>(pageRequest.getCurrent(), pageRequest.getSize());
        LambdaQueryWrapper<AiSymptomTemplate> wrapper = new LambdaQueryWrapper<AiSymptomTemplate>()
            .eq(AiSymptomTemplate::getFgActive, ACTIVE_ENABLED)
            .orderByAsc(AiSymptomTemplate::getSortOrder)
            .orderByDesc(AiSymptomTemplate::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(AiSymptomTemplate::getNaSymptom, keyword).or().like(AiSymptomTemplate::getCdSymptom, keyword));
        }
        if (StringUtils.hasText(medicalMode)) {
            validateMedicalMode(medicalMode);
            wrapper.eq(AiSymptomTemplate::getSdMedicalMode, medicalMode.trim());
        }
        if (StringUtils.hasText(systemCategory)) {
            String token = buildSingleToken(systemCategory.trim());
            wrapper.apply("system_category_tokens LIKE {0}", "%" + token + "%");
        }
        if (StringUtils.hasText(sdStatus)) {
            wrapper.eq(AiSymptomTemplate::getSdStatus, sdStatus.trim());
        }
        if (StringUtils.hasText(idRegion)) {
            wrapper.eq(AiSymptomTemplate::getIdRegion, idRegion.trim());
        }
        if (StringUtils.hasText(idOrg)) {
            wrapper.eq(AiSymptomTemplate::getIdOrg, idOrg.trim());
        }
        Page<AiSymptomTemplate> result = aiSymptomTemplateMapper.selectPage(page, wrapper);
        List<SymptomTemplateVO> records = new ArrayList<SymptomTemplateVO>();
        for (AiSymptomTemplate item : result.getRecords()) {
            records.add(toView(item));
        }
        return new PageResponse<SymptomTemplateVO>(result.getCurrent(), result.getSize(), result.getTotal(), records);
    }

    @Transactional
    public SymptomTemplateVO save(SymptomTemplateVO request) {
        validateRequest(request, null);
        AiSymptomTemplate entity = new AiSymptomTemplate();
        mergeRequest(entity, request, null);
        entity.setFgActive(ACTIVE_ENABLED);
        aiSymptomTemplateMapper.insert(entity);
        SymptomTemplateVO saved = toView(entity);
        recordChange(SymptomTemplateChangeLogService.OPERATION_CREATE, null, saved);
        log.info("symptom template saved. idTemplate={}, mode={}", entity.getIdTemplate(), entity.getSdMedicalMode());
        return saved;
    }

    @Transactional
    public SymptomTemplateVO update(String idTemplate, SymptomTemplateVO request) {
        AiSymptomTemplate existing = requireActiveTemplate(idTemplate);
        SymptomTemplateVO before = toView(existing);
        validateRequest(request, idTemplate);
        mergeRequest(existing, request, existing);
        aiSymptomTemplateMapper.updateById(existing);
        SymptomTemplateVO after = toView(existing);
        recordChange(SymptomTemplateChangeLogService.OPERATION_UPDATE, before, after);
        log.info("symptom template updated. idTemplate={}", idTemplate);
        return after;
    }

    @Transactional
    public void invalidate(String idTemplate) {
        AiSymptomTemplate existing = requireActiveTemplate(idTemplate);
        SymptomTemplateVO before = toView(existing);
        int affected = aiSymptomTemplateMapper.update(null, new LambdaUpdateWrapper<AiSymptomTemplate>()
            .eq(AiSymptomTemplate::getIdTemplate, idTemplate)
            .eq(AiSymptomTemplate::getFgActive, ACTIVE_ENABLED)
            .set(AiSymptomTemplate::getFgActive, ACTIVE_DISABLED)
            .set(AiSymptomTemplate::getUpdateTime, LocalDateTime.now()));
        if (affected <= 0) {
            throw new BusinessException("症状模板不存在");
        }
        recordChange(SymptomTemplateChangeLogService.OPERATION_DELETE, before, null);
        log.info("symptom template invalidated. idTemplate={}", idTemplate);
    }

    @Transactional
    public BuiltinSymptomImportResultVO importBuiltin(BuiltinSymptomImportRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        validateMedicalMode(request.getMedicalMode());
        String medicalMode = request.getMedicalMode().trim();
        String idOrg = trimToNull(request.getIdOrg());
        String idRegion = trimToNull(request.getIdRegion());
        boolean overwriteExisting = request.getOverwriteExisting() == null || request.getOverwriteExisting().booleanValue();

        TemplateDeltaVO snapshot = builtinTemplateSeedService.getSnapshotDelta();
        List<Object> source = MODE_TCM.equals(medicalMode) ? snapshot.getTcm() : snapshot.getWestern();
        List<SymptomTemplateVO> templates = new ArrayList<SymptomTemplateVO>();
        for (Object raw : source) {
            templates.add(objectMapper.convertValue(raw, SymptomTemplateVO.class));
        }
        return importTemplates(templates, medicalMode, idOrg, idRegion, overwriteExisting,
            SymptomTemplateChangeLogService.OPERATION_IMPORT_BUILTIN);
    }

    @Transactional
    public BuiltinSymptomImportResultVO importJson(JsonSymptomImportRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        validateMedicalMode(request.getMedicalMode());
        String medicalMode = request.getMedicalMode().trim();
        String idOrg = trimToNull(request.getIdOrg());
        String idRegion = trimToNull(request.getIdRegion());
        boolean overwriteExisting = request.getOverwriteExisting() == null || request.getOverwriteExisting().booleanValue();
        List<SymptomTemplateVO> templates = parseImportTemplates(request.getContentJson(), medicalMode);
        return importTemplates(templates, medicalMode, idOrg, idRegion, overwriteExisting,
            SymptomTemplateChangeLogService.OPERATION_IMPORT_JSON);
    }

    public TemplateDeltaVO getClientDelta(String orgId, String regionId, String version) {
        List<AiSymptomTemplate> westernTemplates = findVisibleTemplates(MODE_WESTERN, orgId, regionId);
        List<AiSymptomTemplate> tcmTemplates = findVisibleTemplates(MODE_TCM, orgId, regionId);
        if (westernTemplates.isEmpty() && tcmTemplates.isEmpty()) {
            return getNormalizedFallbackDelta(orgId, regionId, version);
        }

        String latestVersion = buildVisibleVersion(westernTemplates, tcmTemplates);
        TemplateDeltaVO delta = new TemplateDeltaVO();
        delta.setVersion(latestVersion);
        if (StringUtils.hasText(version) && version.equals(latestVersion)) {
            return delta;
        }
        delta.setWestern(toClientTemplates(westernTemplates));
        delta.setTcm(toClientTemplates(tcmTemplates));
        return delta;
    }

    public String latestVisibleVersion(String orgId, String regionId) {
        List<AiSymptomTemplate> westernTemplates = findVisibleTemplates(MODE_WESTERN, orgId, regionId);
        List<AiSymptomTemplate> tcmTemplates = findVisibleTemplates(MODE_TCM, orgId, regionId);
        if (westernTemplates.isEmpty() && tcmTemplates.isEmpty()) {
            return buildNormalizedFallbackVersion(dataPackageService.getTemplateDelta(orgId, regionId, null));
        }
        return buildVisibleVersion(westernTemplates, tcmTemplates);
    }

    private TemplateDeltaVO getNormalizedFallbackDelta(String orgId, String regionId, String version) {
        TemplateDeltaVO rawDelta = dataPackageService.getTemplateDelta(orgId, regionId, null);
        String normalizedVersion = buildNormalizedFallbackVersion(rawDelta);
        TemplateDeltaVO delta = new TemplateDeltaVO();
        delta.setVersion(normalizedVersion);
        if (StringUtils.hasText(version) && version.equals(normalizedVersion)) {
            return delta;
        }
        delta.setWestern(SymptomTemplateCompatibilityNormalizer.normalizeTemplates(rawDelta.getWestern()));
        delta.setTcm(SymptomTemplateCompatibilityNormalizer.normalizeTemplates(rawDelta.getTcm()));
        return delta;
    }

    private String buildNormalizedFallbackVersion(TemplateDeltaVO rawDelta) {
        List<Object> western = SymptomTemplateCompatibilityNormalizer.normalizeTemplates(rawDelta.getWestern());
        List<Object> tcm = SymptomTemplateCompatibilityNormalizer.normalizeTemplates(rawDelta.getTcm());
        String sourceVersion = StringUtils.hasText(rawDelta.getVersion()) ? rawDelta.getVersion() : "0";
        String payload = sourceVersion + "|" + writeJson(western) + "|" + writeJson(tcm);
        return sourceVersion + "-compat-" + DigestUtils.md5DigestAsHex(payload.getBytes(StandardCharsets.UTF_8)).substring(0, 8);
    }

    private void mergeRequest(AiSymptomTemplate target, SymptomTemplateVO request, AiSymptomTemplate existing) {
        target.setCdSymptom(trimToNull(request.getKey()));
        target.setNaSymptom(trimToNull(request.getName()));
        target.setSdMedicalMode(trimToNull(request.getMedicalMode()));
        target.setDesSymptom(trimToNull(request.getDescription()));
        target.setFgCommon(Boolean.TRUE.equals(request.getCommonSymptom()) ? ACTIVE_ENABLED : STATUS_DISABLED);
        target.setSortOrder(resolveSortOrder(request, existing));
        target.setSystemCategoryJson(writeJson(defaultStringList(request.getSystemCategory())));
        target.setSystemCategoryTokens(buildTokens(request.getSystemCategory()));
        target.setBodyPartsJson(writeJson(defaultStringList(request.getBodyParts())));
        target.setBodyPartsTokens(buildTokens(request.getBodyParts()));
        target.setCustomScript(trimToNull(request.getCustomScript()));
        target.setApplicablePopulationJson(writeJson(defaultMap(request.getApplicablePopulation())));
        target.setConfigJson(writeJson(defaultMap(request.getConfig())));
        target.setTcmMetadataJson(isEmptyMap(request.getTcmMetadata()) ? null : writeJson(request.getTcmMetadata()));
        target.setIdOrg(trimToNull(request.getIdOrg()));
        target.setIdRegion(trimToNull(request.getIdRegion()));
        target.setSdStatus(resolveStatus(request.getSdStatus(), existing == null ? STATUS_ENABLED : existing.getSdStatus()));
    }

    private BuiltinSymptomImportResultVO importTemplates(List<SymptomTemplateVO> source,
                                                         String medicalMode,
                                                         String idOrg,
                                                         String idRegion,
                                                         boolean overwriteExisting,
                                                         String operationType) {
        if (source == null || source.isEmpty()) {
            throw new BusinessException("未读取到症状模板");
        }
        int createdCount = 0;
        int updatedCount = 0;
        int sortOrder = 1;
        for (SymptomTemplateVO raw : source) {
            if (raw == null) {
                continue;
            }
            SymptomTemplateVO requestVo = prepareImportRequest(raw, medicalMode, idOrg, idRegion, sortOrder++);
            AiSymptomTemplate existing = findExactScopeTemplate(requestVo.getKey(), medicalMode, idOrg, idRegion, null);
            if (existing != null) {
                if (!overwriteExisting) {
                    continue;
                }
                SymptomTemplateVO before = toView(existing);
                mergeRequest(existing, requestVo, existing);
                existing.setFgActive(ACTIVE_ENABLED);
                existing.setSdStatus(STATUS_ENABLED);
                aiSymptomTemplateMapper.updateById(existing);
                SymptomTemplateVO after = toView(existing);
                recordChange(operationType, before, after);
                updatedCount++;
                continue;
            }

            AiSymptomTemplate entity = new AiSymptomTemplate();
            mergeRequest(entity, requestVo, null);
            entity.setFgActive(ACTIVE_ENABLED);
            aiSymptomTemplateMapper.insert(entity);
            SymptomTemplateVO after = toView(entity);
            recordChange(operationType, null, after);
            createdCount++;
        }
        return new BuiltinSymptomImportResultVO(medicalMode, createdCount, updatedCount);
    }

    private void recordChange(String operationType, SymptomTemplateVO before, SymptomTemplateVO after) {
        changeLogService.record(operationType, before, after, AdminContextHolder.get());
    }

    private SymptomTemplateVO prepareImportRequest(SymptomTemplateVO source,
                                                   String medicalMode,
                                                   String idOrg,
                                                   String idRegion,
                                                   int sortOrder) {
        SymptomTemplateVO requestVo = objectMapper.convertValue(source, SymptomTemplateVO.class);
        requestVo.setMedicalMode(medicalMode);
        requestVo.setIdOrg(idOrg);
        requestVo.setIdRegion(idRegion);
        requestVo.setSdStatus(STATUS_ENABLED);
        if (requestVo.getSortOrder() == null) {
            requestVo.setSortOrder(Integer.valueOf(sortOrder));
        }
        return requestVo;
    }

    private List<SymptomTemplateVO> parseImportTemplates(String contentJson, String medicalMode) {
        if (!StringUtils.hasText(contentJson)) {
            throw new BusinessException("contentJson 不能为空");
        }
        try {
            JsonNode root = objectMapper.readTree(contentJson);
            JsonNode templatesNode = resolveImportTemplatesNode(root, medicalMode);
            if (templatesNode == null || !templatesNode.isArray() || templatesNode.size() == 0) {
                throw new BusinessException("未读取到症状模板");
            }
            List<SymptomTemplateVO> result = new ArrayList<SymptomTemplateVO>();
            for (JsonNode item : templatesNode) {
                result.add(objectMapper.convertValue(item, SymptomTemplateVO.class));
            }
            return result;
        } catch (BusinessException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new BusinessException("contentJson 不是合法 JSON");
        }
    }

    private JsonNode resolveImportTemplatesNode(JsonNode root, String medicalMode) {
        if (root == null || root.isNull()) {
            throw new BusinessException("contentJson 不能为空");
        }
        if (root.isArray()) {
            return root;
        }
        if (!root.isObject()) {
            throw new BusinessException("contentJson 不是可识别的模板结构");
        }
        JsonNode symptomsNode = root.get("symptoms");
        if (symptomsNode != null) {
            if (!symptomsNode.isArray()) {
                throw new BusinessException("contentJson.symptoms 必须是数组");
            }
            return symptomsNode;
        }
        JsonNode modeNode = root.get(medicalMode);
        if (modeNode != null) {
            if (!modeNode.isArray()) {
                throw new BusinessException("contentJson." + medicalMode + " 必须是数组");
            }
            return modeNode;
        }
        throw new BusinessException("contentJson 不是可识别的模板结构");
    }

    private Integer resolveSortOrder(SymptomTemplateVO request, AiSymptomTemplate existing) {
        if (request.getSortOrder() != null) {
            return request.getSortOrder();
        }
        if (existing != null && existing.getSortOrder() != null) {
            return existing.getSortOrder();
        }
        return Integer.valueOf(resolveNextSortOrder(request.getMedicalMode(), request.getIdOrg(), request.getIdRegion()));
    }

    private int resolveNextSortOrder(String medicalMode, String idOrg, String idRegion) {
        LambdaQueryWrapper<AiSymptomTemplate> wrapper = new LambdaQueryWrapper<AiSymptomTemplate>()
            .eq(AiSymptomTemplate::getFgActive, ACTIVE_ENABLED)
            .eq(AiSymptomTemplate::getSdMedicalMode, trimToNull(medicalMode))
            .orderByDesc(AiSymptomTemplate::getSortOrder);
        appendExactScopeCondition(wrapper, trimToNull(idOrg), trimToNull(idRegion));
        List<AiSymptomTemplate> records = aiSymptomTemplateMapper.selectList(wrapper);
        if (records.isEmpty() || records.get(0).getSortOrder() == null) {
            return 1;
        }
        return records.get(0).getSortOrder().intValue() + 1;
    }

    private void validateRequest(SymptomTemplateVO request, String currentId) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        validateMedicalMode(request.getMedicalMode());
        if (!StringUtils.hasText(request.getKey())) {
            throw new BusinessException("症状 Key 不能为空");
        }
        if (!StringUtils.hasText(request.getName())) {
            throw new BusinessException("症状名称不能为空");
        }
        validateConfig(request.getConfig());
        validateStatus(request.getSdStatus());
        validateUniqueKey(request, currentId);
    }

    private void validateMedicalMode(String medicalMode) {
        if (!StringUtils.hasText(medicalMode) || !SUPPORTED_MODES.contains(medicalMode.trim())) {
            throw new BusinessException("medicalMode 仅支持 western 或 tcm");
        }
    }

    private void validateStatus(String sdStatus) {
        if (!StringUtils.hasText(sdStatus)) {
            return;
        }
        String value = sdStatus.trim();
        if (!STATUS_ENABLED.equals(value) && !STATUS_DISABLED.equals(value)) {
            throw new BusinessException("sdStatus 仅支持 0 或 1");
        }
    }

    private void validateUniqueKey(SymptomTemplateVO request, String currentId) {
        AiSymptomTemplate existing = findExactScopeTemplate(
            request.getKey(),
            request.getMedicalMode(),
            request.getIdOrg(),
            request.getIdRegion(),
            currentId
        );
        if (existing != null) {
            throw new BusinessException("同作用域下已存在相同症状 Key");
        }
    }

    private AiSymptomTemplate findExactScopeTemplate(String symptomKey,
                                                     String medicalMode,
                                                     String idOrg,
                                                     String idRegion,
                                                     String excludeId) {
        LambdaQueryWrapper<AiSymptomTemplate> wrapper = new LambdaQueryWrapper<AiSymptomTemplate>()
            .eq(AiSymptomTemplate::getFgActive, ACTIVE_ENABLED)
            .eq(AiSymptomTemplate::getCdSymptom, trimToNull(symptomKey))
            .eq(AiSymptomTemplate::getSdMedicalMode, trimToNull(medicalMode));
        appendExactScopeCondition(wrapper, trimToNull(idOrg), trimToNull(idRegion));
        if (StringUtils.hasText(excludeId)) {
            wrapper.ne(AiSymptomTemplate::getIdTemplate, excludeId);
        }
        return aiSymptomTemplateMapper.selectOne(wrapper.last(databaseDialect.firstRows(1)));
    }

    private void validateConfig(Map<String, Object> config) {
        if (config == null || config.isEmpty()) {
            throw new BusinessException("config 不能为空");
        }
        JsonNode root = objectMapper.valueToTree(config);
        if (root == null || !root.isObject()) {
            throw new BusinessException("config 必须是对象");
        }
        JsonNode sections = root.get("sections");
        if (sections == null || !sections.isArray()) {
            throw new BusinessException("config.sections 必须是数组");
        }
        for (JsonNode section : sections) {
            if (!section.isObject()) {
                throw new BusinessException("config.sections[*] 必须是对象");
            }
            JsonNode fields = section.get("fields");
            if (fields == null || !fields.isArray()) {
                throw new BusinessException("section.fields 必须是数组");
            }
            for (JsonNode field : fields) {
                validateField(field);
            }
        }
    }

    private void validateField(JsonNode field) {
        if (!field.isObject()) {
            throw new BusinessException("字段配置必须是对象");
        }
        requireText(field, "label", "字段 label 不能为空");
        requireText(field, "storageKey", "字段 storageKey 不能为空");
        String fieldType = requireText(field, "type", "字段 type 不能为空");
        if (!SUPPORTED_FIELD_TYPES.contains(fieldType)) {
            throw new BusinessException("字段 type 不支持: " + fieldType);
        }
        JsonNode props = field.get("props");
        if (props != null && !props.isNull() && !props.isObject()) {
            throw new BusinessException("字段 props 必须是对象");
        }
        if ("radio".equals(fieldType) || "checkbox".equals(fieldType)) {
            requireArray(props, "options", "radio/checkbox 字段 props.options 必须是数组");
        }
        if ("input_radio".equals(fieldType)) {
            requireArray(props, "radioOptions", "input_radio 字段 props.radioOptions 必须是数组");
        }
        if ("degree_slider".equals(fieldType)) {
            requireArray(props, "labels", "degree_slider 字段 props.labels 必须是数组");
        }
        if ("preference_pair".equals(fieldType)) {
            requireArray(props, "pairs", "preference_pair 字段 props.pairs 必须是数组");
        }
    }

    private String requireText(JsonNode node, String fieldName, String message) {
        JsonNode value = node.get(fieldName);
        if (value == null || value.isNull() || !StringUtils.hasText(value.asText())) {
            throw new BusinessException(message);
        }
        return value.asText().trim();
    }

    private void requireArray(JsonNode parent, String fieldName, String message) {
        if (parent == null || parent.isNull()) {
            throw new BusinessException(message);
        }
        JsonNode value = parent.get(fieldName);
        if (value == null || !value.isArray()) {
            throw new BusinessException(message);
        }
    }

    private String resolveStatus(String requestStatus, String fallbackStatus) {
        if (StringUtils.hasText(requestStatus)) {
            return requestStatus.trim();
        }
        return StringUtils.hasText(fallbackStatus) ? fallbackStatus : STATUS_ENABLED;
    }

    private AiSymptomTemplate requireActiveTemplate(String idTemplate) {
        AiSymptomTemplate entity = aiSymptomTemplateMapper.selectById(idTemplate);
        if (entity == null || !ACTIVE_ENABLED.equals(entity.getFgActive())) {
            throw new BusinessException("症状模板不存在");
        }
        return entity;
    }

    private List<AiSymptomTemplate> findVisibleTemplates(String medicalMode, String orgId, String regionId) {
        LambdaQueryWrapper<AiSymptomTemplate> wrapper = new LambdaQueryWrapper<AiSymptomTemplate>()
            .eq(AiSymptomTemplate::getFgActive, ACTIVE_ENABLED)
            .eq(AiSymptomTemplate::getSdStatus, STATUS_ENABLED)
            .eq(AiSymptomTemplate::getSdMedicalMode, medicalMode)
            .orderByAsc(AiSymptomTemplate::getSortOrder)
            .orderByDesc(AiSymptomTemplate::getUpdateTime);
        appendVisibleScopeCondition(wrapper, trimToNull(orgId), trimToNull(regionId));
        List<AiSymptomTemplate> templates = aiSymptomTemplateMapper.selectList(wrapper);
        if (templates.isEmpty()) {
            return Collections.emptyList();
        }

        templates.sort((left, right) -> {
            int scoreCompare = Integer.compare(score(right, orgId, regionId), score(left, orgId, regionId));
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            int sortCompare = Integer.compare(resolveSortOrderValue(left), resolveSortOrderValue(right));
            if (sortCompare != 0) {
                return sortCompare;
            }
            return safeText(left.getNaSymptom()).compareToIgnoreCase(safeText(right.getNaSymptom()));
        });

        Map<String, AiSymptomTemplate> merged = new LinkedHashMap<String, AiSymptomTemplate>();
        for (AiSymptomTemplate item : templates) {
            if (!merged.containsKey(item.getCdSymptom())) {
                merged.put(item.getCdSymptom(), item);
            }
        }

        List<AiSymptomTemplate> result = new ArrayList<AiSymptomTemplate>(merged.values());
        result.sort((left, right) -> {
            int sortCompare = Integer.compare(resolveSortOrderValue(left), resolveSortOrderValue(right));
            if (sortCompare != 0) {
                return sortCompare;
            }
            return safeText(left.getNaSymptom()).compareToIgnoreCase(safeText(right.getNaSymptom()));
        });
        return result;
    }

    private void appendVisibleScopeCondition(LambdaQueryWrapper<AiSymptomTemplate> wrapper, String orgId, String regionId) {
        final boolean hasOrgId = StringUtils.hasText(orgId);
        final boolean hasRegionId = StringUtils.hasText(regionId);
        wrapper.and(q -> {
            if (hasOrgId) {
                q.eq(AiSymptomTemplate::getIdOrg, orgId);
                if (hasRegionId) {
                    q.or(inner -> inner.isNull(AiSymptomTemplate::getIdOrg).eq(AiSymptomTemplate::getIdRegion, regionId));
                }
                q.or(inner -> inner.isNull(AiSymptomTemplate::getIdOrg).isNull(AiSymptomTemplate::getIdRegion));
                return;
            }
            if (hasRegionId) {
                q.isNull(AiSymptomTemplate::getIdOrg).eq(AiSymptomTemplate::getIdRegion, regionId)
                    .or(inner -> inner.isNull(AiSymptomTemplate::getIdOrg).isNull(AiSymptomTemplate::getIdRegion));
                return;
            }
            q.isNull(AiSymptomTemplate::getIdOrg).isNull(AiSymptomTemplate::getIdRegion);
        });
    }

    private void appendExactScopeCondition(LambdaQueryWrapper<AiSymptomTemplate> wrapper, String idOrg, String idRegion) {
        if (StringUtils.hasText(idOrg)) {
            wrapper.eq(AiSymptomTemplate::getIdOrg, idOrg);
            if (StringUtils.hasText(idRegion)) {
                wrapper.eq(AiSymptomTemplate::getIdRegion, idRegion);
            } else {
                wrapper.isNull(AiSymptomTemplate::getIdRegion);
            }
            return;
        }
        if (StringUtils.hasText(idRegion)) {
            wrapper.isNull(AiSymptomTemplate::getIdOrg).eq(AiSymptomTemplate::getIdRegion, idRegion);
            return;
        }
        wrapper.isNull(AiSymptomTemplate::getIdOrg).isNull(AiSymptomTemplate::getIdRegion);
    }

    private int score(AiSymptomTemplate item, String orgId, String regionId) {
        if (StringUtils.hasText(orgId) && orgId.equals(item.getIdOrg())) {
            return 3;
        }
        if (StringUtils.hasText(regionId) && regionId.equals(item.getIdRegion()) && !StringUtils.hasText(item.getIdOrg())) {
            return 2;
        }
        return 1;
    }

    private int resolveSortOrderValue(AiSymptomTemplate item) {
        return item.getSortOrder() == null ? Integer.MAX_VALUE : item.getSortOrder().intValue();
    }

    private String buildVisibleVersion(List<AiSymptomTemplate> westernTemplates, List<AiSymptomTemplate> tcmTemplates) {
        StringBuilder builder = new StringBuilder();
        appendVersionPart(builder, MODE_WESTERN, westernTemplates, toClientTemplates(westernTemplates));
        appendVersionPart(builder, MODE_TCM, tcmTemplates, toClientTemplates(tcmTemplates));
        return "symptom-" + DigestUtils.md5DigestAsHex(builder.toString().getBytes(StandardCharsets.UTF_8)).substring(0, 12);
    }

    private void appendVersionPart(StringBuilder builder,
                                   String medicalMode,
                                   List<AiSymptomTemplate> templates,
                                   List<Object> normalizedTemplates) {
        builder.append(medicalMode).append(':');
        for (AiSymptomTemplate item : templates) {
            builder.append(item.getIdTemplate()).append('|')
                .append(safeText(item.getCdSymptom())).append('|')
                .append(resolveSortOrderValue(item)).append('|')
                .append(safeText(item.getIdOrg())).append('|')
                .append(safeText(item.getIdRegion())).append('|')
                .append(toEpochMillis(item.getUpdateTime())).append(';');
        }
        builder.append("normalized=").append(writeJson(normalizedTemplates));
        builder.append('#');
    }

    private List<Object> toClientTemplates(List<AiSymptomTemplate> templates) {
        List<Object> result = new ArrayList<Object>();
        for (AiSymptomTemplate item : templates) {
            result.add(toClientTemplate(item));
        }
        return SymptomTemplateCompatibilityNormalizer.normalizeTemplates(result);
    }

    private Map<String, Object> toClientTemplate(AiSymptomTemplate item) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", item.getIdTemplate());
        result.put("key", item.getCdSymptom());
        result.put("name", item.getNaSymptom());
        result.put("description", defaultString(item.getDesSymptom()));
        result.put("isCommonSymptom", Boolean.valueOf(ACTIVE_ENABLED.equals(item.getFgCommon())));
        result.put("systemCategory", readStringList(item.getSystemCategoryJson()));
        result.put("bodyParts", readStringList(item.getBodyPartsJson()));
        result.put("customScript", defaultString(item.getCustomScript()));
        result.put("config", readMap(item.getConfigJson()));
        result.put("applicablePopulation", readMap(item.getApplicablePopulationJson()));
        if (StringUtils.hasText(item.getTcmMetadataJson())) {
            result.put("tcmMetadata", readMap(item.getTcmMetadataJson()));
        }
        result.put("createdAt", Long.valueOf(toEpochMillis(item.getInsertTime())));
        result.put("updatedAt", Long.valueOf(toEpochMillis(item.getUpdateTime())));
        return SymptomTemplateCompatibilityNormalizer.normalizeTemplateMap(result);
    }

    private SymptomTemplateVO toView(AiSymptomTemplate item) {
        SymptomTemplateVO vo = new SymptomTemplateVO();
        vo.setId(item.getIdTemplate());
        vo.setMedicalMode(item.getSdMedicalMode());
        vo.setKey(item.getCdSymptom());
        vo.setName(item.getNaSymptom());
        vo.setDescription(defaultString(item.getDesSymptom()));
        vo.setCommonSymptom(Boolean.valueOf(ACTIVE_ENABLED.equals(item.getFgCommon())));
        vo.setSystemCategory(readStringList(item.getSystemCategoryJson()));
        vo.setBodyParts(readStringList(item.getBodyPartsJson()));
        vo.setCustomScript(defaultString(item.getCustomScript()));
        vo.setConfig(readMap(item.getConfigJson()));
        vo.setApplicablePopulation(readMap(item.getApplicablePopulationJson()));
        vo.setTcmMetadata(StringUtils.hasText(item.getTcmMetadataJson()) ? readMap(item.getTcmMetadataJson()) : null);
        vo.setSortOrder(item.getSortOrder());
        vo.setSdStatus(item.getSdStatus());
        vo.setIdRegion(item.getIdRegion());
        vo.setIdOrg(item.getIdOrg());
        vo.setCreatedAt(Long.valueOf(toEpochMillis(item.getInsertTime())));
        vo.setUpdatedAt(Long.valueOf(toEpochMillis(item.getUpdateTime())));
        SymptomTemplateCompatibilityNormalizer.normalizeTemplateVO(vo);
        return vo;
    }

    private List<String> readStringList(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (IOException ex) {
            throw new BusinessException("症状模板数组字段解析失败");
        }
    }

    private Map<String, Object> readMap(String json) {
        if (!StringUtils.hasText(json)) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (IOException ex) {
            throw new BusinessException("症状模板 JSON 解析失败");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (IOException ex) {
            throw new BusinessException("症状模板 JSON 序列化失败");
        }
    }

    private List<String> defaultStringList(List<String> value) {
        return value == null ? Collections.<String>emptyList() : value;
    }

    private Map<String, Object> defaultMap(Map<String, Object> value) {
        return value == null ? Collections.<String, Object>emptyMap() : value;
    }

    private boolean isEmptyMap(Map<String, Object> value) {
        return value == null || value.isEmpty();
    }

    private String buildTokens(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            if (!StringUtils.hasText(value)) {
                continue;
            }
            builder.append(buildSingleToken(value.trim()));
        }
        return builder.length() == 0 ? null : builder.toString();
    }

    private String buildSingleToken(String value) {
        return "|" + value + "|";
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String defaultString(String value) {
        return value == null ? "" : value;
    }

    private String safeText(String value) {
        return value == null ? "" : value;
    }

    private long toEpochMillis(LocalDateTime value) {
        if (value == null) {
            return 0L;
        }
        return value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
