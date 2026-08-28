package com.regionalai.floatingball.server.modules.emrtemplate.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.emrtemplate.dto.OutpatientEmrTemplateSnapshotVO;
import com.regionalai.floatingball.server.modules.emrtemplate.service.OutpatientEmrTemplateSnapshotService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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
}
