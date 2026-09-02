package com.regionalai.floatingball.server.modules.emrtemplate.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.regionalai.floatingball.server.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("c_ai_outpatient_emr_tpl_mapping")
public class AiOutpatientEmrTemplateMapping extends BaseEntity {

    @TableId(value = "id_mapping", type = IdType.ASSIGN_UUID)
    private String idMapping;

    @TableField("id_snapshot")
    private String idSnapshot;

    @TableField("field_id")
    private String fieldId;

    @TableField("record_field")
    private String recordField;

    @TableField("projection_mode")
    private String projectionMode;
}
