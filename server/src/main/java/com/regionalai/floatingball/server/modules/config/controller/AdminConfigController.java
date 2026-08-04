package com.regionalai.floatingball.server.modules.config.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigSaveRequest;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigTestResult;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigView;
import com.regionalai.floatingball.server.modules.config.service.ConfigConnectionTestService;
import com.regionalai.floatingball.server.modules.config.service.ConfigService;
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
@RequestMapping("/admin/api/configs")
public class AdminConfigController {

    private final ConfigService configService;
    private final ConfigConnectionTestService configConnectionTestService;

    public AdminConfigController(ConfigService configService,
                                 ConfigConnectionTestService configConnectionTestService) {
        this.configService = configService;
        this.configConnectionTestService = configConnectionTestService;
    }

    @GetMapping
    public ApiResponse<PageResponse<AiConfigView>> list(@RequestParam(defaultValue = "1") long current,
                                                        @RequestParam(defaultValue = "10") long size,
                                                        @RequestParam(required = false) String keyword,
                                                        HttpServletRequest request) {
        return ApiResponse.success(configService.list(current, size, keyword), RequestIdUtils.resolve(request));
    }

    @PostMapping
    public ApiResponse<AiConfigView> save(@RequestBody AiConfigSaveRequest body, HttpServletRequest request) {
        return ApiResponse.success(configService.save(body), RequestIdUtils.resolve(request));
    }

    @PutMapping("/{idConfig}")
    public ApiResponse<AiConfigView> update(@PathVariable String idConfig,
                                            @RequestBody AiConfigSaveRequest body,
                                            HttpServletRequest request) {
        return ApiResponse.success(configService.update(idConfig, body), RequestIdUtils.resolve(request));
    }

    @PostMapping("/test")
    public ApiResponse<AiConfigTestResult> test(@RequestBody AiConfigSaveRequest body,
                                                HttpServletRequest request) {
        return ApiResponse.success(configConnectionTestService.testMainModel(body), RequestIdUtils.resolve(request));
    }

    @PostMapping("/test/speech/realtime")
    public ApiResponse<AiConfigTestResult> testRealtimeSpeech(@RequestBody AiConfigSaveRequest body,
                                                              HttpServletRequest request) {
        return ApiResponse.success(configConnectionTestService.testRealtimeSpeech(body), RequestIdUtils.resolve(request));
    }

    @PostMapping("/test/speech/batch")
    public ApiResponse<AiConfigTestResult> testBatchSpeech(@RequestBody AiConfigSaveRequest body,
                                                           HttpServletRequest request) {
        return ApiResponse.success(configConnectionTestService.testBatchSpeech(body), RequestIdUtils.resolve(request));
    }

    @DeleteMapping("/{idConfig}")
    public ApiResponse<Void> invalidate(@PathVariable String idConfig, HttpServletRequest request) {
        configService.invalidate(idConfig);
        return ApiResponse.success(null, RequestIdUtils.resolve(request));
    }
}
