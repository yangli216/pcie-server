package com.regionalai.floatingball.server.modules.emrqc.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class EmrQcProxyRefillTest {
    @ParameterizedTest
    @ValueSource(strings = {"代配药", "家长代配药", "家属代配药", "丈夫来院代配药", "母亲要求代配药",
        "家长代配药，患儿咳嗽3天", "家长代配药，无咳嗽", "否认咳嗽；今日家属代配药",
        "家属代配药＋咳嗽伴发热流涕咳痰三天且要求续配长期药物", "既往代配药，今日家属代配药",
        "无咳嗽\n家属代配药"})
    void acceptsExplicitCurrentAffirmativeMarker(String complaint) {
        assertTrue(EmrQcProxyRefill.matches(complaint));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "咳嗽3天", "双歧杆菌三联活菌散", "代开药", "代取药", "要求配药",
        "无代配药", "非代配药", "不是代配药", "不属于代配药", "否认代配药", "没有代配药",
        "无须家长代配药", "不要求家属代配药", "未进行代配药", "取消代配药", "拒绝代配药",
        "既往家属代配药", "上次代配药", "昨天家长代配药", "咨询代配药流程", "是否代配药",
        "家长代配药？", "如果家长代配药", "考虑代配药", "代配药史", "代配药不属实",
        "主诉举例：家长代配药", "询问‘家长代配药’的含义", "家长代配药，非代配药",
        "不是无代配药", "代 配药", "否认近期家属代配药", "不是来代配药", "未进行过代配药", "没有要求母亲代配药"})
    void doesNotInferExemptionFromAbsentNegativeHistoricalOrUncertainText(String complaint) {
        assertFalse(EmrQcProxyRefill.matches(complaint));
    }

    @Test
    void missingChiefComplaintDoesNotInheritHistoricalIdentity() {
        assertFalse(EmrQcProxyRefill.matches(null));
    }
}
