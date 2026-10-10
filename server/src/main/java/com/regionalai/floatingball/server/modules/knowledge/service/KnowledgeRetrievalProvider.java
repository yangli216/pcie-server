package com.regionalai.floatingball.server.modules.knowledge.service;

import java.util.List;

public interface KnowledgeRetrievalProvider {
    String name();
    List<KnowledgeProviderHit> search(KnowledgeCatalogService.Release release, String query, int limit);
}
