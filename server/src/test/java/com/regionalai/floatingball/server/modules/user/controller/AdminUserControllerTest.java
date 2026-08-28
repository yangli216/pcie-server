package com.regionalai.floatingball.server.modules.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.GlobalExceptionHandler;
import com.regionalai.floatingball.server.modules.user.dto.AdminUserSaveRequest;
import com.regionalai.floatingball.server.modules.user.dto.AdminUserView;
import com.regionalai.floatingball.server.modules.user.service.UserService;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerTest {

    @Mock
    private UserService userService;

    @Mock
    private AdminAuthMode adminAuthMode;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminUserController(userService, adminAuthMode))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    }

    @Test
    void listShouldReturnPagedUsers() throws Exception {
        AdminUserView user = new AdminUserView();
        user.setIdUser("USER001");
        user.setCdUser("zhangsan");
        user.setNaUser("张三");
        user.setIdOrg("ORG001");
        user.setNaOrg("默认机构");
        user.setRoleIds(Collections.singletonList("ROLE001"));
        user.setRoleNames(Collections.singletonList("系统管理员"));
        user.setSdStatus("1");

        PageResponse<AdminUserView> pageResponse = new PageResponse<AdminUserView>(1, 10, 1, Collections.singletonList(user));
        when(userService.list(1, 10, "张", "1", "ORG001", "ROLE001")).thenReturn(pageResponse);

        mockMvc.perform(get("/admin/api/users")
                .param("current", "1")
                .param("size", "10")
                .param("keyword", "张")
                .param("sdStatus", "1")
                .param("idOrg", "ORG001")
                .param("idRole", "ROLE001")
                .header("X-Request-Id", "RID-user-list"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-user-list"))
            .andExpect(jsonPath("$.data.total").value(1))
            .andExpect(jsonPath("$.data.records[0].cdUser").value("zhangsan"))
            .andExpect(jsonPath("$.data.records[0].naOrg").value("默认机构"))
            .andExpect(jsonPath("$.data.records[0].roleNames[0]").value("系统管理员"));
    }

    @Test
    void saveShouldReturnCreatedUser() throws Exception {
        AdminUserView user = new AdminUserView();
        user.setIdUser("USER001");
        user.setCdUser("zhangsan");
        user.setNaUser("张三");
        user.setIdOrg("ORG001");
        user.setRoleIds(Arrays.asList("ROLE001", "ROLE002"));
        user.setRoleNames(Arrays.asList("系统管理员", "审计员"));
        user.setSdStatus("1");

        AdminUserSaveRequest request = new AdminUserSaveRequest();
        request.setCdUser("zhangsan");
        request.setNaUser("张三");
        request.setPassword("123456");
        request.setIdOrg("ORG001");
        request.setRoleIds(Arrays.asList("ROLE001", "ROLE002"));
        request.setSdStatus("1");

        when(userService.save(org.mockito.ArgumentMatchers.any(AdminUserSaveRequest.class))).thenReturn(user);

        mockMvc.perform(post("/admin/api/users")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Request-Id", "RID-user-save")
                .content(objectMapper.writeValueAsString(request)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-user-save"))
            .andExpect(jsonPath("$.data.idUser").value("USER001"))
            .andExpect(jsonPath("$.data.roleIds[1]").value("ROLE002"));
    }

    @Test
    void invalidateShouldDelegateToService() throws Exception {
        mockMvc.perform(delete("/admin/api/users/USER001")
                .header("X-Request-Id", "RID-user-delete"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-user-delete"));

        verify(userService).invalidate(eq("USER001"));
    }

    @Test
    void enableShouldDelegateToService() throws Exception {
        AdminUserView user = new AdminUserView();
        user.setIdUser("USER001");
        user.setCdUser("zhangsan");
        user.setSdStatus("1");

        when(userService.enable("USER001")).thenReturn(user);

        mockMvc.perform(post("/admin/api/users/USER001/enable")
                .header("X-Request-Id", "RID-user-enable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-user-enable"))
            .andExpect(jsonPath("$.data.sdStatus").value("1"));

        verify(userService).enable(eq("USER001"));
    }

    @Test
    void disableShouldDelegateToService() throws Exception {
        AdminUserView user = new AdminUserView();
        user.setIdUser("USER001");
        user.setCdUser("zhangsan");
        user.setSdStatus("0");

        when(userService.disable("USER001")).thenReturn(user);

        mockMvc.perform(post("/admin/api/users/USER001/disable")
                .header("X-Request-Id", "RID-user-disable"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("0"))
            .andExpect(jsonPath("$.requestId").value("RID-user-disable"))
            .andExpect(jsonPath("$.data.sdStatus").value("0"));

        verify(userService).disable(eq("USER001"));
    }
}
