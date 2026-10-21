package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import org.springframework.stereotype.Component;

@Component
public class DisconnectedRegionalExaminationHistoryProvider implements RegionalExaminationHistoryProvider {
    @Override
    public ExaminationRecommendationResponse.RegionalHistory query(ExaminationRecommendationQuery query) {
        return new ExaminationRecommendationResponse.RegionalHistory();
    }
}
