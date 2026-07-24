package com.regionalai.floatingball.server.modules.chronicdisease.dto;

import lombok.Data;

import javax.validation.constraints.Size;

@Data
public class TcdVisitDrugRequest {

    @Size(max = 64, message = "drugList.id 不能超过 64 个字符")
    private String id;

    @Size(max = 64, message = "drugList.idDrug 不能超过 64 个字符")
    private String idDrug;

    @Size(max = 64, message = "drugList.idPherec 不能超过 64 个字符")
    private String idPherec;

    @Size(max = 255, message = "drugList.naDrug 不能超过 255 个字符")
    private String naDrug;

    private String sdDrugFreq;
    private String perDose;

    @Size(max = 10, message = "drugList.doseUnit 不能超过 10 个字符")
    private String doseUnit;

    private String insulin;
}
