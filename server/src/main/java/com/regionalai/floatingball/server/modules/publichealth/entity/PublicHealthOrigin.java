package com.regionalai.floatingball.server.modules.publichealth.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import com.regionalai.floatingball.server.common.model.BaseEntity;

@Data @EqualsAndHashCode(callSuper = true) @TableName("c_ai_ph_origin")
public class PublicHealthOrigin extends BaseEntity {
    @TableId(value = "id_origin", type = IdType.INPUT)
    private String idOrigin;
    @TableField("id_org")
    private String idOrg;
    @TableField("id_device")
    private String idDevice;
    @TableField("origin_request_id")
    private String originRequestId;
    @TableField("binding_hash")
    private String bindingHash;
    @TableField("patient_sex")
    private String patientSex;
}
