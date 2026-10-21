package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ChronicExaminationServiceTest {
    private HealthPrescriptionClient client;
    private ChronicExaminationService service;
    private ExaminationRecommendationQuery query;
    private final ObjectMapper mapper = new ObjectMapper();
    @BeforeEach void setup() {
        client = mock(HealthPrescriptionClient.class);
        service = new ChronicExaminationService(client, new DisconnectedRegionalExaminationHistoryProvider());
        query = new ExaminationRecommendationQuery();
        query.setPatientId("p"); query.setVisitId("v"); query.setIdCard("110101200001010011");
    }
    @Test void returnsOnlyNeutralRecommendationsAndExplicitHistoryScope() throws Exception {
        when(client.query(query.getIdCard())).thenReturn(mapper.readTree("{\"inspectionItems\":[{\"inspectResult\":\"血脂\",\"stdItemDefDesc\":\"说明\",\"items\":[{\"inspectSubResult\":\"子项\"}]}],\"abnormalResults\":[{\"private\":\"must-not-leak\"}]}"));
        ExaminationRecommendationResponse result = service.query(query);
        assertEquals("getUserInfo", result.getSource());
        assertEquals("血脂", result.getRecommendations().get(0).getName());
        assertEquals("子项", result.getRecommendations().get(0).getSubItems().get(0));
        assertEquals("not_connected", result.getRegionalHistory().getStatus());
        assertFalse(mapper.writeValueAsString(result).contains("must-not-leak"));
        assertFalse(mapper.writeValueAsString(result).contains(query.getIdCard()));
    }
    @Test void emptyRecommendationsAreValidButMalformedPayloadIsRejected() throws Exception {
        assertTrue(service.normalize(mapper.readTree("{\"inspectionItems\":[]}"), query).getRecommendations().isEmpty());
        for (String json : new String[]{"{}", "null", "{\"inspectionItems\":null}", "{\"inspectionItems\":[{}]}"}) {
            assertThrows(IllegalArgumentException.class, () -> service.normalize(mapper.readTree(json), query));
        }
    }
    @Test void knownMockIdentityAndCrossPatientIdentityAreRejected() throws Exception {
        for (String identity : new String[]{"12306", "110101200001010022"}) {
            String json = "{\"inspectionItems\":[{\"idNo\":\"" + identity + "\",\"inspectResult\":\"血脂\"}]}";
            assertThrows(IllegalArgumentException.class, () -> service.normalize(mapper.readTree(json), query));
        }
    }
    @Test void upstreamFailureNeverLeaksResponseOrReturnsEmptySuccess() throws Exception {
        when(client.query(any())).thenThrow(new IllegalArgumentException("private-patient-data"));
        ServiceUnavailableException error = assertThrows(ServiceUnavailableException.class, () -> service.query(query));
        assertFalse(error.getMessage().contains("private-patient-data"));
        assertEquals("CHRONIC-EXAM-UNAVAILABLE", error.getCode());
    }
}
