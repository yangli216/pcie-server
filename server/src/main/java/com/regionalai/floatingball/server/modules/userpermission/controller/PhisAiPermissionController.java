package com.regionalai.floatingball.server.modules.userpermission.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.userpermission.dto.PhisAiPermissionCheckRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.PhisAiPermissionCheckResponse;
import com.regionalai.floatingball.server.modules.userpermission.service.AiUserPermissionService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/integration/api/phis/ai-permissions")
public class PhisAiPermissionController {

    private final AiUserPermissionService permissionService;

    public PhisAiPermissionController(AiUserPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @PostMapping("/check")
    public ApiResponse<PhisAiPermissionCheckResponse> check(
        @Valid @RequestBody PhisAiPermissionCheckRequest body,
        HttpServletRequest request) {
        return ApiResponse.success(permissionService.check(body), RequestIdUtils.resolve(request));
    }
}
