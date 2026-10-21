package com.regionalai.floatingball.server.modules.knowledge.dto;

import lombok.Getter;
import lombok.Setter;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

@Getter
@Setter
public class DrugInstructionSearchRequest {
    @NotBlank(message = "请输入药品名称、通用名或拼音")
    @Size(max = 128, message = "检索内容不能超过128字符")
    private String keyword;
    @Min(value = 1, message = "页码不能小于1")
    @Max(value = 1000, message = "请缩小检索范围")
    private int current = 1;
    @Min(value = 1, message = "每页条数不能小于1")
    @Max(value = 100, message = "每页最多100条")
    private int size = 20;
}
