package com.regionalai.floatingball.server.modules.knowledge.service;

import lombok.Value;

@Value
public class KnowledgeProviderHit {
    String chunkId;
    String knowledgeId;
    String knowledgeBaseId;
    String content;
    double score;
    String matchType;
}
