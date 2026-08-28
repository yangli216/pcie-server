package com.regionalai.floatingball.server.modules.release.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseBatchUploadRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseHistoryView;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyUpdateRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseRollbackRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseUploadRequest;
import com.regionalai.floatingball.server.modules.release.dto.ReleaseView;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/admin/api/releases")
public class AdminReleaseController {

    private final ReleaseService releaseService;

    public AdminReleaseController(ReleaseService releaseService) {
        this.releaseService = releaseService;
    }

    @GetMapping
    public ApiResponse<List<ReleaseView>> list(@RequestParam(required = false) String channel,
                                               HttpServletRequest request) {
        return ApiResponse.success(releaseService.list(channel), RequestIdUtils.resolve(request));
    }

    @GetMapping("/history")
    public ApiResponse<PageResponse<ReleaseHistoryView>> history(@RequestParam(required = false) String channel,
                                                                 @RequestParam(defaultValue = "1") long current,
                                                                 @RequestParam(defaultValue = "10") long size,
                                                                 HttpServletRequest request) {
        return ApiResponse.success(releaseService.history(channel, current, size), RequestIdUtils.resolve(request));
    }

    @PostMapping("/upload")
    public ApiResponse<ReleaseView> upload(@ModelAttribute ReleaseUploadRequest uploadRequest,
                                           HttpServletRequest request) {
        return ApiResponse.success(releaseService.upload(uploadRequest), RequestIdUtils.resolve(request));
    }

    @PostMapping("/upload/batch")
    public ApiResponse<List<ReleaseView>> uploadBatch(@ModelAttribute ReleaseBatchUploadRequest uploadRequest,
                                                      HttpServletRequest request) {
        return ApiResponse.success(releaseService.uploadBatch(uploadRequest), RequestIdUtils.resolve(request));
    }

    @PostMapping("/policy")
    public ApiResponse<ReleaseView> updatePolicy(@RequestBody ReleasePolicyUpdateRequest updateRequest,
                                                 HttpServletRequest request) {
        return ApiResponse.success(releaseService.updatePolicy(updateRequest), RequestIdUtils.resolve(request));
    }

    @PostMapping("/rollback")
    public ApiResponse<ReleaseView> rollback(@RequestBody ReleaseRollbackRequest rollbackRequest,
                                             HttpServletRequest request) {
        return ApiResponse.success(releaseService.rollback(rollbackRequest), RequestIdUtils.resolve(request));
    }
}
