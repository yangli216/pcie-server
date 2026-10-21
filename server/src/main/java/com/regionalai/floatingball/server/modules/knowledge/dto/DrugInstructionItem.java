package com.regionalai.floatingball.server.modules.knowledge.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class DrugInstructionItem {
    private final String productId;
    private final String productName;
    private final String genericName;
    private final String drugSpec;
    private final String producer;
    private final String drugForm;
    private final String category;
}
