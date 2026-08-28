package com.regionalai.floatingball.server.modules.auth.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpDirectoryService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpOrganizationView;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpPersonView;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/admin/api/bbp")
public class AdminBbpDirectoryController {

    private final BbpDirectoryService directoryService;

    public AdminBbpDirectoryController(BbpDirectoryService directoryService) {
        this.directoryService = directoryService;
    }

    @GetMapping("/organizations")
    public ApiResponse<List<BbpOrganizationView>> organizations(HttpServletRequest request) {
        return ApiResponse.success(directoryService.organizations(AdminContextHolder.get()), RequestIdUtils.resolve(request));
    }

    @GetMapping("/organizations/{orgId}/persons")
    public ApiResponse<List<BbpPersonView>> persons(@PathVariable String orgId, HttpServletRequest request) {
        return ApiResponse.success(directoryService.persons(AdminContextHolder.get(), orgId), RequestIdUtils.resolve(request));
    }

    @GetMapping("/departments/{deptId}/persons")
    public ApiResponse<List<BbpPersonView>> personsByDepartment(@PathVariable String deptId, HttpServletRequest request) {
        return ApiResponse.success(directoryService.personsByDepartment(AdminContextHolder.get(), deptId), RequestIdUtils.resolve(request));
    }
}
