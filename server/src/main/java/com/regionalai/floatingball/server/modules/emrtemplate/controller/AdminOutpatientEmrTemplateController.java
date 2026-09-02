package com.regionalai.floatingball.server.modules.emrtemplate.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateMappingRequest;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotVO;
import com.regionalai.floatingball.server.modules.emrtemplate.service.OutpatientEmrTemplateSnapshotService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/admin/api/outpatient-emr/templates")
public class AdminOutpatientEmrTemplateController {

    private final OutpatientEmrTemplateSnapshotService snapshotService;

    public AdminOutpatientEmrTemplateController(OutpatientEmrTemplateSnapshotService snapshotService) {
        this.snapshotService = snapshotService;
    }

    @GetMapping
    public ApiResponse<PageResponse<OutpatientEmrTemplateSnapshotVO>> list(
        @RequestParam(defaultValue = "1") long current,
        @RequestParam(defaultValue = "10") long size,
        @RequestParam(required = false) String keyword,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            snapshotService.list(current, size, keyword),
            RequestIdUtils.resolve(request)
        );
    }

    @GetMapping("/{idSnapshot}")
    public ApiResponse<OutpatientEmrTemplateSnapshotVO> get(
        @PathVariable String idSnapshot,
        HttpServletRequest request
    ) {
        return ApiResponse.success(snapshotService.get(idSnapshot), RequestIdUtils.resolve(request));
    }

    @PutMapping("/{idSnapshot}/mapping")
    public ApiResponse<OutpatientEmrTemplateSnapshotVO> updateMapping(
        @PathVariable String idSnapshot,
        @RequestBody OutpatientEmrTemplateMappingRequest mappingRequest,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            snapshotService.updateMapping(idSnapshot, mappingRequest),
            RequestIdUtils.resolve(request)
        );
    }

    @DeleteMapping("/{idSnapshot}/mapping")
    public ApiResponse<OutpatientEmrTemplateSnapshotVO> clearMapping(
        @PathVariable String idSnapshot,
        @RequestParam String fieldId,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            snapshotService.clearMapping(idSnapshot, fieldId),
            RequestIdUtils.resolve(request)
        );
    }
}
