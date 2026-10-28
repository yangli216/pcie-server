package com.regionalai.floatingball.server.modules.emrqc.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.ConflictException;
import com.regionalai.floatingball.server.common.exception.NotFoundException;
import com.regionalai.floatingball.server.common.exception.ServiceUnavailableException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.emrqc.config.EmrQcProperties;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcTaskCreateRequest;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcTaskCreateVO;
import com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcTaskVO;
import com.regionalai.floatingball.server.modules.emrqc.entity.AiEmrQcTask;
import com.regionalai.floatingball.server.modules.emrqc.mapper.AiEmrQcTaskMapper;
import com.regionalai.floatingball.server.modules.phisemr.model.PhisEmrRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class EmrQcTaskService {

    private static final Logger log = LoggerFactory.getLogger(EmrQcTaskService.class);
    private static final int HARD_MAX_RECORDS = 50;

    private final EmrQcProperties properties;
    private final EmrQcSchemaService schemaService;
    private final EmrQcService emrQcService;
    private final EmrQcAuditService auditService;
    private final AiEmrQcTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final SensitiveDataMasker masker;

    private final ExecutorService executor;
    private final ExecutorService coordinator;

    public EmrQcTaskService(EmrQcProperties properties,
                            EmrQcSchemaService schemaService,
                            EmrQcService emrQcService,
                            EmrQcAuditService auditService,
                            AiEmrQcTaskMapper taskMapper,
                            ObjectMapper objectMapper,
                            SensitiveDataMasker masker) {
        this.properties = properties;
        this.schemaService = schemaService;
        this.emrQcService = emrQcService;
        this.auditService = auditService;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.masker = masker;
        int concurrency = Math.min(5, Math.max(1, properties.getWorkerConcurrency()));
        this.executor = Executors.newFixedThreadPool(concurrency, threadFactory());
        this.coordinator = Executors.newSingleThreadExecutor(threadFactory());
    }

    @PostConstruct
    public void markInterruptedTasks() {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            taskMapper.update(null, new UpdateWrapper<AiEmrQcTask>()
                .in("status", "queued", "running")
                .set("status", "failed")
                .set("error_message", "服务重启，任务已中断，请重新发起")
                .set("finished_at", LocalDateTime.now()));
        } catch (Exception ex) {
            log.warn("emr qc interrupted task cleanup failed: {}", ex.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        coordinator.shutdownNow();
        executor.shutdownNow();
    }

    public synchronized EmrQcTaskCreateVO create(AdminCurrentUser currentUser,
                                                 EmrQcTaskCreateRequest request,
                                                 String requestId) {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException("EMR-QC-DISABLED", "门诊病历质控功能未启用，请联系管理员");
        }
        schemaService.requireReady();
        if (request == null || request.getRecordIds() == null || request.getRecordIds().isEmpty()) {
            throw new BusinessException("EMR-QC-400", "批量质控至少选择 1 份病历");
        }
        EmrQcContext context = emrQcService.context(currentUser, requestId);
        int maxRecords = Math.min(HARD_MAX_RECORDS, Math.max(1, properties.getMaxTaskRecords()));
        Set<String> uniqueIds = new LinkedHashSet<String>();
        for (String id : request.getRecordIds()) {
            if (!StringUtils.hasText(id)) {
                throw new BusinessException("EMR-QC-400", "批量质控病历 ID 不能为空");
            }
            uniqueIds.add(id.trim());
        }
        if (uniqueIds.size() > maxRecords) {
            throw new BusinessException("EMR-QC-400", "批量质控单批最多 " + maxRecords + " 份病历");
        }

        List<EmrQcTaskVO.Item> items = new ArrayList<EmrQcTaskVO.Item>();
        Map<String, PhisEmrRecord> authorizedRecords = new LinkedHashMap<String, PhisEmrRecord>();
        for (String id : uniqueIds) {
            PhisEmrRecord record = emrQcService.requireRecord(context, id);
            authorizedRecords.put(id, record);
            EmrQcTaskVO.Item item = new EmrQcTaskVO.Item();
            item.setIdMedrecdoc(id);
            item.setPatientName(masker.maskName(record.getPatientName()));
            item.setStatus("pending");
            items.add(item);
        }

        int running = taskMapper.selectCount(new QueryWrapper<AiEmrQcTask>()
            .eq("id_org", context.getIdOrg())
            .eq("created_by", context.getOperatorId())
            .in("status", "queued", "running")).intValue();
        if (running > 0) {
            throw new ConflictException("EMR-QC-TASK-BUSY", "当前已有进行中的批量质控任务，请等待完成");
        }

        AiEmrQcTask task = new AiEmrQcTask();
        task.setIdOrg(context.getIdOrg());
        task.setCreatedBy(context.getOperatorId());
        task.setCreatedByName(context.getOperatorName());
        task.setRecordIdsJson(writeJson(new ArrayList<String>(uniqueIds)));
        task.setItemsJson(writeJson(items));
        task.setTotalCount(uniqueIds.size());
        task.setProcessedCount(0);
        task.setSucceededCount(0);
        task.setFailedCount(0);
        task.setStatus("queued");
        taskMapper.insert(task);

        auditService.record(context, "batch_analyze", null, task.getIdTask(), "queued");
        final EmrQcContext taskContext = context;
        final List<String> ids = new ArrayList<String>(uniqueIds);
        final boolean force = request.forceEnabled();
        coordinator.submit(() -> run(task.getIdTask(), taskContext, ids, authorizedRecords, force));

        EmrQcTaskCreateVO vo = new EmrQcTaskCreateVO();
        vo.setTaskId(task.getIdTask());
        vo.setStatus("queued");
        vo.setTotal(uniqueIds.size());
        return vo;
    }

    public EmrQcTaskVO get(AdminCurrentUser currentUser, String taskId, String requestId) {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException("EMR-QC-DISABLED", "门诊病历质控功能未启用，请联系管理员");
        }
        schemaService.requireReady();
        EmrQcContext context = emrQcService.context(currentUser, requestId);
        AiEmrQcTask task = taskMapper.selectOne(new QueryWrapper<AiEmrQcTask>()
            .eq("id_task", taskId)
            .eq("id_org", context.getIdOrg())
            .eq("created_by", context.getOperatorId()));
        if (task == null) {
            throw new NotFoundException("EMR-RECORD-NOT-FOUND", "批量质控任务不存在");
        }
        return toVO(task);
    }

    public EmrQcTaskVO latest(AdminCurrentUser currentUser) {
        if (!properties.isEnabled()) {
            throw new ServiceUnavailableException("EMR-QC-DISABLED", "门诊病历质控功能未启用，请联系管理员");
        }
        schemaService.requireReady();
        EmrQcContext context = emrQcService.context(currentUser, null);
        List<AiEmrQcTask> tasks = taskMapper.selectList(new QueryWrapper<AiEmrQcTask>()
            .eq("id_org", context.getIdOrg())
            .eq("created_by", context.getOperatorId())
            .orderByDesc("insert_time"));
        return tasks.isEmpty() ? null : toVO(tasks.get(0));
    }

    private void run(String taskId, EmrQcContext context, List<String> ids,
                     Map<String, PhisEmrRecord> authorizedRecords, boolean force) {
        AiEmrQcTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        List<EmrQcTaskVO.Item> items = readItems(task.getItemsJson());
        task.setStatus("running");
        task.setStartedAt(LocalDateTime.now());
        taskMapper.updateById(task);

        CompletionService<EmrQcTaskVO.Item> completion = new ExecutorCompletionService<EmrQcTaskVO.Item>(executor);
        List<Future<EmrQcTaskVO.Item>> futures = new ArrayList<Future<EmrQcTaskVO.Item>>();
        int processed = 0;
        int succeeded = 0;
        int failed = 0;
        try {
            for (EmrQcTaskVO.Item item : items) {
                futures.add(completion.submit(() -> analyzeItem(context, authorizedRecords.get(item.getIdMedrecdoc()), item, force)));
            }
            while (processed < items.size()) {
                EmrQcTaskVO.Item completed = completion.take().get();
                for (int index = 0; index < items.size(); index++) {
                    if (items.get(index).getIdMedrecdoc().equals(completed.getIdMedrecdoc())) {
                        items.set(index, completed);
                        break;
                    }
                }
                if ("succeeded".equals(completed.getStatus())) succeeded++;
                else failed++;
                processed++;
                task.setProcessedCount(processed);
                task.setSucceededCount(succeeded);
                task.setFailedCount(failed);
                task.setItemsJson(writeJson(items));
                taskMapper.updateById(task);
            }
            task.setStatus(failed > 0 && succeeded == 0 ? "failed"
                : (failed > 0 ? "partially_completed" : "completed"));
            task.setFinishedAt(LocalDateTime.now());
            taskMapper.updateById(task);
            log.info("emr qc batch task finished. taskId={}, total={}, succeeded={}, failed={}",
                taskId, ids.size(), succeeded, failed);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            failTask(task, futures);
        } catch (ExecutionException | RuntimeException ex) {
            log.warn("emr qc batch coordinator failed. taskId={}", taskId, ex);
            failTask(task, futures);
        }
    }

    private EmrQcTaskVO.Item analyzeItem(EmrQcContext context, PhisEmrRecord record,
                                         EmrQcTaskVO.Item item, boolean force) {
        // Each worker owns a separate result; the coordinator alone mutates/persists task items.
        EmrQcTaskVO.Item result = new EmrQcTaskVO.Item();
        result.setIdMedrecdoc(item.getIdMedrecdoc());
        result.setPatientName(item.getPatientName());
        try {
            EmrQcAnalysisVO analysis = emrQcService.analyzeForTask(context, record, force);
            result.setStatus("succeeded");
            result.setScore(analysis.getScore());
            result.setFindingCount(analysis.getFindingCount());
        } catch (RuntimeException ex) {
            result.setStatus("failed");
            result.setErrorCode(errorCode(ex));
            result.setErrorMessage(errorMessage(ex));
        }
        return result;
    }

    private void failTask(AiEmrQcTask task, List<Future<EmrQcTaskVO.Item>> futures) {
        for (Future<EmrQcTaskVO.Item> future : futures) future.cancel(true);
        task.setStatus("failed");
        task.setErrorMessage("批量任务中断，请重新发起");
        task.setFinishedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private EmrQcTaskVO toVO(AiEmrQcTask task) {
        EmrQcTaskVO vo = new EmrQcTaskVO();
        vo.setTaskId(task.getIdTask());
        vo.setStatus(task.getStatus());
        vo.setTotal(task.getTotalCount());
        vo.setProcessed(task.getProcessedCount());
        vo.setSucceeded(task.getSucceededCount());
        vo.setFailed(task.getFailedCount());
        vo.setCreatedAt(task.getInsertTime());
        vo.setStartedAt(task.getStartedAt());
        vo.setFinishedAt(task.getFinishedAt());
        vo.setItems(readItems(task.getItemsJson()));
        return vo;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private List<EmrQcTaskVO.Item> readItems(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<EmrQcTaskVO.Item>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<EmrQcTaskVO.Item>>() {});
        } catch (Exception ex) {
            return new ArrayList<EmrQcTaskVO.Item>();
        }
    }

    private String errorCode(RuntimeException ex) {
        if (ex instanceof ServiceUnavailableException) {
            return ((ServiceUnavailableException) ex).getCode();
        }
        if (ex instanceof ConflictException) {
            return ((ConflictException) ex).getCode();
        }
        if (ex instanceof NotFoundException) {
            return ((NotFoundException) ex).getCode();
        }
        if (ex instanceof BusinessException) {
            return ((BusinessException) ex).getCode();
        }
        return "EMR-QC-UNAVAILABLE";
    }

    private String errorMessage(RuntimeException ex) {
        String message = ex.getMessage();
        if (!StringUtils.hasText(message)) {
            return "质控失败，请稍后重试";
        }
        return message.length() <= 200 ? message : message.substring(0, 200);
    }

    private ThreadFactory threadFactory() {
        final AtomicInteger index = new AtomicInteger(1);
        return runnable -> {
            Thread thread = new Thread(runnable, "emr-qc-worker-" + index.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        };
    }
}
