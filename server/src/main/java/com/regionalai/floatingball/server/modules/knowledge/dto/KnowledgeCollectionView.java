package com.regionalai.floatingball.server.modules.knowledge.dto;

import lombok.Value;

@Value
public class KnowledgeCollectionView {
    String id;
    String name;
    String releaseVersion;
    String retrievalVersion;
    int documentCount;
}
