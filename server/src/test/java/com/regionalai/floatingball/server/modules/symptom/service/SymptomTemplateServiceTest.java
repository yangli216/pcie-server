package com.regionalai.floatingball.server.modules.symptom.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.datapackage.dto.TemplateDeltaVO;
import com.regionalai.floatingball.server.modules.datapackage.service.BuiltinTemplateSeedService;
import com.regionalai.floatingball.server.modules.datapackage.service.DataPackageService;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.symptom.dto.BuiltinSymptomImportResultVO;
import com.regionalai.floatingball.server.modules.symptom.dto.JsonSymptomImportRequest;
import com.regionalai.floatingball.server.modules.symptom.dto.SymptomTemplateVO;
import com.regionalai.floatingball.server.modules.symptom.entity.AiSymptomTemplate;
import com.regionalai.floatingball.server.modules.symptom.mapper.AiSymptomTemplateMapper;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class SymptomTemplateServiceTest {

    @Mock
    private AiSymptomTemplateMapper aiSymptomTemplateMapper;

    @Mock
    private DataPackageService dataPackageService;

    @Mock
    private SymptomTemplateChangeLogService changeLogService;

    private SymptomTemplateService symptomTemplateService;

    @BeforeEach
    void setUp() {
        AdminContextHolder.clear();
        Configuration configuration = new Configuration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, AiSymptomTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        symptomTemplateService = new SymptomTemplateService(
            aiSymptomTemplateMapper,
            objectMapper,
            new BuiltinTemplateSeedService(objectMapper),
            dataPackageService,
            changeLogService,
            new DatabaseDialect(DatabaseDialect.Kind.ORACLE)
        );
    }

    @Test
    void listShouldOnlyQueryActiveTemplates() {
        AiSymptomTemplate active = buildTemplate("T1", "fever", "发热", "western", null, null, 1, LocalDateTime.now());
        Page<AiSymptomTemplate> mapperResult = new Page<AiSymptomTemplate>(1, 100, 1);
        mapperResult.setRecords(Collections.singletonList(active));
        when(aiSymptomTemplateMapper.selectPage(any(Page.class), any(LambdaQueryWrapper.class))).thenReturn(mapperResult);

        PageResponse<SymptomTemplateVO> response = symptomTemplateService.list(1, 500, null, "western", null, "1", null, null);

        assertEquals(1L, response.getTotal());
        assertEquals("fever", response.getRecords().get(0).getKey());
        ArgumentCaptor<LambdaQueryWrapper<AiSymptomTemplate>> wrapperCaptor = ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(aiSymptomTemplateMapper).selectPage(any(Page.class), wrapperCaptor.capture());
        String sqlSegment = wrapperCaptor.getValue().getSqlSegment();
        assertTrue(sqlSegment.contains("fg_active"));
        assertTrue(sqlSegment.contains("sd_status"));
    }

    @Test
    void getClientDeltaShouldPreferOrgScopeAndMergeBySymptomKey() {
        AiSymptomTemplate globalFever = buildTemplate("T1", "fever", "全局发热", "western", null, null, 2, LocalDateTime.of(2026, 4, 20, 10, 0));
        AiSymptomTemplate orgFever = buildTemplate("T2", "fever", "机构发热", "western", "ORG001", null, 1, LocalDateTime.of(2026, 4, 21, 10, 0));
        AiSymptomTemplate globalCough = buildTemplate("T3", "cough", "全局咳嗽", "western", null, null, 3, LocalDateTime.of(2026, 4, 19, 10, 0));

        when(aiSymptomTemplateMapper.selectList(any()))
            .thenReturn(Arrays.asList(globalFever, orgFever, globalCough))
            .thenReturn(Collections.<AiSymptomTemplate>emptyList());

        TemplateDeltaVO delta = symptomTemplateService.getClientDelta("ORG001", "REG001", null);

        assertTrue(delta.getVersion().startsWith("symptom-"));
        assertEquals(2, delta.getWestern().size());
        LinkedHashMap<?, ?> fever = (LinkedHashMap<?, ?>) delta.getWestern().get(0);
        LinkedHashMap<?, ?> cough = (LinkedHashMap<?, ?>) delta.getWestern().get(1);
        assertEquals("机构发热", fever.get("name"));
        assertEquals("fever", fever.get("key"));
        assertEquals("全局咳嗽", cough.get("name"));
        assertTrue(delta.getTcm().isEmpty());
    }

    @Test
    void getClientDeltaShouldFallbackToLegacyTemplatePackageWhenSymptomTableEmpty() {
        TemplateDeltaVO legacy = new TemplateDeltaVO();
        legacy.setVersion("legacy-1");
        legacy.setWestern(Collections.<Object>singletonList(Collections.singletonMap("key", "legacy")));

        when(aiSymptomTemplateMapper.selectList(any()))
            .thenReturn(Collections.<AiSymptomTemplate>emptyList())
            .thenReturn(Collections.<AiSymptomTemplate>emptyList());
        when(dataPackageService.getTemplateDelta("ORG001", "REG001", null)).thenReturn(legacy);

        TemplateDeltaVO delta = symptomTemplateService.getClientDelta("ORG001", "REG001", "0");

        assertTrue(delta.getVersion().startsWith("legacy-1-compat-"));
        assertEquals(1, delta.getWestern().size());
        verify(dataPackageService).getTemplateDelta("ORG001", "REG001", null);
    }

    @Test
    void getClientDeltaShouldNormalizeFallbackTemplatePackageWhenSymptomTableEmpty() {
        TemplateDeltaVO legacy = new TemplateDeltaVO();
        legacy.setVersion("legacy-1");
        legacy.setWestern(Collections.<Object>singletonList(buildSectionScopedTemplate()));

        when(aiSymptomTemplateMapper.selectList(any()))
            .thenReturn(Collections.<AiSymptomTemplate>emptyList())
            .thenReturn(Collections.<AiSymptomTemplate>emptyList());
        when(dataPackageService.getTemplateDelta("ORG001", "REG001", null)).thenReturn(legacy);

        TemplateDeltaVO delta = symptomTemplateService.getClientDelta("ORG001", "REG001", "0");

        assertTrue(delta.getVersion().startsWith("legacy-1-compat-"));
        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) delta.getWestern().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> applicablePopulation = (Map<String, Object>) template.get("applicablePopulation");
        assertEquals(Collections.singletonList("2"), applicablePopulation.get("genders"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) template.get("config");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) config.get("sections");
        assertNull(sections.get(0).get("applicablePopulation"));
    }

    @Test
    void saveShouldRejectDuplicateSymptomKeyInSameScope() {
        SymptomTemplateVO request = new SymptomTemplateVO();
        request.setMedicalMode("western");
        request.setKey("fever");
        request.setName("发热");
        request.setConfig(Collections.<String, Object>singletonMap("sections", Collections.emptyList()));

        AiSymptomTemplate existing = buildTemplate("T1", "fever", "已存在", "western", null, null, 1, LocalDateTime.now());
        when(aiSymptomTemplateMapper.selectOne(any())).thenReturn(existing);

        BusinessException ex = assertThrows(BusinessException.class, () -> symptomTemplateService.save(request));

        assertEquals("同作用域下已存在相同症状 Key", ex.getMessage());
        verify(aiSymptomTemplateMapper, never()).insert(any(AiSymptomTemplate.class));
    }

    @Test
    void importJsonShouldSupportArrayTemplates() {
        JsonSymptomImportRequest request = new JsonSymptomImportRequest();
        request.setMedicalMode("western");
        request.setOverwriteExisting(Boolean.TRUE);
        request.setContentJson("[{\"key\":\"fever\",\"name\":\"发热\",\"config\":{\"sections\":[]}},{\"key\":\"cough\",\"name\":\"咳嗽\",\"config\":{\"sections\":[]}}]");

        when(aiSymptomTemplateMapper.selectOne(any())).thenReturn(null);

        BuiltinSymptomImportResultVO result = symptomTemplateService.importJson(request);

        assertEquals("western", result.getMedicalMode());
        assertEquals(2, result.getCreatedCount());
        assertEquals(0, result.getUpdatedCount());
        verify(aiSymptomTemplateMapper, times(2)).insert(any(AiSymptomTemplate.class));
        verify(changeLogService, times(2)).record(
            org.mockito.ArgumentMatchers.eq(SymptomTemplateChangeLogService.OPERATION_IMPORT_JSON),
            org.mockito.ArgumentMatchers.isNull(),
            any(SymptomTemplateVO.class),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void importJsonShouldSupportSymptomsWrapperObject() {
        JsonSymptomImportRequest request = new JsonSymptomImportRequest();
        request.setMedicalMode("tcm");
        request.setOverwriteExisting(Boolean.TRUE);
        request.setContentJson("{\"version\":\"1.0.0\",\"symptoms\":[{\"key\":\"tcm_headache\",\"name\":\"头痛\",\"config\":{\"sections\":[]}}]}");

        AiSymptomTemplate existing = buildTemplate("T1", "tcm_headache", "旧头痛", "tcm", null, null, 1, LocalDateTime.now());
        when(aiSymptomTemplateMapper.selectOne(any())).thenReturn(existing);

        BuiltinSymptomImportResultVO result = symptomTemplateService.importJson(request);

        assertEquals("tcm", result.getMedicalMode());
        assertEquals(0, result.getCreatedCount());
        assertEquals(1, result.getUpdatedCount());
        verify(aiSymptomTemplateMapper, times(1)).updateById(any(AiSymptomTemplate.class));
        verify(changeLogService).record(
            org.mockito.ArgumentMatchers.eq(SymptomTemplateChangeLogService.OPERATION_IMPORT_JSON),
            any(SymptomTemplateVO.class),
            any(SymptomTemplateVO.class),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void saveShouldRecordCreateChangeLogWithCurrentAdmin() {
        AdminCurrentUser admin = new AdminCurrentUser();
        admin.setIdUser("USER001");
        admin.setCdUser("admin");
        admin.setNaUser("系统管理员");
        AdminContextHolder.set(admin);

        SymptomTemplateVO request = new SymptomTemplateVO();
        request.setMedicalMode("western");
        request.setKey("fever");
        request.setName("发热");
        request.setConfig(Collections.<String, Object>singletonMap("sections", Collections.emptyList()));

        when(aiSymptomTemplateMapper.selectOne(any())).thenReturn(null);

        symptomTemplateService.save(request);

        verify(aiSymptomTemplateMapper).insert(any(AiSymptomTemplate.class));
        verify(changeLogService).record(
            org.mockito.ArgumentMatchers.eq(SymptomTemplateChangeLogService.OPERATION_CREATE),
            org.mockito.ArgumentMatchers.isNull(),
            any(SymptomTemplateVO.class),
            org.mockito.ArgumentMatchers.same(admin)
        );
        AdminContextHolder.clear();
    }

    @Test
    void updateShouldRecordBeforeAndAfterSnapshots() {
        AiSymptomTemplate existing = buildTemplate("T1", "fever", "发热", "western", null, null, 1, LocalDateTime.now());
        SymptomTemplateVO request = new SymptomTemplateVO();
        request.setMedicalMode("western");
        request.setKey("fever");
        request.setName("高热");
        request.setConfig(Collections.<String, Object>singletonMap("sections", Collections.emptyList()));
        request.setSdStatus("1");

        when(aiSymptomTemplateMapper.selectById("T1")).thenReturn(existing);
        when(aiSymptomTemplateMapper.selectOne(any())).thenReturn(null);

        symptomTemplateService.update("T1", request);

        verify(aiSymptomTemplateMapper).updateById(existing);
        verify(changeLogService).record(
            org.mockito.ArgumentMatchers.eq(SymptomTemplateChangeLogService.OPERATION_UPDATE),
            org.mockito.ArgumentMatchers.argThat(before -> "发热".equals(before.getName())),
            org.mockito.ArgumentMatchers.argThat(after -> "高热".equals(after.getName())),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void invalidateShouldRecordDeleteChangeLog() {
        AiSymptomTemplate existing = buildTemplate("T1", "fever", "发热", "western", null, null, 1, LocalDateTime.now());
        when(aiSymptomTemplateMapper.selectById("T1")).thenReturn(existing);
        when(aiSymptomTemplateMapper.update(org.mockito.ArgumentMatchers.isNull(), any(LambdaUpdateWrapper.class)))
            .thenReturn(Integer.valueOf(1));

        symptomTemplateService.invalidate("T1");

        ArgumentCaptor<LambdaUpdateWrapper<AiSymptomTemplate>> wrapperCaptor = ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(aiSymptomTemplateMapper).update(org.mockito.ArgumentMatchers.isNull(), wrapperCaptor.capture());
        assertTrue(wrapperCaptor.getValue().getSqlSet().contains("fg_active"));
        assertTrue(wrapperCaptor.getValue().getSqlSegment().contains("id_template"));
        verify(changeLogService).record(
            org.mockito.ArgumentMatchers.eq(SymptomTemplateChangeLogService.OPERATION_DELETE),
            any(SymptomTemplateVO.class),
            org.mockito.ArgumentMatchers.isNull(),
            org.mockito.ArgumentMatchers.isNull()
        );
    }

    @Test
    void invalidateShouldFailWhenSoftDeleteDoesNotAffectRows() {
        AiSymptomTemplate existing = buildTemplate("T1", "fever", "发热", "western", null, null, 1, LocalDateTime.now());
        when(aiSymptomTemplateMapper.selectById("T1")).thenReturn(existing);
        when(aiSymptomTemplateMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(Integer.valueOf(0));

        BusinessException ex = assertThrows(BusinessException.class, () -> symptomTemplateService.invalidate("T1"));

        assertEquals("症状模板不存在", ex.getMessage());
        verify(changeLogService, never()).record(
            org.mockito.ArgumentMatchers.anyString(),
            any(SymptomTemplateVO.class),
            any(SymptomTemplateVO.class),
            org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    void getClientDeltaShouldPromoteSectionApplicablePopulationToSymptomLevel() {
        AiSymptomTemplate nipplePain = buildTemplate("T1", "nipple-pain", "乳头痛", "western", null, null, 1, LocalDateTime.now());
        nipplePain.setConfigJson("{\"title\":\"\",\"sections\":[{\"id\":\"nipple-pain_section_0\",\"title\":\"症状属性问诊\",\"applicablePopulation\":{\"genders\":[\"2\"]},\"fields\":[{\"id\":\"nipple-pain_field_0\",\"key\":\"onsetTime\",\"label\":\"发病时间\",\"type\":\"input_radio\",\"props\":{\"placeholder\":\"请输入\",\"radioOptions\":[\"小时\",\"天\"]},\"storageKey\":\"onsetTime\"}]}]}");
        nipplePain.setApplicablePopulationJson("{\"genders\":[],\"ageGroups\":[]}");

        when(aiSymptomTemplateMapper.selectList(any()))
            .thenReturn(Collections.singletonList(nipplePain))
            .thenReturn(Collections.<AiSymptomTemplate>emptyList());

        TemplateDeltaVO delta = symptomTemplateService.getClientDelta("ORG001", "REG001", null);

        @SuppressWarnings("unchecked")
        Map<String, Object> template = (Map<String, Object>) delta.getWestern().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> applicablePopulation = (Map<String, Object>) template.get("applicablePopulation");
        assertEquals(Collections.singletonList("2"), applicablePopulation.get("genders"));
        @SuppressWarnings("unchecked")
        Map<String, Object> config = (Map<String, Object>) template.get("config");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sections = (List<Map<String, Object>>) config.get("sections");
        assertNull(sections.get(0).get("applicablePopulation"));
    }

    private AiSymptomTemplate buildTemplate(String id,
                                            String key,
                                            String name,
                                            String medicalMode,
                                            String idOrg,
                                            String idRegion,
                                            int sortOrder,
                                            LocalDateTime updateTime) {
        AiSymptomTemplate item = new AiSymptomTemplate();
        item.setIdTemplate(id);
        item.setCdSymptom(key);
        item.setNaSymptom(name);
        item.setSdMedicalMode(medicalMode);
        item.setDesSymptom(name + "-desc");
        item.setFgCommon("1");
        item.setSortOrder(Integer.valueOf(sortOrder));
        item.setSystemCategoryJson("[\"nervous\"]");
        item.setBodyPartsJson("[]");
        item.setCustomScript("");
        item.setApplicablePopulationJson("{\"genders\":[],\"ageGroups\":[]}");
        item.setConfigJson("{\"title\":\"智能问诊\",\"sections\":[]}");
        item.setIdOrg(idOrg);
        item.setIdRegion(idRegion);
        item.setSdStatus("1");
        item.setFgActive("1");
        item.setInsertTime(updateTime.minusHours(1));
        item.setUpdateTime(updateTime);
        return item;
    }

    private Map<String, Object> buildSectionScopedTemplate() {
        Map<String, Object> field = new LinkedHashMap<String, Object>();
        field.put("id", "nipple-pain_field_0");
        field.put("key", "onsetTime");
        field.put("label", "发病时间");
        field.put("type", "input_radio");
        field.put("props", Collections.<String, Object>singletonMap("radioOptions", Arrays.asList("小时", "天")));
        field.put("storageKey", "onsetTime");

        Map<String, Object> section = new LinkedHashMap<String, Object>();
        section.put("id", "nipple-pain_section_0");
        section.put("title", "症状属性问诊");
        section.put("applicablePopulation", Collections.<String, Object>singletonMap("genders", Collections.singletonList("2")));
        section.put("fields", Collections.singletonList(field));

        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("sections", Collections.singletonList(section));

        Map<String, Object> template = new LinkedHashMap<String, Object>();
        template.put("key", "nipple-pain");
        template.put("name", "乳头痛");
        template.put("applicablePopulation", Collections.<String, Object>singletonMap("genders", Collections.emptyList()));
        template.put("config", config);
        return template;
    }
}
