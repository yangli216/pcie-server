package com.regionalai.floatingball.server.modules.emrqc.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.ForbiddenException;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalyzeRequest;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcDetailVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcDoctorVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcRecordVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcReviewRequest;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcSessionVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcTaskCreateRequest;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcTaskCreateVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcTaskVO;
import com.regionalai.floatingball.server.modules.emrqc.service.EmrQcService;
import com.regionalai.floatingball.server.modules.emrqc.service.EmrQcTaskService;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

@RestController
@RequestMapping("/admin/api/emr-qc")
public class AdminEmrQcController {

    private final EmrQcService emrQcService;
    private final EmrQcTaskService taskService;

    public AdminEmrQcController(EmrQcService emrQcService, EmrQcTaskService taskService) {
        this.emrQcService = emrQcService;
        this.taskService = taskService;
    }

    @GetMapping("/session")
    public ApiResponse<EmrQcSessionVO> session(HttpServletRequest request) {
        return ApiResponse.success(emrQcService.session(requireUser()), RequestIdUtils.resolve(request));
    }

    @GetMapping("/doctors")
    public ApiResponse<List<EmrQcDoctorVO>> doctors(HttpServletRequest request) {
        return ApiResponse.success(emrQcService.doctors(requireUser()), RequestIdUtils.resolve(request));
    }

    @GetMapping("/records")
    public ApiResponse<PageResponse<EmrQcRecordVO>> records(
        @RequestParam(required = false) String dateFrom,
        @RequestParam(required = false) String dateTo,
        @RequestParam(required = false) String patientName,
        @RequestParam(required = false) String idCard,
        @RequestParam(required = false) String doctorUserId,
        @RequestParam(required = false) String deptName,
        @RequestParam(required = false) String qcStatus,
        @RequestParam(defaultValue = "1") long current,
        @RequestParam(defaultValue = "10") long size,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            emrQcService.list(requireUser(), dateFrom, dateTo, patientName, idCard, doctorUserId, qcStatus, current, size, deptName),
            RequestIdUtils.resolve(request));
    }

    @GetMapping("/records/{idMedrecdoc}")
    public ApiResponse<EmrQcDetailVO> detail(@PathVariable String idMedrecdoc, HttpServletRequest request) {
        return ApiResponse.success(
            emrQcService.detail(requireUser(), idMedrecdoc, RequestIdUtils.resolve(request)),
            RequestIdUtils.resolve(request));
    }

    @PostMapping("/records/{idMedrecdoc}/analyze")
    public ApiResponse<EmrQcAnalysisVO> analyze(
        @PathVariable String idMedrecdoc,
        @RequestBody(required = false) EmrQcAnalyzeRequest body,
        HttpServletRequest request
    ) {
        boolean force = body != null && body.forceEnabled();
        return ApiResponse.success(
            emrQcService.analyze(requireUser(), idMedrecdoc, force, RequestIdUtils.resolve(request)),
            RequestIdUtils.resolve(request));
    }

    @PutMapping("/records/{idMedrecdoc}/review")
    public ApiResponse<EmrQcAnalysisVO> review(
        @PathVariable String idMedrecdoc,
        @RequestBody EmrQcReviewRequest body,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            emrQcService.review(requireUser(), idMedrecdoc, body, RequestIdUtils.resolve(request)),
            RequestIdUtils.resolve(request));
    }

    @PostMapping("/tasks")
    public ApiResponse<EmrQcTaskCreateVO> createTask(
        @RequestBody EmrQcTaskCreateRequest body,
        HttpServletRequest request
    ) {
        return ApiResponse.success(
            taskService.create(requireUser(), body, RequestIdUtils.resolve(request)),
            RequestIdUtils.resolve(request));
    }

    @GetMapping("/tasks/latest")
    public ApiResponse<EmrQcTaskVO> latestTask(HttpServletRequest request) {
        return ApiResponse.success(taskService.latest(requireUser()), RequestIdUtils.resolve(request));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<EmrQcTaskVO> task(@PathVariable String taskId, HttpServletRequest request) {
        return ApiResponse.success(
            taskService.get(requireUser(), taskId, RequestIdUtils.resolve(request)),
            RequestIdUtils.resolve(request));
    }

    private AdminCurrentUser requireUser() {
        AdminCurrentUser user = AdminContextHolder.get();
        if (user == null) {
            throw new ForbiddenException("AUTH-403", "当前登录状态无效，请重新登录后台");
        }
        return user;
    }
}
