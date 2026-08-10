package com.regionalai.floatingball.server.modules.userlog.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_user_consultation_log")
public class AiUserConsultationLog extends BaseEntity {

    @TableId(value = "id_log", type = IdType.ASSIGN_UUID)
    private String idLog;

    @TableField("consultation_round_id")
    private String consultationRoundId;

    @TableField("consultation_id")
    private String consultationId;

    @TableField("id_device")
    private String idDevice;

    @TableField("id_org")
    private String idOrg;

    @TableField("id_his_org")
    private String hisOrgId;

    @TableField("na_org")
    private String naOrg;

    @TableField("id_doctor")
    private String idDoctor;

    @TableField("cd_doctor")
    private String doctorWorkNo;

    @TableField("na_doctor")
    private String naDoctor;

    @TableField("id_dept")
    private String idDept;

    @TableField("na_dept")
    private String naDept;

    @TableField("consultation_type")
    private String consultationType;

    @TableField("consultation_time")
    private LocalDateTime consultationTime;

    @TableField("patient_id")
    private String patientId;

    @TableField("patient_name")
    private String patientName;

    @TableField("patient_gender")
    private String patientGender;

    @TableField("patient_age")
    private String patientAge;

    @TableField("speech_text")
    private String speechText;

    @JsonIgnore
    @TableField("audio_file_path")
    private String audioFilePath;

    @TableField("audio_file_name")
    private String audioFileName;

    @TableField("audio_mime_type")
    private String audioMimeType;

    @TableField("audio_size")
    private Long audioSize;

    @TableField("first_snapshot_json")
    private String firstSnapshotJson;

    @TableField("final_snapshot_json")
    private String finalSnapshotJson;

    @TableField("selection_json")
    private String selectionJson;

    @TableField("change_summary_json")
    private String changeSummaryJson;

    @TableField("total_changes")
    private Integer totalChanges;

    @TableField("status")
    private String status;

    public Boolean getHasAudio() {
        return audioFilePath != null && audioFilePath.trim().length() > 0;
    }

    public Boolean getHasSpeechText() {
        return speechText != null && speechText.trim().length() > 0;
    }
}
