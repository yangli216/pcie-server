package com.regionalai.floatingball.server.modules.symptom.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.symptom.dto.BuiltinSymptomImportRequest;
import com.regionalai.floatingball.server.modules.symptom.dto.BuiltinSymptomImportResultVO;
import com.regionalai.floatingball.server.modules.symptom.dto.JsonSymptomImportRequest;
import com.regionalai.floatingball.server.modules.symptom.dto.SymptomTemplateChangeLogVO;
import com.regionalai.floatingball.server.modules.symptom.dto.SymptomTemplateVO;
import com.regionalai.floatingball.server.modules.symptom.service.SymptomTemplateChangeLogService;
import com.regionalai.floatingball.server.modules.symptom.service.SymptomTemplateService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/admin/api/symptom-templates")
public class AdminSymptomTemplateController {

    private final SymptomTemplateService symptomTemplateService;
    private final SymptomTemplateChangeLogService changeLogService;

    public AdminSymptomTemplateController(SymptomTemplateService symptomTemplateService,
                                          SymptomTemplateChangeLogService changeLogService) {
        this.symptomTemplateService = symptomTemplateService;
        this.changeLogService = changeLogService;
    }

    @GetMapping("/change-logs")
    public ApiResponse<PageResponse<SymptomTemplateChangeLogVO>> listChangeLogs(@RequestParam(defaultValue = "1") long current,
                                                                                @RequestParam(defaultValue = "10") long size,
                                                                                @RequestParam(required = false) String idTemplate,
                                                                                @RequestParam(required = false) String keyword,
                                                                                @RequestParam(required = false) String medicalMode,
                                                                                @RequestParam(required = false) String operationType,
                                                                                @RequestParam(required = false) String operatorKeyword,
                                                                                @RequestParam(required = false) String dateFrom,
                                                                                @RequestParam(required = false) String dateTo,
                                                                                HttpServletRequest request) {
        return ApiResponse.success(
            changeLogService.list(current, size, idTemplate, keyword, medicalMode, operationType, operatorKeyword, dateFrom, dateTo),
            RequestIdUtils.resolve(request)
        );
    }

    @GetMapping
    public ApiResponse<PageResponse<SymptomTemplateVO>> list(@RequestParam(defaultValue = "1") long current,
                                                             @RequestParam(defaultValue = "10") long size,
                                                             @RequestParam(required = false) String keyword,
                                                             @RequestParam(required = false) String medicalMode,
                                                             @RequestParam(required = false) String systemCategory,
                                                             @RequestParam(required = false) String sdStatus,
                                                             @RequestParam(required = false) String idRegion,
                                                             @RequestParam(required = false) String idOrg,
                                                             HttpServletRequest request) {
        return ApiResponse.success(
            symptomTemplateService.list(current, size, keyword, medicalMode, systemCategory, sdStatus, idRegion, idOrg),
            RequestIdUtils.resolve(request)
        );
    }

    @PostMapping
    public ApiResponse<SymptomTemplateVO> save(@RequestBody SymptomTemplateVO request, HttpServletRequest httpServletRequest) {
        return ApiResponse.success(symptomTemplateService.save(request), RequestIdUtils.resolve(httpServletRequest));
    }

    @PutMapping("/{id}")
    public ApiResponse<SymptomTemplateVO> update(@PathVariable String id,
                                                 @RequestBody SymptomTemplateVO request,
                                                 HttpServletRequest httpServletRequest) {
        return ApiResponse.success(symptomTemplateService.update(id, request), RequestIdUtils.resolve(httpServletRequest));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> invalidate(@PathVariable String id, HttpServletRequest request) {
        symptomTemplateService.invalidate(id);
        return ApiResponse.success(null, RequestIdUtils.resolve(request));
    }

    @PostMapping("/import-builtin")
    public ApiResponse<BuiltinSymptomImportResultVO> importBuiltin(@RequestBody BuiltinSymptomImportRequest request,
                                                                   HttpServletRequest httpServletRequest) {
        return ApiResponse.success(symptomTemplateService.importBuiltin(request), RequestIdUtils.resolve(httpServletRequest));
    }

    @PostMapping("/import-json")
    public ApiResponse<BuiltinSymptomImportResultVO> importJson(@RequestBody JsonSymptomImportRequest request,
                                                                HttpServletRequest httpServletRequest) {
        return ApiResponse.success(symptomTemplateService.importJson(request), RequestIdUtils.resolve(httpServletRequest));
    }
}
