package com.regionalai.floatingball.server.modules.user.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.user.dto.AdminUserSaveRequest;
import com.regionalai.floatingball.server.modules.user.dto.AdminUserView;
import com.regionalai.floatingball.server.modules.user.service.UserService;
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
@RequestMapping("/admin/api/users")
public class AdminUserController {

    private final UserService userService;
    private final AdminAuthMode adminAuthMode;

    public AdminUserController(UserService userService, AdminAuthMode adminAuthMode) {
        this.userService = userService;
        this.adminAuthMode = adminAuthMode;
    }

    @GetMapping
    public ApiResponse<PageResponse<AdminUserView>> list(@RequestParam(defaultValue = "1") long current,
                                                         @RequestParam(defaultValue = "10") long size,
                                                         @RequestParam(required = false) String keyword,
                                                         @RequestParam(required = false) String sdStatus,
                                                         @RequestParam(required = false) String idOrg,
                                                         @RequestParam(required = false) String idRole,
                                                         HttpServletRequest request) {
        return ApiResponse.success(userService.list(current, size, keyword, sdStatus, idOrg, idRole), RequestIdUtils.resolve(request));
    }

    @PostMapping
    public ApiResponse<AdminUserView> save(@RequestBody AdminUserSaveRequest request,
                                           HttpServletRequest httpServletRequest) {
        adminAuthMode.requireDirectoryWritable();
        return ApiResponse.success(userService.save(request), RequestIdUtils.resolve(httpServletRequest));
    }

    @PutMapping("/{idUser}")
    public ApiResponse<AdminUserView> update(@PathVariable String idUser,
                                             @RequestBody AdminUserSaveRequest request,
                                             HttpServletRequest httpServletRequest) {
        adminAuthMode.requireDirectoryWritable();
        return ApiResponse.success(userService.update(idUser, request), RequestIdUtils.resolve(httpServletRequest));
    }

    @PostMapping("/{idUser}/enable")
    public ApiResponse<AdminUserView> enable(@PathVariable String idUser, HttpServletRequest request) {
        adminAuthMode.requireDirectoryWritable();
        return ApiResponse.success(userService.enable(idUser), RequestIdUtils.resolve(request));
    }

    @PostMapping("/{idUser}/disable")
    public ApiResponse<AdminUserView> disable(@PathVariable String idUser, HttpServletRequest request) {
        adminAuthMode.requireDirectoryWritable();
        return ApiResponse.success(userService.disable(idUser), RequestIdUtils.resolve(request));
    }

    @DeleteMapping("/{idUser}")
    public ApiResponse<Void> invalidate(@PathVariable String idUser, HttpServletRequest request) {
        adminAuthMode.requireDirectoryWritable();
        userService.invalidate(idUser);
        return ApiResponse.success(null, RequestIdUtils.resolve(request));
    }
}
