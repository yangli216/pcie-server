package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PublicHealthPatientTextTest {
    private static final String OBSERVED_EXERCISE = "1. 平地步行：在现有每周三次、每次20分钟的基础上，保持原有节奏与路线；起始阶段维持该频次与时长不变，适应两周后，每次增加5分钟，逐步调整至每次30分钟。操作要点为步伐稳健，呼吸均匀，以运动时能正常交谈但不宜唱歌为宜，避免憋气或突然加速。\n"
            + "2. 坐姿抬腿练习：坐在稳固且无扶手的椅子上，背部挺直，双手轻扶椅面保持平衡；交替缓慢抬起单侧小腿至水平位置，保持3秒后缓慢放下，双腿轮流进行。建议每日早晚各进行一次，每组每侧腿重复10-15次，共做2-3组。动作需控制速度，避免惯性甩腿，若感到膝关节不适即停止或减小幅度。\n"
            + "3. 靠墙静蹲：背靠平整墙面，双脚分开与肩同宽，向前迈出一步，身体沿墙面缓慢下滑至大腿与地面呈约45度角（无需达到90度）；保持该姿势，感受大腿前侧肌肉发力。起始阶段每次坚持10-15秒，休息30秒，重复3-5次。随着腿部力量增强，可每周尝试增加5秒保持时间，上限不超过60秒。过程中确保膝盖不超过脚尖，腰部紧贴墙面，出现头晕或关节疼痛应立即终止。";

    @Test void acceptsTheObserved433CodePointOutputWithoutRewritingSentences() {
        assertEquals(433, OBSERVED_EXERCISE.codePointCount(0, OBSERVED_EXERCISE.length()));
        assertEquals(OBSERVED_EXERCISE, PublicHealthPatientText.requirePatientText(OBSERVED_EXERCISE));
    }

    @Test void acceptsShortLongUnnumberedAndTitledExerciseTextWithoutRewriting() {
        for (String text : Arrays.asList("按既有安排活动。", "运动建议\n" + PublicHealthTestFixture.EXERCISE,
                PublicHealthTestFixture.EXERCISE + "\n4. 额外条目。", repeat("𠮷", 451),
                "先热身。再活动。逐步放慢。出现不适立即停止。")) {
            assertEquals(text, PublicHealthPatientText.requirePatientText(text));
        }
    }

    @Test void rejectsExplicitInternalExplanationsButKeepsPatientSafetyActions() {
        for (String internal : Arrays.asList("制定依据：年龄65岁", "请医生核对关节情况。", "适用性待医生核实",
                "来源 ：批准资料", "证据: 既往结果", "AI 定性分析", "待医生审核", "合成展示，不构成医疗建议",
                "## 医生提醒\n核对资料", "待补充资料：活动能力", "建议如下（来源：原库）", "按安排执行。证据：原库", "按安排执行\uFEFF来源：原库",
                "患者动作\r医生提醒\r补充资料", "患者动作\u2028医生提醒\u2028补充资料", "患者动作\u2029医生提醒\u2029补充资料")) {
            assertThrows(PublicHealthException.class, () -> PublicHealthPatientText.requirePatientText(internal), internal);
        }
        String patient = "按医生指导执行活动安排，出现胸闷或头晕时立即停止运动，持续不适请及时就诊。";
        assertEquals(patient, PublicHealthPatientText.requirePatientText(patient));
        assertEquals("蛋白质来源：鱼肉、豆类和蛋类。", PublicHealthPatientText.requirePatientText("蛋白质来源：鱼肉、豆类和蛋类。"));
    }

    @Test void patientPreparationRetainsConcreteActionsAndMovesOnlySeparateInternalBlocks() {
        List<String> doctor = new ArrayList<String>();
        assertEquals("检查前按已给出的安排禁食。\n携带既有检测记录。", PublicHealthPatientText.patientPreparation(
                "检查前按已给出的安排禁食。机构规范请医生核对。\n携带既有检测记录。", doctor));
        assertEquals(Collections.singletonList("机构规范请医生核对。"), doctor);
        assertThrows(PublicHealthException.class, () -> PublicHealthPatientText.patientPreparation(
                "机构核对禁食安排。", new ArrayList<String>()));
        doctor.clear();
        assertEquals("", PublicHealthPatientText.patientPreparation("来源：批准准备字典。", doctor));
        assertEquals(Collections.singletonList("来源：批准准备字典。"), doctor);
        doctor.clear();
        assertEquals("携带机构就诊卡到院。", PublicHealthPatientText.patientPreparation("携带机构就诊卡到院。\n来源：批准规则。", doctor));
        assertEquals(Collections.singletonList("来源：批准规则。"), doctor);
        assertThrows(PublicHealthException.class, () -> PublicHealthPatientText.patientPreparation(
                "空腹8小时并由机构核对准备规则。", new ArrayList<String>()));
        for (String delimiter : Arrays.asList("\n", "\r", "\u2028", "\u2029")) {
            doctor.clear();
            assertEquals("检查前按安排禁食", PublicHealthPatientText.patientPreparation("检查前按安排禁食" + delimiter + "请医生核对目录", doctor));
            assertEquals(Collections.singletonList("请医生核对目录"), doctor);
        }
    }

    @Test void separatesOptionalDoctorNotesWithoutMutatingOrRelaxingOtherModelFields() {
        ObjectNode raw = PublicHealthTestFixture.M.createObjectNode().put("content", PublicHealthTestFixture.PATIENT_CONTENT).put("unknown", true);
        raw.putArray("doctorNotes").add("请医生核对原方案。").add("资料来源：合成资产");
        ArrayNode warnings = PublicHealthTestFixture.M.createArrayNode();
        JsonNode patient = PublicHealthPatientText.separateDoctorNotes(raw, warnings, "meal_plan", "食谱 A：");
        assertFalse(patient.has("doctorNotes"));
        assertTrue(patient.has("unknown"));
        assertTrue(raw.has("doctorNotes"));
        assertEquals(2, warnings.size());
        assertEquals("食谱 A：请医生核对原方案。", warnings.get(0).path("message").asText());
        assertEquals("meal_plan", warnings.get(0).path("sectionId").asText());
    }

    @Test void validatesDoctorNoteTypeCountNonEmptyAndUnicodeLength() {
        for (JsonNode notes : Arrays.asList(PublicHealthTestFixture.M.nullNode(), PublicHealthTestFixture.M.getNodeFactory().textNode("notes"),
                PublicHealthTestFixture.M.createArrayNode().add(" "), PublicHealthTestFixture.M.createArrayNode().add("\uFEFF"), PublicHealthTestFixture.M.createArrayNode().add(1),
                PublicHealthTestFixture.M.createArrayNode().add(repeat("𠮷", 1001)))) {
            ObjectNode raw = PublicHealthTestFixture.M.createObjectNode(); raw.set("doctorNotes", notes);
            assertThrows(PublicHealthException.class, () -> PublicHealthPatientText.separateDoctorNotes(raw,
                    PublicHealthTestFixture.M.createArrayNode(), "exercise_plan", ""));
        }
        ObjectNode raw = PublicHealthTestFixture.M.createObjectNode();
        ArrayNode notes = raw.putArray("doctorNotes");
        for (int i = 0; i < 8; i++) notes.add(repeat("𠮷", 1000));
        ArrayNode warnings = PublicHealthTestFixture.M.createArrayNode();
        PublicHealthPatientText.separateDoctorNotes(raw, warnings, "exercise_plan", "");
        assertEquals(8, warnings.size());
        notes.add("第九条");
        assertThrows(PublicHealthException.class, () -> PublicHealthPatientText.separateDoctorNotes(raw,
                PublicHealthTestFixture.M.createArrayNode(), "exercise_plan", ""));
    }

    @Test void preservesCompleteProgramEvidenceInUnicodeWarningChunksAndRejectsWireOverflow() {
        ArrayNode warnings = PublicHealthTestFixture.M.createArrayNode();
        String original = "证据：" + repeat("𠮷", 4000) + "138 mmHg";
        PublicHealthPatientText.warning(warnings, "CHECK_RATIONALE", "check_plan", original);
        assertEquals(3, warnings.size());
        StringBuilder actual = new StringBuilder();
        for (JsonNode warning : warnings) {
            String message = warning.path("message").asText();
            assertTrue(message.codePointCount(0, message.length()) <= 2000);
            actual.append(message);
        }
        assertEquals(original, actual.toString());
        while (warnings.size() < 32) PublicHealthPatientText.warning(warnings, "DOCTOR_NOTE", "check_plan", "核对资料");
        assertThrows(PublicHealthException.class, () -> PublicHealthPatientText.warning(warnings, "DOCTOR_NOTE", "check_plan", "超过现有边界"));
    }

    private static String repeat(String value, int count) { return String.join("", Collections.nCopies(count, value)); }
}
