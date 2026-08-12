package com.regionalai.floatingball.server.modules.clientusage.controller;

import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageItemVO;
import com.regionalai.floatingball.server.modules.clientusage.dto.ClientUsageQueryDTO;
import com.regionalai.floatingball.server.modules.clientusage.service.ClientUsageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminClientUsageControllerTest {

    @Mock
    private ClientUsageService clientUsageService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminClientUsageController(clientUsageService)).build();
    }

    @Test
    void listShouldReturnPagedDoctorUsage() throws Exception {
        ClientUsageItemVO item = new ClientUsageItemVO();
        item.setOrgName("新塘社区卫生服务中心");
        item.setDoctorName("张医生");
        item.setDoctorWorkNo("0123");
        item.setFirstInteractionTime("2026-08-10 09:30:00");
        item.setClientVersion("1.3.9");
        item.setLastActiveTime("2026-08-10 15:20:00");
        when(clientUsageService.list(any(ClientUsageQueryDTO.class), eq(2L), eq(50L)))
            .thenReturn(new PageResponse<ClientUsageItemVO>(2, 50, 1, Collections.singletonList(item)));

        mockMvc.perform(get("/admin/api/client-usage")
                .param("current", "2")
                .param("size", "50")
                .param("keyword", "张医生")
                .header("X-Request-Id", "RID-client-usage"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.requestId").value("RID-client-usage"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].doctorWorkNo").value("0123"))
            .andExpect(jsonPath("$.data.records[0].firstInteractionTime").value("2026-08-10 09:30:00"))
            .andExpect(jsonPath("$.data.records[0].clientVersion").value("1.3.9"));

        ArgumentCaptor<ClientUsageQueryDTO> captor = ArgumentCaptor.forClass(ClientUsageQueryDTO.class);
        verify(clientUsageService).list(captor.capture(), eq(2L), eq(50L));
        assertEquals("张医生", captor.getValue().getKeyword());
    }

    @Test
    void exportShouldReturnExcelAttachment() throws Exception {
        byte[] content = "xlsx".getBytes(StandardCharsets.UTF_8);
        when(clientUsageService.exportExcel(any(ClientUsageQueryDTO.class))).thenReturn(content);

        mockMvc.perform(get("/admin/api/client-usage/export"))
            .andExpect(status().isOk())
            .andExpect(content().bytes(content))
            .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("client-usage-")));
    }
}
