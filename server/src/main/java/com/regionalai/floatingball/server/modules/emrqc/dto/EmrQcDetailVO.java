package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EmrQcDetailVO {

    private EmrQcRecordVO record;
    private String contentHash;
    private String htmlPreview = "";
    private List<EmrQcSectionVO> sections = new ArrayList<EmrQcSectionVO>();
    private EmrQcAnalysisVO qc;
}
