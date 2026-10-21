package com.regionalai.floatingball.server.modules.knowledge.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionDetail;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionSearchRequest;
import com.regionalai.floatingball.server.modules.knowledge.dto.DrugInstructionSearchResponse;
import com.regionalai.floatingball.server.modules.knowledge.service.DrugInstructionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.servlet.http.HttpServletRequest;
import javax.validation.Valid;
import java.util.Optional;

@RestController
@RequestMapping("/v1/knowledge/drug-instructions")
public class DrugInstructionController {
    private final DrugInstructionService service;

    public DrugInstructionController(DrugInstructionService service) {
        this.service = service;
    }

    @PostMapping("/search")
    public ApiResponse<DrugInstructionSearchResponse> search(@Valid @RequestBody DrugInstructionSearchRequest request,
                                                             HttpServletRequest httpRequest) {
        return ApiResponse.success(service.search(request), RequestIdUtils.resolve(httpRequest));
    }

    @GetMapping("/{productId}")
    public ResponseEntity<ApiResponse<DrugInstructionDetail>> detail(@PathVariable String productId, HttpServletRequest request) {
        Optional<DrugInstructionDetail> detail = service.find(productId);
        String requestId = RequestIdUtils.resolve(request);
        return detail.isPresent() ? ResponseEntity.ok(ApiResponse.success(detail.get(), requestId))
            : ResponseEntity.status(404).body(ApiResponse.error("DRUG_INSTRUCTION_NOT_FOUND", "未找到该药品说明书，请重新检索", requestId));
    }
}
