package com.regionalai.floatingball.server.modules.knowledge.controller;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.modules.knowledge.dto.*;
import com.regionalai.floatingball.server.modules.knowledge.service.KnowledgeCatalogService;
import com.regionalai.floatingball.server.modules.knowledge.service.KnowledgeRetrievalService;
import com.regionalai.floatingball.server.security.DeviceContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/v1/knowledge")
public class KnowledgeRetrievalController {
    private final KnowledgeCatalogService catalog;
    private final KnowledgeRetrievalService retrieval;
    public KnowledgeRetrievalController(KnowledgeCatalogService catalog, KnowledgeRetrievalService retrieval) {
        this.catalog = catalog; this.retrieval = retrieval;
    }

    @GetMapping("/collections")
    public ApiResponse<KnowledgeCollectionsResponse> collections(HttpServletRequest request) {
        return ApiResponse.success(catalog.list(DeviceContextHolder.get()), RequestIdUtils.resolve(request));
    }

    @PostMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(@Validated @RequestBody KnowledgeSearchRequest query, HttpServletRequest request) {
        return ApiResponse.success(retrieval.search(DeviceContextHolder.get(), query), RequestIdUtils.resolve(request));
    }
}
