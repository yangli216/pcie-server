package com.regionalai.floatingball.server.modules.phisemr.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class PhisEmrSection {

    private String name;
    private List<Field> fields = new ArrayList<Field>();

    @Data
    public static class Field {
        private String name;
        private String value;
    }
}
