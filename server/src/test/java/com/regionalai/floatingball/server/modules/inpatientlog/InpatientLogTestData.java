package com.regionalai.floatingball.server.modules.inpatientlog;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.InpatientLogSnapshot;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import java.util.Collections;
final class InpatientLogTestData {
 static InpatientLogSnapshot snapshot() {
  InpatientLogSnapshot s=new InpatientLogSnapshot();s.setGenerationId("123e4567-e89b-42d3-a456-426614174000");s.setEventId("123e4567-e89b-42d3-a456-426614174001");
  s.setSequence(1);s.setEventType("generated");s.setStatus("generated");s.setGenerationStartedAt(1792500000000L);s.setOccurredAt(1792500001000L);
  s.setAdmissionId("admission");s.setTemplateId("template");s.setTemplateName("日常病程");s.setTemplateHash(String.join("", Collections.nCopies(64,"a")));
  InpatientLogSnapshot.Field f=new InpatientLogSnapshot.Field();f.setId("field");f.setName("病程");f.setSource("ai");f.setGeneratedValue("AI原稿");s.setFields(Collections.singletonList(f));return s;
 }
 static AiDevice device(){AiDevice d=new AiDevice();d.setIdDevice("device");d.setIdOrg("org");return d;}
}
