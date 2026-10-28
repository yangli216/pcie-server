package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EmrQcSectionVO {

    private String name;
    private List<Field> fields = new ArrayList<Field>();

    @Data
    public static class Field {
        private String name;
        private String value;
    }
}
