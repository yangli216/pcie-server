package com.regionalai.floatingball.server.modules.phisemr.model;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class PhisEmrDetail {

    private String idMedrecdoc;
    private String templateName;
    private LocalDateTime visitTime;
    private LocalDateTime updateTime;
    private List<PhisEmrSection> sections = new ArrayList<PhisEmrSection>();
    private String contentHash;
}
