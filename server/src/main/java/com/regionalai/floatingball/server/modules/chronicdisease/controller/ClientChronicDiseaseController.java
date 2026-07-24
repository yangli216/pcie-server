package com.regionalai.floatingball.server.modules.chronicdisease.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicArtifactSnapshotResponse;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicDiseaseFollowUpRequest;
import com.regionalai.floatingball.server.modules.chronicdisease.dto.ChronicDiseaseFollowUpResponse;
import com.regionalai.floatingball.server.modules.chronicdisease.service.ChronicArtifactSnapshotService;
import com.regionalai.floatingball.server.modules.chronicdisease.service.ChronicDiseaseFollowUpService;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/client/chronic-disease")
public class ClientChronicDiseaseController {

    private final ChronicDiseaseFollowUpService followUpService;
    private final ChronicArtifactSnapshotService artifactSnapshotService;

    public ClientChronicDiseaseController(
        ChronicDiseaseFollowUpService followUpService,
        ChronicArtifactSnapshotService artifactSnapshotService
    ) {
        this.followUpService = followUpService;
        this.artifactSnapshotService = artifactSnapshotService;
    }

    @PostMapping("/follow-ups")
    public ApiResponse<ChronicDiseaseFollowUpResponse> saveFollowUp(
        @Validated @RequestBody ChronicDiseaseFollowUpRequest request,
        HttpServletRequest httpServletRequest
    ) {
        AiDevice device = DeviceContextHolder.get();
        String requestId = RequestIdUtils.resolve(httpServletRequest);
        return ApiResponse.success(
            followUpService.save(device, requestId, request),
            requestId
        );
    }

    @PostMapping("/artifact-snapshots")
    public ApiResponse<ChronicArtifactSnapshotResponse> saveArtifactSnapshot(
        @Validated @RequestBody ChronicArtifactSnapshotRequest request,
        HttpServletRequest httpServletRequest
    ) {
        AiDevice device = DeviceContextHolder.get();
        return ApiResponse.success(
            artifactSnapshotService.save(device, request),
            RequestIdUtils.resolve(httpServletRequest)
        );
    }
}
