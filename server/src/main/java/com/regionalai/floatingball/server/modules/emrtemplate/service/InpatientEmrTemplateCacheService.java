package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.api.PageRequest;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplateCacheVO;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplateFieldGenerationRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplatePromptGenerateRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplatePromptGenerateVO;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplatePromptRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplateResolveRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.entity.AiInpatientEmrTemplateCache;
import com.regionalai.floatingball.server.modules.emrtemplate.mapper.AiInpatientEmrTemplateCacheMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class InpatientEmrTemplateCacheService {

    private static final Logger log = LoggerFactory.getLogger(InpatientEmrTemplateCacheService.class);

    private static final String ACTIVE_ENABLED = "1";
    private static final String ACTIVE_DISABLED = "0";
    private static final String STATUS_ENABLED = "1";
    private static final String STATUS_DISABLED = "0";
    private static final String PROMPT_SOURCE_CUSTOM = "custom";
    private static final String PROMPT_SOURCE_DEFAULT = "default";
    private static final String PROMPT_SOURCE_NOT_AI = "not_ai";
    private static final String DEFAULT_PROMPT_GENERATOR_INSTRUCTION =
        "请根据住院病历模板字段信息，生成一个严谨、可审查、可直接用于 AI 回填该字段的提示词。"
            + "提示词必须约束 AI 仅依据住院登记、诊断、医嘱、体温单等已提供资料生成，"
            + "不得编造未提供的症状、查体、检查结果或治疗效果。";

    private static final TypeReference<List<Map<String, Object>>> FIELD_LIST_TYPE =
        new TypeReference<List<Map<String, Object>>>() {
        };

    private final AiInpatientEmrTemplateCacheMapper cacheMapper;
    private final ObjectMapper objectMapper;

    public InpatientEmrTemplateCacheService(AiInpatientEmrTemplateCacheMapper cacheMapper,
                                            ObjectMapper objectMapper) {
        this.cacheMapper = cacheMapper;
        this.objectMapper = objectMapper;
    }

    public PageResponse<InpatientEmrTemplateCacheVO> list(long current,
                                                          long size,
                                                          String keyword,
                                                          String sdStatus) {
        PageRequest pageRequest = PageRequest.of(current, size);
        Page<AiInpatientEmrTemplateCache> page = new Page<AiInpatientEmrTemplateCache>(pageRequest.getCurrent(), pageRequest.getSize());
        LambdaQueryWrapper<AiInpatientEmrTemplateCache> wrapper = new LambdaQueryWrapper<AiInpatientEmrTemplateCache>()
            .eq(AiInpatientEmrTemplateCache::getFgActive, ACTIVE_ENABLED)
            .orderByDesc(AiInpatientEmrTemplateCache::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(AiInpatientEmrTemplateCache::getTemplateId, keyword.trim())
                .or()
                .like(AiInpatientEmrTemplateCache::getTemplateHash, keyword.trim())
                .or()
                .like(AiInpatientEmrTemplateCache::getTemplateName, keyword.trim()));
        }
        if (StringUtils.hasText(sdStatus)) {
            wrapper.eq(AiInpatientEmrTemplateCache::getSdStatus, sdStatus.trim());
        }
        Page<AiInpatientEmrTemplateCache> result = cacheMapper.selectPage(page, wrapper);
        List<InpatientEmrTemplateCacheVO> records = new ArrayList<InpatientEmrTemplateCacheVO>();
        for (AiInpatientEmrTemplateCache item : result.getRecords()) {
            records.add(toAdminView(item, false));
        }
        return new PageResponse<InpatientEmrTemplateCacheVO>(
            result.getCurrent(),
            result.getSize(),
            result.getTotal(),
            records
        );
    }

    public InpatientEmrTemplateCacheVO get(String idCache) {
        return toAdminView(requireCache(idCache), false);
    }

    @Transactional
    public InpatientEmrTemplateCacheVO resolve(InpatientEmrTemplateResolveRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        String templateId = resolveTemplateId(request);
        String templateHash = resolveTemplateHash(request);
        AiInpatientEmrTemplateCache cached = findEnabledByTemplateId(templateId);
        if (cached != null) {
            updateCachedTemplateMetadata(cached, request, templateHash);
            updateCachedFieldsFromRequest(cached, request.getFields());
            return toClientView(cached, request, true);
        }

        if (!StringUtils.hasText(request.getHtmlContent())) {
            throw new BusinessException("htmlContent 不能为空");
        }
        if (request.getFields() == null || request.getFields().isEmpty()) {
            throw new BusinessException("fields 不能为空");
        }

        AiInpatientEmrTemplateCache entity = new AiInpatientEmrTemplateCache();
        List<Map<String, Object>> fields = copyFields(request.getFields());
        entity.setTemplateId(templateId);
        entity.setTemplateHash(templateHash);
        entity.setTemplateName(trimToNull(request.getTemplateName()));
        entity.setHtmlContent(request.getHtmlContent());
        entity.setFieldsJson(writeFields(fields));
        entity.setFieldCount(Integer.valueOf(fields.size()));
        entity.setSdStatus(STATUS_ENABLED);
        entity.setFgActive(ACTIVE_ENABLED);
        cacheMapper.insert(entity);
        log.info("inpatient emr template cache saved. idCache={}, templateId={}", entity.getIdCache(), templateId);
        return toClientView(cacheMapper.selectById(entity.getIdCache()), request, false);
    }

    private void updateCachedTemplateMetadata(AiInpatientEmrTemplateCache cached,
                                              InpatientEmrTemplateResolveRequest request,
                                              String templateHash) {
        boolean changed = false;
        String templateName = trimToNull(request.getTemplateName());
        if (templateName != null && !templateName.equals(cached.getTemplateName())) {
            cached.setTemplateName(templateName);
            changed = true;
        }
        if (StringUtils.hasText(templateHash) && !templateHash.equals(cached.getTemplateHash())) {
            cached.setTemplateHash(templateHash);
            changed = true;
        }
        if (StringUtils.hasText(request.getHtmlContent()) && !request.getHtmlContent().equals(cached.getHtmlContent())) {
            cached.setHtmlContent(request.getHtmlContent());
            changed = true;
        }
        if (changed) {
            cacheMapper.updateById(cached);
        }
    }

    private void updateCachedFieldsFromRequest(AiInpatientEmrTemplateCache cached,
                                               List<Map<String, Object>> requestFields) {
        if (requestFields == null || requestFields.isEmpty()) {
            return;
        }
        List<Map<String, Object>> cachedFields = parseFields(cached.getFieldsJson());
        List<Map<String, Object>> mergedFields = mergeCachedFields(requestFields, cachedFields);
        String nextFieldsJson = writeFields(mergedFields);
        Integer nextFieldCount = Integer.valueOf(mergedFields.size());
        String currentFieldsJson = StringUtils.hasText(cached.getFieldsJson()) ? cached.getFieldsJson() : "[]";
        if (!nextFieldsJson.equals(currentFieldsJson) || !nextFieldCount.equals(cached.getFieldCount())) {
            cached.setFieldsJson(nextFieldsJson);
            cached.setFieldCount(nextFieldCount);
            cacheMapper.updateById(cached);
        }
    }

    @Transactional
    public InpatientEmrTemplateCacheVO updateFieldPrompt(String idCache,
                                                        String fieldId,
                                                        InpatientEmrTemplatePromptRequest request) {
        AiInpatientEmrTemplateCache cache = requireCache(idCache);
        if (!StringUtils.hasText(fieldId)) {
            throw new BusinessException("fieldId 不能为空");
        }
        List<Map<String, Object>> fields = parseFields(cache.getFieldsJson());
        boolean updated = false;
        for (Map<String, Object> field : fields) {
            String id = stringValue(field.get("id"));
            if (!fieldId.equals(id)) {
                continue;
            }
            if (!isAiGenerationField(field)) {
                throw new BusinessException("仅支持维护 AI 辅助生成字段");
            }
            Map<String, Object> rule = ensureRule(field);
            String prompt = request == null ? null : trimToNull(request.getPrompt());
            if (prompt == null) {
                rule.remove("prompt");
            } else {
                rule.put("prompt", prompt);
            }
            String generatorInstruction = request == null ? null : trimToNull(request.getGeneratorInstruction());
            if (generatorInstruction == null) {
                rule.remove("promptGeneratorInstruction");
            } else {
                rule.put("promptGeneratorInstruction", generatorInstruction);
            }
            updated = true;
            break;
        }
        if (!updated) {
            throw new BusinessException("未找到指定模板字段");
        }
        cache.setFieldsJson(writeFields(fields));
        cache.setFieldCount(Integer.valueOf(fields.size()));
        cacheMapper.updateById(cache);
        log.info("inpatient emr template prompt updated. idCache={}, fieldId={}", idCache, fieldId);
        return toAdminView(cacheMapper.selectById(idCache), false);
    }

    @Transactional
    public InpatientEmrTemplateCacheVO updateFieldGeneration(String idCache,
                                                             String fieldId,
                                                             InpatientEmrTemplateFieldGenerationRequest request) {
        AiInpatientEmrTemplateCache cache = requireCache(idCache);
        if (!StringUtils.hasText(fieldId)) {
            throw new BusinessException("fieldId 不能为空");
        }
        if (request == null || request.getAiSuitable() == null) {
            throw new BusinessException("aiSuitable 不能为空");
        }
        List<Map<String, Object>> fields = parseFields(cache.getFieldsJson());
        boolean updated = false;
        for (Map<String, Object> field : fields) {
            if (!fieldId.equals(stringValue(field.get("id")))) {
                continue;
            }
            applyFieldGeneration(field, Boolean.TRUE.equals(request.getAiSuitable()), cache.getTemplateName());
            updated = true;
            break;
        }
        if (!updated) {
            throw new BusinessException("未找到指定模板字段");
        }
        cache.setFieldsJson(writeFields(fields));
        cache.setFieldCount(Integer.valueOf(fields.size()));
        cacheMapper.updateById(cache);
        log.info("inpatient emr template generation updated. idCache={}, fieldId={}, aiSuitable={}",
            idCache, fieldId, request.getAiSuitable());
        return toAdminView(cacheMapper.selectById(idCache), false);
    }

    public InpatientEmrTemplatePromptGenerateVO generateFieldPrompt(String idCache,
                                                                    String fieldId,
                                                                    InpatientEmrTemplatePromptGenerateRequest request) {
        AiInpatientEmrTemplateCache cache = requireCache(idCache);
        if (!StringUtils.hasText(fieldId)) {
            throw new BusinessException("fieldId 不能为空");
        }
        Map<String, Object> field = findField(parseFields(cache.getFieldsJson()), fieldId);
        if (field == null) {
            throw new BusinessException("未找到指定模板字段");
        }
        if (!isAiGenerationField(field)) {
            throw new BusinessException("请先将字段设置为 AI 生成");
        }
        Map<String, Object> rule = ensureRule(field);
        String generatorInstruction = trimToNull(request == null ? null : request.getGeneratorInstruction());
        if (generatorInstruction == null) {
            generatorInstruction = defaultPromptGeneratorInstruction(field, rule, cache.getTemplateName());
        }
        InpatientEmrTemplatePromptGenerateVO vo = new InpatientEmrTemplatePromptGenerateVO();
        vo.setGeneratorInstruction(generatorInstruction);
        vo.setPrompt(buildGeneratedPrompt(field, rule, generatorInstruction, cache.getTemplateName()));
        return vo;
    }

    @Transactional
    public InpatientEmrTemplateCacheVO enable(String idCache) {
        AiInpatientEmrTemplateCache cache = requireCache(idCache);
        cache.setSdStatus(STATUS_ENABLED);
        cacheMapper.updateById(cache);
        return toAdminView(cacheMapper.selectById(idCache), false);
    }

    @Transactional
    public InpatientEmrTemplateCacheVO disable(String idCache) {
        AiInpatientEmrTemplateCache cache = requireCache(idCache);
        cache.setSdStatus(STATUS_DISABLED);
        cacheMapper.updateById(cache);
        return toAdminView(cacheMapper.selectById(idCache), false);
    }

    @Transactional
    public void invalidate(String idCache) {
        requireCache(idCache);
        int affected = cacheMapper.deleteById(idCache);
        if (affected <= 0) {
            throw new BusinessException("模板缓存删除失败");
        }
        log.info("inpatient emr template cache invalidated. idCache={}", idCache);
    }

    private AiInpatientEmrTemplateCache findEnabledByTemplateId(String templateId) {
        List<AiInpatientEmrTemplateCache> records = cacheMapper.selectList(
            new LambdaQueryWrapper<AiInpatientEmrTemplateCache>()
                .eq(AiInpatientEmrTemplateCache::getTemplateId, templateId)
                .eq(AiInpatientEmrTemplateCache::getFgActive, ACTIVE_ENABLED)
                .eq(AiInpatientEmrTemplateCache::getSdStatus, STATUS_ENABLED)
                .orderByDesc(AiInpatientEmrTemplateCache::getUpdateTime)
        );
        return records.isEmpty() ? null : records.get(0);
    }

    private AiInpatientEmrTemplateCache requireCache(String idCache) {
        if (!StringUtils.hasText(idCache)) {
            throw new BusinessException("idCache 不能为空");
        }
        AiInpatientEmrTemplateCache cache = cacheMapper.selectById(idCache);
        if (cache == null || !ACTIVE_ENABLED.equals(cache.getFgActive())) {
            throw new BusinessException("模板缓存不存在或已删除");
        }
        return cache;
    }

    private InpatientEmrTemplateCacheVO toAdminView(AiInpatientEmrTemplateCache entity, boolean cacheHit) {
        List<Map<String, Object>> fields = enrichPromptInfo(parseFields(entity.getFieldsJson()), entity.getTemplateName());
        return toView(entity, cacheHit, fields, Integer.valueOf(fields.size()));
    }

    private InpatientEmrTemplateCacheVO toClientView(AiInpatientEmrTemplateCache entity,
                                                     InpatientEmrTemplateResolveRequest request,
                                                     boolean cacheHit) {
        List<Map<String, Object>> cachedFields = parseFields(entity.getFieldsJson());
        List<Map<String, Object>> responseFields = request == null || request.getFields() == null || request.getFields().isEmpty()
            ? enrichPromptInfo(cachedFields, entity.getTemplateName())
            : enrichPromptInfo(mergeCachedFields(request.getFields(), cachedFields), entity.getTemplateName());
        return toView(entity, cacheHit, responseFields, Integer.valueOf(responseFields.size()));
    }

    private InpatientEmrTemplateCacheVO toView(AiInpatientEmrTemplateCache entity,
                                               boolean cacheHit,
                                               List<Map<String, Object>> fields,
                                               Integer fieldCount) {
        InpatientEmrTemplateCacheVO vo = new InpatientEmrTemplateCacheVO();
        vo.setId(entity.getIdCache());
        vo.setTemplateId(entity.getTemplateId());
        vo.setTemplateHash(entity.getTemplateHash());
        vo.setTemplateName(entity.getTemplateName());
        vo.setHtmlContent(entity.getHtmlContent());
        vo.setFields(fields == null ? Collections.emptyList() : fields);
        vo.setFieldCount(fieldCount);
        vo.setSdStatus(entity.getSdStatus());
        vo.setCacheHit(Boolean.valueOf(cacheHit));
        vo.setCreatedAt(toMillis(entity.getInsertTime()));
        vo.setUpdatedAt(toMillis(entity.getUpdateTime()));
        return vo;
    }

    private String resolveTemplateId(InpatientEmrTemplateResolveRequest request) {
        if (!StringUtils.hasText(request.getTemplateId())) {
            throw new BusinessException("templateId 不能为空");
        }
        return request.getTemplateId().trim();
    }

    private String resolveTemplateHash(InpatientEmrTemplateResolveRequest request) {
        if (StringUtils.hasText(request.getTemplateHash())) {
            return request.getTemplateHash().trim();
        }
        if (!StringUtils.hasText(request.getHtmlContent())) {
            throw new BusinessException("templateHash 或 htmlContent 不能为空");
        }
        return "sha256_" + sha256(request.getHtmlContent());
    }

    private String writeFields(List<Map<String, Object>> fields) {
        try {
            return objectMapper.writeValueAsString(fields == null ? Collections.emptyList() : fields);
        } catch (JsonProcessingException ex) {
            throw new BusinessException("字段结构无法序列化");
        }
    }

    private List<Map<String, Object>> mergeCachedFields(List<Map<String, Object>> requestFields,
                                                        List<Map<String, Object>> cachedFields) {
        if (requestFields == null || requestFields.isEmpty()) {
            return copyFields(cachedFields);
        }
        Map<String, Map<String, Object>> cachedById = new LinkedHashMap<String, Map<String, Object>>();
        for (Map<String, Object> cachedField : cachedFields) {
            String id = stringValue(cachedField.get("id"));
            if (StringUtils.hasText(id)) {
                cachedById.put(id, cachedField);
            }
        }
        List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> requestField : requestFields) {
            Map<String, Object> field = copyField(requestField);
            Map<String, Object> cachedField = cachedById.get(stringValue(field.get("id")));
            if (cachedField != null) {
                mergeCachedFieldConfig(field, cachedField);
            }
            merged.add(field);
        }
        return merged;
    }

    private void mergeCachedFieldConfig(Map<String, Object> targetField, Map<String, Object> cachedField) {
        Map<String, Object> targetRule = ensureRule(targetField);
        Map<String, Object> cachedRule = readRule(cachedField);
        boolean cachedAiSuitable = isAiGenerationField(cachedField);
        targetField.put("aiSuitable", Boolean.valueOf(cachedAiSuitable));
        copyRuleValue(cachedRule, targetRule, "source");
        copyRuleValue(cachedRule, targetRule, "prompt");
        copyRuleValue(cachedRule, targetRule, "promptGeneratorInstruction");
        copyRuleValue(cachedRule, targetRule, "promptIntent");
        copyRuleValue(cachedRule, targetRule, "dependencies");
        copyRuleValue(cachedRule, targetRule, "constraints");
        if (cachedAiSuitable && !StringUtils.hasText(stringValue(targetRule.get("source")))) {
            targetRule.put("source", "ai");
        }
    }

    private void copyRuleValue(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.get(key));
        }
    }

    private List<Map<String, Object>> copyFields(List<Map<String, Object>> fields) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> field : fields) {
            result.add(copyField(field));
        }
        return result;
    }

    private boolean isAiGenerationField(Map<String, Object> field) {
        if (field == null) {
            return false;
        }
        if (isTruthy(field.get("aiSuitable"))) {
            return true;
        }
        Map<String, Object> rule = readRule(field);
        return "ai".equalsIgnoreCase(stringValue(rule.get("source")));
    }

    private boolean isTruthy(Object value) {
        if (value instanceof Boolean) {
            return ((Boolean) value).booleanValue();
        }
        if (value instanceof String) {
            return "true".equalsIgnoreCase(((String) value).trim()) || "1".equals(((String) value).trim());
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        return false;
    }

    private List<Map<String, Object>> enrichPromptInfo(List<Map<String, Object>> fields, String templateName) {
        if (fields == null || fields.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> field : fields) {
            Map<String, Object> copy = copyField(field);
            Map<String, Object> rule = ensureRule(copy);
            if (!isAiGenerationField(copy)) {
                rule.remove("resolvedPrompt");
                rule.put("promptSource", PROMPT_SOURCE_NOT_AI);
                result.add(copy);
                continue;
            }
            if (!StringUtils.hasText(stringValue(rule.get("promptGeneratorInstruction")))) {
                rule.put("promptGeneratorInstruction", defaultPromptGeneratorInstruction(copy, rule, templateName));
            }
            String customPrompt = trimToNull(stringValue(rule.get("prompt")));
            if (customPrompt != null) {
                rule.put("prompt", customPrompt);
                rule.put("resolvedPrompt", customPrompt);
                rule.put("promptSource", PROMPT_SOURCE_CUSTOM);
            } else {
                rule.put("resolvedPrompt", buildDefaultPrompt(copy, rule, templateName));
                rule.put("promptSource", PROMPT_SOURCE_DEFAULT);
            }
            result.add(copy);
        }
        return result;
    }

    private Map<String, Object> findField(List<Map<String, Object>> fields, String fieldId) {
        if (fields == null || fields.isEmpty()) {
            return null;
        }
        for (Map<String, Object> field : fields) {
            if (fieldId.equals(stringValue(field.get("id")))) {
                return field;
            }
        }
        return null;
    }

    private void applyFieldGeneration(Map<String, Object> field, boolean aiSuitable, String templateName) {
        field.put("aiSuitable", Boolean.valueOf(aiSuitable));
        Map<String, Object> rule = ensureRule(field);
        if (aiSuitable) {
            rule.put("source", "ai");
            if (!StringUtils.hasText(stringValue(rule.get("promptIntent")))) {
                rule.put("promptIntent", defaultPromptIntent(field));
            }
            if (!hasListValue(rule.get("dependencies"))) {
                rule.put("dependencies", defaultDependencies(field));
            }
            if (!hasListValue(rule.get("constraints"))) {
                rule.put("constraints", defaultConstraints());
            }
            if (!StringUtils.hasText(stringValue(rule.get("promptGeneratorInstruction")))) {
                rule.put("promptGeneratorInstruction", defaultPromptGeneratorInstruction(field, rule, templateName));
            }
            return;
        }
        if ("ai".equalsIgnoreCase(stringValue(rule.get("source")))) {
            rule.put("source", "manual_or_his");
        }
    }

    private boolean hasListValue(Object value) {
        return value instanceof List && !((List<?>) value).isEmpty();
    }

    private String defaultPromptIntent(Map<String, Object> field) {
        String text = stringValue(field.get("id")) + " " + stringValue(field.get("name")) + " " + stringValue(field.get("meaning"));
        return text.contains("出院") ? "inpatientDischargeRecordSection" : "inpatientRecordSection";
    }

    private List<String> defaultDependencies(Map<String, Object> field) {
        String text = stringValue(field.get("id")) + " " + stringValue(field.get("name")) + " " + stringValue(field.get("meaning"));
        List<String> dependencies = new ArrayList<String>();
        dependencies.add("registration");
        dependencies.add("registration.diagnoses");
        if (!text.contains("出院医嘱")) {
            dependencies.add("orders");
            dependencies.add("temperatureChart");
        }
        return dependencies;
    }

    private List<String> defaultConstraints() {
        List<String> constraints = new ArrayList<String>();
        constraints.add("仅依据已提供 HIS 数据生成，不补充未出现的检查结果或症状");
        constraints.add("围绕字段含义生成对应段落，不跨字段混写其他模板项");
        constraints.add("保留医生最终审核空间，避免给出绝对疗效判断");
        return constraints;
    }

    private String defaultPromptGeneratorInstruction(Map<String, Object> field, Map<String, Object> rule, String templateName) {
        return DEFAULT_PROMPT_GENERATOR_INSTRUCTION + "\n"
            + "模板名称：" + templateDisplayName(templateName) + "\n"
            + "记录类型：" + templateDisplayName(templateName) + "\n"
            + "字段 data-id：" + defaultString(field.get("id"), "未命名字段") + "\n"
            + "字段名称：" + defaultString(field.get("name"), defaultString(field.get("id"), "未命名字段")) + "\n"
            + "字段所属段落：" + defaultString(field.get("article"), "未标注段落") + "\n"
            + "字段含义：" + defaultString(field.get("meaning"), "模板字段") + "\n"
            + "生成意图：" + defaultString(rule.get("promptIntent"), defaultPromptIntent(field));
    }

    private String buildGeneratedPrompt(Map<String, Object> field,
                                        Map<String, Object> rule,
                                        String generatorInstruction,
                                        String templateName) {
        List<String> lines = new ArrayList<String>();
        lines.add(generatorInstruction.trim());
        lines.add("");
        lines.add("请为以下住院病历模板字段生成可直接回填的正文：");
        lines.add("模板名称：" + templateDisplayName(templateName));
        lines.add("记录类型：" + templateDisplayName(templateName));
        lines.add("字段 data-id：" + defaultString(field.get("id"), "未命名字段"));
        lines.add("字段名称：" + defaultString(field.get("name"), defaultString(field.get("id"), "未命名字段")));
        lines.add("字段所属段落：" + defaultString(field.get("article"), "未标注段落"));
        lines.add("字段含义：" + defaultString(field.get("meaning"), "模板字段，需结合模板上下文和 HIS 字段映射确认含义"));
        lines.add("生成意图：" + defaultString(rule.get("promptIntent"), defaultPromptIntent(field)));
        lines.add("依赖数据：" + joinList(rule.get("dependencies"), "住院登记、诊断、医嘱、体温单"));
        lines.add("生成约束：" + joinList(rule.get("constraints"), "仅依据已提供 HIS 数据生成"));
        lines.add("输出要求：只输出该字段应回填的正文，不输出字段名、JSON、解释、免责声明或无关内容。");
        return String.join("\n", lines);
    }

    private String buildDefaultPrompt(Map<String, Object> field, Map<String, Object> rule, String templateName) {
        List<String> lines = new ArrayList<String>();
        lines.add("模板名称：" + templateDisplayName(templateName));
        lines.add("记录类型：" + templateDisplayName(templateName));
        lines.add("字段 data-id：" + defaultString(field.get("id"), "未命名字段"));
        lines.add("字段名称：" + defaultString(field.get("name"), defaultString(field.get("id"), "未命名字段")));
        lines.add("字段所属段落：" + defaultString(field.get("article"), "未标注段落"));
        lines.add("字段含义：" + defaultString(field.get("meaning"), "模板字段，需结合模板上下文和 HIS 字段映射确认含义"));
        lines.add("生成意图：" + defaultString(rule.get("promptIntent"), "inpatientRecordSection"));
        lines.add("依赖数据：" + joinList(rule.get("dependencies"), "住院上下文"));
        lines.add("约束：" + joinList(rule.get("constraints"), "仅依据已提供 HIS 数据生成"));
        lines.add("输出要求：只生成该字段应回填的正文，不输出字段名、JSON、解释或免责声明。");
        return String.join("\n", lines);
    }

    private String templateDisplayName(String templateName) {
        String text = trimToNull(templateName);
        return text == null ? "住院病历模板" : text;
    }

    private String joinList(Object value, String fallback) {
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            List<String> parts = new ArrayList<String>();
            for (Object item : values) {
                String text = trimToNull(stringValue(item));
                if (text != null) {
                    parts.add(text);
                }
            }
            if (!parts.isEmpty()) {
                return String.join("、", parts);
            }
        }
        String text = trimToNull(stringValue(value));
        return text == null ? fallback : text;
    }

    private String defaultString(Object value, String fallback) {
        String text = trimToNull(stringValue(value));
        return text == null ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readRule(Map<String, Object> field) {
        if (field == null) {
            return Collections.emptyMap();
        }
        Object ruleValue = field.get("rule");
        if (ruleValue instanceof Map) {
            return (Map<String, Object>) ruleValue;
        }
        return Collections.emptyMap();
    }

    private Map<String, Object> copyField(Map<String, Object> field) {
        Map<String, Object> copy = new LinkedHashMap<String, Object>();
        if (field == null) {
            return copy;
        }
        copy.putAll(field);
        Object ruleValue = copy.get("rule");
        if (ruleValue instanceof Map) {
            Map<String, Object> rule = new LinkedHashMap<String, Object>(readRule(field));
            rule.remove("resolvedPrompt");
            rule.remove("promptSource");
            copy.put("rule", rule);
        }
        return copy;
    }

    private List<Map<String, Object>> parseFields(String fieldsJson) {
        if (!StringUtils.hasText(fieldsJson)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(fieldsJson, FIELD_LIST_TYPE);
        } catch (Exception ex) {
            log.warn("invalid inpatient emr template fields json", ex);
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ensureRule(Map<String, Object> field) {
        Object ruleValue = field.get("rule");
        if (ruleValue instanceof Map) {
            return (Map<String, Object>) ruleValue;
        }
        Map<String, Object> rule = new LinkedHashMap<String, Object>();
        field.put("rule", rule);
        return rule;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder();
            for (byte item : bytes) {
                builder.append(String.format("%02x", item & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new BusinessException("当前运行环境不支持 SHA-256");
        }
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Long toMillis(LocalDateTime value) {
        if (value == null) {
            return null;
        }
        return Long.valueOf(value.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
    }
}
