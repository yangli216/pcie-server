package com.regionalai.floatingball.server.modules.config.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.config.dto.AiConfigSaveRequest;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SpeechTranscriptionSettingsTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void oldConfigurationKeepsBatchDefaultsAndBooleanFlags() {
        assertEquals("batch", SpeechTranscriptionSettings.read(mapper, null).getMode());
        assertEquals(4000, SpeechTranscriptionSettings.read(mapper, "{}").getDurationMs());
        assertEquals("{\"voice\":true}", SpeechTranscriptionSettings.merge(mapper, "{\"voice\":true}", null, null));
    }

    @Test
    void explicitSettingsRoundTripWithoutLeakingIntoBooleanFeatures() {
        String json = SpeechTranscriptionSettings.merge(mapper, "{\"voice\":true,\"search\":false}", "segmented", 3000);
        SpeechTranscriptionSettings saved = SpeechTranscriptionSettings.read(mapper, json);
        assertEquals("segmented", saved.getMode());
        assertEquals(3000, saved.getDurationMs());
        assertEquals(2, SpeechTranscriptionSettings.features(mapper, json).size());
        assertTrue(SpeechTranscriptionSettings.features(mapper, json).get("voice"));
        assertFalse(SpeechTranscriptionSettings.features(mapper, json).containsKey("speechTranscription"));
        assertEquals("batch", SpeechTranscriptionSettings.read(mapper,
            SpeechTranscriptionSettings.merge(mapper, json, "batch", null)).getMode());
    }

    @Test
    void anOlderClientCanUpdateFeatureFlagsWithoutResettingSavedSpeechSettings() {
        String saved = SpeechTranscriptionSettings.merge(mapper, "{}", "segmented", 3000);
        String updated = SpeechTranscriptionSettings.merge(mapper, "{\"voice\":false}", null, null, saved);
        assertEquals("segmented", SpeechTranscriptionSettings.read(mapper, updated).getMode());
        assertEquals(3000, SpeechTranscriptionSettings.read(mapper, updated).getDurationMs());
        assertFalse(SpeechTranscriptionSettings.features(mapper, updated).get("voice"));
    }

    @Test
    void publicRequestMustNotSilentlyTruncateFractionalMilliseconds() throws Exception {
        assertEquals(3000, mapper.readValue("{\"speechSegmentDurationMs\":3000}", AiConfigSaveRequest.class).getSpeechSegmentDurationMs());
        assertThrows(java.io.IOException.class, () -> mapper.readValue("{\"speechSegmentDurationMs\":3000.5}", AiConfigSaveRequest.class));
        assertThrows(java.io.IOException.class, () -> mapper.readValue("{\"speechSegmentDurationMs\":\"3000\"}", AiConfigSaveRequest.class));
    }

    @Test
    void invalidSettingsAreRejectedRatherThanQuietlyEnablingTranscription() {
        for (String json : new String[]{"[]", "null", "{", "{\"speechTranscription\":true}",
            "{\"speechTranscription\":{\"mode\":\"stream\"}}",
            "{\"speechTranscription\":{\"segmentDurationMs\":2500.5}}",
            "{\"speechTranscription\":{\"segmentDurationMs\":\"3000\"}}",
            "{\"speechTranscription\":{\"segmentDurationMs\":99999999999999}}"}) {
            assertThrows(BusinessException.class, () -> SpeechTranscriptionSettings.merge(mapper, json, null, null));
        }
        for (int duration : new int[]{0, 1999, 15001}) {
            assertThrows(BusinessException.class, () -> SpeechTranscriptionSettings.merge(mapper, "{}", "segmented", duration));
        }
        assertThrows(BusinessException.class, () -> SpeechTranscriptionSettings.merge(mapper, "{}", "auto", 4000));
    }
}
