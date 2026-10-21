package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
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
        assertTrue(health.contains("应从policy.medicationReminders提炼"));
        assertTrue(health.contains("患者实际执行及安全动作"));
        assertFalse(weight.contains("面向医生形成随访"));
        assertFalse(weight.contains("healthProfile.evidence"));
        assertFalse(weight.contains("checkScheduleConstraints"));
        assertFalse(weight.contains("不生成减重指标"));
        assertFalse(weight.contains("应从policy.medicationReminders提炼"));
        assertTrue(health.contains("可直接交给患者"));
        assertTrue(weight.contains("可直接交给患者"));
        assertTrue(health.contains("以下患者正文约束优先于已有Prompt"));
        assertTrue(weight.contains("doctorNotes数组（0至8条"));
        assertTrue(weight.contains("食谱options每项也可额外返回doctorNotes"));
        assertFalse(weight.contains("恰好3条"));
        assertFalse(weight.contains("250至450"));
        assertTrue(health.contains("用药只依据机构已批准类别（policy.medicationReminders）"));
        assertTrue(health.contains("不限于居民用药记录"));
        assertTrue(health.contains("哪些类别、是否给内容"));
        assertFalse(health.contains("用药只指导已提供类别"));
        assertTrue(weight.contains("绝不进入患者正文"));
        assertTrue(health.contains("程序放入医生区"));
        assertFalse(health.contains("该提醒由程序追加"));
        assertEquals("合成批准Prompt", prompt.getSysPrompt());
        assertTrue(calls.getAllValues().get(0).getMessages().get(1).get("content").toString().startsWith("输入资料："));
        assertFalse(calls.getAllValues().get(0).getEnableSearch());
    }

    @Test void guidesExerciseActionsWithoutStyleLimitsOrExamplesAndKeepsSectionIsolation() {
        AiProxyService proxy = mock(AiProxyService.class);
        when(proxy.chat(any(), any())).thenReturn("{\"content\":\"合成测试\"}");
        PromptView prompt = new PromptView();
        prompt.setSysPrompt("旧版要求：尽量简短。");
        prompt.setUserTemplate("输入资料：{{input}}");
        PublicHealthModelService service = new PublicHealthModelService(proxy, PublicHealthTestFixture.M);
        String shape = "{\"content\":\"本章节非空正文\"}";
        service.generate(PublicHealthTestFixture.device(), prompt,
                PublicHealthTestFixture.M.createObjectNode().put("capability", "weight_management").put("requestedSection", "exercise_plan"),
                "generation", "exercise_plan", shape);
        service.generate(PublicHealthTestFixture.device(), prompt,
                PublicHealthTestFixture.M.createObjectNode().put("capability", "weight_management").put("requestedSection", "meal_plan"),
                "generation", "meal_plan", "{}");
        service.generate(PublicHealthTestFixture.device(), prompt,
                PublicHealthTestFixture.M.createObjectNode().put("capability", "health_prescription").put("requestedSection", "lifestyle_guidance"),
                "generation", "lifestyle_guidance", PublicHealthHealthEducation.OUTPUT_SHAPE);

        ArgumentCaptor<ChatRequest> calls = ArgumentCaptor.forClass(ChatRequest.class);
        verify(proxy, times(3)).chat(any(), calls.capture());
        String exercise = calls.getAllValues().get(0).getMessages().get(0).get("content").toString();
        assertTrue(exercise.startsWith("旧版要求：尽量简短。"));
        assertTrue(exercise.endsWith("输出结构：" + shape));
        String guidance = "使用已传资料和受控规则写具体运动方式、频率或时长、时段安排与患者安全动作";
        assertTrue(exercise.contains(guidance));
        assertTrue(exercise.contains("运动数值只使用已传资料或受控规则给定的值"));
        assertTrue(exercise.contains("没有数值时使用定性安排"));
        for (ChatRequest call : calls.getAllValues()) {
            String system = call.getMessages().get(0).get("content").toString();
            for (String removed : new String[]{"篇幅规划", "120至140", "380至420", "250至450", "每条最多2句", "合成的篇幅与格式示例", "恰好3条"}) {
                assertFalse(system.contains(removed), removed);
            }
        }
        assertFalse(calls.getAllValues().get(1).getMessages().get(0).get("content").toString().contains(guidance));
        assertFalse(calls.getAllValues().get(2).getMessages().get(0).get("content").toString().contains(guidance));
        assertEquals("旧版要求：尽量简短。", prompt.getSysPrompt());
        assertEquals(2, calls.getAllValues().get(0).getMessages().size());
        assertFalse(calls.getAllValues().get(0).getStream());
        assertEquals(0.2, calls.getAllValues().get(0).getTemperature());
    }

    @Test void preservesACompliantExerciseResponseWithoutPaddingOrAnotherModelCall() {
        AiProxyService proxy = mock(AiProxyService.class);
        String raw = PublicHealthTestFixture.M.createObjectNode().put("content", PublicHealthTestFixture.EXERCISE).toString();
        when(proxy.chat(any(), any())).thenReturn(raw);
        PromptView prompt = new PromptView();
        prompt.setSysPrompt("合成批准Prompt");
        PublicHealthModelService service = new PublicHealthModelService(proxy, PublicHealthTestFixture.M);
        JsonNode generated = service.generate(PublicHealthTestFixture.device(), prompt,
                PublicHealthTestFixture.M.createObjectNode().put("capability", "weight_management"),
                "generation", "exercise_plan", "{\"content\":\"本章节非空正文\"}");
        assertEquals(PublicHealthTestFixture.EXERCISE, generated.path("content").asText());
        assertEquals(PublicHealthTestFixture.EXERCISE,
                PublicHealthPatientText.requirePatientText(generated.path("content").asText()));
        verify(proxy, times(1)).chat(any(), any());
    }

    @Test void reported155CodePointExerciseIsReturnedWithoutPaddingOrRetry() {
        String text = "1. 每日坚持散步30至40分钟，可在早晨或傍晚气温适宜时进行，保持匀速，避免剧烈加速或急停。\n"
                + "2. 每周选择3天进行轻度有氧运动，如太极拳或八段锦，每次练习40分钟，动作缓慢连贯，注意呼吸均匀。\n"
                + "3. 避免久坐，每小时起身活动2至3分钟，可做原地抬腿或伸展手臂，若运动中出现胸闷、头晕或心悸，立即停止并休息。";
        assertEquals(155, text.codePointCount(0, text.length()));
        AiProxyService proxy = mock(AiProxyService.class);
        when(proxy.chat(any(), any())).thenReturn(PublicHealthTestFixture.M.createObjectNode().put("content", text).toString());
        PromptView prompt = new PromptView();
        prompt.setSysPrompt("合成批准Prompt");
        JsonNode generated = new PublicHealthModelService(proxy, PublicHealthTestFixture.M).generate(
                PublicHealthTestFixture.device(), prompt,
                PublicHealthTestFixture.M.createObjectNode().put("capability", "weight_management"),
                "generation", "exercise_plan", "{\"content\":\"本章节非空正文\"}");
        assertEquals(text, generated.path("content").asText());
        assertEquals(text, PublicHealthPatientText.requirePatientText(generated.path("content").asText()));
        verify(proxy, times(1)).chat(any(), any());
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
