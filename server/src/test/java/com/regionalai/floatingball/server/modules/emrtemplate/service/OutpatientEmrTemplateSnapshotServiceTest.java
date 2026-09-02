package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateDictionaryItemSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateFieldSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateMappingRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateParseSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotReceipt;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotResolution;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotResolveRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotVO;
import com.regionalai.floatingball.server.modules.emrtemplate.entity.AiOutpatientEmrTemplateMapping;
import com.regionalai.floatingball.server.modules.emrtemplate.entity.AiOutpatientEmrTemplateSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.mapper.AiOutpatientEmrTemplateMappingMapper;
import com.regionalai.floatingball.server.modules.emrtemplate.mapper.AiOutpatientEmrTemplateSnapshotMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutpatientEmrTemplateSnapshotServiceTest {

    @Mock
    private AiOutpatientEmrTemplateSnapshotMapper snapshotMapper;

    @Mock
    private AiOutpatientEmrTemplateMappingMapper mappingMapper;

    private ObjectMapper objectMapper;
    private OutpatientEmrTemplateSnapshotService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        service = new OutpatientEmrTemplateSnapshotService(snapshotMapper, mappingMapper, objectMapper);
    }

    @Test
    void resolveReturnsAnExactHistoricalParseWithoutWriting() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = new AiOutpatientEmrTemplateSnapshot();
        existing.setIdSnapshot("snapshot-history");
        existing.setIdOrg("ORG001");
        existing.setTemplateId(source.getTemplateId());
        existing.setTemplateHash(source.getTemplateHash());
        existing.setParseResultJson(objectMapper.writeValueAsString(source.getParseResult()));
        existing.setLastReceivedAt(LocalDateTime.of(2026, 8, 27, 12, 0));
        existing.setFgActive("1");
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(existing));

        OutpatientEmrTemplateSnapshotResolution resolution = service.resolve(
            device(),
            validResolveRequest(source)
        );

        assertEquals(Boolean.TRUE, resolution.getCacheHit());
        assertEquals("snapshot-history", resolution.getId());
        assertEquals(source.getTemplateHash(), resolution.getTemplateHash());
        assertEquals(Integer.valueOf(2), Integer.valueOf(resolution.getParseResult().getFields().size()));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
        verify(snapshotMapper, never()).updateById(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void resolveReturnsAnExplicitMissWithoutWriting() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        when(snapshotMapper.selectList(any())).thenReturn(Collections.emptyList());

        OutpatientEmrTemplateSnapshotResolution resolution = service.resolve(
            device(),
            validResolveRequest(source)
        );

        assertEquals(Boolean.FALSE, resolution.getCacheHit());
        assertEquals(source.getTemplateId(), resolution.getTemplateId());
        assertEquals(source.getTemplateHash(), resolution.getTemplateHash());
        assertEquals(null, resolution.getId());
        assertEquals(null, resolution.getParseResult());
        assertEquals(null, resolution.getReceivedAt());
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
        verify(snapshotMapper, never()).updateById(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void resolveRejectsADamagedHistoricalParseInsteadOfReturningAHit() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = new AiOutpatientEmrTemplateSnapshot();
        existing.setIdSnapshot("snapshot-damaged");
        existing.setIdOrg("ORG001");
        existing.setTemplateId(source.getTemplateId());
        existing.setTemplateHash(source.getTemplateHash());
        existing.setParseResultJson("{\"schemaVersion\":\"outpatient-emr-template-pair.v1\"}");
        existing.setLastReceivedAt(LocalDateTime.of(2026, 8, 27, 12, 0));
        existing.setFgActive("1");
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(existing));

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.resolve(device(), validResolveRequest(source))
        );

        assertTrue(error.getMessage().contains("parseResult.fields"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
        verify(snapshotMapper, never()).updateById(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void resolveRejectsUnknownFieldsInsteadOfFallingBack() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        String json = objectMapper.writeValueAsString(validResolveRequest(source));
        String withUnknownTemplate = json.substring(0, json.length() - 1)
            + ",\"templateHtml\":\"legacy\"}";
        OutpatientEmrTemplateSnapshotResolveRequest request = objectMapper.readValue(
            withUnknownTemplate,
            OutpatientEmrTemplateSnapshotResolveRequest.class
        );

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.resolve(device(), request)
        );

        assertTrue(error.getMessage().contains("templateHtml"));
        verify(snapshotMapper, never()).selectList(any());
    }

    @Test
    void resolveAppliesTheVersionSpecificMappingOverride() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        AiOutpatientEmrTemplateMapping mapping = mapping(
            existing.getIdSnapshot(),
            "婚育状况",
            "personalHistory",
            "direct"
        );
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        when(mappingMapper.selectList(any())).thenReturn(Collections.singletonList(mapping));

        OutpatientEmrTemplateSnapshotResolution resolution = service.resolve(
            device(),
            validResolveRequest(source)
        );

        OutpatientEmrTemplateFieldSnapshot effective = resolution.getParseResult().getFields().get(1);
        assertEquals("personalHistory", effective.getRecordField());
        assertEquals("definition-record-field", effective.getMappingSource());
        assertEquals("direct", effective.getProjectionMode());
    }

    @Test
    void updateMappingStoresAnOverrideWithoutChangingTheSnapshot() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        AiOutpatientEmrTemplateMapping persisted = mapping(
            existing.getIdSnapshot(),
            "婚育状况",
            "personalHistory",
            "direct"
        );
        when(snapshotMapper.selectById(existing.getIdSnapshot())).thenReturn(existing);
        when(mappingMapper.selectList(any()))
            .thenReturn(Collections.emptyList())
            .thenReturn(Collections.emptyList())
            .thenReturn(Collections.singletonList(persisted));

        OutpatientEmrTemplateSnapshotVO result = service.updateMapping(
            existing.getIdSnapshot(),
            mappingRequest("婚育状况", "mapped", "personalHistory", "direct")
        );

        ArgumentCaptor<AiOutpatientEmrTemplateMapping> captor =
            ArgumentCaptor.forClass(AiOutpatientEmrTemplateMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertEquals(existing.getIdSnapshot(), captor.getValue().getIdSnapshot());
        assertEquals("婚育状况", captor.getValue().getFieldId());
        assertEquals("personalHistory", captor.getValue().getRecordField());
        assertEquals("direct", captor.getValue().getProjectionMode());
        assertEquals(Integer.valueOf(2), result.getMappedFieldCount());
        assertEquals(Integer.valueOf(1), Integer.valueOf(result.getMappingOverrides().size()));
        assertEquals("personalHistory", result.getParseResult().getFields().get(1).getRecordField());
        verify(snapshotMapper, never()).updateById(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void updateMappingRejectsConflictingDirectOwnership() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        when(snapshotMapper.selectById(existing.getIdSnapshot())).thenReturn(existing);
        when(mappingMapper.selectList(any())).thenReturn(Collections.emptyList());

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.updateMapping(
                existing.getIdSnapshot(),
                mappingRequest("婚育状况", "mapped", "chiefComplaint", "direct")
            )
        );

        assertTrue(error.getMessage().contains("标准字段 chiefComplaint 存在重复映射"));
        verify(mappingMapper, never()).insert(any(AiOutpatientEmrTemplateMapping.class));
        verify(mappingMapper, never()).updateById(any(AiOutpatientEmrTemplateMapping.class));
    }

    @Test
    void updateMappingCanExplicitlyExcludeAnAutomaticallyMappedField() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        AiOutpatientEmrTemplateMapping persisted = mapping(
            existing.getIdSnapshot(),
            "chiefComplaint",
            null,
            null
        );
        when(snapshotMapper.selectById(existing.getIdSnapshot())).thenReturn(existing);
        when(mappingMapper.selectList(any()))
            .thenReturn(Collections.emptyList())
            .thenReturn(Collections.emptyList())
            .thenReturn(Collections.singletonList(persisted));

        OutpatientEmrTemplateSnapshotVO result = service.updateMapping(
            existing.getIdSnapshot(),
            mappingRequest("chiefComplaint", "unmapped", null, null)
        );

        ArgumentCaptor<AiOutpatientEmrTemplateMapping> captor =
            ArgumentCaptor.forClass(AiOutpatientEmrTemplateMapping.class);
        verify(mappingMapper).insert(captor.capture());
        assertEquals(null, captor.getValue().getRecordField());
        assertEquals(null, captor.getValue().getProjectionMode());
        assertEquals(Integer.valueOf(0), result.getMappedFieldCount());
        assertEquals("unmapped", result.getParseResult().getFields().get(0).getMappingSource());
        assertEquals("unmapped", result.getMappingOverrides().get(0).getMappingStatus());
    }

    @Test
    void clearMappingRestoresTheAutomaticParse() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        AiOutpatientEmrTemplateMapping persisted = mapping(
            existing.getIdSnapshot(),
            "婚育状况",
            "personalHistory",
            "direct"
        );
        when(snapshotMapper.selectById(existing.getIdSnapshot())).thenReturn(existing);
        when(mappingMapper.selectList(any()))
            .thenReturn(Collections.singletonList(persisted))
            .thenReturn(Collections.emptyList());

        OutpatientEmrTemplateSnapshotVO result = service.clearMapping(
            existing.getIdSnapshot(),
            "婚育状况"
        );

        verify(mappingMapper).deleteById(persisted.getIdMapping());
        assertTrue(result.getMappingOverrides().isEmpty());
        assertEquals(null, result.getParseResult().getFields().get(1).getRecordField());
        assertEquals("unmapped", result.getParseResult().getFields().get(1).getMappingSource());
    }

    @Test
    void saveStoresTemplateAndCompleteParseSnapshotOnly() throws Exception {
        when(snapshotMapper.selectList(any())).thenReturn(Collections.emptyList());
        OutpatientEmrTemplateSnapshotRequest request = validRequest();

        OutpatientEmrTemplateSnapshotReceipt receipt = service.save(device(), request);

        ArgumentCaptor<AiOutpatientEmrTemplateSnapshot> captor =
            ArgumentCaptor.forClass(AiOutpatientEmrTemplateSnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        AiOutpatientEmrTemplateSnapshot stored = captor.getValue();
        assertEquals(request.getTemplateHtml(), stored.getTemplateHtml());
        assertEquals(request.getTemplateDefinition(), stored.getTemplateDefinition());
        assertEquals(Integer.valueOf(2), stored.getFieldCount());
        assertEquals(Integer.valueOf(2), stored.getWritableFieldCount());
        assertEquals(Integer.valueOf(1), stored.getDictionaryFieldCount());
        assertEquals(Integer.valueOf(1), stored.getMappedFieldCount());
        assertTrue(stored.getParseResultJson().contains("chiefComplaint"));
        assertTrue(stored.getParseResultJson().contains("婚育状况"));
        JsonNode unmappedField = objectMapper.readTree(stored.getParseResultJson())
            .path("fields")
            .path(1);
        assertTrue(unmappedField.has("recordField"));
        assertTrue(unmappedField.get("recordField").isNull());
        assertTrue(unmappedField.has("projectionMode"));
        assertTrue(unmappedField.get("projectionMode").isNull());
        assertFalse(stored.getParseResultJson().contains("\"recordContext\""));
        assertFalse(stored.getParseResultJson().contains("\"patient\""));
        assertEquals(Boolean.FALSE, receipt.getDeduplicated());
        assertEquals(request.getTemplateHash(), receipt.getTemplateHash());
    }

    @Test
    void saveReturnsExistingSnapshotForSameIdentityWithoutWriting() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = new AiOutpatientEmrTemplateSnapshot();
        existing.setIdSnapshot("snapshot-existing");
        existing.setIdOrg("ORG001");
        existing.setTemplateId(request.getTemplateId());
        existing.setTemplateHash(request.getTemplateHash());
        existing.setLastReceivedAt(LocalDateTime.of(2026, 8, 27, 12, 0));
        existing.setFgActive("1");
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(existing));

        OutpatientEmrTemplateSnapshotReceipt receipt = service.save(device(), request);

        assertEquals(Boolean.TRUE, receipt.getDeduplicated());
        assertEquals("snapshot-existing", receipt.getId());
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
        verify(snapshotMapper, never()).updateById(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveConvergesWhenConcurrentInsertWinsTheUniqueKeyRace() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        AiOutpatientEmrTemplateSnapshot concurrent = new AiOutpatientEmrTemplateSnapshot();
        concurrent.setIdSnapshot("snapshot-concurrent");
        concurrent.setIdOrg("ORG001");
        concurrent.setTemplateId(request.getTemplateId());
        concurrent.setTemplateHash(request.getTemplateHash());
        concurrent.setLastReceivedAt(LocalDateTime.of(2026, 8, 27, 12, 0));
        concurrent.setFgActive("1");
        when(snapshotMapper.selectList(any()))
            .thenReturn(Collections.emptyList())
            .thenReturn(Collections.singletonList(concurrent));
        doThrow(new DuplicateKeyException("duplicate"))
            .when(snapshotMapper)
            .insert(any(AiOutpatientEmrTemplateSnapshot.class));

        OutpatientEmrTemplateSnapshotReceipt receipt = service.save(device(), request);

        assertEquals(Boolean.TRUE, receipt.getDeduplicated());
        assertEquals("snapshot-concurrent", receipt.getId());
        verify(snapshotMapper, never()).updateById(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRejectsHashThatDoesNotMatchTemplatePair() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        request.setTemplateHash(String.join("", Collections.nCopies(64, "0")));

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device(), request)
        );

        assertTrue(error.getMessage().contains("不匹配"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRejectsUnknownTopLevelFields() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        String json = objectMapper.writeValueAsString(source);
        String withUnknownPatient = json.substring(0, json.length() - 1)
            + ",\"patient\":{\"idPi\":\"PATIENT-001\"}}";
        OutpatientEmrTemplateSnapshotRequest request = objectMapper.readValue(
            withUnknownPatient,
            OutpatientEmrTemplateSnapshotRequest.class
        );

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device(), request)
        );

        assertTrue(error.getMessage().contains("patient"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRejectsEitherMissingTemplateHalf() throws Exception {
        OutpatientEmrTemplateSnapshotRequest missingHtml = validRequest();
        missingHtml.setTemplateHtml(null);
        OutpatientEmrTemplateSnapshotRequest missingDefinition = validRequest();
        missingDefinition.setTemplateDefinition(null);

        assertTrue(assertThrows(
            BusinessException.class,
            () -> service.save(device(), missingHtml)
        ).getMessage().contains("templateHtml"));
        assertTrue(assertThrows(
            BusinessException.class,
            () -> service.save(device(), missingDefinition)
        ).getMessage().contains("templateDefinition"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRejectsMissingDictionaryItemsInsteadOfDefaultingToAnEmptyList() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        request.getParseResult().getFields().get(0).setDictionaryItems(null);

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device(), request)
        );

        assertTrue(error.getMessage().contains("dictionaryItems 不能为空"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRejectsDictionaryItemsWithoutDisplayText() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        request.getParseResult().getFields().get(1).getDictionaryItems().get(0).setText(" ");

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device(), request)
        );

        assertTrue(error.getMessage().contains(".text 不能为空"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRejectsAmbiguousDictionaryTokens() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        OutpatientEmrTemplateDictionaryItemSnapshot duplicate =
            new OutpatientEmrTemplateDictionaryItemSnapshot();
        duplicate.setValue("2");
        duplicate.setText("已婚已育");
        request.getParseResult().getFields().get(1).setDictionaryItems(Arrays.asList(
            request.getParseResult().getFields().get(1).getDictionaryItems().get(0),
            duplicate
        ));

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device(), request)
        );

        assertTrue(error.getMessage().contains("重复匹配项"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRejectsFieldIdentityThatWouldRequireTrimming() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        request.getParseResult().getFields().get(0).setId(" chiefComplaint");

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device(), request)
        );

        assertTrue(error.getMessage().contains("不能包含首尾空白"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRequiresNullForAnUnmappedRecordField() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        request.getParseResult().getFields().get(1).setRecordField("");

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device(), request)
        );

        assertTrue(error.getMessage().contains("空值必须使用 null"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    private AiDevice device() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setCdDevice("PCIE-TEST-001");
        device.setIdOrg("ORG001");
        device.setIdRegion("REGION001");
        return device;
    }

    private AiOutpatientEmrTemplateSnapshot snapshotEntity(
        OutpatientEmrTemplateSnapshotRequest source
    ) throws Exception {
        AiOutpatientEmrTemplateSnapshot entity = new AiOutpatientEmrTemplateSnapshot();
        entity.setIdSnapshot("snapshot-history");
        entity.setIdOrg("ORG001");
        entity.setTemplateId(source.getTemplateId());
        entity.setTemplateName(source.getTemplateName());
        entity.setTemplateHash(source.getTemplateHash());
        entity.setTemplateHtml(source.getTemplateHtml());
        entity.setTemplateDefinition(source.getTemplateDefinition());
        entity.setParseResultJson(objectMapper.writeValueAsString(source.getParseResult()));
        entity.setFieldCount(Integer.valueOf(2));
        entity.setWritableFieldCount(Integer.valueOf(2));
        entity.setDictionaryFieldCount(Integer.valueOf(1));
        entity.setMappedFieldCount(Integer.valueOf(1));
        entity.setLastReceivedAt(LocalDateTime.of(2026, 8, 27, 12, 0));
        entity.setFgActive("1");
        return entity;
    }

    private AiOutpatientEmrTemplateMapping mapping(String idSnapshot,
                                                    String fieldId,
                                                    String recordField,
                                                    String projectionMode) {
        AiOutpatientEmrTemplateMapping mapping = new AiOutpatientEmrTemplateMapping();
        mapping.setIdMapping("mapping-1");
        mapping.setIdSnapshot(idSnapshot);
        mapping.setFieldId(fieldId);
        mapping.setRecordField(recordField);
        mapping.setProjectionMode(projectionMode);
        mapping.setFgActive("1");
        return mapping;
    }

    private OutpatientEmrTemplateMappingRequest mappingRequest(String fieldId,
                                                               String mappingStatus,
                                                               String recordField,
                                                               String projectionMode) {
        OutpatientEmrTemplateMappingRequest request = new OutpatientEmrTemplateMappingRequest();
        request.setFieldId(fieldId);
        request.setMappingStatus(mappingStatus);
        request.setRecordField(recordField);
        request.setProjectionMode(projectionMode);
        return request;
    }

    private OutpatientEmrTemplateSnapshotRequest validRequest() throws Exception {
        String templateHtml = "<section data-id=\"article-chief\" data-article=\"主诉\" data-name=\"主诉\">咳嗽</section>";
        String templateDefinition = "[{\"ID\":\"article-chief\",\"NAME\":\"主诉\",\"ARTICLE\":\"主诉\",\"eles\":[]}]";
        OutpatientEmrTemplateSnapshotRequest request = new OutpatientEmrTemplateSnapshotRequest();
        request.setSchemaVersion("outpatient-emr-template-pair-snapshot.v1");
        request.setTemplateId("TPL-001");
        request.setTemplateName("门诊初诊病历");
        request.setTemplateHash(sha256(
            "outpatient-emr-template-pair.v1:"
                + sha256(templateHtml)
                + ":"
                + sha256(templateDefinition)
        ));
        request.setTemplateHtml(templateHtml);
        request.setTemplateDefinition(templateDefinition);

        OutpatientEmrTemplateParseSnapshot parseResult = new OutpatientEmrTemplateParseSnapshot();
        parseResult.setSchemaVersion("outpatient-emr-template-pair.v1");
        parseResult.setFields(Arrays.asList(textField(), dictionaryField()));
        request.setParseResult(parseResult);
        return request;
    }

    private OutpatientEmrTemplateSnapshotResolveRequest validResolveRequest(
        OutpatientEmrTemplateSnapshotRequest source
    ) {
        OutpatientEmrTemplateSnapshotResolveRequest request =
            new OutpatientEmrTemplateSnapshotResolveRequest();
        request.setSchemaVersion("outpatient-emr-template-pair-resolve.v1");
        request.setTemplateId(source.getTemplateId());
        request.setTemplateHash(source.getTemplateHash());
        return request;
    }

    private OutpatientEmrTemplateFieldSnapshot textField() {
        OutpatientEmrTemplateFieldSnapshot field = new OutpatientEmrTemplateFieldSnapshot();
        field.setId("chiefComplaint");
        field.setName("主诉");
        field.setType("text");
        field.setArticleTemplateId("article-chief");
        field.setArticleId("chiefComplaint");
        field.setArticleName("主诉");
        field.setArticleDefinitionName("主诉");
        field.setReadonly(Boolean.FALSE);
        field.setAiSuitable(Boolean.TRUE);
        field.setBaselineValue("咳嗽");
        field.setBaselineDictionaryValue("");
        field.setDictionaryItems(Collections.emptyList());
        field.setRecordField("chiefComplaint");
        field.setMappingSource("canonical-id");
        field.setProjectionMode("direct");
        return field;
    }

    private OutpatientEmrTemplateFieldSnapshot dictionaryField() {
        OutpatientEmrTemplateDictionaryItemSnapshot item = new OutpatientEmrTemplateDictionaryItemSnapshot();
        item.setValue("1");
        item.setText("已婚已育");

        OutpatientEmrTemplateFieldSnapshot field = new OutpatientEmrTemplateFieldSnapshot();
        field.setId("婚育状况");
        field.setName("婚育状况");
        field.setType("select");
        field.setArticleTemplateId("article-marriage-history");
        field.setArticleId("婚育史");
        field.setArticleName("婚育史");
        field.setArticleDefinitionName("婚育史");
        field.setReadonly(Boolean.FALSE);
        field.setAiSuitable(Boolean.TRUE);
        field.setBaselineValue("");
        field.setBaselineDictionaryValue("");
        field.setDictionaryItems(Collections.singletonList(item));
        field.setRecordField(null);
        field.setMappingSource("unmapped");
        field.setProjectionMode(null);
        return field;
    }

    private String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder();
        for (byte item : bytes) {
            result.append(String.format("%02x", item & 0xff));
        }
        return result.toString();
    }
}
