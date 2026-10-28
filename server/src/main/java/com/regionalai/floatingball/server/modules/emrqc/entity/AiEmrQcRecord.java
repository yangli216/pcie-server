package com.regionalai.floatingball.server.modules.emrqc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_emr_qc_record")
public class AiEmrQcRecord extends BaseEntity {

    @TableId(value = "id_qc_record", type = IdType.ASSIGN_UUID)
    private String idQcRecord;

    @TableField("id_org")
    private String idOrg;

    @TableField("id_medrecdoc")
    private String idMedrecdoc;

    @TableField("his_org_id")
    private String hisOrgId;

    @TableField("id_tep")
    private String idTep;

    @TableField("template_name")
    private String templateName;

    @TableField("patient_name_masked")
    private String patientNameMasked;

    @TableField("patient_name")
    @ToString.Exclude
    private String patientName;
    @TableField("gender_code")
    private String genderCode;
    @TableField("patient_age")
    private String patientAge;
    @TableField("id_card_encrypted")
    @ToString.Exclude
    private String idCardEncrypted;
    @TableField("archives_encrypted")
    @ToString.Exclude
    private String archivesEncrypted;
    @TableField("doctor_user_id")
    private String doctorUserId;
    @TableField("phis_create_time")
    private LocalDateTime phisCreateTime;
    @TableField("snapshot_complete")
    private Integer snapshotComplete;

    @TableField("id_card_masked")
    private String idCardMasked;

    @TableField("doctor_name")
    private String doctorName;

    @TableField("dept_name")
    private String deptName;

    @TableField("visit_time")
    private LocalDateTime visitTime;

    @TableField("phis_update_time")
    private LocalDateTime phisUpdateTime;

    @TableField("content_hash")
    private String contentHash;

    @TableField("latest_analysis_id")
    private String latestAnalysisId;

    @TableField("score")
    private Integer score;

    @TableField("dimensions_json")
    private String dimensionsJson;

    @TableField("finding_count")
    private Integer findingCount;

    @TableField("high_severity_count")
    private Integer highSeverityCount;

    @TableField("model")
    private String model;

    @TableField("rule_version")
    private String ruleVersion;

    @TableField("analyzed_at")
    private LocalDateTime analyzedAt;

    @TableField("result_source")
    private String resultSource;

    @TableField("ai_score")
    private Integer aiScore;

    @TableField("ai_analysis_id")
    private String aiAnalysisId;

    @TableField("reviewed_by")
    private String reviewedBy;

    @TableField("reviewed_by_name")
    private String reviewedByName;

    @TableField("reviewed_at")
    private LocalDateTime reviewedAt;

    @TableField("review_reason")
    private String reviewReason;
}
