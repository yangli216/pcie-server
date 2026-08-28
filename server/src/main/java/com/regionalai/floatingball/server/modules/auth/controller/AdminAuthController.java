package com.regionalai.floatingball.server.modules.auth.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.AdminLoginRequest;
import com.regionalai.floatingball.server.modules.auth.dto.AdminLoginResponse;
import com.regionalai.floatingball.server.modules.auth.dto.AdminPasswordChangeRequest;
import com.regionalai.floatingball.server.modules.auth.dto.AdminAuthCapabilities;
import com.regionalai.floatingball.server.modules.auth.dto.BbpLoginRequest;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpAuthService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpDirectoryService;
import com.regionalai.floatingball.server.modules.auth.service.AdminAuthService;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/admin/api/auth")
public class AdminAuthController {

    private final AdminAuthService adminAuthService;
    private final BbpAuthService bbpAuthService;
    private final BbpDirectoryService bbpDirectoryService;

    public AdminAuthController(AdminAuthService adminAuthService,
                               BbpAuthService bbpAuthService,
                               BbpDirectoryService bbpDirectoryService) {
        this.adminAuthService = adminAuthService;
        this.bbpAuthService = bbpAuthService;
        this.bbpDirectoryService = bbpDirectoryService;
    }

    @GetMapping("/capabilities")
    public ApiResponse<AdminAuthCapabilities> capabilities(HttpServletRequest request) {
        return ApiResponse.success(bbpAuthService.capabilities(), RequestIdUtils.resolve(request));
    }

    @PostMapping("/login")
    public ApiResponse<AdminLoginResponse> login(@Valid @RequestBody AdminLoginRequest request,
                                                 HttpServletRequest httpServletRequest) {
        return ApiResponse.success(adminAuthService.login(request), RequestIdUtils.resolve(httpServletRequest));
    }

    @PostMapping("/bbp/login")
    public ApiResponse<AdminLoginResponse> bbpLogin(@Valid @RequestBody BbpLoginRequest request,
                                                    HttpServletRequest httpServletRequest) {
        return ApiResponse.success(bbpAuthService.login(request), RequestIdUtils.resolve(httpServletRequest));
    }

    @GetMapping("/me")
    public ApiResponse<AdminCurrentUser> me(HttpServletRequest request) {
        return ApiResponse.success(adminAuthService.currentUser(AdminContextHolder.get()), RequestIdUtils.resolve(request));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, String>> logout(HttpServletRequest request) {
        bbpDirectoryService.logout(AdminContextHolder.get());
        return ApiResponse.success(Collections.singletonMap("status", "ok"), RequestIdUtils.resolve(request));
    }

    @PutMapping("/password")
    public ApiResponse<Map<String, String>> changePassword(@Valid @RequestBody AdminPasswordChangeRequest request,
                                                           HttpServletRequest httpServletRequest) {
        adminAuthService.changePassword(AdminContextHolder.get(), request);
        return ApiResponse.success(Collections.singletonMap("status", "ok"), RequestIdUtils.resolve(httpServletRequest));
    }
}
