package com.regionalai.floatingball.server.modules.emrtemplate.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.ForbiddenException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.audit.service.AuditService;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientFieldPolicy;
import org.junit.jupiter.api.AfterEach;
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
import java.util.Base64;
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

    @Mock
    private AuditService auditService;

    private ObjectMapper objectMapper;
    private OutpatientEmrTemplateSnapshotService service;

    @BeforeEach
    void setUp() {
        AdminCurrentUser admin = new AdminCurrentUser();
        admin.setIdOrg("ORG001");
        admin.setIdUser("SYN-ADMIN");
        AdminContextHolder.set(admin);
        objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        service = new OutpatientEmrTemplateSnapshotService(snapshotMapper, mappingMapper, objectMapper, new OutpatientEmrTemplateDecodeService(objectMapper), auditService);
    }

    @AfterEach
    void clearAdmin() {
        AdminContextHolder.clear();
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
    void resolveIsolatesDamagedRowsAndConflictingOwnersWhileHonoringManualMappings() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        ObjectNode stored = (ObjectNode) objectMapper.readTree(existing.getParseResultJson());
        ArrayNode rows = (ArrayNode) stored.get("fields");
        ObjectNode unmapped = (ObjectNode) rows.get(1);
        rows.add(unmapped.deepCopy().put("id", "SYN-DUPLICATE"));
        rows.add(unmapped.deepCopy().put("id", "SYN-DUPLICATE").put("readonly", true));
        rows.add(unmapped.deepCopy().put("id", "SYN-BAD-ROW").put("dictionaryItems", "damaged dictionary"));
        rows.add(unmapped.deepCopy().put("id", "SYN-FALSE-BOOLEAN").put("readonly", "false"));
        rows.add(unmapped.deepCopy().put("id", "SYN-CONFLICT-A").put("recordField", "familyHistory")
            .put("mappingSource", "canonical-id").put("projectionMode", "direct"));
        rows.add(unmapped.deepCopy().put("id", "SYN-CONFLICT-B").put("recordField", "familyHistory")
            .put("mappingSource", "canonical-id").put("projectionMode", "direct"));
        rows.addNull();
        String original = objectMapper.writeValueAsString(stored);
        existing.setParseResultJson(original);
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        when(mappingMapper.selectList(any())).thenReturn(Arrays.asList(
            mapping(existing.getIdSnapshot(), "chiefComplaint", null, null),
            mapping(existing.getIdSnapshot(), "婚育状况", "personalHistory", "direct"),
            mapping(existing.getIdSnapshot(), "SYN-BAD-ROW", "pastMedicalHistory", "direct")
        ));

        OutpatientEmrTemplateParseSnapshot resolved = service.resolve(device(), validResolveRequest(source)).getParseResult();
        assertEquals(4, resolved.getFields().size());
        assertEquals("chiefComplaint", resolved.getFields().get(0).getId());
        assertEquals("unmapped", resolved.getFields().get(0).getMappingSource());
        assertEquals(null, resolved.getFields().get(0).getRecordField());
        assertEquals("婚育状况", resolved.getFields().get(1).getId());
        assertEquals("manual-override", resolved.getFields().get(1).getMappingSource());
        assertEquals("personalHistory", resolved.getFields().get(1).getRecordField());
        assertEquals("SYN-CONFLICT-A", resolved.getFields().get(2).getId());
        assertEquals("SYN-CONFLICT-B", resolved.getFields().get(3).getId());
        for (OutpatientEmrTemplateFieldSnapshot field : resolved.getFields().subList(2, 4)) {
            assertEquals("unmapped", field.getMappingSource());
            assertEquals(null, field.getRecordField());
            assertEquals(null, field.getProjectionMode());
        }
        assertEquals(original, existing.getParseResultJson());
        verify(snapshotMapper, never()).updateById(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void resolveRejectsOnlyConflictedFieldsUnknownSchemaAndWrongOrganization() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        ObjectNode stored = (ObjectNode) objectMapper.readTree(existing.getParseResultJson());
        ArrayNode rows = (ArrayNode) stored.get("fields");
        rows.remove(1);
        rows.add(rows.get(0).deepCopy());
        existing.setParseResultJson(stored.toString());
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        assertThrows(BusinessException.class, () -> service.resolve(device(), validResolveRequest(source)));
        existing.setParseResultJson(stored.put("schemaVersion", "unsupported").toString());
        assertThrows(BusinessException.class, () -> service.resolve(device(), validResolveRequest(source)));
        existing.setParseResultJson(objectMapper.writeValueAsString(source.getParseResult()));
        existing.setIdOrg("SYN-OTHER-ORG");
        assertThrows(BusinessException.class, () -> service.resolve(device(), validResolveRequest(source)));
        verify(snapshotMapper, never()).updateById(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void historicalWritableFieldsDoNotRequireAnAiSuggestionAndIntactReadonlyMetadataStillResolves() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        ObjectNode stored = (ObjectNode) objectMapper.readTree(existing.getParseResultJson());
        ArrayNode rows = (ArrayNode) stored.get("fields");
        ((ObjectNode) rows.get(0)).put("aiSuitable", false);
        ((ObjectNode) rows.get(1)).remove("aiSuitable");
        rows.add("synthetic damaged row");
        existing.setParseResultJson(stored.toString());
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        OutpatientEmrTemplateParseSnapshot result = service.resolve(device(), validResolveRequest(source)).getParseResult();
        assertEquals(2, result.getFields().size());
        for (OutpatientEmrTemplateFieldSnapshot field : result.getFields()) {
            assertFalse(field.getReadonly());
            assertFalse(field.getAiSuitable());
        }
        rows.remove(2);
        ((ObjectNode) rows.get(0)).put("readonly", true);
        ((ObjectNode) rows.get(1)).put("readonly", true);
        existing.setParseResultJson(stored.toString());
        assertEquals(2, service.resolve(device(), validResolveRequest(source)).getParseResult().getFields().size());
        rows.addNull();
        existing.setParseResultJson(stored.toString());
        assertThrows(BusinessException.class, () -> service.resolve(device(), validResolveRequest(source)));
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
        assertEquals("manual-override", effective.getMappingSource());
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
    void resolveReportsASectionComposedManualOverrideWithItsRealSource() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot existing = snapshotEntity(source);
        AiOutpatientEmrTemplateMapping mapping = mapping(
            existing.getIdSnapshot(),
            "婚育状况",
            "maritalReproductiveHistory",
            "section-compose"
        );
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(existing));
        when(mappingMapper.selectList(any())).thenReturn(Collections.singletonList(mapping));

        OutpatientEmrTemplateSnapshotResolution resolution = service.resolve(
            device(),
            validResolveRequest(source)
        );

        OutpatientEmrTemplateFieldSnapshot effective = resolution.getParseResult().getFields().get(1);
        assertEquals("maritalReproductiveHistory", effective.getRecordField());
        assertEquals("manual-override", effective.getMappingSource());
        assertEquals("section-compose", effective.getProjectionMode());
    }

    @Test
    void saveAcceptsTheMaritalReproductiveHistoryProjection() throws Exception {
        when(snapshotMapper.selectList(any())).thenReturn(Collections.emptyList());
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        OutpatientEmrTemplateFieldSnapshot marital = request.getParseResult().getFields().get(1);
        marital.setRecordField("maritalReproductiveHistory");
        marital.setMappingSource("deterministic-article");
        marital.setProjectionMode("section-compose");

        service.save(device(), request);

        verify(snapshotMapper).insert(any(AiOutpatientEmrTemplateSnapshot.class));
    }

    @Test
    void saveRejectsAManualOverrideSourceInTheImmutableParse() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        request.getParseResult().getFields().get(0).setMappingSource("manual-override");

        BusinessException error = assertThrows(
            BusinessException.class,
            () -> service.save(device(), request)
        );

        assertTrue(error.getMessage().contains("mappingSource"));
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
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

    private ObjectNode rule(String id, long revision, String mode, String body, String extra) {
        return objectMapper.createObjectNode().put("fieldId", id).put("expectedRevision", revision)
            .put("generationMode", mode).putNull("meaningOverride").put("customBody", body).put("additionalConstraint", extra);
    }

    @Test
    void remappingAFullTextChiefComplaintKeepsItsOriginalDefaultWritingTask() throws Exception {
        AiOutpatientEmrTemplateSnapshot row = snapshotEntity(validRequest());
        String id = row.getIdSnapshot();
        when(snapshotMapper.selectById(id)).thenReturn(row);
        OutpatientFieldPolicy.Detail automatic = service.get(id).getFields().get(0);
        when(mappingMapper.selectList(any())).thenReturn(Collections.singletonList(mapping(id, "chiefComplaint", "physicalExam", "direct")));
        OutpatientFieldPolicy.Detail mapped = service.get(id).getFields().get(0);
        assertEquals("physicalExam", mapped.getOriginalField().getRecordField());
        assertEquals("manual-override", mapped.getOriginalField().getMappingSource());
        assertEquals(automatic.getDefaultMeaning(), mapped.getDefaultMeaning());
        assertEquals(automatic.getDefaultBody(), mapped.getDefaultBody());
        assertEquals(automatic.getEffective().getResolvedPrompt(), mapped.getEffective().getResolvedPrompt());
        assertTrue(mapped.getDefaultBody().contains("持续时间"));
    }

    @Test
    void regeneratedDraftMatchesSavedAndDeviceRuleWithSourceConflictGuard() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot row = snapshotEntity(source);
        String id = row.getIdSnapshot();
        when(snapshotMapper.selectById(id)).thenReturn(row);
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(row));
        ObjectNode edit = rule("chiefComplaint", 0, "inherit", null, "医院原有的20字约束");
        java.util.Map<String, Object> draft = service.draftRule(id, edit);
        String body = (String) draft.get("bodyDraft");
        assertTrue(body.contains("本次已选病种"));
        edit.put("customBody", body);
        String effective = service.saveRule(id, edit).getFields().get(0).getEffective().getResolvedPrompt();
        assertEquals(draft.get("resolvedPrompt"), effective);
        assertTrue(effective.contains("未经医生核实时返回空字符串"));
        assertEquals(2, effective.split("医院原有的20字约束", -1).length);
        OutpatientEmrTemplateSnapshotResolveRequest query = validResolveRequest(source);
        query.setSchemaVersion("outpatient-emr-template-pair-resolve.v2");
        assertEquals(effective, objectMapper.valueToTree(service.resolve(device(), query))
            .at("/fieldPolicy/fields/0/resolvedPrompt").asText());
    }

    @Test
    void quantityUnitAndExaminationSourcesProduceDifferentClinicalTasks() {
        OutpatientEmrTemplateFieldSnapshot quantity = splitField("q", "时间", "text");
        OutpatientEmrTemplateFieldSnapshot unit = splitField("u", "时间单位1", "select");
        OutpatientEmrTemplateFieldSnapshot exam = splitField("x", "呼吸音", "text");
        exam.setRecordField("physicalExam");
        OutpatientFieldRuleCompiler compiler = new OutpatientFieldRuleCompiler(objectMapper, null,
            Arrays.asList(quantity, unit, exam), Collections.emptyMap());
        assertTrue(compiler.describe(quantity).getDefaultBody().contains("只填数量"));
        assertTrue(compiler.describe(unit).getDefaultBody().contains("不重复数量"));
        OutpatientEmrTemplateDictionaryItemSnapshot phase = new OutpatientEmrTemplateDictionaryItemSnapshot();
        phase.setText("全程");
        phase.setValue("1");
        quantity.setDictionaryItems(Collections.singletonList(phase));
        assertTrue(compiler.describe(quantity).getDefaultBody().contains("阶段选项只能匹配明确的阶段"));
        assertTrue(compiler.describe(exam).getDefaultBody().contains("候选呼吸音不能作为查体所见"));
        assertTrue(compiler.describe(exam).getEffective().getUnmentionedDefault() == null);
    }

    @Test
    void scopedRulesSurviveMappingEditsDisableAndDeleteWithoutChangingTheSource() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        AiOutpatientEmrTemplateSnapshot row = snapshotEntity(source);
        String original = row.getParseResultJson();
        when(snapshotMapper.selectById(row.getIdSnapshot())).thenReturn(row);
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(row));
        OutpatientEmrTemplateSnapshotResolveRequest query = validResolveRequest(source);
        query.setSchemaVersion("outpatient-emr-template-pair-resolve.v2");
        String id = row.getIdSnapshot();
        ObjectNode edit = rule("chiefComplaint", 0, "ai", "只填写症状名称", "保留原话");
        assertTrue(service.draftRule(id, edit).get("resolvedPrompt").toString().contains("保留原话"));
        assertEquals(0L, service.get(id).getRevision().longValue());
        OutpatientEmrTemplateSnapshotVO saved = service.saveRule(id, edit);
        assertEquals(1L, saved.getRevision().longValue());
        OutpatientFieldPolicy.Field effective = saved.getFields().get(0).getEffective();
        assertEquals("custom", effective.getPromptSource());
        assertEquals(2, effective.getResolvedPrompt().split("保留原话", -1).length);
        assertEquals(effective.getResolvedPrompt(), objectMapper.valueToTree(service.resolve(device(), query))
            .at("/fieldPolicy/fields/0/resolvedPrompt").asText());
        assertThrows(ConflictException.class, () -> service.saveRule(id, edit));
        assertEquals(1L, service.saveRule(id, edit.put("expectedRevision", 1)).getRevision().longValue());
        assertThrows(ConflictException.class, () -> service.resolve(device(), validResolveRequest(source)));
        OutpatientEmrTemplateMappingRequest mapping = mappingRequest("chiefComplaint", "unmapped", null, null);
        mapping.setExpectedRevision(1L);
        assertEquals("custom", service.updateMapping(id, mapping).getFields().get(0).getEffective().getPromptSource());
        service.saveRule(id, edit.put("expectedRevision", 2).put("generationMode", "manual_or_his"));
        JsonNode manual = objectMapper.valueToTree(service.resolve(device(), query));
        assertTrue(manual.at("/fieldPolicy/fields/0/resolvedPrompt").isNull());
        assertFalse(manual.at("/fieldPolicy/fields/0/aiSuitable").asBoolean());
        assertEquals("只填写症状名称", service.get(id).getFields().get(0).getSettings().getCustomBody());
        service.changeState(id, 3, "disabled");
        assertEquals(0, objectMapper.valueToTree(service.resolve(device(), query)).at("/fieldPolicy/fields").size());
        service.changeState(id, 4, "enabled");
        assertFalse(service.get(id).getFields().get(0).getEffective().isAiSuitable());
        service.changeState(id, 5, "deleted");
        source.setSchemaVersion("outpatient-emr-template-pair-snapshot.v2");
        when(snapshotMapper.readRevision(id)).thenReturn(6L);
        JsonNode receipt = objectMapper.valueToTree(service.save(device(), source));
        assertEquals("deleted", receipt.at("/fieldPolicy/templateState").asText());
        assertTrue(receipt.path("deduplicated").asBoolean());
        assertThrows(BusinessException.class, () -> service.changeState(id, 6, "enabled"));
        assertEquals(original, row.getParseResultJson());
        verify(auditService).saveAdminOperation(org.mockito.ArgumentMatchers.eq("ORG001"),
            org.mockito.ArgumentMatchers.eq("outpatient-emr-template"), org.mockito.ArgumentMatchers.eq("deleted"),
            org.mockito.ArgumentMatchers.argThat(log -> "SYN-ADMIN".equals(log.get("operatorId"))
                && Long.valueOf(6).equals(log.get("revision")) && !log.containsKey("customBody")));
    }

    @Test
    void adminRequiresOrganizationAndCannotEnableProtectedFields() throws Exception {
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        source.getParseResult().getFields().get(0).setReadonly(true);
        AiOutpatientEmrTemplateSnapshot row = snapshotEntity(source);
        String id = row.getIdSnapshot();
        when(snapshotMapper.selectById(id)).thenReturn(row);
        assertThrows(BusinessException.class, () -> service.saveRule(id, rule("chiefComplaint", 0, "ai", null, null)));
        AdminContextHolder.get().setIdOrg("OTHER");
        assertThrows(ForbiddenException.class, () -> service.get(id));
        AdminContextHolder.get().setIdOrg(null);
        assertThrows(ForbiddenException.class, () -> service.get(id));
        AdminContextHolder.clear();
        assertThrows(ForbiddenException.class, () -> service.get(id));
    }

    @Test
    void originalAiDeclarationAndLegacyMissingDeclarationStayDistinct() throws Exception {
        OutpatientEmrTemplateFieldSnapshot legacy = textField();
        legacy.setAiSuitable(false);
        OutpatientEmrTemplateFieldSnapshot manual = textField();
        manual.setId("manual");
        OutpatientEmrTemplateFieldSnapshot duration = textField();
        duration.setId("duration");
        duration.setName("持续时间");
        duration.setProjectionMode("section-compose");
        String definition = "[{\"ID\":\"article-chief\",\"eles\":[{\"ID\":\"chiefComplaint\"},"
            + "{\"ID\":\"manual\",\"AI_SUITABLE\":false},{\"ID\":\"duration\",\"AI_SUITABLE\":true}]}]";
        OutpatientFieldRuleCompiler compiler = new OutpatientFieldRuleCompiler(objectMapper, definition,
            Arrays.asList(legacy, manual, duration), Collections.emptyMap());
        assertTrue(compiler.describe(legacy).getEffective().isAiSuitable());
        assertFalse(compiler.describe(manual).getEffective().isAiSuitable());
        assertTrue(compiler.describe(duration).getEffective().getResolvedPrompt().contains("只填数量"));
    }

    @Test
    void splitChiefComplaintProducesMatchingAdminAndDeviceWireExamples() throws Exception {
        String html = "<section data-id='chief' data-article='主诉' data-name='主诉'><p>"
            + "<span data-id='symptom' data-type='text' data-name='症状名称' data-readonly='false'></span>"
            + "<span data-id='duration' data-type='number' data-name='持续时间' data-readonly='false'></span>天</p></section>"
            + "<section data-id='notes' data-article='备注' data-name='备注'><p>"
            + "<span data-id='manualRemark' data-type='text' data-name='登记备注' data-readonly='false'>HIS已登记</span></p></section>";
        String definition = "[{\"ID\":\"chief\",\"NAME\":\"主诉\",\"ARTICLE\":\"主诉\",\"eles\":["
            + "{\"ID\":\"symptom\",\"TYPE\":\"text\",\"NAME\":\"症状名称\",\"READONLY\":false,\"VALUE\":\"\",\"TEXT\":\"\"},"
            + "{\"ID\":\"duration\",\"TYPE\":\"number\",\"NAME\":\"持续时间\",\"READONLY\":false,\"VALUE\":\"\",\"TEXT\":\"\"}]},"
            + "{\"ID\":\"notes\",\"NAME\":\"备注\",\"ARTICLE\":\"备注\",\"eles\":["
            + "{\"ID\":\"manualRemark\",\"TYPE\":\"text\",\"NAME\":\"登记备注\",\"READONLY\":false,\"VALUE\":\"HIS已登记\",\"TEXT\":\"HIS已登记\"}]}]";
        OutpatientEmrTemplateSnapshotRequest source = validRequest();
        source.setSchemaVersion("outpatient-emr-template-pair-snapshot.v2");
        source.setTemplateId("SYN-CHIEF");
        source.setTemplateName("合成门诊病历");
        source.setTemplateHtml(html);
        source.setTemplateDefinition(definition);
        source.setTemplateHash(sha256("outpatient-emr-template-pair.v1:" + sha256(html) + ":" + sha256(definition)));
        OutpatientEmrTemplateFieldSnapshot symptom = splitField("symptom", "症状名称", "text");
        OutpatientEmrTemplateFieldSnapshot duration = splitField("duration", "持续时间", "number");
        OutpatientEmrTemplateFieldSnapshot remark = splitField("manualRemark", "登记备注", "text");
        remark.setArticleTemplateId("notes");
        remark.setArticleId("备注");
        remark.setArticleName("备注");
        remark.setArticleDefinitionName("备注");
        remark.setRecordField(null);
        remark.setMappingSource("unmapped");
        remark.setProjectionMode(null);
        remark.setBaselineValue("HIS已登记");
        remark.setBaselineDictionaryValue("HIS已登记");
        source.getParseResult().setFields(Arrays.asList(symptom, duration, remark));

        String id = "SYN-POLICY-SNAPSHOT";
        java.util.concurrent.atomic.AtomicReference<AiOutpatientEmrTemplateSnapshot> store = new java.util.concurrent.atomic.AtomicReference<>();
        when(snapshotMapper.selectList(any())).thenAnswer(inv -> store.get() == null
            ? Collections.emptyList() : Collections.singletonList(store.get()));
        when(snapshotMapper.insert(any(AiOutpatientEmrTemplateSnapshot.class))).thenAnswer(inv -> {
            AiOutpatientEmrTemplateSnapshot inserted = inv.getArgument(0);
            inserted.setIdSnapshot(id);
            inserted.setInsertTime(LocalDateTime.of(2026, 11, 19, 9, 0));
            inserted.setUpdateTime(inserted.getInsertTime());
            inserted.setLastReceivedAt(inserted.getInsertTime());
            store.set(inserted);
            return 1;
        });
        when(snapshotMapper.readRevision(id)).thenAnswer(inv -> store.get().getPolicyRevision());
        when(snapshotMapper.selectById(id)).thenAnswer(inv -> store.get());
        OutpatientEmrTemplateSnapshotResolveRequest query = validResolveRequest(source);
        query.setSchemaVersion("outpatient-emr-template-pair-resolve.v2");
        ObjectNode capture = objectMapper.createObjectNode();
        capture.set("request", objectMapper.valueToTree(source));
        capture.set("miss", objectMapper.valueToTree(service.resolve(device(), query)));
        capture.set("firstReceipt", objectMapper.valueToTree(service.save(device(), source)));
        capture.set("defaultAdmin", objectMapper.valueToTree(service.get(id)));
        assertTrue(capture.at("/firstReceipt/fieldPolicy/fields/0/aiSuitable").asBoolean());
        ObjectNode edit = rule("symptom", 0, "ai", "仅填写患者本次主症状名称。", "持续时间由相邻栏填写。");
        capture.set("draft", objectMapper.valueToTree(service.draftRule(id, edit)));
        capture.set("writtenAdmin", objectMapper.valueToTree(service.saveRule(id, edit)));
        capture.set("configuredAdmin", objectMapper.valueToTree(service.saveRule(id, rule("manualRemark", 1, "manual_or_his", null, null))));
        capture.set("configured", objectMapper.valueToTree(service.resolve(device(), query)));
        capture.set("deduplicated", objectMapper.valueToTree(service.save(device(), source)));
        assertEquals(capture.at("/configured/fieldPolicy"), capture.at("/deduplicated/fieldPolicy"));
        assertEquals(capture.at("/configured/fieldPolicy/fields/0/resolvedPrompt"),
            capture.at("/configuredAdmin/fields/0/effective/resolvedPrompt"));
        assertTrue(capture.at("/configured/fieldPolicy/fields/2/resolvedPrompt").isNull());
        service.changeState(id, 2, "disabled");
        capture.set("disabled", objectMapper.valueToTree(service.resolve(device(), query)));
        assertEquals(0, capture.at("/disabled/fieldPolicy/fields").size());
        // The cross-repository fixture reader and the synthetic browser scenario use the exact serialized responses.
        java.nio.file.Path evidence = java.nio.file.Paths.get("target", "outpatient-field-policy-evidence.json");
        java.nio.file.Files.createDirectories(evidence.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(evidence.toFile(), capture);
    }

    private OutpatientEmrTemplateFieldSnapshot splitField(String id, String name, String type) {
        OutpatientEmrTemplateFieldSnapshot field = textField();
        field.setId(id);
        field.setName(name);
        field.setType(type);
        field.setAiSuitable(false);
        field.setBaselineValue("");
        field.setArticleTemplateId("chief");
        field.setArticleId("主诉");
        field.setMappingSource("deterministic-article");
        field.setProjectionMode("section-compose");
        return field;
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

    @Test
    void encodedPairIsDecodedBeforeHashingAndStoredAsOriginalSources() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        String html = request.getTemplateHtml();
        String definition = request.getTemplateDefinition();
        request.setTemplateHtml(OutpatientEmrTemplateDecodeService.HTML_PREFIX
            + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)));
        request.setTemplateDefinition(OutpatientEmrTemplateDecodeService.DEFINITION_PREFIX
            + Base64.getEncoder().encodeToString(definition.getBytes(StandardCharsets.UTF_8)));
        service.save(device(), request);
        ArgumentCaptor<AiOutpatientEmrTemplateSnapshot> stored = ArgumentCaptor.forClass(AiOutpatientEmrTemplateSnapshot.class);
        verify(snapshotMapper).insert(stored.capture());
        assertEquals(html, stored.getValue().getTemplateHtml());
        assertEquals(definition, stored.getValue().getTemplateDefinition());
        assertEquals(request.getTemplateHash(), stored.getValue().getTemplateHash());
        assertTrue(request.getTemplateHtml().startsWith(OutpatientEmrTemplateDecodeService.HTML_PREFIX));
    }

    @Test
    void incompleteChapterDoesNotPreventSavingAndResolvingIndependentlyPairedFields() throws Exception {
        OutpatientEmrTemplateSnapshotRequest request = validRequest();
        OutpatientEmrTemplateFieldSnapshot field = textField();
        field.setArticleDefinitionName("主诉 ");
        request.getParseResult().setFields(Collections.singletonList(field));
        String html = "<section data-id=\"article-chief\" data-article=\"chiefComplaint\" data-name=\"主诉\">"
            + "<span data-id=\"chiefComplaint\" data-name=\"主诉\" data-type=\"text\" data-readonly=\"false\">咳嗽</span></section>";
        String definition = "[{\"ID\":\"article-chief\",\"NAME\":\"主诉 \",\"ARTICLE\":\"chiefComplaint\","
            + "\"eles\":[{\"ID\":\"chiefComplaint\",\"NAME\":\"主诉\",\"TYPE\":\"text\",\"TEXT\":\"咳嗽\",\"READONLY\":false}]},"
            + "{\"NAME\":\"缺少章节标识\",\"eles\":[]}]";
        request.setTemplateHash(sha256("outpatient-emr-template-pair.v1:" + sha256(html) + ":" + sha256(definition)));
        request.setTemplateHtml(OutpatientEmrTemplateDecodeService.HTML_PREFIX
            + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8)));
        request.setTemplateDefinition(OutpatientEmrTemplateDecodeService.DEFINITION_PREFIX
            + Base64.getEncoder().encodeToString(definition.getBytes(StandardCharsets.UTF_8)));
        service.save(device(), request);
        ArgumentCaptor<AiOutpatientEmrTemplateSnapshot> captor = ArgumentCaptor.forClass(AiOutpatientEmrTemplateSnapshot.class);
        verify(snapshotMapper).insert(captor.capture());
        assertEquals(definition, captor.getValue().getTemplateDefinition());
        when(snapshotMapper.selectList(any())).thenReturn(Collections.singletonList(captor.getValue()));
        OutpatientEmrTemplateSnapshotResolution resolved = service.resolve(device(), validResolveRequest(request));
        assertEquals(Boolean.TRUE, resolved.getCacheHit());
        assertEquals("主诉 ", resolved.getParseResult().getFields().get(0).getArticleDefinitionName());
        assertEquals(1, resolved.getParseResult().getFields().size());
    }

    @Test
    void snapshotRejectsMixedAndUnsupportedDataUriSourcesBeforeAnyPersistence() throws Exception {
        OutpatientEmrTemplateSnapshotRequest mixed = validRequest();
        mixed.setTemplateHtml(OutpatientEmrTemplateDecodeService.HTML_PREFIX
            + Base64.getEncoder().encodeToString(mixed.getTemplateHtml().getBytes(StandardCharsets.UTF_8)));
        assertThrows(BusinessException.class, () -> service.save(device(), mixed));
        OutpatientEmrTemplateSnapshotRequest unsupported = validRequest();
        unsupported.setTemplateDefinition("data:text/xml;base64,PHhtbC8+");
        assertThrows(BusinessException.class, () -> service.save(device(), unsupported));
        OutpatientEmrTemplateSnapshotRequest caseVariant = validRequest();
        caseVariant.setTemplateHtml(" DATA:text/html;charset=utf-8;base64,PGgvPg==");
        assertThrows(BusinessException.class, () -> service.save(device(), caseVariant));
        verify(snapshotMapper, never()).selectList(any());
        verify(snapshotMapper, never()).insert(any(AiOutpatientEmrTemplateSnapshot.class));
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
