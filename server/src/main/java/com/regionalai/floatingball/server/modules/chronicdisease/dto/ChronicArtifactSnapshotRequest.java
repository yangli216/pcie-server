package com.regionalai.floatingball.server.modules.chronicdisease.dto;

import lombok.Data;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;

@Data
public class ChronicArtifactSnapshotRequest {

    @NotBlank(message = "requestId 不能为空")
    @Size(max = 64, message = "requestId 不能超过 64 个字符")
    private String requestId;

    @NotBlank(message = "快照类型不能为空")
    @Size(max = 32, message = "快照类型不能超过 32 个字符")
    private String artifactType;

    @Size(max = 64, message = "HIS 机构 ID 不能超过 64 个字符")
    private String hisOrgId;

    @Size(max = 255, message = "HIS 机构名称不能超过 255 个字符")
    private String hisOrgName;

    @NotBlank(message = "患者 ID 不能为空")
    @Size(max = 64, message = "患者 ID 不能超过 64 个字符")
    private String patientId;

    @Size(max = 64, message = "就诊 ID 不能超过 64 个字符")
    private String visitId;

    @NotBlank(message = "患者姓名不能为空")
    @Size(max = 128, message = "患者姓名不能超过 128 个字符")
    private String patientName;

    @NotEmpty(message = "慢病类型不能为空")
    @Size(max = 2, message = "慢病类型不能超过 2 项")
    private List<String> diseaseTypes;

    @NotBlank(message = "数据截至时间不能为空")
    @Size(max = 64, message = "数据截至时间不能超过 64 个字符")
    private String dataAsOf;

    private Integer assessmentYear;

    @NotEmpty(message = "表单模板版本不能为空")
    @Size(max = 2, message = "表单模板版本不能超过 2 项")
    private List<String> templateVersions;

    @NotEmpty(message = "临床路径版本不能为空")
    @Size(max = 2, message = "临床路径版本不能超过 2 项")
    private List<String> pathVersions;

    @NotEmpty(message = "依据版本不能为空")
    @Size(max = 2, message = "依据版本不能超过 2 项")
    private List<String> evidenceVersions;

    @NotBlank(message = "规则版本不能为空")
    @Size(max = 64, message = "规则版本不能超过 64 个字符")
    private String ruleVersion;

    @NotBlank(message = "快照摘要不能为空")
    @Size(max = 4000, message = "快照摘要不能超过 4000 个字符")
    private String summaryText;

    private Integer systolicPressure;
    private Integer diastolicPressure;
    private BigDecimal bloodGlucose;
    private Integer bloodPressureRecordCount;
    private Integer bloodGlucoseRecordCount;

    @Valid
    @Size(max = 100, message = "确认项不能超过 100 项")
    private List<ChronicArtifactSnapshotItemRequest> acceptedItems;

    @Size(max = 2000, message = "医生备注不能超过 2000 个字符")
    private String doctorNotes;

    @Size(max = 64, message = "医生 ID 不能超过 64 个字符")
    private String doctorId;

    @NotBlank(message = "打印医生不能为空")
    @Size(max = 128, message = "医生姓名不能超过 128 个字符")
    private String doctorName;
}
