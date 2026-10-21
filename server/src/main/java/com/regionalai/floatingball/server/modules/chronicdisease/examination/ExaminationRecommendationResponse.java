package com.regionalai.floatingball.server.modules.chronicdisease.examination;

import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class ExaminationRecommendationResponse {
    private String patientId;
    private String visitId;
    private String source = "getUserInfo";
    private List<Item> recommendations = new ArrayList<Item>();
    private RegionalHistory regionalHistory;

    @Data
    public static class Item {
        private String name;
        private String description;
        private List<String> subItems = new ArrayList<String>();
    }

    @Data
    public static class RegionalHistory {
        private String status = "not_connected";
        private List<Record> records = new ArrayList<Record>();
    }

    @Data
    public static class Record {
        private String reportId;
        private String name;
        private String reportedAt;
        private String organizationName;
    }
}
