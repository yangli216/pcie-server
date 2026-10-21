package com.regionalai.floatingball.server.modules.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import java.util.List;

@Getter
@AllArgsConstructor
public class DrugInstructionSearchResponse {
    private final int current;
    private final int size;
    private final boolean hasMore;
    private final List<DrugInstructionItem> records;
}
