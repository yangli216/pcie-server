package com.regionalai.floatingball.server.modules.prompt.service;

import com.regionalai.floatingball.server.modules.prompt.dto.PromptDeltaVO;
import com.regionalai.floatingball.server.modules.prompt.entity.AiPrompt;
import com.regionalai.floatingball.server.modules.prompt.mapper.AiPromptMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PromptServiceTest {

    @Mock
    private AiPromptMapper aiPromptMapper;

    private PromptService promptService;

    @BeforeEach
    void setUp() {
        promptService = new PromptService(aiPromptMapper, new PromptDefaultCatalog());
    }

    @Test
    void getDeltaShouldPreferOrgPromptAndMergeBySceneCode() {
        AiPrompt globalDiagnosis = buildPrompt("P1", "diagnosis", "global", "1.0.0", null, null, "1");
        AiPrompt regionDiagnosis = buildPrompt("P2", "diagnosis", "region", "2.0.0", null, "REG001", "1");
        AiPrompt orgDiagnosis = buildPrompt("P3", "diagnosis", "org", "3.0.0", "ORG001", null, "1");
        AiPrompt triage = buildPrompt("P4", "triage", "triage", "1.0.0", null, null, "1");

        when(aiPromptMapper.selectList(any())).thenReturn(Arrays.asList(globalDiagnosis, regionDiagnosis, orgDiagnosis, triage));

        PromptDeltaVO delta = promptService.getDelta("ORG001", "REG001", null);

        assertEquals("3.0.0", delta.getVersion());
        assertEquals(2, delta.getPrompts().size());
        assertEquals("diagnosis", delta.getPrompts().get(0).getCdPrompt());
        assertEquals("org", delta.getPrompts().get(0).getSysPrompt());
        assertEquals("triage", delta.getPrompts().get(1).getCdPrompt());
    }

    @Test
    void getDeltaShouldReturnEmptyWhenVersionAlreadyLatest() {
        AiPrompt orgDiagnosis = buildPrompt("P3", "diagnosis", "org", "3.0.0", "ORG001", null, "1");
        when(aiPromptMapper.selectList(any())).thenReturn(Arrays.asList(orgDiagnosis));

        PromptDeltaVO delta = promptService.getDelta("ORG001", "REG001", "3.0.0");

        assertEquals("3.0.0", delta.getVersion());
        assertTrue(delta.getPrompts().isEmpty());
    }

    @Test
    void shouldExposeVersionedVoiceIntentStreamDefault() {
        when(aiPromptMapper.selectList(any())).thenReturn(Collections.emptyList());

        com.regionalai.floatingball.server.modules.prompt.dto.PromptView prompt =
            promptService.resolveEffectivePrompt("voiceIntentRecognitionStream", null, null);

        assertNotNull(prompt);
        assertEquals("v1.3", prompt.getVersionNum());
        assertTrue(prompt.getSysPrompt().contains("record_suggestions"));
        assertTrue(prompt.getSysPrompt().contains("recommendation_plan"));
        assertTrue(prompt.getSysPrompt().contains("evidenceScope"));
        assertTrue(prompt.getSysPrompt().contains("currentVisitEvidenceText"));
        assertTrue(prompt.getSysPrompt().contains("clinicalRole"));
        assertTrue(prompt.getSysPrompt().contains("diagnosisKind"));
        assertTrue(prompt.getSysPrompt().contains("贫血等不得仅因历史已确诊"));
        assertTrue(prompt.getSysPrompt().contains("symptom_working+formal"));
        assertTrue(prompt.getSysPrompt().contains("历史已确诊而标为 high"));
        assertTrue(prompt.getSysPrompt().contains("type 必须为 medicine、examination、labTest、procedure 之一的字符串"));

        com.regionalai.floatingball.server.modules.prompt.dto.PromptView repairPrompt =
            promptService.resolveEffectivePrompt("voiceIntentRepair", null, null);
        assertNotNull(repairPrompt);
        assertEquals("v1.3", repairPrompt.getVersionNum());
        assertTrue(repairPrompt.getSysPrompt().contains("无法确认时删除该条"));
        assertTrue(repairPrompt.getSysPrompt().contains("currentVisitEvidenceText"));
        assertTrue(repairPrompt.getSysPrompt().contains("clinicalRole、diagnosisKind"));
    }

    private AiPrompt buildPrompt(String idPrompt,
                                 String cdPrompt,
                                 String sysPrompt,
                                 String versionNum,
                                 String idOrg,
                                 String idRegion,
                                 String sdStatus) {
        AiPrompt prompt = new AiPrompt();
        prompt.setIdPrompt(idPrompt);
        prompt.setCdPrompt(cdPrompt);
        prompt.setNaPrompt(cdPrompt + "-name");
        prompt.setSysPrompt(sysPrompt);
        prompt.setUserTemplate("{{input}}");
        prompt.setVersionNum(versionNum);
        prompt.setIdOrg(idOrg);
        prompt.setIdRegion(idRegion);
        prompt.setSdStatus(sdStatus);
        prompt.setFgActive("1");
        return prompt;
    }
}
