package com.regionalai.floatingball.server.modules.chronicdisease.examination;

/** 健康大脑全区已报告历史端口。接入实现须校验患者身份、时间窗、来源与覆盖完整性。 */
public interface RegionalExaminationHistoryProvider {
    ExaminationRecommendationResponse.RegionalHistory query(ExaminationRecommendationQuery query);
}
