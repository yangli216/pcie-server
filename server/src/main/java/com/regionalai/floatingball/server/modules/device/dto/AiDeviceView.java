package com.regionalai.floatingball.server.modules.device.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AiDeviceView {

    private String idDevice;
    private String cdDevice;
    private String naDevice;
    private String naUser;
    private String doctorWorkNo;
    private String idOrg;
    private String naOrg;
    private String idRegion;
    private String naRegion;
    private String deviceTokenMasked;
    private String sdStatus;
    private String clientVersion;
    private String osInfo;
    private String registerIp;
    private String lastSeenIp;
    private LocalDateTime dtLastHeartbeat;
    private LocalDateTime dtRegistered;
    private LocalDateTime lastActiveTime;
}
