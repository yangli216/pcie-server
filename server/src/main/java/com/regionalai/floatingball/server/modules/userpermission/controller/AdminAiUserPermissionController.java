package com.regionalai.floatingball.server.modules.userpermission.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionListView;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionBatchUpdateRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionBatchUpdateResponse;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionRecordView;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionUpdateRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionOrgRequest;
import com.regionalai.floatingball.server.modules.userpermission.service.AiUserPermissionService;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;

@RestController
@RequestMapping("/admin/api/ai-user-permissions")
public class AdminAiUserPermissionController {

    private final AiUserPermissionService permissionService;

    public AdminAiUserPermissionController(AiUserPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @GetMapping
    public ApiResponse<AiUserPermissionListView> list(@RequestParam String orgId,
                                                      @RequestParam(required = false) String keyword,
                                                      @RequestParam(required = false) Boolean configured,
                                                      @RequestParam(required = false) String departmentId,
                                                      @RequestParam(required = false) Boolean active,
                                                      @RequestParam(defaultValue = "1") int current,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      HttpServletRequest request) {
        return ApiResponse.success(
            permissionService.list(AdminContextHolder.get(), orgId, keyword, configured,
                departmentId, active, current, size),
            RequestIdUtils.resolve(request));
    }

    @PutMapping("/{personId}")
    public ApiResponse<AiUserPermissionRecordView> update(@PathVariable String personId,
                                                          @Valid @RequestBody AiUserPermissionUpdateRequest body,
                                                          HttpServletRequest request) {
        return ApiResponse.success(
            permissionService.update(AdminContextHolder.get(), personId, body),
            RequestIdUtils.resolve(request));
    }

    @PutMapping("/batch")
    public ApiResponse<AiUserPermissionBatchUpdateResponse> batchUpdate(
        @Valid @RequestBody AiUserPermissionBatchUpdateRequest body,
        HttpServletRequest request) {
        return ApiResponse.success(
            permissionService.batchUpdate(AdminContextHolder.get(), body),
            RequestIdUtils.resolve(request));
    }

    @PutMapping("/revoke-risk")
    public ApiResponse<AiUserPermissionBatchUpdateResponse> revokeRiskPermissions(
        @Valid @RequestBody AiUserPermissionOrgRequest body,
        HttpServletRequest request) {
        return ApiResponse.success(
            permissionService.revokeRiskPermissions(AdminContextHolder.get(), body),
            RequestIdUtils.resolve(request));
    }
}
