package com.regionalai.floatingball.server.modules.emrtemplate.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateFieldSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateParseSnapshot;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotReceipt;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotResolution;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotResolveRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotVO;
import com.regionalai.floatingball.server.modules.emrtemplate.service.OutpatientEmrTemplateSnapshotService;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Collections;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class OutpatientEmrTemplateControllerTest {

    @Mock
    private OutpatientEmrTemplateSnapshotService snapshotService;

    @AfterEach
    void tearDown() {
        DeviceContextHolder.clear();
    }

    @Test
    void clientSaveUsesAuthenticatedDeviceContext() throws Exception {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
        DeviceContextHolder.set(device);
        when(snapshotService.save(eq(device), any(OutpatientEmrTemplateSnapshotRequest.class)))
            .thenReturn(new OutpatientEmrTemplateSnapshotReceipt(
                "snapshot-1",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                Boolean.FALSE,
                Long.valueOf(1)
            ));
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ClientOutpatientEmrTemplateController(snapshotService))
            .build();

        mockMvc.perform(post("/v1/client/outpatient-emr/templates/snapshots")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-outpatient-snapshot")
                .content("{}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-outpatient-snapshot"))
            .andExpect(jsonPath("$.data.id").value("snapshot-1"));

        verify(snapshotService).save(eq(device), any(OutpatientEmrTemplateSnapshotRequest.class));
    }

    @Test
    void clientResolveUsesAuthenticatedDeviceContextAndKeepsExplicitMissShape() throws Exception {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setIdOrg("ORG001");
        DeviceContextHolder.set(device);
        String templateHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        when(snapshotService.resolve(
            eq(device),
            any(OutpatientEmrTemplateSnapshotResolveRequest.class)
        )).thenReturn(new OutpatientEmrTemplateSnapshotResolution(
            "outpatient-emr-template-pair-resolution.v1",
            Boolean.FALSE,
            null,
            "TPL-001",
            templateHash,
            null,
            null
        ));
        ObjectMapper objectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new ClientOutpatientEmrTemplateController(snapshotService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();

        mockMvc.perform(post("/v1/client/outpatient-emr/templates/snapshots/resolve")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-outpatient-resolve")
                .content("{\"schemaVersion\":\"outpatient-emr-template-pair-resolve.v1\","
                    + "\"templateId\":\"TPL-001\",\"templateHash\":\"" + templateHash + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-outpatient-resolve"))
            .andExpect(jsonPath("$.data.cacheHit").value(false))
            .andExpect(jsonPath("$.data.id").value(nullValue()))
            .andExpect(jsonPath("$.data.parseResult").value(nullValue()))
            .andExpect(jsonPath("$.data.receivedAt").value(nullValue()));

        verify(snapshotService).resolve(
            eq(device),
            any(OutpatientEmrTemplateSnapshotResolveRequest.class)
        );
    }

    @Test
    void adminListAndDetailExposeSnapshotViews() throws Exception {
        OutpatientEmrTemplateSnapshotVO item = new OutpatientEmrTemplateSnapshotVO();
        item.setId("snapshot-1");
        item.setTemplateName("门诊初诊病历");
        item.setFieldCount(Integer.valueOf(83));
        OutpatientEmrTemplateFieldSnapshot unmappedField = new OutpatientEmrTemplateFieldSnapshot();
        unmappedField.setMappingSource("unmapped");
        unmappedField.setRecordField(null);
        unmappedField.setProjectionMode(null);
        OutpatientEmrTemplateParseSnapshot parseResult = new OutpatientEmrTemplateParseSnapshot();
        parseResult.setSchemaVersion("outpatient-emr-template-pair.v1");
        parseResult.setFields(Collections.singletonList(unmappedField));
        item.setParseResult(parseResult);
        when(snapshotService.list(2L, 20L, "初诊"))
            .thenReturn(new PageResponse<OutpatientEmrTemplateSnapshotVO>(
                2,
                20,
                1,
                Collections.singletonList(item)
            ));
        when(snapshotService.get("snapshot-1")).thenReturn(item);
        when(snapshotService.updateMapping(eq("snapshot-1"), any())).thenReturn(item);
        when(snapshotService.clearMapping("snapshot-1", "婚育状况")).thenReturn(item);
        ObjectMapper nonNullObjectMapper = new ObjectMapper()
            .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        MockMvc mockMvc = MockMvcBuilders
            .standaloneSetup(new AdminOutpatientEmrTemplateController(snapshotService))
            .setMessageConverters(new MappingJackson2HttpMessageConverter(nonNullObjectMapper))
            .build();

        mockMvc.perform(get("/admin/api/outpatient-emr/templates")
                .param("current", "2")
                .param("size", "20")
                .param("keyword", "初诊"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].fieldCount").value(83));

        mockMvc.perform(get("/admin/api/outpatient-emr/templates/snapshot-1"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.templateName").value("门诊初诊病历"))
            .andExpect(jsonPath("$.data.parseResult.fields[0].recordField").value(nullValue()))
            .andExpect(jsonPath("$.data.parseResult.fields[0].mappingSource").value("unmapped"))
            .andExpect(jsonPath("$.data.parseResult.fields[0].projectionMode").value(nullValue()));

        mockMvc.perform(put("/admin/api/outpatient-emr/templates/snapshot-1/mapping")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-outpatient-mapping")
                .content("{\"fieldId\":\"婚育状况\",\"mappingStatus\":\"mapped\","
                    + "\"recordField\":\"personalHistory\",\"projectionMode\":\"direct\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("RID-outpatient-mapping"))
            .andExpect(jsonPath("$.data.templateName").value("门诊初诊病历"));

        mockMvc.perform(delete("/admin/api/outpatient-emr/templates/snapshot-1/mapping")
                .param("fieldId", "婚育状况"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.templateName").value("门诊初诊病历"));

        verify(snapshotService).updateMapping(eq("snapshot-1"), any());
        verify(snapshotService).clearMapping("snapshot-1", "婚育状况");
    }
}
