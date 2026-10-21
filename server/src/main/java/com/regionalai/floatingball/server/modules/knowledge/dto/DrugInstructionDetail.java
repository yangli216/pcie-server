package com.regionalai.floatingball.server.modules.knowledge.dto;

import lombok.Getter;

@Getter
public class DrugInstructionDetail extends DrugInstructionItem {
    private final String summary;
    private final String createDate;

    public DrugInstructionDetail(String productId, String productName, String genericName,
                                 String drugSpec, String producer, String drugForm, String category,
                                 String summary, String createDate) {
        super(productId, productName, genericName, drugSpec, producer, drugForm, category);
        this.summary = summary;
        this.createDate = createDate;
    }
}
