package com.regionalai.floatingball.server.modules.chronicdisease.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Data
public class ChronicArtifactSnapshotItemRequest {

    @NotBlank(message = "确认项 ID 不能为空")
    @Size(max = 128, message = "确认项 ID 不能超过 128 个字符")
    private String itemId;

    @NotBlank(message = "确认项分类不能为空")
    @Size(max = 32, message = "确认项分类不能超过 32 个字符")
    private String category;

    @NotBlank(message = "确认项标题不能为空")
    @Size(max = 255, message = "确认项标题不能超过 255 个字符")
    private String title;

    @NotBlank(message = "确认项内容不能为空")
    @Size(max = 2000, message = "确认项内容不能超过 2000 个字符")
    private String detail;

    @NotBlank(message = "确认项依据不能为空")
    @Size(max = 2000, message = "确认项依据不能超过 2000 个字符")
    private String reason;
}
