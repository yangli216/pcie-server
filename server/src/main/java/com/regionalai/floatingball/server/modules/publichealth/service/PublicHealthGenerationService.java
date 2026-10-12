package com.regionalai.floatingball.server.modules.publichealth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.regionalai.floatingball.server.modules.publichealth.entity.PublicHealthRun;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import javax.annotation.PreDestroy;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class PublicHealthGenerationService {
    private final PublicHealthProperties properties;
    private final PublicHealthRunStore store;
    private final PublicHealthOrchestrator orchestrator;
    private final ObjectMapper mapper;
    private final Executor executor;
    private final ScheduledExecutorService timer = Executors.newScheduledThreadPool(2, r -> {
        Thread thread = new Thread(r, "public-health-deadline");
        thread.setDaemon(true);
        return thread;
    });

    public PublicHealthGenerationService(PublicHealthProperties properties, PublicHealthRunStore store,
            PublicHealthOrchestrator orchestrator, ObjectMapper mapper,
            @Qualifier("aiStreamExecutor") Executor executor) {
        this.properties = properties;
        this.store = store;
        this.orchestrator = orchestrator;
        this.mapper = mapper;
        this.executor = executor;
    }

    public SseEmitter generate(AiDevice device, PublicHealthValidator.Parsed parsed) {
        if (!properties.isEnabled()) {
            throw new PublicHealthException(503,"POLICY_NOT_READY","公卫生成尚未启用");
        }
        PublicHealthRun existing = store.find(device, parsed.request, parsed.inputHash);
        PublicHealthOrchestrator.Prepared prepared = existing == null
                ? orchestrator.prepare(device, parsed.request) : null;
        PublicHealthRunStore.Reservation reservation = existing == null
                ? store.reserve(device, parsed.request, parsed.inputHash)
                : new PublicHealthRunStore.Reservation(existing, false);
        Session session = new Session(parsed.request, reservation.run);
        try {
            executor.execute(() -> {
                if (!reservation.created) {
                    session.replay();
                    return;
                }
                try {
                    session.start();
                    session.check();
                    ObjectNode result = orchestrator.generate(device, parsed.request,
                            reservation.run.getGenerationId(), prepared, session);
                    session.success(result);
                } catch (PublicHealthException e) {
                    session.failure(e.getCode(), e.getMessage());
                } catch (Exception e) {
                    session.failure("GENERATION_FAILED","生成未完成，请保留资料并明确发起新生成");
                }
            });
        } catch (RejectedExecutionException e) {
            if (reservation.created) {
                store.fail(reservation.run,"GENERATION_FAILED","生成队列已满，本次未执行");
            }
            throw new PublicHealthException(429,"GENERATION_FAILED","生成队列已满，本次未执行");
        }
        return session.emitter;
    }
    @PreDestroy
    public void close() {
        timer.shutdownNow();
    }

    private final class Session implements PublicHealthOrchestrator.Listener {
        private final PublicHealthRequest request;
        private final PublicHealthRun run;
        private final String streamId = UUID.randomUUID().toString();
        private final SseEmitter emitter = new SseEmitter(PublicHealthRunStore.TIMEOUT_MS+5000);
        private volatile boolean connected = true;
        private boolean terminal;
        private int sequence;
        private ScheduledFuture<?> heartbeat, deadline;
        Session(PublicHealthRequest request, PublicHealthRun run) {
            this.request = request;
            this.run = run;
            emitter.onCompletion(() -> connected = false);
            emitter.onError(e -> connected = false);
            emitter.onTimeout(() -> connected = false);
        }
        synchronized void start() {
            ObjectNode accepted = base();
            accepted.put("mode","new");
            accepted.put("acceptedAt", OffsetDateTime.now().toString());
            send("accepted", accepted);
            heartbeat = timer.scheduleAtFixedRate(() -> {
                synchronized(this) {
                    if (!terminal && connected) {
                        try {
                            emitter.send(SseEmitter.event().comment("keepalive"));
                        } catch (Exception e) {
                            connected = false;
                        }
                    }
                }
            }, 15, 15, TimeUnit.SECONDS);
            deadline = timer.schedule(
                    () -> failure("GENERATION_TIMEOUT", "生成达到时间上限，已停止后续节点；已有上游请求可能继续执行"),
                    Math.max(1, run.getExpiresAtMs() - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
        }
        synchronized void check() {
            if (terminal || (run.getExpiresAtMs() != null && System.currentTimeMillis() >= run.getExpiresAtMs())) {
                throw new PublicHealthException(504, "GENERATION_TIMEOUT","生成达到时间上限，未自动重试");
            }
        }
        @Override
        public synchronized void progress(String stage) {
            check();
            ObjectNode data = base();
            data.put("stage", stage);
            data.put("message", message(stage));
            send("progress", data);
        }
        @Override
        public synchronized void metrics(ObjectNode metrics) {
            check();
            ObjectNode data = base();
            data.set("metrics", metrics);
            send("metrics", data);
        }
        @Override
        public synchronized void section(ObjectNode section) {
            check();
            ObjectNode data = base();
            data.set("section", section);
            data.put("provisional", true);
            send("section", data);
        }
        synchronized void success(ObjectNode result) {
            check();
            if (!store.complete(run, result)) {
                failure("GENERATION_STATE_UNKNOWN","生成运行已终止，不能覆盖原状态");
                return;
            }
            terminal = true;
            ObjectNode data = base();
            data.set("result", result);
            send("completed", data);
            finish();
        }
        synchronized void failure(String code, String message) {
            if (terminal) {
                return;
            }
            try {
                if (!store.fail(run, code, message)) {
                    code = "GENERATION_STATE_UNKNOWN";
                    message = "原运行已终止，请恢复原请求核对结果";
                }
            } catch (Exception e) {
                code = "GENERATION_STATE_UNKNOWN";
                message = "生成状态保存未确认，请保留请求ID核对结果";
            }
            terminal = true;
            ObjectNode data = base();
            data.put("errorCode", code);
            data.put("message", message);
            data.put("retryable", false);
            data.put("failedAt", OffsetDateTime.now().toString());
            send("failed", data);
            finish();
        }
        synchronized void replay() {
            ObjectNode accepted = base();
            accepted.put("mode","replayed");
            accepted.put("acceptedAt", OffsetDateTime.now().toString());
            send("accepted", accepted);
            ObjectNode data = base();
            try {
                if ("COMPLETED".equals(run.getStatus())) {
                    if (run.getResultJson() == null) {
                        throw new IllegalStateException();
                    }
                    data.set("result", mapper.readTree(run.getResultJson()));
                    send("completed", data);
                } else {
                    data.put("errorCode", run.getErrorCode() == null?"GENERATION_STATE_UNKNOWN":run.getErrorCode());
                    data.put("message", run.getErrorMessage() == null?"原运行结果未知，请明确发起新生成":run.getErrorMessage());
                    data.put("retryable", false);
                    data.put("failedAt", run.getFinishedAt() == null?OffsetDateTime.now().toString():run.getFinishedAt());
                    send("failed", data);
                }
            } catch (Exception e) {
                data = base();
                data.put("errorCode","GENERATION_STATE_UNKNOWN");
                data.put("message","无法恢复原生成结果");
                data.put("retryable", false);
                data.put("failedAt", OffsetDateTime.now().toString());
                send("failed", data);
            }
            terminal = true;
            finish();
        }
        private ObjectNode base() {
            ObjectNode data = orchestrator.identity(request);
            data.put("schemaVersion","public-health.v1");
            data.put("requestId", request.getRequestId());
            data.put("generationId", run.getGenerationId());
            data.put("streamId", streamId);
            return data;
        }
        private void send(String event, ObjectNode data) {
            data.put("sequence", ++sequence);
            if (connected) {
                try {
                    emitter.send(SseEmitter.event().name(event).id(streamId+":"+sequence).data(data));
                } catch (Exception e) {
                    connected = false;
                }
            }
        }
        private void finish() {
            if (heartbeat != null) {
                heartbeat.cancel(false);
            }
            if (deadline != null) {
                deadline.cancel(false);
            }
            if (connected) {
                emitter.complete();
            }
        }
        private String message(String stage) {
            if ("calculating_metrics".equals(stage)) {
                return "正在计算体重指标";
            }
            if ("retrieving_knowledge".equals(stage)) {
                return "正在检索地区食谱";
            }
            if ("validating_output".equals(stage)) {
                return "正在核对完整结果";
            }
            return "正在生成建议章节";
        }
    }
}
