package com.regionalai.floatingball.server.modules.publichealth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.publichealth.dto.PublicHealthRequest;
import com.regionalai.floatingball.server.modules.publichealth.entity.*;
import com.regionalai.floatingball.server.modules.publichealth.mapper.PublicHealthRunMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import java.util.UUID;
import java.util.Locale;

@Service
public class PublicHealthRunStore {
    public static final long TIMEOUT_MS = 180000L;
    private final PublicHealthRunMapper runs;
    private final PublicHealthRunWriter writer;
    private final ObjectMapper mapper;
    public PublicHealthRunStore(PublicHealthRunMapper runs, PublicHealthRunWriter writer, ObjectMapper mapper) {
        this.runs = runs;
        this.writer = writer;
        this.mapper = mapper;
    }

    public PublicHealthRun find(AiDevice device, PublicHealthRequest request, String inputHash) {
        scope(device);
        PublicHealthRun run = runs.selectOne(new LambdaQueryWrapper<PublicHealthRun>().eq(PublicHealthRun::getIdOrg, device.getIdOrg()).eq(PublicHealthRun::getIdDevice,
        device.getIdDevice()).eq(PublicHealthRun::getRequestId, key(request.getRequestId())));
        if (run != null) {
            if (!inputHash.equals(run.getInputHash())) {
                throw new PublicHealthException(409,"REQUEST_ID_CONFLICT","同一请求ID不能对应不同资料快照");
            }
            if ("RUNNING".equals(run.getStatus())) {
                if (run.getExpiresAtMs() != null && run.getExpiresAtMs()<System.currentTimeMillis()) {
                    writer.finish(run.getGenerationId(),"FAILED", null,"GENERATION_STATE_UNKNOWN","原生成运行已过期，结果未知；请明确发起新生成");
                    run = runs.selectById(run.getGenerationId());
                }
                if (run == null || "RUNNING".equals(run.getStatus())) {
                    throw new PublicHealthException(409,"GENERATION_IN_PROGRESS","该请求正在生成，请勿重复提交");
                }
            }
        }
        return run;
    }

    public Reservation reserve(AiDevice device, PublicHealthRequest request, String inputHash) {
        PublicHealthRun existing = find(device, request, inputHash);
        if (existing != null) {
            return new Reservation(existing, false);
        }
        PublicHealthOrigin origin = new PublicHealthOrigin();
        origin.setIdOrigin(UUID.randomUUID().toString());
        origin.setIdOrg(device.getIdOrg());
        origin.setIdDevice(device.getIdDevice());
        origin.setOriginRequestId(key(request.getOriginRequestId()));
        origin.setFgActive("1");
        origin.setPatientSex(request.getPatient().getSex());
        ObjectNode binding = mapper.createObjectNode();
        binding.put("patientId", request.getPatientId());
        binding.put("scene", request.getScene());
        binding.put("businessRecordId", request.getBusinessRecordId());
        binding.put("capability", request.getCapability());
        binding.put("recordVersion", request.getRecordVersion());
        binding.put("patientName", request.getPatient().getName());
        binding.put("patientAgeYears", request.getPatient().getAgeYears());
        binding.set("writebackTargets", mapper.valueToTree(request.getWritebackTargets()));
        try {
            origin.setBindingHash(PublicHealthJson.sha256(mapper.writeValueAsBytes(PublicHealthJson.sorted(binding, mapper))));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        PublicHealthRun run = new PublicHealthRun();
        run.setGenerationId(UUID.randomUUID().toString());
        run.setIdOrg(device.getIdOrg());
        run.setIdDevice(device.getIdDevice());
        run.setOriginRequestId(key(request.getOriginRequestId()));
        run.setRequestId(key(request.getRequestId()));
        run.setInputHash(inputHash);
        run.setStatus("RUNNING");
        run.setExpiresAtMs(System.currentTimeMillis()+TIMEOUT_MS);
        run.setFgActive("1");
        for (int i = 0; i<2; i++) {
            try {
                writer.reserve(origin, run, request.getPatient().getSexSource() != null && "PCIE_MANUAL".equals(request.getPatient().getSexSource().getSystem()));
                return new Reservation(run, true);
            } catch (DuplicateKeyException e) {
                existing = find(device, request, inputHash);
                if (existing != null) {
                    return new Reservation(existing, false);
                }
            }
        }
        throw new PublicHealthException(409,"GENERATION_IN_PROGRESS","请求正在受理，请勿重复生成");
    }

    public boolean complete(PublicHealthRun run, ObjectNode result) {
        try {
            return writer.finish(run.getGenerationId(),"COMPLETED", mapper.writeValueAsString(result), null, null);
        } catch (PublicHealthException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("无法保存生成结果", e);
        }
    }

    public boolean fail(PublicHealthRun run, String code, String message) {
        return writer.finish(run.getGenerationId(),"FAILED", null, code, message);
    }

    private void scope(AiDevice device) {
        if (device == null || device.getIdOrg() == null || device.getIdOrg().trim().isEmpty() || device.getIdDevice() == null || device.getIdDevice().trim().isEmpty()) {
            throw new PublicHealthException(403, "TASK_CONTEXT_MISMATCH","缺少有效设备机构上下文");
        }
    }

    private String key(String id) {
        return id.toLowerCase(Locale.ROOT);
    }

    public static final class Reservation {
        public final PublicHealthRun run;
        public final boolean created;
        public Reservation(PublicHealthRun run, boolean created) {
            this.run = run;
            this.created = created;
        }
    }
}
