package com.regionalai.floatingball.server.modules.knowledge.dto;

import lombok.Value;
import java.util.List;

@Value
public class KnowledgeCollectionsResponse {
    boolean enabled;
    List<KnowledgeCollectionView> collections;
}
