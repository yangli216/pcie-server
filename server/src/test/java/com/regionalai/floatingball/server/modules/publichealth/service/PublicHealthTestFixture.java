package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import java.util.*;

final class PublicHealthTestFixture {
    static final ObjectMapper M = new ObjectMapper();
    static final String NOTICE = "合成展示，不构成医疗建议";
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
        c.put("name", NOTICE);
        c.put("prep", NOTICE);
        c.put("minIntervalDays", 30);
        c.put("abnormalRetestIntervalDays", 7);
        p.putObject("medicationReminders").put("SYNTHETIC-CLASS", NOTICE);
        p.putArray("lifestyleRules").add(NOTICE);
        return p;
    }
    static ObjectNode healthEducation() {
        ObjectNode draft = M.createObjectNode();
        for (String field : Arrays.asList("risks", "prevention", "diet", "exercise", "followup", "missingFacts")) draft.putArray(field);
        draft.withArray("diet").add(NOTICE);
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
