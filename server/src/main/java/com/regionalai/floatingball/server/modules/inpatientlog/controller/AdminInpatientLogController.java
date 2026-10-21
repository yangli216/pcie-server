package com.regionalai.floatingball.server.modules.inpatientlog.controller;
import com.regionalai.floatingball.server.common.api.*;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.InpatientLogQuery;
import com.regionalai.floatingball.server.modules.inpatientlog.service.InpatientLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
@RestController @RequestMapping("/admin/api/inpatient-emr-logs")
public class AdminInpatientLogController {
    private final InpatientLogService service;
    public AdminInpatientLogController(InpatientLogService service) { this.service = service; }
    @GetMapping public ApiResponse<PageResponse<Map<String, Object>>> list(InpatientLogQuery query,
        @RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "10") long size, HttpServletRequest request) {
        return ApiResponse.success(service.list(query, current, size), RequestIdUtils.resolve(request));
    }
    @GetMapping("/options") public ApiResponse<Map<String, Object>> options(HttpServletRequest request) {
        return ApiResponse.success(service.options(), RequestIdUtils.resolve(request));
    }
    @GetMapping("/export") public ResponseEntity<byte[]> export(InpatientLogQuery query) {
        return ResponseEntity.ok().header("Content-Type", "text/csv;charset=UTF-8").header("Content-Disposition", "attachment; filename=inpatient-emr-logs.csv").body(service.exportCsv(query));
    }
    @GetMapping("/{id}") public ApiResponse<Map<String, Object>> detail(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.success(service.detail(id), RequestIdUtils.resolve(request));
    }
}
