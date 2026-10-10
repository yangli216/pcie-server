package com.regionalai.floatingball.server.modules.knowledge.service;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.knowledge.dto.*;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;

@Service
public class KnowledgeRetrievalService {
    private final KnowledgeCatalogService catalog;
    private final KnowledgeRetrievalProvider provider;

    public KnowledgeRetrievalService(KnowledgeCatalogService catalog, KnowledgeRetrievalProvider provider) {
        this.catalog = catalog; this.provider = provider;
    }

    public KnowledgeSearchResponse search(AiDevice device, KnowledgeSearchRequest request) {
        if (request == null || request.getQuery() == null || request.getQuery().trim().isEmpty()
                || request.getQuery().length() > 2000 || request.getCollectionId() == null || request.getReleaseVersion() == null
                || request.getLimit() == null || request.getLimit() < 1 || request.getLimit() > 20) {
            throw new BusinessException("VALIDATION-001", "知识检索参数不正确");
        }
        KnowledgeCatalogService.Release release = catalog.resolve(device, request.getCollectionId(), request.getReleaseVersion());
        int limit = request.getLimit();
        String query = request.getQuery().trim();
        List<KnowledgeProviderHit> hits = provider.search(release, query, limit);
        List<KnowledgeEvidence> evidence = new ArrayList<KnowledgeEvidence>();
        Set<String> seen = new HashSet<String>();
        for (KnowledgeProviderHit hit : hits) {
            KnowledgeCatalogService.Document doc = release.getDocuments().get(hit.getKnowledgeId());
            String hash = doc == null ? null : doc.getChunks().get(hit.getChunkId());
            if (hash == null || !hash.equals(KnowledgeJson.sha256(hit.getContent())) || !seen.add(hit.getChunkId())
                    || (hit.getKnowledgeBaseId() != null && !release.getKnowledgeBaseId().equals(hit.getKnowledgeBaseId()))) {
                throw new BusinessException("KNOWLEDGE_CONTENT_CHANGED", "知识资料内容已变化，请联系管理员核对并发布新版本");
            }
            evidence.add(new KnowledgeEvidence(hit.getChunkId(), doc.getSourceId(), doc.getTitle(), doc.getSourceVersion(),
                hash, hit.getContent(), hit.getScore(), hit.getMatchType()));
        }
        return new KnowledgeSearchResponse(provider.name(), release.view(), query, release.getManifestSha256(),
            Instant.now().toString(), Collections.unmodifiableList(evidence));
    }
}
