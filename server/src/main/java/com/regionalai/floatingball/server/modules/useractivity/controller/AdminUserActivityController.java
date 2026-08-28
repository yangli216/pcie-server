package com.regionalai.floatingball.server.modules.useractivity.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.useractivity.dto.RegionTreeNodeVO;
import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivityItemVO;
import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivityQueryDTO;
import com.regionalai.floatingball.server.modules.useractivity.dto.UserActivitySummaryVO;
import com.regionalai.floatingball.server.modules.useractivity.service.UserActivityService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/admin/api/user-activity")
public class AdminUserActivityController {

    private final UserActivityService userActivityService;
    private final BbpStatisticsScope statisticsScope;

    public AdminUserActivityController(UserActivityService userActivityService,
                                       BbpStatisticsScope statisticsScope) {
        this.userActivityService = userActivityService;
        this.statisticsScope = statisticsScope;
    }

    @GetMapping("/summary")
    public ApiResponse<UserActivitySummaryVO> summary(UserActivityQueryDTO query, HttpServletRequest request) {
        applyScope(query);
        return ApiResponse.success(userActivityService.getSummary(query), RequestIdUtils.resolve(request));
    }

    @GetMapping("/region-tree")
    public ApiResponse<List<RegionTreeNodeVO>> regionTree(UserActivityQueryDTO query, HttpServletRequest request) {
        applyScope(query);
        return ApiResponse.success(userActivityService.getRegionTree(query), RequestIdUtils.resolve(request));
    }

    @GetMapping("/users")
    public ApiResponse<PageResponse<UserActivityItemVO>> users(UserActivityQueryDTO query,
                                                               @RequestParam(defaultValue = "1") long current,
                                                               @RequestParam(defaultValue = "10") long size,
                                                               HttpServletRequest request) {
        applyScope(query);
        return ApiResponse.success(userActivityService.getUserList(query, current, size), RequestIdUtils.resolve(request));
    }

    @GetMapping("/export")
    public ResponseEntity<Resource> export(UserActivityQueryDTO query) {
        applyScope(query);
        byte[] data = userActivityService.exportExcel(query);
        String fileName = "user-activity-" + System.currentTimeMillis() + ".xlsx";
        ByteArrayResource resource = new ByteArrayResource(data);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(fileName, StandardCharsets.UTF_8)
                .build()
                .toString())
            .contentLength(data.length)
            .body(resource);
    }

    private void applyScope(UserActivityQueryDTO query) {
        BbpStatisticsScope.Scope scope = statisticsScope.resolve(AdminContextHolder.get(), query.getHisOrgId());
        if (scope.isRestricted()) {
            query.setHisOrgId(scope.getHisOrgId());
            query.setIdRegion(null);
            query.setIdOrg(null);
        }
    }
}
