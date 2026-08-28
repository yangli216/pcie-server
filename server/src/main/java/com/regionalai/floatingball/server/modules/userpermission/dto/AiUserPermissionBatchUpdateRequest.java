package com.regionalai.floatingball.server.modules.userpermission.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.List;

@Data
public class AiUserPermissionBatchUpdateRequest {

    @NotBlank(message = "机构 ID 不能为空")
    private String orgId;

    @NotNull(message = "人员 ID 列表不能为空")
    @Size(min = 1, max = 200, message = "每次批量操作人数必须在 1 到 200 之间")
    private List<String> personIds;

    @NotNull(message = "权限状态不能为空")
    private Boolean enabled;
}
