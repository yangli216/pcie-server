package com.regionalai.floatingball.server.modules.useractivity.dto;

import lombok.Data;

@Data
public class UserActivityItemVO {

    private String idDoctor;
    private String naDoctor;
    private long deviceCount;
    private String idOrg;
    private String naOrg;
    private String hisOrgId;
    private String hisOrgName;
    private String idRegion;
    private String naRegion;
    private String activeStatus;
    private long consultationCount;
    private long effectiveConsultationCount;
    private String lastActiveTime;
}
