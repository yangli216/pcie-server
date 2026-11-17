package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplateCacheVO;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplateFieldGenerationRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplatePromptGenerateRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplatePromptGenerateVO;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplatePromptRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.InpatientEmrTemplateResolveRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.entity.AiInpatientEmrTemplateCache;
import com.regionalai.floatingball.server.modules.emrtemplate.mapper.AiInpatientEmrTemplateCacheMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InpatientEmrTemplateCacheServiceTest {

    private static final String LEGACY_DEFAULT_INSTRUCTION =
        "请根据住院病历模板字段信息，生成一个严谨、可审查、可直接用于 AI 回填该字段的提示词。"
            + "提示词必须约束 AI 仅依据住院登记、诊断、医嘱、体温单等已提供资料生成，"
            + "不得编造未提供的症状、查体、检查结果或治疗效果。\n"
            + "模板名称：日常病程记录\n记录类型：日常病程记录\n字段 data-id：病程记录文本\n"
            + "字段名称：病程记录文本\n字段所属段落：未标注段落\n字段含义：病程记录正文\n生成意图：inpatientRecordSection";
    private static final String LEGACY_BODY =
        "请为以下住院病历模板字段生成可直接回填的正文：\n"
            + "模板名称：日常病程记录\n记录类型：日常病程记录\n字段 data-id：病程记录文本\n"
            + "字段名称：病程记录文本\n字段所属段落：未标注段落\n字段含义：病程记录正文\n"
            + "生成意图：inpatientRecordSection\n依赖数据：registration\n生成约束：仅依据 HIS 数据生成\n"
            + "输出要求：只输出该字段应回填的正文，不输出字段名、JSON、解释、免责声明或无关内容。";

    @Mock
    private AiInpatientEmrTemplateCacheMapper cacheMapper;

    private InpatientEmrTemplateCacheService service;

    @BeforeEach
    void setUp() {
        service = new InpatientEmrTemplateCacheService(cacheMapper, new ObjectMapper());
    }

    @Test
    void resolveStoresAllFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<AiInpatientEmrTemplateCache> inserted = new AtomicReference<AiInpatientEmrTemplateCache>();
        when(cacheMapper.selectList(any())).thenReturn(Collections.emptyList());
        doAnswer(invocation -> {
            AiInpatientEmrTemplateCache entity = invocation.getArgument(0);
            entity.setIdCache("cache-1");
            inserted.set(entity);
            return Integer.valueOf(1);
        }).when(cacheMapper).insert(any(AiInpatientEmrTemplateCache.class));
        when(cacheMapper.selectById("cache-1")).thenAnswer(invocation -> inserted.get());

        InpatientEmrTemplateResolveRequest request = new InpatientEmrTemplateResolveRequest();
        request.setTemplateId("emr_tpl_daily_course");
        request.setTemplateHash("tpl_hash");
        request.setTemplateName("日常病程记录");
        request.setHtmlContent("<span data-id=\"病程记录文本\"></span>");
        request.setFields(sampleFields());

        InpatientEmrTemplateCacheVO result = service.resolve(request);

        assertEquals("tpl_hash", result.getTemplateHash());
        assertEquals(Integer.valueOf(2), result.getFieldCount());
        assertEquals(Boolean.FALSE, result.getCacheHit());
        assertEquals(2, result.getFields().size());
        assertEquals("病程记录文本", result.getFields().get(0).get("id"));
        List<Map<String, Object>> storedFields = objectMapper.readValue(inserted.get().getFieldsJson(), List.class);
        assertEquals(2, storedFields.size());
        assertEquals("病程记录文本", storedFields.get(0).get("id"));
        assertEquals("页眉姓名", storedFields.get(1).get("id"));
        verify(cacheMapper).insert(any(AiInpatientEmrTemplateCache.class));
    }

    @Test
    void resolveReturnsEnabledCacheBeforeParsingPayload() throws Exception {
        AiInpatientEmrTemplateCache cached = new AiInpatientEmrTemplateCache();
        cached.setIdCache("cache-2");
        cached.setTemplateId("emr_tpl_daily_course");
        cached.setTemplateHash("tpl_hash");
        cached.setTemplateName("日常病程记录");
        cached.setFieldsJson(new ObjectMapper().writeValueAsString(aiFieldsWithPrompt(null)));
        cached.setFieldCount(Integer.valueOf(1));
        cached.setFgActive("1");
        cached.setSdStatus("1");
        when(cacheMapper.selectList(any())).thenReturn(Collections.singletonList(cached));

        InpatientEmrTemplateResolveRequest request = new InpatientEmrTemplateResolveRequest();
        request.setTemplateId("emr_tpl_daily_course");
        request.setTemplateHash("tpl_hash");

        InpatientEmrTemplateCacheVO result = service.resolve(request);

        assertEquals(Boolean.TRUE, result.getCacheHit());
        assertEquals("病程记录文本", result.getFields().get(0).get("id"));
        verify(cacheMapper, never()).insert(any(AiInpatientEmrTemplateCache.class));
    }

    @Test
    void resolveUpdatesTemplateNameWhenCacheHit() throws Exception {
        AiInpatientEmrTemplateCache cached = new AiInpatientEmrTemplateCache();
        cached.setIdCache("cache-4");
        cached.setTemplateId("emr_tpl_daily_course");
        cached.setTemplateHash("tpl_hash");
        cached.setTemplateName("旧模板名");
        cached.setFieldsJson(new ObjectMapper().writeValueAsString(aiFieldsWithPrompt(null)));
        cached.setFieldCount(Integer.valueOf(1));
        cached.setFgActive("1");
        cached.setSdStatus("1");
        when(cacheMapper.selectList(any())).thenReturn(Collections.singletonList(cached));

        InpatientEmrTemplateResolveRequest request = new InpatientEmrTemplateResolveRequest();
        request.setTemplateId("emr_tpl_daily_course");
        request.setTemplateHash("tpl_hash");
        request.setTemplateName("日常病程记录");

        InpatientEmrTemplateCacheVO result = service.resolve(request);

        assertEquals("日常病程记录", result.getTemplateName());
        verify(cacheMapper).updateById(cached);
    }

    @Test
    void resolveMergesCachedPromptIntoCurrentClientFields() throws Exception {
        AiInpatientEmrTemplateCache cached = new AiInpatientEmrTemplateCache();
        cached.setIdCache("cache-5");
        cached.setTemplateId("emr_tpl_daily_course");
        cached.setTemplateHash("tpl_hash");
        cached.setTemplateName("日常病程记录");
        cached.setFieldsJson(new ObjectMapper().writeValueAsString(aiFieldsWithPrompt("请按今日住院资料生成病程记录。")));
        cached.setFieldCount(Integer.valueOf(1));
        cached.setFgActive("1");
        cached.setSdStatus("1");
        when(cacheMapper.selectList(any())).thenReturn(Collections.singletonList(cached));

        InpatientEmrTemplateResolveRequest request = new InpatientEmrTemplateResolveRequest();
        request.setTemplateId("emr_tpl_daily_course");
        request.setTemplateHash("tpl_hash");
        request.setFields(sampleFields());

        InpatientEmrTemplateCacheVO result = service.resolve(request);

        assertEquals(2, result.getFields().size());
        Map<?, ?> rule = (Map<?, ?>) result.getFields().get(0).get("rule");
        assertEquals("请按今日住院资料生成病程记录。", rule.get("prompt"));
        assertEquals("请按今日住院资料生成病程记录。", rule.get("resolvedPrompt"));
        assertEquals("custom", rule.get("promptSource"));
        assertEquals("页眉姓名", result.getFields().get(1).get("id"));
    }

    @Test
    void resolveOverridesFreshExcludedPresetWithSavedAiConfiguration() throws Exception {
        List<Map<String, Object>> cachedFields = aiFieldsWithPrompt("请根据事实书写诊断依据。");
        cachedFields.get(0).put("presetStatus", "exclude");
        AiInpatientEmrTemplateCache cached = cacheWithFields(cachedFields);
        when(cacheMapper.selectList(any())).thenReturn(Collections.singletonList(cached));
        List<Map<String, Object>> localFields = aiFieldsWithPrompt(null);
        localFields.get(0).put("aiSuitable", Boolean.FALSE);
        localFields.get(0).put("presetStatus", "exclude");
        ((Map<String, Object>) localFields.get(0).get("rule")).put("source", "manual_or_his");
        InpatientEmrTemplateResolveRequest request = new InpatientEmrTemplateResolveRequest();
        request.setTemplateId(cached.getTemplateId());
        request.setTemplateHash(cached.getTemplateHash());
        request.setFields(localFields);

        Map<String, Object> effective = service.resolve(request).getFields().get(0);

        assertEquals(Boolean.TRUE, effective.get("aiSuitable"));
        assertEquals("ai", effective.get("presetStatus"));
        assertEquals("ai", ((Map<?, ?>) effective.get("rule")).get("source"));
        assertEquals("请根据事实书写诊断依据。", ((Map<?, ?>) effective.get("rule")).get("resolvedPrompt"));
        assertEquals("exclude", localFields.get(0).get("presetStatus"), "Do not mutate the request's parser result");
        List<?> storedFields = new ObjectMapper().readValue(cached.getFieldsJson(), List.class);
        Map<?, ?> stored = (Map<?, ?>) storedFields.get(0);
        assertEquals("ai", stored.get("presetStatus"));
    }

    @Test
    void resolveHonorsExplicitFalseOverAStaleAiSource() throws Exception {
        for (Object disabled : new Object[] { Boolean.FALSE, "false", "0", Integer.valueOf(0) }) {
            List<Map<String, Object>> cachedFields = aiFieldsWithPrompt("历史提示词应保留但不得启用生成。");
            cachedFields.get(0).put("aiSuitable", disabled);
            cachedFields.get(0).put("presetStatus", "ai");
            AiInpatientEmrTemplateCache cached = cacheWithFields(cachedFields);
            when(cacheMapper.selectList(any())).thenReturn(Collections.singletonList(cached));
            InpatientEmrTemplateResolveRequest request = new InpatientEmrTemplateResolveRequest();
            request.setTemplateId(cached.getTemplateId());
            request.setTemplateHash(cached.getTemplateHash());
            request.setFields(aiFieldsWithPrompt(null));

            Map<String, Object> effective = service.resolve(request).getFields().get(0);

            assertEquals(Boolean.FALSE, effective.get("aiSuitable"));
            assertEquals("exclude", effective.get("presetStatus"));
            assertEquals("manual_or_his", ((Map<?, ?>) effective.get("rule")).get("source"));
            assertEquals("not_ai", ((Map<?, ?>) effective.get("rule")).get("promptSource"));
            assertFalse(((Map<?, ?>) effective.get("rule")).containsKey("resolvedPrompt"));
        }
    }

    @Test
    void legacyAiSourceIsUsedOnlyWhenTheAiFlagIsAbsent() throws Exception {
        List<Map<String, Object>> fields = aiFieldsWithPrompt(null);
        fields.get(0).remove("aiSuitable");
        AiInpatientEmrTemplateCache cache = cacheWithFields(fields);
        when(cacheMapper.selectById(cache.getIdCache())).thenReturn(cache);

        Map<String, Object> effective = service.get(cache.getIdCache()).getFields().get(0);

        assertEquals(Boolean.TRUE, effective.get("aiSuitable"));
        assertEquals("ai", effective.get("presetStatus"));
        assertEquals("ai", ((Map<?, ?>) effective.get("rule")).get("source"));
    }

    @Test
    void resolveRefreshesStoredFieldsToCurrentFullFieldsAndKeepsPrompt() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        List<Map<String, Object>> legacyFields = aiFieldsWithPrompt("沿用已维护提示词。");

        AiInpatientEmrTemplateCache cached = new AiInpatientEmrTemplateCache();
        cached.setIdCache("cache-7");
        cached.setTemplateId("emr_tpl_daily_course");
        cached.setTemplateHash("tpl_hash");
        cached.setTemplateName("日常病程记录");
        cached.setFieldsJson(objectMapper.writeValueAsString(legacyFields));
        cached.setFieldCount(Integer.valueOf(2));
        cached.setFgActive("1");
        cached.setSdStatus("1");
        when(cacheMapper.selectList(any())).thenReturn(Collections.singletonList(cached));

        InpatientEmrTemplateResolveRequest request = new InpatientEmrTemplateResolveRequest();
        request.setTemplateId("emr_tpl_daily_course");
        request.setTemplateHash("tpl_hash");
        request.setFields(sampleFields());

        InpatientEmrTemplateCacheVO result = service.resolve(request);

        assertEquals(2, result.getFields().size());
        Map<?, ?> responseRule = (Map<?, ?>) result.getFields().get(0).get("rule");
        assertEquals("沿用已维护提示词。", responseRule.get("prompt"));

        List<Map<String, Object>> storedFields = objectMapper.readValue(cached.getFieldsJson(), List.class);
        assertEquals(2, storedFields.size());
        assertEquals("病程记录文本", storedFields.get(0).get("id"));
        assertEquals("页眉姓名", storedFields.get(1).get("id"));
        assertEquals("沿用已维护提示词。", ((Map<?, ?>) storedFields.get(0).get("rule")).get("prompt"));
        verify(cacheMapper).updateById(cached);
    }

    @Test
    void updateFieldPromptWritesPromptIntoRule() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AiInpatientEmrTemplateCache cache = new AiInpatientEmrTemplateCache();
        cache.setIdCache("cache-3");
        cache.setTemplateId("emr_tpl_daily_course");
        cache.setTemplateHash("tpl_hash");
        cache.setFieldsJson(objectMapper.writeValueAsString(sampleFields()));
        cache.setFieldCount(Integer.valueOf(1));
        cache.setFgActive("1");
        cache.setSdStatus("1");
        when(cacheMapper.selectById("cache-3")).thenReturn(cache);

        InpatientEmrTemplatePromptRequest request = new InpatientEmrTemplatePromptRequest();
        request.setPrompt("仅依据住院登记、医嘱和体温单生成日常病程记录。");

        InpatientEmrTemplateCacheVO result = service.updateFieldPrompt("cache-3", "病程记录文本", request);

        Map<?, ?> rule = (Map<?, ?>) result.getFields().get(0).get("rule");
        assertEquals("仅依据住院登记、医嘱和体温单生成日常病程记录。", rule.get("prompt"));
        assertEquals("仅依据住院登记、医嘱和体温单生成日常病程记录。", rule.get("resolvedPrompt"));
        assertEquals("custom", rule.get("promptSource"));
        assertEquals(2, result.getFields().size());
        verify(cacheMapper).updateById(cache);
    }

    @Test
    void updateFieldGenerationCanPromoteNonAiField() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AiInpatientEmrTemplateCache cache = new AiInpatientEmrTemplateCache();
        cache.setIdCache("cache-8");
        cache.setTemplateId("emr_tpl_daily_course");
        cache.setTemplateHash("tpl_hash");
        cache.setFieldsJson(objectMapper.writeValueAsString(sampleFields()));
        cache.setFieldCount(Integer.valueOf(2));
        cache.setFgActive("1");
        cache.setSdStatus("1");
        when(cacheMapper.selectById("cache-8")).thenReturn(cache);

        InpatientEmrTemplateFieldGenerationRequest request = new InpatientEmrTemplateFieldGenerationRequest();
        request.setAiSuitable(Boolean.TRUE);

        InpatientEmrTemplateCacheVO result = service.updateFieldGeneration("cache-8", "页眉姓名", request);

        Map<String, Object> promoted = result.getFields().get(1);
        assertEquals(Boolean.TRUE, promoted.get("aiSuitable"));
        assertEquals("ai", promoted.get("presetStatus"));
        assertEquals("ai", ((Map<?, ?>) promoted.get("rule")).get("source"));
        assertEquals("default", ((Map<?, ?>) promoted.get("rule")).get("promptSource"));
        assertTrue(String.valueOf(((Map<?, ?>) promoted.get("rule")).get("resolvedPrompt")).contains("字段 data-id：页眉姓名"));
        verify(cacheMapper).updateById(cache);
    }

    @Test
    void disablingAiClearsItsEffectivePresetAndSource() throws Exception {
        AiInpatientEmrTemplateCache cache = cacheWithFields(aiFieldsWithPrompt(null));
        when(cacheMapper.selectById(cache.getIdCache())).thenReturn(cache);
        InpatientEmrTemplateFieldGenerationRequest request = new InpatientEmrTemplateFieldGenerationRequest();
        request.setAiSuitable(Boolean.FALSE);

        Map<String, Object> effective = service.updateFieldGeneration(cache.getIdCache(), "病程记录文本", request).getFields().get(0);

        assertEquals(Boolean.FALSE, effective.get("aiSuitable"));
        assertEquals("exclude", effective.get("presetStatus"));
        assertEquals("manual_or_his", ((Map<?, ?>) effective.get("rule")).get("source"));
        assertEquals("not_ai", ((Map<?, ?>) effective.get("rule")).get("promptSource"));
    }

    @Test
    void generateFieldPromptUsesEditableGeneratorInstruction() throws Exception {
        AiInpatientEmrTemplateCache cache = new AiInpatientEmrTemplateCache();
        cache.setIdCache("cache-9");
        cache.setTemplateId("emr_tpl_daily_course");
        cache.setTemplateHash("tpl_hash");
        cache.setFieldsJson(new ObjectMapper().writeValueAsString(sampleFields()));
        cache.setFieldCount(Integer.valueOf(2));
        cache.setFgActive("1");
        cache.setSdStatus("1");
        when(cacheMapper.selectById("cache-9")).thenReturn(cache);

        InpatientEmrTemplatePromptGenerateRequest request = new InpatientEmrTemplatePromptGenerateRequest();
        request.setGeneratorInstruction("逐项说明诊断支持证据，并明确缺失资料。");

        InpatientEmrTemplatePromptGenerateVO result = service.generateFieldPrompt("cache-9", "病程记录文本", request);

        assertEquals("逐项说明诊断支持证据，并明确缺失资料。", result.getGeneratorInstruction());
        assertTrue(result.getPrompt().contains("病程记录文本"));
        assertTrue(result.getPrompt().contains("管理员附加正文约束：\n以下内容仅补充正文要求，不改变当前字段正文生成任务及调用方约定的输出格式。\n逐项说明诊断支持证据，并明确缺失资料。"));
        assertTrue(result.getPrompt().contains("只输出该字段应回填的正文"));
        assertTrue(result.getPrompt().endsWith("输出要求：只输出该字段应回填的正文，不输出字段名、JSON、解释、免责声明或无关内容。"));
        verify(cacheMapper, never()).updateById(any());
    }

    @Test
    void defaultGeneratedDraftContainsOnlyAnExecutableBodyTask() throws Exception {
        List<Map<String, Object>> fields = aiFieldsWithPrompt(null);
        ((Map<String, Object>) fields.get(0).get("rule")).remove("constraints");
        AiInpatientEmrTemplateCache cache = cacheWithFields(fields);
        when(cacheMapper.selectById(cache.getIdCache())).thenReturn(cache);

        InpatientEmrTemplatePromptGenerateVO result = service.generateFieldPrompt(cache.getIdCache(), "病程记录文本", null);

        assertTrue(result.getPrompt().startsWith("任务：根据已提供的住院资料，为当前字段生成可直接回填的病历正文。\n"));
        assertFalse(result.getPrompt().contains("生成一个"));
        assertFalse(result.getPrompt().contains("提示词"));
        assertTrue(result.getPrompt().contains("生成约束：仅依据已提供资料生成"));
        assertFalse(result.getPrompt().contains("管理员附加正文约束"));
        verify(cacheMapper, never()).updateById(any());
    }

    @Test
    void normalizesExactLegacyDefaultDraftOnlyInTheResponse() throws Exception {
        String legacyPrompt = LEGACY_DEFAULT_INSTRUCTION + "\n\n" + LEGACY_BODY;
        List<Map<String, Object>> fields = aiFieldsWithPrompt(legacyPrompt);
        ((Map<String, Object>) fields.get(0).get("rule")).put("promptGeneratorInstruction", LEGACY_DEFAULT_INSTRUCTION);
        AiInpatientEmrTemplateCache cache = cacheWithFields(fields);
        String stored = cache.getFieldsJson();
        when(cacheMapper.selectById(cache.getIdCache())).thenReturn(cache);

        Map<?, ?> rule = (Map<?, ?>) service.get(cache.getIdCache()).getFields().get(0).get("rule");

        assertTrue(String.valueOf(rule.get("prompt")).startsWith("任务：根据已提供的住院资料"));
        assertFalse(String.valueOf(rule.get("resolvedPrompt")).contains("提示词"));
        assertEquals(rule.get("prompt"), rule.get("resolvedPrompt"));
        assertEquals("custom", rule.get("promptSource"));
        assertFalse(String.valueOf(rule.get("promptGeneratorInstruction")).contains("提示词"));
        assertEquals(stored, cache.getFieldsJson());
        verify(cacheMapper, never()).updateById(any());
    }

    @Test
    void resolvingLegacyDraftDoesNotRewriteItsSavedPrompt() throws Exception {
        String legacyPrompt = LEGACY_DEFAULT_INSTRUCTION + "\n\n" + LEGACY_BODY;
        AiInpatientEmrTemplateCache cached = cacheWithFields(aiFieldsWithPrompt(legacyPrompt));
        when(cacheMapper.selectList(any())).thenReturn(Collections.singletonList(cached));
        InpatientEmrTemplateResolveRequest request = new InpatientEmrTemplateResolveRequest();
        request.setTemplateId(cached.getTemplateId());
        request.setTemplateHash(cached.getTemplateHash());
        request.setFields(aiFieldsWithPrompt(null));

        Map<?, ?> rule = (Map<?, ?>) service.resolve(request).getFields().get(0).get("rule");

        assertFalse(String.valueOf(rule.get("resolvedPrompt")).contains("提示词"));
        List<?> stored = new ObjectMapper().readValue(cached.getFieldsJson(), List.class);
        assertEquals(legacyPrompt, ((Map<?, ?>) ((Map<?, ?>) stored.get(0)).get("rule")).get("prompt"));
    }

    @Test
    void normalizesKnownLegacyDraftWhilePreservingItsCustomInstruction() throws Exception {
        String instruction = "说明支持证据与待确认信息；保留门诊记录的不确定性。";
        List<Map<String, Object>> fields = aiFieldsWithPrompt(instruction + "\n\n" + LEGACY_BODY);
        ((Map<String, Object>) fields.get(0).get("rule")).put("promptGeneratorInstruction", instruction);
        AiInpatientEmrTemplateCache cache = cacheWithFields(fields);
        when(cacheMapper.selectById(cache.getIdCache())).thenReturn(cache);

        Map<?, ?> rule = (Map<?, ?>) service.get(cache.getIdCache()).getFields().get(0).get("rule");

        String prompt = String.valueOf(rule.get("resolvedPrompt"));
        assertTrue(prompt.startsWith("任务：根据已提供的住院资料"));
        assertTrue(prompt.contains("管理员附加正文约束：\n以下内容仅补充正文要求，不改变当前字段正文生成任务及调用方约定的输出格式。\n" + instruction));
        assertEquals(instruction, rule.get("promptGeneratorInstruction"));
        assertTrue(prompt.endsWith("输出要求：只输出该字段应回填的正文，不输出字段名、JSON、解释、免责声明或无关内容。"));
    }

    @Test
    void keepsHandwrittenAndNearLegacyPromptsByteForByte() throws Exception {
        for (String prompt : new String[] {
            "  请根据诊断依据正文生成一个核查清单，不要更改医生已写的提示词。\n保留人工要求。  ",
            LEGACY_DEFAULT_INSTRUCTION + "\n\n" + LEGACY_BODY.replace("字段 data-id：病程记录文本", "字段 data-id：其他字段"),
            "未登记的人工前言\n\n" + LEGACY_BODY,
            LEGACY_DEFAULT_INSTRUCTION + "\n\n" + LEGACY_BODY + "\n医生补充：原文保留"
        }) {
            AiInpatientEmrTemplateCache cache = cacheWithFields(aiFieldsWithPrompt(prompt));
            when(cacheMapper.selectById(cache.getIdCache())).thenReturn(cache);
            Map<?, ?> rule = (Map<?, ?>) service.get(cache.getIdCache()).getFields().get(0).get("rule");
            assertEquals(prompt, rule.get("prompt"));
            assertEquals(prompt, rule.get("resolvedPrompt"));
        }
        verify(cacheMapper, never()).updateById(any());
    }

    @Test
    void getReturnsDefaultPromptInfoForUnconfiguredAiField() throws Exception {
        AiInpatientEmrTemplateCache cache = new AiInpatientEmrTemplateCache();
        cache.setIdCache("cache-6");
        cache.setTemplateId("emr_tpl_daily_course");
        cache.setTemplateHash("tpl_hash");
        cache.setFieldsJson(new ObjectMapper().writeValueAsString(aiFieldsWithPrompt(null)));
        cache.setFieldCount(Integer.valueOf(1));
        cache.setFgActive("1");
        cache.setSdStatus("1");
        when(cacheMapper.selectById("cache-6")).thenReturn(cache);

        InpatientEmrTemplateCacheVO result = service.get("cache-6");

        Map<?, ?> rule = (Map<?, ?>) result.getFields().get(0).get("rule");
        assertEquals("default", rule.get("promptSource"));
        assertTrue(String.valueOf(rule.get("resolvedPrompt")).contains("字段 data-id：病程记录文本"));
        assertFalse(((Map<?, ?>) result.getFields().get(0).get("rule")).containsKey("prompt"));
    }

    @Test
    void invalidateMarksCacheInactive() throws Exception {
        AiInpatientEmrTemplateCache cache = new AiInpatientEmrTemplateCache();
        cache.setIdCache("cache-delete");
        cache.setTemplateId("emr_tpl_daily_course");
        cache.setTemplateHash("tpl_hash");
        cache.setFieldsJson(new ObjectMapper().writeValueAsString(sampleFields()));
        cache.setFieldCount(Integer.valueOf(2));
        cache.setFgActive("1");
        cache.setSdStatus("1");
        when(cacheMapper.selectById("cache-delete")).thenReturn(cache);
        when(cacheMapper.deleteById("cache-delete")).thenReturn(Integer.valueOf(1));

        service.invalidate("cache-delete");

        verify(cacheMapper).deleteById("cache-delete");
    }

    @Test
    void invalidateFailsWhenDeleteDoesNotAffectRows() throws Exception {
        AiInpatientEmrTemplateCache cache = new AiInpatientEmrTemplateCache();
        cache.setIdCache("cache-delete-failed");
        cache.setTemplateId("emr_tpl_daily_course");
        cache.setTemplateHash("tpl_hash");
        cache.setFieldsJson(new ObjectMapper().writeValueAsString(sampleFields()));
        cache.setFieldCount(Integer.valueOf(2));
        cache.setFgActive("1");
        cache.setSdStatus("1");
        when(cacheMapper.selectById("cache-delete-failed")).thenReturn(cache);
        when(cacheMapper.deleteById("cache-delete-failed")).thenReturn(Integer.valueOf(0));

        assertThrows(BusinessException.class, () -> service.invalidate("cache-delete-failed"));
    }

    private AiInpatientEmrTemplateCache cacheWithFields(List<Map<String, Object>> fields) throws Exception {
        AiInpatientEmrTemplateCache cache = new AiInpatientEmrTemplateCache();
        cache.setIdCache("cache-config-regression");
        cache.setTemplateId("emr_tpl_daily_course");
        cache.setTemplateHash("tpl_hash");
        cache.setTemplateName("日常病程记录");
        cache.setFieldsJson(new ObjectMapper().writeValueAsString(fields));
        cache.setFieldCount(Integer.valueOf(fields.size()));
        cache.setFgActive("1");
        cache.setSdStatus("1");
        return cache;
    }

    private List<Map<String, Object>> sampleFields() {
        List<Map<String, Object>> fields = aiFieldsWithPrompt(null);

        Map<String, Object> nonAiRule = new LinkedHashMap<String, Object>();
        nonAiRule.put("source", "his_or_system");
        nonAiRule.put("constraints", Collections.singletonList("按 HIS 事实填充"));

        Map<String, Object> nonAiField = new LinkedHashMap<String, Object>();
        nonAiField.put("id", "页眉姓名");
        nonAiField.put("name", "页眉姓名");
        nonAiField.put("meaning", "患者姓名");
        nonAiField.put("type", "text");
        nonAiField.put("readonly", Boolean.FALSE);
        nonAiField.put("key", Boolean.FALSE);
        nonAiField.put("aiSuitable", Boolean.FALSE);
        nonAiField.put("rule", nonAiRule);
        fields.add(nonAiField);

        return fields;
    }

    private List<Map<String, Object>> aiFieldsWithPrompt(String prompt) {
        Map<String, Object> rule = new LinkedHashMap<String, Object>();
        rule.put("source", "ai");
        rule.put("constraints", Collections.singletonList("仅依据 HIS 数据生成"));
        rule.put("dependencies", Collections.singletonList("registration"));
        rule.put("promptIntent", "inpatientRecordSection");
        if (prompt != null) {
            rule.put("prompt", prompt);
        }

        Map<String, Object> field = new LinkedHashMap<String, Object>();
        field.put("id", "病程记录文本");
        field.put("name", "病程记录文本");
        field.put("meaning", "病程记录正文");
        field.put("type", "text");
        field.put("readonly", Boolean.FALSE);
        field.put("key", Boolean.FALSE);
        field.put("aiSuitable", Boolean.TRUE);
        field.put("rule", rule);

        List<Map<String, Object>> fields = new ArrayList<Map<String, Object>>();
        fields.add(field);
        return fields;
    }
}
