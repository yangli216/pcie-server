package com.regionalai.floatingball.server.modules.knowledge.dto;

import lombok.Value;

@Value
public class KnowledgeEvidence {
    String chunkId;
    String sourceId;
    String title;
    String sourceVersion;
    String contentSha256;
    String content;
    double score;
    String matchType;
}
