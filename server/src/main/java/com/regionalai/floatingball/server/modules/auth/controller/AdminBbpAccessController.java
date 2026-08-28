package com.regionalai.floatingball.server.modules.auth.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpAdminAccessService;
import com.regionalai.floatingball.server.modules.auth.dto.BbpAdminAccessUpdateRequest;
import com.regionalai.floatingball.server.modules.auth.dto.BbpAdminAccessView;
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
import java.util.List;

@RestController
@RequestMapping("/admin/api/bbp/admin-access")
public class AdminBbpAccessController {

    private final BbpAdminAccessService accessService;

    public AdminBbpAccessController(BbpAdminAccessService accessService) {
        this.accessService = accessService;
    }

    @GetMapping
    public ApiResponse<List<BbpAdminAccessView>> list(@RequestParam String orgId,
                                                      @RequestParam(required = false) String roleCode,
                                                      HttpServletRequest request) {
        return ApiResponse.success(accessService.list(AdminContextHolder.get(), orgId, roleCode),
            RequestIdUtils.resolve(request));
    }

    @PutMapping("/{bbpUserId}")
    public ApiResponse<BbpAdminAccessView> update(@PathVariable String bbpUserId,
                                                  @Valid @RequestBody BbpAdminAccessUpdateRequest body,
                                                  HttpServletRequest request) {
        return ApiResponse.success(accessService.update(AdminContextHolder.get(), bbpUserId, body),
            RequestIdUtils.resolve(request));
    }
}
