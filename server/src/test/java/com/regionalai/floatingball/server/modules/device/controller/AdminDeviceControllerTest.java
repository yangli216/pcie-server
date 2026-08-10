package com.regionalai.floatingball.server.modules.device.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.device.dto.AiDeviceSaveRequest;
import com.regionalai.floatingball.server.modules.device.dto.AiDeviceView;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminDeviceControllerTest {

    @Mock
    private DeviceService deviceService;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminDeviceController(deviceService))
            .setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new MappingJackson2HttpMessageConverter(objectMapper))
            .build();
    }

    @Test
    void listShouldReturnWrappedPagedDevices() throws Exception {
        AiDeviceView device = new AiDeviceView();
        device.setIdDevice("DEV001");
        device.setCdDevice("clinic-room-1");
        device.setNaDevice("诊室一终端");
        device.setNaUser("张医生");
        device.setDoctorWorkNo("0123");
        device.setIdOrg("ORG001");
        device.setNaOrg("默认机构");
        device.setIdRegion("REG001");
        device.setNaRegion("默认区域");
        device.setSdStatus("1");
        device.setDeviceTokenMasked("abcd****wxyz");
        device.setRegisterIp("10.0.0.10");
        device.setLastSeenIp("10.0.0.11");
        device.setLastActiveTime(LocalDateTime.of(2026, 8, 6, 9, 15));

        PageResponse<AiDeviceView> page = new PageResponse<AiDeviceView>(2, 20, 1, Collections.singletonList(device));
        when(deviceService.list(2, 20, "clinic")).thenReturn(page);

        mockMvc.perform(get("/admin/api/devices")
                .param("current", "2")
                .param("size", "20")
                .param("keyword", "clinic")
                .header("X-Request-Id", "RID-device-list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-device-list"))
            .andExpect(jsonPath("$.data.current").value(2))
            .andExpect(jsonPath("$.data.size").value(20))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].idDevice").value("DEV001"))
            .andExpect(jsonPath("$.data.records[0].naUser").value("张医生"))
            .andExpect(jsonPath("$.data.records[0].doctorWorkNo").value("0123"))
            .andExpect(jsonPath("$.data.records[0].naOrg").value("默认机构"))
            .andExpect(jsonPath("$.data.records[0].deviceTokenMasked").value("abcd****wxyz"))
            .andExpect(jsonPath("$.data.records[0].registerIp").value("10.0.0.10"))
            .andExpect(jsonPath("$.data.records[0].lastSeenIp").value("10.0.0.11"))
            .andExpect(jsonPath("$.data.records[0].lastActiveTime").value("2026-08-06T09:15:00"));
    }

    @Test
    void saveShouldPassBodyToServiceAndReturnCreatedDevice() throws Exception {
        AiDeviceView created = new AiDeviceView();
        created.setIdDevice("DEV002");
        created.setCdDevice("clinic-room-2");
        created.setIdOrg("ORG001");
        created.setIdRegion("REG001");
        created.setSdStatus("0");

        AiDeviceSaveRequest request = new AiDeviceSaveRequest();
        request.setCdDevice("clinic-room-2");
        request.setNaDevice("诊室二终端");
        request.setIdOrg("ORG001");
        request.setClientVersion("1.2.3");
        request.setOsInfo("Windows 11");

        when(deviceService.save(any(AiDeviceSaveRequest.class))).thenReturn(created);

        mockMvc.perform(post("/admin/api/devices")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-device-save")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-device-save"))
            .andExpect(jsonPath("$.data.idDevice").value("DEV002"))
            .andExpect(jsonPath("$.data.cdDevice").value("clinic-room-2"));

        verify(deviceService).save(any(AiDeviceSaveRequest.class));
    }

    @Test
    void updateShouldUsePathIdAndReturnUpdatedDevice() throws Exception {
        AiDeviceView updated = new AiDeviceView();
        updated.setIdDevice("DEV001");
        updated.setCdDevice("clinic-room-renamed");
        updated.setIdOrg("ORG002");
        updated.setSdStatus("1");

        AiDeviceSaveRequest request = new AiDeviceSaveRequest();
        request.setCdDevice("clinic-room-renamed");
        request.setNaDevice("重命名终端");
        request.setIdOrg("ORG002");
        request.setSdStatus("1");

        when(deviceService.update(eq("DEV001"), any(AiDeviceSaveRequest.class))).thenReturn(updated);

        mockMvc.perform(put("/admin/api/devices/DEV001")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-device-update")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-device-update"))
            .andExpect(jsonPath("$.data.idDevice").value("DEV001"))
            .andExpect(jsonPath("$.data.cdDevice").value("clinic-room-renamed"));

        verify(deviceService).update(eq("DEV001"), any(AiDeviceSaveRequest.class));
    }

    @Test
    void disableShouldDelegateToService() throws Exception {
        mockMvc.perform(post("/admin/api/devices/DEV001/disable")
                .header("X-Request-Id", "RID-device-disable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-device-disable"));

        verify(deviceService).invalidate("DEV001");
    }

    @Test
    void deleteForResetShouldDelegateToService() throws Exception {
        mockMvc.perform(delete("/admin/api/devices/DEV001")
                .header("X-Request-Id", "RID-device-delete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-device-delete"));

        verify(deviceService).deleteForReset("DEV001");
    }

    @Test
    void businessExceptionShouldUseGlobalErrorEnvelope() throws Exception {
        doThrow(new BusinessException("设备不存在")).when(deviceService).deleteForReset("MISSING");

        mockMvc.perform(delete("/admin/api/devices/MISSING")
                .header("X-Request-Id", "RID-device-error"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BIZ-001"))
            .andExpect(jsonPath("$.requestId").value("RID-device-error"))
            .andExpect(jsonPath("$.message").value("设备不存在"));
    }

    @Test
    void businessExceptionShouldHideTechnicalDetails() throws Exception {
        doThrow(new BusinessException("导出Excel失败：java.sql.SQLException: ORA-00942 table or view does not exist"))
            .when(deviceService).deleteForReset("EXPORT-BROKEN");

        mockMvc.perform(delete("/admin/api/devices/EXPORT-BROKEN")
                .header("X-Request-Id", "RID-business-tech-error"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BIZ-001"))
            .andExpect(jsonPath("$.requestId").value("RID-business-tech-error"))
            .andExpect(jsonPath("$.message").value("导出Excel失败，请稍后重试；如持续出现，请联系管理员查看日志"))
            .andExpect(jsonPath("$.message").value(not(containsString("SQLException"))))
            .andExpect(jsonPath("$.message").value(not(containsString("ORA-00942"))));
    }

    @Test
    void unexpectedExceptionShouldReturnFriendlyMessage() throws Exception {
        doThrow(new RuntimeException("java.sql.SQLException: ORA-00942 table or view does not exist"))
            .when(deviceService).deleteForReset("BROKEN");

        mockMvc.perform(delete("/admin/api/devices/BROKEN")
                .header("X-Request-Id", "RID-device-sys-error"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("SYS-500"))
            .andExpect(jsonPath("$.requestId").value("RID-device-sys-error"))
            .andExpect(jsonPath("$.message").value("后台服务处理失败，请稍后重试；如持续出现，请联系管理员并提供请求ID"))
            .andExpect(jsonPath("$.message").value(not(containsString("SQLException"))))
            .andExpect(jsonPath("$.message").value(not(containsString("ORA-00942"))));
    }
}
