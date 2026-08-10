package com.regionalai.floatingball.server.modules.device.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.UpdateRequiredException;
import com.regionalai.floatingball.server.common.util.MaskingUtils;
import com.regionalai.floatingball.server.modules.device.dto.AiDeviceSaveRequest;
import com.regionalai.floatingball.server.modules.device.dto.RegisterDeviceRequest;
import com.regionalai.floatingball.server.modules.device.dto.RegisterDeviceResponse;
import com.regionalai.floatingball.server.modules.device.dto.AiDeviceView;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.mapper.AiDeviceMapper;
import com.regionalai.floatingball.server.modules.org.entity.AiOrg;
import com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper;
import com.regionalai.floatingball.server.modules.region.entity.AiRegion;
import com.regionalai.floatingball.server.modules.region.mapper.AiRegionMapper;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyView;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.userlog.mapper.AiUserConsultationLogMapper;
import org.apache.ibatis.exceptions.TooManyResultsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private static final int DEFAULT_HEARTBEAT_INTERVAL = 30;
    private static final String UNKNOWN_IP = "unknown";
    private static final String DEVICE_DISABLED_CODE = "DEVICE-DISABLED";
    private static final String DEVICE_DISABLED_MESSAGE = "设备已被管理员停用，请联系管理员重新发放令牌后再连接";

    private final AiDeviceMapper aiDeviceMapper;
    private final AiOrgMapper aiOrgMapper;
    private final AiRegionMapper aiRegionMapper;
    private final AiUserConsultationLogMapper userConsultationLogMapper;
    private final ReleaseService releaseService;
    private final DatabaseDialect databaseDialect;

    public DeviceService(AiDeviceMapper aiDeviceMapper,
                         AiOrgMapper aiOrgMapper,
                         AiRegionMapper aiRegionMapper,
                         AiUserConsultationLogMapper userConsultationLogMapper,
                         ReleaseService releaseService,
                         DatabaseDialect databaseDialect) {
        this.aiDeviceMapper = aiDeviceMapper;
        this.aiOrgMapper = aiOrgMapper;
        this.aiRegionMapper = aiRegionMapper;
        this.userConsultationLogMapper = userConsultationLogMapper;
        this.releaseService = releaseService;
        this.databaseDialect = databaseDialect;
    }

    @Transactional
    public RegisterDeviceResponse register(RegisterDeviceRequest request) {
        return register(request, null, null);
    }

    @Transactional
    public RegisterDeviceResponse register(RegisterDeviceRequest request, String clientIp) {
        return register(request, clientIp, null);
    }

    @Transactional
    public RegisterDeviceResponse register(RegisterDeviceRequest request, String clientIp, String presentedDeviceToken) {
        String normalizedClientIp = normalizeIp(clientIp);
        AiOrg org = findUniqueActiveOrgByCode(request.getCdOrg());
        if (org == null) {
            throw new BusinessException("机构编码不存在: " + request.getCdOrg());
        }

        if (releaseService.isUpdateRequired(request.getUpdateChannel(), request.getClientVersion())) {
            ReleasePolicyView policy = releaseService.getRequiredPolicy(request.getUpdateChannel());
            throw new UpdateRequiredException(policy.getMinSupportedVersion());
        }

        AiDevice existing = findDeviceByCodeAndOrg(request.getCdDevice(), org.getIdOrg(), "1");

        if (existing != null) {
            if (requiresExistingDeviceToken(existing) && !existing.getDeviceToken().equals(presentedDeviceToken)) {
                log.warn("device public key refresh rejected: token proof missing or mismatched. idDevice={}, cdDevice={}",
                    existing.getIdDevice(), existing.getCdDevice());
                throw new BusinessException(
                    "DEVICE-KEY-ROTATION-UNAUTHORIZED",
                    "设备已注册，请先使用原设备令牌完成密钥轮换；如本机令牌丢失，请联系管理员重置设备");
            }
            boolean publicKeyChanged = StringUtils.hasText(request.getPublicKey())
                && !request.getPublicKey().equals(existing.getDevicePublicKey());
            existing.setNaDevice(request.getNaDevice());
            existing.setClientVersion(request.getClientVersion());
            existing.setOsInfo(request.getOsInfo());
            if (!StringUtils.hasText(existing.getRegisterIp())) {
                existing.setRegisterIp(normalizedClientIp);
            }
            existing.setLastSeenIp(normalizedClientIp);
            if (!StringUtils.hasText(existing.getDeviceToken())) {
                existing.setDeviceToken(generateToken());
            }
            if (StringUtils.hasText(request.getPublicKey())) {
                existing.setDevicePublicKey(request.getPublicKey());
            }
            try {
                aiDeviceMapper.updateById(existing);
            } catch (DuplicateKeyException ex) {
                throw new BusinessException("设备编码已存在");
            }
            if (publicKeyChanged) {
                log.info("device public key refreshed during register. idDevice={}, cdDevice={}", existing.getIdDevice(), existing.getCdDevice());
            } else {
                log.info("device re-registered. idDevice={}, cdDevice={}", existing.getIdDevice(), existing.getCdDevice());
            }
            return new RegisterDeviceResponse(existing.getIdDevice(), existing.getDeviceToken(), DEFAULT_HEARTBEAT_INTERVAL, StringUtils.hasText(existing.getDevicePublicKey()));
        }

        AiDevice disabled = findDeviceByCodeAndOrg(request.getCdDevice(), org.getIdOrg(), "0");
        if (disabled != null) {
            log.warn("device register rejected: disabled device code. disabledIdDevice={}, cdDevice={}, idOrg={}",
                disabled.getIdDevice(), disabled.getCdDevice(), org.getIdOrg());
            throw new BusinessException(DEVICE_DISABLED_CODE, DEVICE_DISABLED_MESSAGE);
        }

        AiDevice device = new AiDevice();
        device.setCdDevice(request.getCdDevice());
        device.setNaDevice(request.getNaDevice());
        device.setIdOrg(org.getIdOrg());
        device.setIdRegion(org.getIdRegion());
        device.setDeviceToken(generateToken());
        device.setDevicePublicKey(request.getPublicKey());
        device.setSdStatus("0");
        device.setDtRegistered(LocalDateTime.now());
        device.setClientVersion(request.getClientVersion());
        device.setOsInfo(request.getOsInfo());
        device.setRegisterIp(normalizedClientIp);
        device.setLastSeenIp(normalizedClientIp);
        device.setFgActive("1");
        try {
            aiDeviceMapper.insert(device);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("设备编码已存在");
        }
        log.info("device registered. idDevice={}, cdDevice={}, idOrg={}", device.getIdDevice(), device.getCdDevice(), device.getIdOrg());
        return new RegisterDeviceResponse(device.getIdDevice(), device.getDeviceToken(), DEFAULT_HEARTBEAT_INTERVAL, StringUtils.hasText(device.getDevicePublicKey()));
    }

    public AiDevice findActiveByToken(String token) {
        return aiDeviceMapper.selectOne(new LambdaQueryWrapper<AiDevice>()
            .eq(AiDevice::getDeviceToken, token)
            .eq(AiDevice::getFgActive, "1")
            .last(databaseDialect.firstRows(1)));
    }

    @Transactional
    public void heartbeat(AiDevice device) {
        heartbeat(device, null, null);
    }

    @Transactional
    public void heartbeat(AiDevice device, String clientIp) {
        heartbeat(device, clientIp, null);
    }

    @Transactional
    public void heartbeat(AiDevice device, String clientIp, String clientVersion) {
        device.setDtLastHeartbeat(LocalDateTime.now());
        device.setSdStatus("1");
        String normalizedClientIp = normalizeIp(clientIp);
        if (StringUtils.hasText(normalizedClientIp) && !UNKNOWN_IP.equalsIgnoreCase(normalizedClientIp)) {
            device.setLastSeenIp(normalizedClientIp);
        }
        if (StringUtils.hasText(clientVersion)) {
            String normalizedClientVersion = clientVersion.trim();
            device.setClientVersion(normalizedClientVersion.length() > 64
                ? normalizedClientVersion.substring(0, 64)
                : normalizedClientVersion);
        }
        aiDeviceMapper.updateById(device);
        log.debug("device heartbeat. idDevice={}", device.getIdDevice());
    }

    public PageResponse<AiDeviceView> list(long current, long size, String keyword) {
        Page<AiDevice> page = new Page<AiDevice>(current, size);
        LambdaQueryWrapper<AiDevice> wrapper = new LambdaQueryWrapper<AiDevice>()
            .eq(AiDevice::getFgActive, "1")
            .orderByDesc(AiDevice::getUpdateTime);
        if (StringUtils.hasText(keyword)) {
            wrapper.and(q -> q.like(AiDevice::getCdDevice, keyword).or().like(AiDevice::getNaDevice, keyword));
        }
        Page<AiDevice> result = aiDeviceMapper.selectPage(page, wrapper);
        return new PageResponse<AiDeviceView>(result.getCurrent(), result.getSize(), result.getTotal(), toViews(result.getRecords()));
    }

    @Transactional
    public AiDeviceView save(AiDeviceSaveRequest request) {
        validateSaveRequest(request);
        AiOrg org = requireActiveOrg(request.getIdOrg());
        ensureUniqueCode(request.getCdDevice(), org.getIdOrg(), null);

        AiDevice device = new AiDevice();
        device.setCdDevice(request.getCdDevice().trim());
        device.setNaDevice(request.getNaDevice());
        device.setIdOrg(org.getIdOrg());
        device.setIdRegion(org.getIdRegion());
        device.setDeviceToken(generateToken());
        device.setSdStatus(StringUtils.hasText(request.getSdStatus()) ? request.getSdStatus() : "0");
        device.setDtRegistered(LocalDateTime.now());
        device.setClientVersion(request.getClientVersion());
        device.setOsInfo(request.getOsInfo());
        device.setFgActive("1");
        try {
            aiDeviceMapper.insert(device);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("设备编码已存在");
        }
        return toView(device, org, null);
    }

    @Transactional
    public AiDeviceView update(String idDevice, AiDeviceSaveRequest request) {
        validateSaveRequest(request);
        AiDevice existing = aiDeviceMapper.selectById(idDevice);
        if (existing == null || !"1".equals(existing.getFgActive())) {
            throw new BusinessException("设备不存在");
        }

        AiOrg org = requireActiveOrg(request.getIdOrg());
        ensureUniqueCode(request.getCdDevice(), org.getIdOrg(), idDevice);

        existing.setCdDevice(request.getCdDevice().trim());
        existing.setNaDevice(request.getNaDevice());
        existing.setIdOrg(org.getIdOrg());
        existing.setIdRegion(org.getIdRegion());
        existing.setSdStatus(StringUtils.hasText(request.getSdStatus()) ? request.getSdStatus() : existing.getSdStatus());
        existing.setClientVersion(request.getClientVersion());
        existing.setOsInfo(request.getOsInfo());
        try {
            aiDeviceMapper.updateById(existing);
        } catch (DuplicateKeyException ex) {
            throw new BusinessException("设备编码已存在");
        }
        return toView(existing, org, null);
    }

    @Transactional
    public void invalidate(String idDevice) {
        AiDevice device = aiDeviceMapper.selectById(idDevice);
        if (device == null) {
            throw new BusinessException("设备不存在");
        }
        device.setFgActive("0");
        device.setSdStatus("0");
        aiDeviceMapper.updateById(device);
    }

    @Transactional
    public void deleteForReset(String idDevice) {
        AiDevice device = aiDeviceMapper.selectById(idDevice);
        if (device == null) {
            throw new BusinessException("设备不存在");
        }
        aiDeviceMapper.deleteById(idDevice);
    }

    private void validateSaveRequest(AiDeviceSaveRequest request) {
        if (request == null) {
            throw new BusinessException("请求体不能为空");
        }
        if (!StringUtils.hasText(request.getCdDevice())) {
            throw new BusinessException("设备编码不能为空");
        }
        if (!StringUtils.hasText(request.getIdOrg())) {
            throw new BusinessException("所属机构不能为空");
        }
    }

    private AiOrg requireActiveOrg(String idOrg) {
        AiOrg org = aiOrgMapper.selectOne(new LambdaQueryWrapper<AiOrg>()
            .eq(AiOrg::getIdOrg, idOrg)
            .eq(AiOrg::getFgActive, "1")
            .last(databaseDialect.firstRows(1)));
        if (org == null) {
            throw new BusinessException("机构不存在");
        }
        return org;
    }

    private AiOrg findUniqueActiveOrgByCode(String cdOrg) {
        try {
            return aiOrgMapper.selectOne(new LambdaQueryWrapper<AiOrg>()
                .eq(AiOrg::getCdOrg, cdOrg)
                .eq(AiOrg::getFgActive, "1"));
        } catch (TooManyResultsException ex) {
            log.warn("duplicate active org code found during device register. cdOrg={}", cdOrg);
            throw new BusinessException("机构编码重复，请先在管理端清理重复机构: " + cdOrg);
        }
    }

    private void ensureUniqueCode(String cdDevice, String idOrg, String excludeIdDevice) {
        LambdaQueryWrapper<AiDevice> wrapper = new LambdaQueryWrapper<AiDevice>()
            .eq(AiDevice::getCdDevice, cdDevice.trim())
            .eq(AiDevice::getIdOrg, idOrg)
            .eq(AiDevice::getFgActive, "1");
        if (StringUtils.hasText(excludeIdDevice)) {
            wrapper.ne(AiDevice::getIdDevice, excludeIdDevice);
        }
        AiDevice existing = aiDeviceMapper.selectOne(wrapper.last(databaseDialect.firstRows(1)));
        if (existing != null) {
            throw new BusinessException("设备编码已存在");
        }
    }

    private AiDevice findDeviceByCodeAndOrg(String cdDevice, String idOrg, String activeFlag) {
        return aiDeviceMapper.selectOne(new LambdaQueryWrapper<AiDevice>()
            .eq(AiDevice::getCdDevice, cdDevice)
            .eq(AiDevice::getIdOrg, idOrg)
            .eq(AiDevice::getFgActive, activeFlag)
            .last(databaseDialect.firstRows(1)));
    }

    private boolean requiresExistingDeviceToken(AiDevice existing) {
        return StringUtils.hasText(existing.getDevicePublicKey())
            && StringUtils.hasText(existing.getDeviceToken());
    }

    private List<AiDeviceView> toViews(List<AiDevice> records) {
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, AiOrg> orgMap = aiOrgMapper.selectBatchIds(records.stream()
                .map(AiDevice::getIdOrg)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList()))
            .stream()
            .collect(Collectors.toMap(AiOrg::getIdOrg, Function.identity(), (left, right) -> left));
        Map<String, AiRegion> regionMap = aiRegionMapper.selectBatchIds(records.stream()
                .map(AiDevice::getIdRegion)
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList()))
            .stream()
            .collect(Collectors.toMap(AiRegion::getIdRegion, Function.identity(), (left, right) -> left));
        Map<String, Map<String, Object>> userIdentityMap = loadLatestUserIdentities(records);
        return records.stream()
            .map(item -> {
                AiDeviceView view = toView(item, orgMap.get(item.getIdOrg()), regionMap.get(item.getIdRegion()));
                Map<String, Object> identity = userIdentityMap.get(item.getIdDevice());
                if (identity != null) {
                    view.setNaUser(stringValue(mapValue(identity, "NAUSER", "nauser")));
                    view.setDoctorWorkNo(stringValue(mapValue(identity, "DOCTORWORKNO", "doctorworkno")));
                    view.setLastActiveTime(laterOf(
                        view.getDtLastHeartbeat(),
                        localDateTimeValue(mapValue(identity, "CONSULTATIONTIME", "consultationtime"))
                    ));
                }
                return view;
            })
            .collect(Collectors.toList());
    }

    private Map<String, Map<String, Object>> loadLatestUserIdentities(List<AiDevice> records) {
        List<String> deviceIds = records.stream()
            .map(AiDevice::getIdDevice)
            .filter(StringUtils::hasText)
            .distinct()
            .collect(Collectors.toList());
        if (deviceIds.isEmpty()) {
            return Collections.emptyMap();
        }

        List<Map<String, Object>> rows = userConsultationLogMapper.selectLatestUserIdentities(deviceIds);
        if (rows == null || rows.isEmpty()) {
            return Collections.emptyMap();
        }

        return rows.stream()
            .filter(row -> mapValue(row, "IDDEVICE", "iddevice") != null)
            .collect(Collectors.toMap(
                row -> String.valueOf(mapValue(row, "IDDEVICE", "iddevice")),
                Function.identity(),
                (left, right) -> left
            ));
    }

    private AiDeviceView toView(AiDevice device, AiOrg org, AiRegion region) {
        AiDeviceView view = new AiDeviceView();
        view.setIdDevice(device.getIdDevice());
        view.setCdDevice(device.getCdDevice());
        view.setNaDevice(device.getNaDevice());
        view.setIdOrg(device.getIdOrg());
        view.setNaOrg(org == null ? null : org.getNaOrg());
        view.setIdRegion(device.getIdRegion());
        view.setNaRegion(region == null ? null : region.getNaRegion());
        view.setDeviceTokenMasked(MaskingUtils.maskSecret(device.getDeviceToken()));
        view.setSdStatus(device.getSdStatus());
        view.setClientVersion(device.getClientVersion());
        view.setOsInfo(device.getOsInfo());
        view.setRegisterIp(device.getRegisterIp());
        view.setLastSeenIp(device.getLastSeenIp());
        view.setDtLastHeartbeat(device.getDtLastHeartbeat());
        view.setDtRegistered(device.getDtRegistered());
        view.setLastActiveTime(device.getDtLastHeartbeat());
        return view;
    }

    private String normalizeIp(String clientIp) {
        String ip = StringUtils.hasText(clientIp) ? clientIp.trim() : UNKNOWN_IP;
        return ip.length() > 64 ? ip.substring(0, 64) : ip;
    }

    private Object mapValue(Map<String, Object> row, String upperKey, String lowerKey) {
        Object value = row.get(upperKey);
        return value != null ? value : row.get(lowerKey);
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private LocalDateTime localDateTimeValue(Object value) {
        if (value instanceof LocalDateTime) {
            return (LocalDateTime) value;
        }
        if (value instanceof Timestamp) {
            return ((Timestamp) value).toLocalDateTime();
        }
        return null;
    }

    private LocalDateTime laterOf(LocalDateTime first, LocalDateTime second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isAfter(second) ? first : second;
    }

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }
}
