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
import org.springframework.web.bind.annotation.PostMapping;
import com.fasterxml.jackson.databind.JsonNode;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import java.util.Map;
import java.util.LinkedHashMap;
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
        @RequestParam(required = false) String templateState,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            templateState == null ? snapshotService.list(current, size, keyword) : snapshotService.list(current, size, keyword, templateState),
            RequestIdUtils.resolve(request)
        );
    }

    @GetMapping({"/{idSnapshot}", "/{idSnapshot}/field-rules"})
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
        @RequestParam(required = false) Long expectedRevision,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            expectedRevision == null ? snapshotService.clearMapping(idSnapshot, fieldId) : snapshotService.clearMapping(idSnapshot, fieldId, expectedRevision),
            RequestIdUtils.resolve(request)
        );
    }

    @PostMapping("/{idSnapshot}/field-rules/draft")
    public ApiResponse<Map<String,Object>> draft(@PathVariable String idSnapshot, @RequestBody JsonNode body, HttpServletRequest request) {
        return ApiResponse.success(snapshotService.draftRule(idSnapshot, body), RequestIdUtils.resolve(request));
    }

    @PutMapping("/{idSnapshot}/field-rules")
    public ApiResponse<OutpatientEmrTemplateSnapshotVO> saveRule(@PathVariable String idSnapshot, @RequestBody JsonNode body, HttpServletRequest request) {
        return ApiResponse.success(snapshotService.saveRule(idSnapshot, body), RequestIdUtils.resolve(request));
    }

    @PutMapping("/{idSnapshot}/state")
    public ApiResponse<OutpatientEmrTemplateSnapshotVO> state(@PathVariable String idSnapshot, @RequestBody JsonNode body, HttpServletRequest request) {
        if (body.size() != 2 || !body.path("templateState").isTextual() || "deleted".equals(body.path("templateState").textValue()))
            throw new BusinessException("请选择启用或停用");
        return ApiResponse.success(snapshotService.changeState(idSnapshot, OutpatientEmrTemplateSnapshotService.expectedRevision(body),
            body.get("templateState").textValue()), RequestIdUtils.resolve(request));
    }

    @DeleteMapping("/{idSnapshot}")
    public ApiResponse<Map<String,Object>> delete(@PathVariable String idSnapshot, @RequestParam long expectedRevision, HttpServletRequest request) {
        OutpatientEmrTemplateSnapshotVO item = snapshotService.changeState(idSnapshot, expectedRevision, "deleted");
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("id", item.getId());
        result.put("templateState", item.getTemplateState());
        result.put("revision", item.getRevision());
        return ApiResponse.success(result, RequestIdUtils.resolve(request));
    }
}
