package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/client/chronic-disease/examination-recommendations")
public class ClientChronicExaminationController {
    private final ChronicExaminationService service;
    public ClientChronicExaminationController(ChronicExaminationService service) { this.service = service; }
    @PostMapping("/query")
    public ApiResponse<ExaminationRecommendationResponse> query(@Validated @RequestBody ExaminationRecommendationQuery query,
                                                               HttpServletRequest request) {
        return ApiResponse.success(service.query(query), RequestIdUtils.resolve(request));
    }
}
