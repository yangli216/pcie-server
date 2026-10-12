package com.regionalai.floatingball.server.modules.publichealth.service;

import com.regionalai.floatingball.server.modules.ai.service.AiProxyService;
import com.regionalai.floatingball.server.modules.ai.dto.ChatRequest;
import com.regionalai.floatingball.server.modules.prompt.dto.PromptView;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class PublicHealthModelServiceTest {
    @Test void addsDoctorEducationConstraintsOnlyForHealthPrescriptionAndKeepsConfiguredPrompt() {
        AiProxyService proxy = mock(AiProxyService.class);
        when(proxy.chat(any(), any())).thenReturn("{\"content\":\"合成测试\"}");
        PromptView prompt = new PromptView(); prompt.setSysPrompt("合成批准Prompt"); prompt.setUserTemplate("输入资料：{{input}}");
        PublicHealthModelService service = new PublicHealthModelService(proxy, PublicHealthTestFixture.M);
        service.generate(PublicHealthTestFixture.device(), prompt,
                PublicHealthTestFixture.M.createObjectNode().put("capability", "health_prescription"), "generation", "lifestyle_guidance", PublicHealthHealthEducation.OUTPUT_SHAPE);
        service.generate(PublicHealthTestFixture.device(), prompt,
                PublicHealthTestFixture.M.createObjectNode().put("capability", "weight_management"), "generation", "exercise_plan", "{}");
        ArgumentCaptor<ChatRequest> calls = ArgumentCaptor.forClass(ChatRequest.class);
        verify(proxy, times(2)).chat(any(), calls.capture());
        String health = calls.getAllValues().get(0).getMessages().get(0).get("content").toString();
        String weight = calls.getAllValues().get(1).getMessages().get(0).get("content").toString();
        assertTrue(health.startsWith("合成批准Prompt"));
        assertTrue(health.contains("面向医生"));
        assertTrue(health.contains("不输出制定依据"));
        assertTrue(health.contains("healthProfile.evidence"));
        assertTrue(health.contains("不得新增处方药"));
        assertTrue(health.contains("不得当作已有风险等级、患病概率"));
        assertTrue(health.contains("checkScheduleConstraints"));
        assertTrue(health.contains("两种模式都不可用时省略该项目"));
        assertTrue(health.contains("包括本次随访测量"));
        assertTrue(health.contains("随访健康处方为独立能力，不生成减重指标"));
        assertTrue(health.contains("健康教育模型正文不得包含BMI、BMR、kcal、mmHg、mmol/L"));
        assertTrue(health.contains("不根据单次测量判断正常或异常"));
        assertTrue(health.contains("不复述policy.medicationReminders"));
        assertFalse(weight.contains("面向医生形成随访"));
        assertFalse(weight.contains("healthProfile.evidence"));
        assertFalse(weight.contains("checkScheduleConstraints"));
        assertFalse(weight.contains("不生成减重指标"));
        assertFalse(weight.contains("不复述policy.medicationReminders"));
        assertEquals("合成批准Prompt", prompt.getSysPrompt());
        assertTrue(calls.getAllValues().get(0).getMessages().get(1).get("content").toString().startsWith("输入资料："));
        assertFalse(calls.getAllValues().get(0).getEnableSearch());
    }

    @Test void invalidJsonIsNotRetriedAndDoesNotLeakUpstreamText() {
        AiProxyService proxy = mock(AiProxyService.class);
        when(proxy.chat(any(), any())).thenReturn("upstream-secret-like-debug");
        PromptView prompt = new PromptView();
        prompt.setSysPrompt(PublicHealthTestFixture.NOTICE);
        PublicHealthModelService s = new PublicHealthModelService(proxy, PublicHealthTestFixture.M);
        PublicHealthException e = assertThrows(PublicHealthException.class, () -> s.generate(PublicHealthTestFixture.device(), prompt, PublicHealthTestFixture.M.createObjectNode(),
        "generation","exercise_plan","{}"));
        assertFalse(e.getMessage().contains("upstream-secret"));
        verify(proxy, times(1)).chat(any(), any());
    }
}
