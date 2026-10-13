package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import java.util.*;

final class PublicHealthTestFixture {
    static final ObjectMapper M = new ObjectMapper();
    static final String NOTICE = "合成展示，不构成医疗建议";
    static final String PATIENT_CONTENT = "按既有健康管理安排记录变化，有不适时及时就诊。";
    static final String EXERCISE = "1. 平地步行：按既有运动安排选择平坦路面、穿防滑鞋，先做热身再进入步行，行走时保持呼吸均匀，以能正常交谈的程度安排节奏，结束时逐步放慢步速。记录每次步行的时长与身体感受，按已确认的随访安排持续执行，避免憋气、突然加速或过度疲劳。\n"
            + "2. 坐姿抬腿：选择稳固座椅坐好，保持躯干平稳，双手轻扶椅面，交替缓慢抬起和放下小腿，按现有活动能力控制幅度，在日常步行之外安排短时练习。动作轻缓、不要惯性甩腿，出现关节疼痛时停止该练习。\n"
            + "3. 运动安全：运动前检查路面与鞋履，随身携带需要的联系信息，按原有安排分段活动并留出休息时间，避免快速转身及突然改变体位。出现胸闷、头晕、心悸或明显不适时立即停止运动，持续不适应及时就诊。";
    static ObjectNode request(String capability) {
        ObjectNode r = M.createObjectNode();
        r.put("schemaVersion","public-health.v1");
        r.put("originRequestId","00000000-0000-4000-8000-000000000001");
        r.put("requestId","00000000-0000-4000-8000-000000000002");
        r.put("capability", capability);
        r.put("scene","hypertension_followup");
        r.put("patientId","SYNTHETIC-P");
        r.put("businessRecordId","SYNTHETIC-R");
        r.put("recordVersion","v1");
        r.put("snapshotAt","2026-09-29T10:00:00+08:00");
        ObjectNode patient = r.putObject("patient");
        patient.put("name","合成人员");
        patient.put("sex","male");
        patient.put("ageYears", 40);
        ObjectNode facts = r.putObject("facts");
        ArrayNode observations = facts.putArray("observations");
        facts.putArray("conditions");
        facts.putArray("medicationClasses");
        facts.putObject("lifestyle").put("diet", NOTICE);
        if ("weight_management".equals(capability)) {
            ObjectNode profile = facts.putObject("weightProfile");
            profile.put("heightCm", 170);
            profile.put("weightKg", 70);
            profile.put("region","华东");
            profile.put("season","autumn");
            profile.put("heightObservationId","H");
            profile.put("weightObservationId","W");
            observation(observations,"H","height","cm", 170);
            observation(observations,"W","weight","kg", 70);
        }
        ArrayNode targets = r.putArray("writebackTargets");
        ObjectNode target = targets.addObject();
        target.put("targetFieldId","SYNTHETIC-FIELD");
        target.put("label", NOTICE);
        target.put("valueType","string");
        target.put("mode","replace");
        ArrayNode ids = target.putArray("sourceSections");
        for (String s:PublicHealthValidator.sections(capability))ids.add(s);
        return r;
    }
    static void observation(ArrayNode a, String id, String code, String unit, int value) {
        ObjectNode o = a.addObject();
        o.put("id", id);
        o.put("code", code);
        o.put("unit", unit);
        o.put("value", value);
        o.put("observedAt","2026-09-28T09:00:00+08:00");
        o.putObject("source").put("system","SYNTHETIC").put("recordId","SYNTHETIC-FACT");
    }
    static PublicHealthValidator.Parsed parsed(String capability) throws Exception {
        return new PublicHealthValidator(M).parse(M.writeValueAsBytes(request(capability)));
    }
    static AiDevice device() {
        AiDevice d = new AiDevice();
        d.setIdOrg("ORG-SYNTHETIC");
        d.setIdDevice("DEVICE-SYNTHETIC");
        return d;
    }
    static ObjectNode policy() {
        ObjectNode p = M.createObjectNode();
        ObjectNode c = p.putArray("checkWhitelist").addObject();
        c.put("code","SYNTHETIC-CHECK");
        c.put("name", "合成检查项目");
        c.put("prep", "按检查安排携带已有记录。");
        c.put("minIntervalDays", 30);
        c.put("abnormalRetestIntervalDays", 7);
        p.putObject("medicationReminders").put("SYNTHETIC-CLASS", NOTICE);
        p.putArray("lifestyleRules").add(NOTICE);
        return p;
    }
    static ObjectNode healthEducation() {
        ObjectNode draft = M.createObjectNode();
        for (String field : Arrays.asList("risks", "prevention", "diet", "exercise", "followup", "missingFacts")) draft.putArray(field);
        draft.withArray("diet").add(PATIENT_CONTENT);
        return draft;
    }
    static ObjectNode recipes() {
        ObjectNode r = M.createObjectNode();
        r.put("embeddingModel","synthetic-embedding");
        r.put("dimensions", 2);
        r.put("chunkingVersion","synthetic-v1");
        r.put("reranker","bge-reranker-large");
        ArrayNode docs = r.putArray("documents");
        for (int i = 0; i<4; i++) {
            ObjectNode d = docs.addObject();
            d.put("id","CHUNK-"+i);
            d.put("documentId","SYNTHETIC-DOC");
            d.put("source","synthetic-source");
            d.put("title", NOTICE);
            d.put("text", NOTICE);
            d.putArray("vector").add(1).add(i);
        }
        return r;
    }
    static ArrayNode versions() {
        ArrayNode v = M.createArrayNode();
        v.addObject().put("assetId","synthetic-policy").put("version","synthetic-v1").put("sha256", String.join("", Collections.nCopies(64,"0")));
        return v;
    }
}
