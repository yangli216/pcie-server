package com.regionalai.floatingball.server.modules.emrqc.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_emr_qc_task")
public class AiEmrQcTask extends BaseEntity {

    @TableId(value = "id_task", type = IdType.ASSIGN_UUID)
    private String idTask;

    @TableField("id_org")
    private String idOrg;

    @TableField("created_by")
    private String createdBy;

    @TableField("created_by_name")
    private String createdByName;

    @TableField("record_ids_json")
    private String recordIdsJson;

    @TableField("items_json")
    private String itemsJson;

    @TableField("total_count")
    private Integer totalCount;

    @TableField("processed_count")
    private Integer processedCount;

    @TableField("succeeded_count")
    private Integer succeededCount;

    @TableField("failed_count")
    private Integer failedCount;

    @TableField("status")
    private String status;

    @TableField("error_message")
    private String errorMessage;

    @TableField("started_at")
    private LocalDateTime startedAt;

    @TableField("finished_at")
    private LocalDateTime finishedAt;
}
