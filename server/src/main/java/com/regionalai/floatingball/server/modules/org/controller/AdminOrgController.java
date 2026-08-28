package com.regionalai.floatingball.server.modules.org.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.org.entity.AiOrg;
import com.regionalai.floatingball.server.modules.org.service.OrgService;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/admin/api/orgs")
public class AdminOrgController {

    private final OrgService orgService;
    private final AdminAuthMode adminAuthMode;

    public AdminOrgController(OrgService orgService, AdminAuthMode adminAuthMode) {
        this.orgService = orgService;
        this.adminAuthMode = adminAuthMode;
    }

    @GetMapping
    public ApiResponse<PageResponse<AiOrg>> list(@RequestParam(defaultValue = "1") long current,
                                                 @RequestParam(defaultValue = "10") long size,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String idRegion,
                                                 @RequestParam(required = false) String sdStatus,
                                                 HttpServletRequest request) {
        return ApiResponse.success(orgService.list(current, size, keyword, idRegion, sdStatus), RequestIdUtils.resolve(request));
    }

    @PostMapping
    public ApiResponse<AiOrg> save(@RequestBody AiOrg org, HttpServletRequest request) {
        adminAuthMode.requireDirectoryWritable();
        return ApiResponse.success(orgService.save(org), RequestIdUtils.resolve(request));
    }

    @PutMapping("/{idOrg}")
    public ApiResponse<AiOrg> update(@PathVariable String idOrg,
                                     @RequestBody AiOrg org,
                                     HttpServletRequest request) {
        adminAuthMode.requireDirectoryWritable();
        return ApiResponse.success(orgService.update(idOrg, org), RequestIdUtils.resolve(request));
    }

    @DeleteMapping("/{idOrg}")
    public ApiResponse<Void> invalidate(@PathVariable String idOrg, HttpServletRequest request) {
        adminAuthMode.requireDirectoryWritable();
        orgService.invalidate(idOrg);
        return ApiResponse.success(null, RequestIdUtils.resolve(request));
    }

    @PostMapping("/{idOrg}/enable")
    public ApiResponse<Void> enable(@PathVariable String idOrg, HttpServletRequest request) {
        adminAuthMode.requireDirectoryWritable();
        orgService.enable(idOrg);
        return ApiResponse.success(null, RequestIdUtils.resolve(request));
    }
}
