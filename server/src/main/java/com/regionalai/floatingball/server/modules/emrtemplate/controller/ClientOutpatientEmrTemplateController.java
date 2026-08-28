package com.regionalai.floatingball.server.modules.emrtemplate.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotReceipt;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotResolution;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotResolveRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.service.OutpatientEmrTemplateSnapshotService;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/client/outpatient-emr/templates")
public class ClientOutpatientEmrTemplateController {

    private final OutpatientEmrTemplateSnapshotService snapshotService;

    public ClientOutpatientEmrTemplateController(OutpatientEmrTemplateSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @PostMapping("/snapshots/resolve")
    public ApiResponse<OutpatientEmrTemplateSnapshotResolution> resolve(
        @RequestBody OutpatientEmrTemplateSnapshotResolveRequest request,
        HttpServletRequest httpServletRequest
    ) {
        AiDevice device = DeviceContextHolder.get();
        return ApiResponse.success(
            snapshotService.resolve(device, request),
            RequestIdUtils.resolve(httpServletRequest)
        );
    }

    @PostMapping("/snapshots")
    public ApiResponse<OutpatientEmrTemplateSnapshotReceipt> save(
        @RequestBody OutpatientEmrTemplateSnapshotRequest request,
        HttpServletRequest httpServletRequest
    ) {
        AiDevice device = DeviceContextHolder.get();
        return ApiResponse.success(
            snapshotService.save(device, request),
            RequestIdUtils.resolve(httpServletRequest)
        );
    }
}
