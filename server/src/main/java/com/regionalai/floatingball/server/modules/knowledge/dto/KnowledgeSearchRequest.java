package com.regionalai.floatingball.server.modules.knowledge.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import lombok.Data;
import javax.validation.constraints.*;

@Data
public class KnowledgeSearchRequest {
    @NotBlank(message = "请选择知识集合") @Size(max = 128, message = "知识集合标识过长")
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}", message = "知识集合标识不合法")
    private String collectionId;
    @NotBlank(message = "请输入检索内容") @Size(max = 2000, message = "检索内容不能超过2000字符")
    private String query;
    @NotBlank(message = "请刷新知识集合版本") @Size(max = 128, message = "知识版本标识过长")
    private String releaseVersion;
    @NotNull(message = "返回条数不能为空")
    @Min(value = 1, message = "返回条数至少为1") @Max(value = 20, message = "返回条数不能超过20")
    private Integer limit = 5;

    @JsonAnySetter
    public void rejectUnknown(String ignored, Object value) {
        throw new IllegalArgumentException("knowledge request contains unsupported fields");
    }
}
