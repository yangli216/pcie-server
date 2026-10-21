package com.regionalai.floatingball.server.modules.inpatientlog.dto;
import lombok.Data;
@Data
public class InpatientLogQuery {
    private String dateFrom;
    private String dateTo;
    private String doctorName;
    private String patientName;
    private String idOrg;
    private String hisOrgId;
    private String templateId;
    private String status;
}
