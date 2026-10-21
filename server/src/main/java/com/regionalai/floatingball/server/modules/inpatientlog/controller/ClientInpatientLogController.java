package com.regionalai.floatingball.server.modules.inpatientlog.controller;
import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.InpatientLogSnapshot;
import com.regionalai.floatingball.server.modules.inpatientlog.service.InpatientLogService;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;
@RestController @RequestMapping("/v1/client/user-logs/inpatient-emr/events")
public class ClientInpatientLogController {
    private final InpatientLogService service;
    public ClientInpatientLogController(InpatientLogService service) { this.service = service; }
    @PostMapping public ApiResponse<Map<String, Object>> save(@RequestBody InpatientLogSnapshot snapshot, HttpServletRequest request) {
        return ApiResponse.success(service.ingest(DeviceContextHolder.get(), snapshot), RequestIdUtils.resolve(request));
    }
}
