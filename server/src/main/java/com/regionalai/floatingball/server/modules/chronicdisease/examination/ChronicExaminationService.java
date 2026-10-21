package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import com.fasterxml.jackson.databind.JsonNode;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import org.springframework.stereotype.Service;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ChronicExaminationService {
    private final HealthPrescriptionClient client;
    private final RegionalExaminationHistoryProvider history;

    public ChronicExaminationService(HealthPrescriptionClient client, RegionalExaminationHistoryProvider history) {
        this.client = client;
        this.history = history;
    }

    public ExaminationRecommendationResponse query(ExaminationRecommendationQuery query) {
        try {
            ExaminationRecommendationResponse result = normalize(client.query(query.getIdCard()), query);
            result.setRegionalHistory(history.query(query));
            return result;
        } catch (Exception ex) {
            // Never propagate an upstream payload, identity, credentials or raw exception.
            throw new ServiceUnavailableException("CHRONIC-EXAM-UNAVAILABLE", "慢病检查检验推荐暂不可用，请稍后重试");
        }
    }

    ExaminationRecommendationResponse normalize(JsonNode payload, ExaminationRecommendationQuery query) {
        JsonNode items = payload.path("inspectionItems");
        if (!payload.isObject() || !items.isArray() || items.size() > 200) {
            throw new IllegalArgumentException("Invalid recommendation response");
        }
        Map<String, ExaminationRecommendationResponse.Item> unique = new LinkedHashMap<String, ExaminationRecommendationResponse.Item>();
        for (JsonNode item : items) {
            if (!item.isObject()) throw new IllegalArgumentException();
            // Known old Mock payloads carry idNo=12306. Never admit them as patient data.
            String identity = text(item, "idNo", 256);
            if (!identity.isEmpty() && (!identity.matches("[0-9]{17}[0-9Xx]")
                || (query.getIdCard().matches("[0-9]{17}[0-9Xx]") && !identity.equalsIgnoreCase(query.getIdCard())))) {
                throw new IllegalArgumentException("Unexpected identity");
            }
            ExaminationRecommendationResponse.Item result = new ExaminationRecommendationResponse.Item();
            result.setName(text(item, "inspectResult", 256));
            result.setDescription(text(item, "stdItemDefDesc", 4000));
            if (result.getName().isEmpty()) throw new IllegalArgumentException();
            JsonNode children = item.get("items");
            if (children != null && !children.isNull()) {
                if (!children.isArray() || children.size() > 200) throw new IllegalArgumentException();
                for (JsonNode child : children) {
                    String name = text(child, "inspectSubResult", 256);
                    if (name.isEmpty()) throw new IllegalArgumentException();
                    if (!result.getSubItems().contains(name)) result.getSubItems().add(name);
                }
            }
            ExaminationRecommendationResponse.Item prior = unique.get(result.getName());
            if (prior == null) unique.put(result.getName(), result);
            else for (String child : result.getSubItems()) if (!prior.getSubItems().contains(child)) prior.getSubItems().add(child);
        }
        ExaminationRecommendationResponse response = new ExaminationRecommendationResponse();
        response.setPatientId(query.getPatientId());
        response.setVisitId(query.getVisitId());
        response.getRecommendations().addAll(unique.values());
        return response;
    }

    private static String text(JsonNode item, String field, int max) {
        JsonNode value = item.get(field);
        if (value == null || value.isNull()) return "";
        if (!value.isTextual() || value.textValue().length() > max) throw new IllegalArgumentException();
        return value.textValue().trim();
    }
}
