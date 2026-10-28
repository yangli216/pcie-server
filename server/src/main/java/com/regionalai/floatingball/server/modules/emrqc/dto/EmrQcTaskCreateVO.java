package com.regionalai.floatingball.server.modules.emrqc.dto;

import lombok.Data;

@Data
public class EmrQcTaskCreateVO {

    private String taskId;
    private String status;
    private Integer total;
}
