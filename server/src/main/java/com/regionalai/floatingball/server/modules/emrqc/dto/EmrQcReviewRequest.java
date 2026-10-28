package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EmrQcReviewRequest {

    private String reason;
    private String baseAnalysisId;
    private List<EmrQcAnalysisVO.Dimension> dimensions = new ArrayList<EmrQcAnalysisVO.Dimension>();
    private List<EmrQcAnalysisVO.Finding> findings = new ArrayList<EmrQcAnalysisVO.Finding>();
}
