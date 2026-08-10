package com.regionalai.floatingball.server.modules.device.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.common.web.ClientIpUtils;
import com.regionalai.floatingball.server.modules.audit.dto.AuditBatchRequest;
import com.regionalai.floatingball.server.modules.audit.dto.AuditBatchResponse;
import com.regionalai.floatingball.server.modules.audit.service.AuditService;
import com.regionalai.floatingball.server.modules.config.dto.BootstrapVO;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
import com.regionalai.floatingball.server.modules.datapackage.dto.MappingDeltaVO;
import com.regionalai.floatingball.server.modules.datapackage.dto.TemplateDeltaVO;
import com.regionalai.floatingball.server.modules.datapackage.service.DataPackageService;
import com.regionalai.floatingball.server.modules.device.dto.HeartbeatRequest;
import com.regionalai.floatingball.server.modules.device.dto.HeartbeatResponse;
import com.regionalai.floatingball.server.modules.device.dto.RegisterDeviceRequest;
import com.regionalai.floatingball.server.modules.device.dto.RegisterDeviceResponse;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptDeltaVO;
import com.regionalai.floatingball.server.modules.prompt.service.PromptService;
import com.regionalai.floatingball.server.modules.symptom.service.SymptomTemplateService;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/client")
public class ClientController {

    private final DeviceService deviceService;
    private final ConfigService configService;
    private final PromptService promptService;
    private final DataPackageService dataPackageService;
    private final SymptomTemplateService symptomTemplateService;
    private final AuditService auditService;

    public ClientController(DeviceService deviceService,
                            ConfigService configService,
                            PromptService promptService,
                            DataPackageService dataPackageService,
                            SymptomTemplateService symptomTemplateService,
                            AuditService auditService) {
        this.deviceService = deviceService;
        this.configService = configService;
        this.promptService = promptService;
        this.dataPackageService = dataPackageService;
        this.symptomTemplateService = symptomTemplateService;
        this.auditService = auditService;
    }

    @PostMapping("/register")
    public ApiResponse<RegisterDeviceResponse> register(@Validated @RequestBody RegisterDeviceRequest request,
                                                        HttpServletRequest httpServletRequest) {
        return ApiResponse.success(
            deviceService.register(request, ClientIpUtils.resolve(httpServletRequest), resolveBearerToken(httpServletRequest)),
            RequestIdUtils.resolve(httpServletRequest));
    }

    @PostMapping("/heartbeat")
    public ApiResponse<HeartbeatResponse> heartbeat(@RequestBody(required = false) HeartbeatRequest request,
                                                    HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        deviceService.heartbeat(
            device,
            ClientIpUtils.resolve(httpServletRequest),
            httpServletRequest.getHeader("X-Client-Version")
        );
        return ApiResponse.success(new HeartbeatResponse("ok", System.currentTimeMillis()), RequestIdUtils.resolve(httpServletRequest));
    }

    @GetMapping("/bootstrap")
    public ApiResponse<BootstrapVO> bootstrap(HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        return ApiResponse.success(configService.buildBootstrap(device), RequestIdUtils.resolve(httpServletRequest));
    }

    @GetMapping("/prompts/delta")
    public ApiResponse<PromptDeltaVO> promptDelta(@RequestParam(value = "version", required = false) String version,
                                                  HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        return ApiResponse.success(promptService.getDelta(device.getIdOrg(), device.getIdRegion(), version), RequestIdUtils.resolve(httpServletRequest));
    }

    @GetMapping("/templates/delta")
    public ApiResponse<TemplateDeltaVO> templateDelta(@RequestParam(value = "version", required = false) String version,
                                                      HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        return ApiResponse.success(symptomTemplateService.getClientDelta(device.getIdOrg(), device.getIdRegion(), version), RequestIdUtils.resolve(httpServletRequest));
    }

    @GetMapping("/mappings/delta")
    public ApiResponse<MappingDeltaVO> mappingDelta(@RequestParam(value = "version", required = false) String version,
                                                    HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        return ApiResponse.success(dataPackageService.getMappingDelta(device.getIdOrg(), device.getIdRegion(), version), RequestIdUtils.resolve(httpServletRequest));
    }

    @PostMapping("/audit/events/batch")
    public ApiResponse<AuditBatchResponse> auditBatch(@RequestBody AuditBatchRequest request,
                                                      HttpServletRequest httpServletRequest) {
        AiDevice device = DeviceContextHolder.get();
        int accepted = auditService.saveBatch(device, request);
        return ApiResponse.success(new AuditBatchResponse(accepted), RequestIdUtils.resolve(httpServletRequest));
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return null;
        }
        String token = authHeader.substring("Bearer ".length()).trim();
        return token.isEmpty() ? null : token;
    }
}
