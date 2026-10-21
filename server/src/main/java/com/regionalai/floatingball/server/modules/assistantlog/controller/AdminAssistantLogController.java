package com.regionalai.floatingball.server.modules.assistantlog.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.assistantlog.dto.*;
import com.regionalai.floatingball.server.modules.assistantlog.service.AssistantLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/admin/api/medical-assistant-logs")
public class AdminAssistantLogController {
    private final AssistantLogService service;
    public AdminAssistantLogController(AssistantLogService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResponse<AssistantLogItem>> list(AssistantLogQuery query,
        @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long size,
        HttpServletRequest request) {
        return ApiResponse.success(service.list(query, current, size), RequestIdUtils.resolve(request));
    }

    @GetMapping("/organizations")
    public ApiResponse<List<AssistantLogOrganization>> organizations(HttpServletRequest request) {
        return ApiResponse.success(service.organizations(), RequestIdUtils.resolve(request));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(AssistantLogQuery query) {
        return ResponseEntity.ok().header("Content-Type", "text/csv;charset=UTF-8")
            .header("Content-Disposition", "attachment; filename=medical-assistant-logs.csv")
            .body(service.exportCsv(query));
    }

    @GetMapping("/{idLog}")
    public ApiResponse<AssistantLogDetail> detail(@PathVariable String idLog, HttpServletRequest request) {
        return ApiResponse.success(service.detail(idLog), RequestIdUtils.resolve(request));
    }
}
