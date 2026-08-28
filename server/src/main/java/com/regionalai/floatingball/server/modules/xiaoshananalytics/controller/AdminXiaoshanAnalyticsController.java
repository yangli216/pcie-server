package com.regionalai.floatingball.server.modules.xiaoshananalytics.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageQueryDTO;
import com.regionalai.floatingball.server.modules.analytics.dto.FunctionUsageResponseVO;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
import com.regionalai.floatingball.server.modules.xiaoshananalytics.service.XiaoshanFunctionUsageService;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/admin/api/xiaoshan-analytics")
public class AdminXiaoshanAnalyticsController {

    private final XiaoshanFunctionUsageService functionUsageService;
    private final BbpStatisticsScope statisticsScope;

    public AdminXiaoshanAnalyticsController(XiaoshanFunctionUsageService functionUsageService,
                                            BbpStatisticsScope statisticsScope) {
        this.functionUsageService = functionUsageService;
        this.statisticsScope = statisticsScope;
    }

    @GetMapping("/function-modules")
    public ApiResponse<List<String>> functionModules(HttpServletRequest request) {
        return ApiResponse.success(functionUsageService.getFunctionModuleOptions(), RequestIdUtils.resolve(request));
    }

    @GetMapping("/function-usage")
    public ApiResponse<FunctionUsageResponseVO> functionUsage(FunctionUsageQueryDTO query, HttpServletRequest request) {
        applyScope(query);
        return ApiResponse.success(functionUsageService.getFunctionUsage(query), RequestIdUtils.resolve(request));
    }

    @GetMapping("/function-usage/export")
    public ResponseEntity<Resource> exportFunctionUsage(FunctionUsageQueryDTO query) {
        applyScope(query);
        byte[] data = functionUsageService.exportFunctionUsageExcel(query);
        ByteArrayResource resource = new ByteArrayResource(data);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("xiaoshan-function-usage-" + System.currentTimeMillis() + ".xlsx", StandardCharsets.UTF_8)
                .build()
                .toString())
            .contentLength(data.length)
            .body(resource);
    }

    private void applyScope(FunctionUsageQueryDTO query) {
        BbpStatisticsScope.Scope scope = statisticsScope.resolve(AdminContextHolder.get(), query.getHisOrgId());
        if (scope.isRestricted()) {
            query.setHisOrgId(scope.getHisOrgId());
            query.setIdRegion(null);
            query.setIdOrg(null);
        }
    }
}
