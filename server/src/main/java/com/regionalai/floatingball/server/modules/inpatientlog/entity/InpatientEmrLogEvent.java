package com.regionalai.floatingball.server.modules.inpatientlog.entity;
import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;
@Data @TableName("c_ai_inpatient_emr_log_event")
public class InpatientEmrLogEvent {
    @TableId(value = "id_event", type = IdType.INPUT) private String idEvent;
    private String idLog;
    private String idDevice;
    private String eventId;
    private Integer eventSequence;
    private String eventType;
    private String status;
    private LocalDateTime occurredAt;
    private String message;
    private String bodyHash;
    private String snapshotJson;
}
