package com.regionalai.floatingball.server.modules.clientusage.dto;

import lombok.Data;

@Data
public class ClientUsageItemVO {

    private String orgName;
    private String doctorName;
    private String doctorWorkNo;
    private String firstInteractionTime;
    private String clientVersion;
    private String lastActiveTime;
}
