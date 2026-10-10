package com.regionalai.floatingball.server.modules.knowledge.dto;

import lombok.Value;
import java.util.List;

@Value
public class KnowledgeSearchResponse {
    String provider;
    KnowledgeCollectionView collection;
    String query;
    String manifestSha256;
    String retrievedAt;
    List<KnowledgeEvidence> results;
}
