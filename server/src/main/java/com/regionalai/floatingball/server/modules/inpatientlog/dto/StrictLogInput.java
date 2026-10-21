package com.regionalai.floatingball.server.modules.inpatientlog.dto;
import com.fasterxml.jackson.annotation.JsonAnySetter;
public class StrictLogInput {
    @JsonAnySetter public void unknown(String key, Object value) { throw new IllegalArgumentException("不支持的日志字段"); }
}
