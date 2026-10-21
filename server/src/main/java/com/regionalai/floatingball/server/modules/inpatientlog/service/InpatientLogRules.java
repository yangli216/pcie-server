package com.regionalai.floatingball.server.modules.inpatientlog.service;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.inpatientlog.dto.InpatientLogSnapshot;
import java.util.*;
import org.springframework.util.StringUtils;

public final class InpatientLogRules {
    private static final Map<String, String> TYPES = new HashMap<>();
    static {
        TYPES.put("started", "generating"); TYPES.put("waiting", "waiting");
        TYPES.put("generated", "generated"); TYPES.put("generation_failed", "generation_failed");
        TYPES.put("fallback", "fallback"); TYPES.put("writeback_requested", "writeback_pending");
        TYPES.put("writeback_dispatched", "writeback_pending"); TYPES.put("writeback_success", "writeback_success");
        TYPES.put("writeback_failed", "writeback_failed"); TYPES.put("abandoned", "abandoned"); TYPES.put("superseded", "superseded");
    }
    private InpatientLogRules() {}
    public static boolean statusAllowed(String status) { return TYPES.containsValue(status); }
    public static void validate(InpatientLogSnapshot snapshot) {
        if (!Objects.equals(TYPES.get(snapshot.getEventType()), snapshot.getStatus()) || snapshot.getStatus() == null)
            throw new BusinessException("日志事件与处理状态不匹配");
        if (snapshot.getEventType().startsWith("writeback_") && !StringUtils.hasText(snapshot.getRequestId()))
            throw new BusinessException("回填事件必须包含请求标识");
        if (snapshot.getEventType().startsWith("writeback_") && (snapshot.getFields().isEmpty()
            || snapshot.getFields().stream().anyMatch(field -> field == null || field.getFinalValue() == null)))
            throw new BusinessException("回填事件必须包含冻结的最终字段内容");
        Set<String> ids = new HashSet<>();
        for (InpatientLogSnapshot.Field field : snapshot.getFields()) {
            if (field == null || !ids.add(field.getId())) throw new BusinessException("日志字段标识重复或为空");
        }
        Set<String> sources = new HashSet<>();
        for (InpatientLogSnapshot.Source source : snapshot.getSources()) {
            if (source == null || !sources.add(source.getCode())) throw new BusinessException("日志来源重复或为空");
        }
    }
    public static void checkTransition(String oldStatus, String next) {
        if (Arrays.asList("writeback_success", "abandoned", "superseded").contains(oldStatus))
            throw new BusinessException("该生成轮次已结束，请创建新的生成轮次");
        if ("writeback_pending".equals(oldStatus) && !Arrays.asList("writeback_pending", "writeback_success", "writeback_failed").contains(next))
            throw new BusinessException("正在等待HIS回执，不能结束或重新生成");
        if (!"generating".equals(oldStatus)
            && Arrays.asList("generating", "waiting", "generated", "generation_failed", "fallback").contains(next))
            throw new BusinessException("已完成生成的轮次不能退回生成状态");
        if (Arrays.asList("writeback_pending", "writeback_failed").contains(oldStatus)
            && Arrays.asList("generating", "waiting", "generated", "generation_failed", "fallback").contains(next))
            throw new BusinessException("回填后的轮次不能退回生成状态");
    }
    public static String label(String status) {
        switch (status) {
            case "generating": return "生成中"; case "waiting": return "待补充依据";
            case "generated": return "已生成"; case "generation_failed": return "生成失败";
            case "fallback": return "备用草稿"; case "writeback_pending": return "待HIS回执";
            case "writeback_success": return "回填成功"; case "writeback_failed": return "回填失败";
            case "abandoned": return "已放弃"; case "superseded": return "已重新生成";
            default: return status;
        }
    }
}
