package com.regionalai.floatingball.server.modules.device.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.exception.UpdateRequiredException;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceTest {

    @Mock
    private AiDeviceMapper aiDeviceMapper;

    @Mock
    private AiOrgMapper aiOrgMapper;

    @Mock
    private AiRegionMapper aiRegionMapper;

    @Mock
    private AiUserConsultationLogMapper userConsultationLogMapper;

    @Mock
    private ReleaseService releaseService;

    private DeviceService deviceService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceService(
            aiDeviceMapper,
            aiOrgMapper,
            aiRegionMapper,
            userConsultationLogMapper,
            releaseService,
            oracleDialect()
        );
    }

    private DatabaseDialect oracleDialect() {
        return new DatabaseDialect(DatabaseDialect.Kind.ORACLE);
    }

    @Test
    void listShouldAttachLatestDoctorIdentityAndResolveLastActiveTime() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setCdDevice("DEVICE-CODE");
        device.setIdOrg("ORG001");
        device.setIdRegion("REG001");
        device.setFgActive("1");
        device.setDtLastHeartbeat(LocalDateTime.of(2026, 8, 6, 8, 30));

        Page<AiDevice> page = new Page<AiDevice>(1, 10, 1);
        page.setRecords(Collections.singletonList(device));
        when(aiDeviceMapper.selectPage(any(Page.class), any())).thenReturn(page);

        AiOrg org = buildOrg("ORG001", "REG001");
        org.setNaOrg("默认机构");
        when(aiOrgMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(org));

        AiRegion region = new AiRegion();
        region.setIdRegion("REG001");
        region.setNaRegion("默认区域");
        when(aiRegionMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(region));

        Map<String, Object> userRow = new LinkedHashMap<String, Object>();
        userRow.put("IDDEVICE", "DEV001");
        userRow.put("NAUSER", "张医生");
        userRow.put("DOCTORWORKNO", "0123");
        userRow.put("CONSULTATIONTIME", LocalDateTime.of(2026, 8, 6, 9, 15));
        when(userConsultationLogMapper.selectLatestUserIdentities(Collections.singletonList("DEV001")))
            .thenReturn(Collections.singletonList(userRow));

        PageResponse<AiDeviceView> result = deviceService.list(1, 10, null);

        assertEquals(1, result.getRecords().size());
        assertEquals("张医生", result.getRecords().get(0).getNaUser());
        assertEquals("0123", result.getRecords().get(0).getDoctorWorkNo());
        assertEquals(LocalDateTime.of(2026, 8, 6, 9, 15), result.getRecords().get(0).getLastActiveTime());
        assertEquals("默认机构", result.getRecords().get(0).getNaOrg());
        assertEquals("默认区域", result.getRecords().get(0).getNaRegion());
    }

    @Test
    void registerShouldFillPublicKeyForLegacyDeviceWithoutPublicKey() {
        AiOrg org = buildOrg("ORG001", "REG001");
        AiDevice existing = new AiDevice();
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setDeviceToken(null);

        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(existing);

        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdOrg("ORG-CODE");
        request.setCdDevice("DEV-CODE");
        request.setNaDevice("诊室设备");
        request.setClientVersion("1.0.0");
        request.setOsInfo("Windows 11");
        request.setPublicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest-public-key-base64");

        RegisterDeviceResponse response = deviceService.register(request, "10.10.1.8");

        assertEquals("DEV001", response.getIdDevice());
        assertEquals(30, response.getHeartbeatInterval().intValue());
        assertFalse(response.getDeviceToken().isEmpty());
        assertEquals(32, response.getDeviceToken().length());
        assertTrue(response.getHasPublicKey());
        assertEquals("诊室设备", existing.getNaDevice());
        assertEquals("1.0.0", existing.getClientVersion());
        assertEquals("Windows 11", existing.getOsInfo());
        assertEquals("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest-public-key-base64", existing.getDevicePublicKey());
        assertEquals("10.10.1.8", existing.getRegisterIp());
        assertEquals("10.10.1.8", existing.getLastSeenIp());
        verify(aiDeviceMapper, times(1)).updateById(existing);
    }

    @Test
    void registerShouldRefreshPublicKeyForExistingActiveDevice() {
        AiOrg org = buildOrg("ORG001", "REG001");
        AiDevice existing = new AiDevice();
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setDeviceToken("existing-token");
        existing.setDevicePublicKey("existing-public-key");
        existing.setRegisterIp("10.0.0.1");

        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(existing);

        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdOrg("ORG-CODE");
        request.setCdDevice("DEV-CODE");
        request.setNaDevice("升级后终端");
        request.setClientVersion("1.0.1");
        request.setOsInfo("Windows 11");
        request.setPublicKey("refreshed-public-key");

        RegisterDeviceResponse response = deviceService.register(request, "10.0.0.2", "existing-token");

        assertEquals("DEV001", response.getIdDevice());
        assertEquals("existing-token", response.getDeviceToken());
        assertEquals(30, response.getHeartbeatInterval().intValue());
        assertTrue(response.getHasPublicKey());
        assertEquals("existing-token", existing.getDeviceToken());
        assertEquals("refreshed-public-key", existing.getDevicePublicKey());
        assertEquals("升级后终端", existing.getNaDevice());
        assertEquals("1.0.1", existing.getClientVersion());
        assertEquals("Windows 11", existing.getOsInfo());
        assertEquals("10.0.0.1", existing.getRegisterIp());
        assertEquals("10.0.0.2", existing.getLastSeenIp());
        verify(aiDeviceMapper).updateById(existing);
        verify(aiDeviceMapper, never()).insert(any(AiDevice.class));
    }

    @Test
    void registerShouldRejectPublicKeyRefreshWithoutExistingTokenProof() {
        AiOrg org = buildOrg("ORG001", "REG001");
        AiDevice existing = new AiDevice();
        existing.setIdDevice("DEV001");
        existing.setFgActive("1");
        existing.setDeviceToken("existing-token");
        existing.setDevicePublicKey("existing-public-key");

        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(existing);

        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdOrg("ORG-CODE");
        request.setCdDevice("DEV-CODE");
        request.setNaDevice("未知终端");
        request.setClientVersion("1.0.1");
        request.setOsInfo("Windows 11");
        request.setPublicKey("refreshed-public-key");

        BusinessException ex = assertThrows(BusinessException.class, () -> deviceService.register(request, "10.0.0.2"));

        assertEquals("DEVICE-KEY-ROTATION-UNAUTHORIZED", ex.getCode());
        assertEquals("设备已注册，请先使用原设备令牌完成密钥轮换；如本机令牌丢失，请联系管理员重置设备", ex.getMessage());
        assertEquals("existing-public-key", existing.getDevicePublicKey());
        verify(aiDeviceMapper, never()).updateById(any(AiDevice.class));
        verify(aiDeviceMapper, never()).insert(any(AiDevice.class));
    }

    @Test
    void registerShouldRejectDisabledDeviceCode() {
        AiOrg org = buildOrg("ORG001", "REG001");
        AiDevice disabled = new AiDevice();
        disabled.setIdDevice("DEV-DISABLED");
        disabled.setCdDevice("DEV-CODE");
        disabled.setFgActive("0");
        disabled.setDeviceToken("disabled-token");
        disabled.setDevicePublicKey("old-public-key");

        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(null, disabled);

        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdOrg("ORG-CODE");
        request.setCdDevice("DEV-CODE");
        request.setNaDevice("旧版本终端");
        request.setClientVersion("1.0.0");
        request.setOsInfo("Windows 11");
        request.setPublicKey("new-public-key");

        BusinessException ex = assertThrows(BusinessException.class, () -> deviceService.register(request));

        assertEquals("DEVICE-DISABLED", ex.getCode());
        assertEquals("设备已被管理员停用，请联系管理员重新发放令牌后再连接", ex.getMessage());
        verify(aiDeviceMapper, never()).insert(any(AiDevice.class));
        verify(aiDeviceMapper, never()).updateById(any(AiDevice.class));
    }

    @Test
    void registerShouldCreateNewDeviceWhenResetDeleteRemovedPreviousRecord() {
        AiOrg org = buildOrg("ORG001", "REG001");
        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(null, null);
        when(aiDeviceMapper.insert(any(AiDevice.class))).thenAnswer(invocation -> {
            AiDevice inserted = invocation.getArgument(0);
            inserted.setIdDevice("DEV-NEW");
            return 1;
        });

        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdOrg("ORG-CODE");
        request.setCdDevice("DEV-CODE");
        request.setNaDevice("重置后终端");
        request.setClientVersion("1.2.13");
        request.setOsInfo("Windows 11");
        request.setPublicKey("new-public-key");

        RegisterDeviceResponse response = deviceService.register(request, "172.16.0.10");

        ArgumentCaptor<AiDevice> captor = ArgumentCaptor.forClass(AiDevice.class);
        verify(aiDeviceMapper).insert(captor.capture());
        AiDevice saved = captor.getValue();
        assertEquals("DEV-CODE", saved.getCdDevice());
        assertEquals("ORG001", saved.getIdOrg());
        assertEquals("REG001", saved.getIdRegion());
        assertEquals("0", saved.getSdStatus());
        assertEquals("1", saved.getFgActive());
        assertEquals("new-public-key", saved.getDevicePublicKey());
        assertEquals("172.16.0.10", saved.getRegisterIp());
        assertEquals("172.16.0.10", saved.getLastSeenIp());
        assertEquals("DEV-NEW", response.getIdDevice());
        assertEquals(saved.getDeviceToken(), response.getDeviceToken());
        assertTrue(response.getHasPublicKey());
        verify(aiDeviceMapper, never()).updateById(any(AiDevice.class));
    }

    @Test
    void registerShouldAllowAdminIssuedActivePlaceholderWhenDisabledHistoryExists() {
        AiOrg org = buildOrg("ORG001", "REG001");
        AiDevice activePlaceholder = new AiDevice();
        activePlaceholder.setIdDevice("DEV-NEW");
        activePlaceholder.setCdDevice("DEV-CODE");
        activePlaceholder.setFgActive("1");
        activePlaceholder.setDeviceToken("active-token");

        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(activePlaceholder);

        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdOrg("ORG-CODE");
        request.setCdDevice("DEV-CODE");
        request.setNaDevice("恢复终端");
        request.setClientVersion("1.2.13");
        request.setOsInfo("Windows 11");
        request.setPublicKey("restored-public-key");

        RegisterDeviceResponse response = deviceService.register(request, "172.16.0.9");

        assertEquals("DEV-NEW", response.getIdDevice());
        assertEquals("active-token", response.getDeviceToken());
        assertTrue(response.getHasPublicKey());
        assertEquals("restored-public-key", activePlaceholder.getDevicePublicKey());
        assertEquals("172.16.0.9", activePlaceholder.getRegisterIp());
        assertEquals("172.16.0.9", activePlaceholder.getLastSeenIp());
        verify(aiDeviceMapper).updateById(activePlaceholder);
        verify(aiDeviceMapper, never()).insert(any(AiDevice.class));
    }

    @Test
    void registerShouldRejectWhenClientVersionRequiresUpdate() {
        AiOrg org = buildOrg("ORG001", "REG001");
        ReleasePolicyView policy = new ReleasePolicyView();
        policy.setMinSupportedVersion("1.2.13");

        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(releaseService.isUpdateRequired(eq("production"), eq("1.2.12"))).thenReturn(true);
        when(releaseService.getRequiredPolicy("production")).thenReturn(policy);

        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdOrg("ORG-CODE");
        request.setCdDevice("DEV-CODE");
        request.setClientVersion("1.2.12");
        request.setUpdateChannel("production");
        request.setPublicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest-public-key");

        UpdateRequiredException ex = assertThrows(UpdateRequiredException.class, () -> deviceService.register(request));

        assertEquals("1.2.13", ex.getMinSupportedVersion());
        verify(aiDeviceMapper, never()).insert(any(AiDevice.class));
        verify(aiDeviceMapper, never()).updateById(any(AiDevice.class));
    }

    @Test
    void registerShouldTranslateDuplicateOrgCodeToBusinessError() {
        when(aiOrgMapper.selectOne(any())).thenThrow(new TooManyResultsException("found: 2"));

        RegisterDeviceRequest request = new RegisterDeviceRequest();
        request.setCdOrg("ORG-CODE");
        request.setCdDevice("DEV-CODE");
        request.setClientVersion("1.2.13");
        request.setPublicKey("MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtest-public-key");

        BusinessException ex = assertThrows(BusinessException.class, () -> deviceService.register(request));

        assertEquals("机构编码重复，请先在管理端清理重复机构: ORG-CODE", ex.getMessage());
        verify(aiDeviceMapper, never()).insert(any(AiDevice.class));
        verify(aiDeviceMapper, never()).updateById(any(AiDevice.class));
    }

    @Test
    void saveShouldPopulateOrgRegionAndMaskGeneratedToken() {
        AiOrg org = buildOrg("ORG001", "REG001");
        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(null);

        AiDeviceSaveRequest request = new AiDeviceSaveRequest();
        request.setCdDevice(" DEV-NEW ");
        request.setNaDevice("新终端");
        request.setIdOrg("ORG001");
        request.setClientVersion("2.0.0");
        request.setOsInfo("Windows 10");

        AiDeviceView view = deviceService.save(request);

        ArgumentCaptor<AiDevice> captor = ArgumentCaptor.forClass(AiDevice.class);
        verify(aiDeviceMapper).insert(captor.capture());
        AiDevice saved = captor.getValue();
        assertEquals("DEV-NEW", saved.getCdDevice());
        assertEquals("ORG001", saved.getIdOrg());
        assertEquals("REG001", saved.getIdRegion());
        assertEquals("0", saved.getSdStatus());
        assertEquals("1", saved.getFgActive());
        assertEquals(32, saved.getDeviceToken().length());
        assertEquals(MaskingUtils.maskSecret(saved.getDeviceToken()), view.getDeviceTokenMasked());
        assertEquals("ORG001", view.getIdOrg());
        assertEquals("REG001", view.getIdRegion());
    }

    @Test
    void saveShouldRejectDuplicateDeviceCodeWithinSameOrg() {
        AiOrg org = buildOrg("ORG001", "REG001");
        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(new AiDevice());

        AiDeviceSaveRequest request = new AiDeviceSaveRequest();
        request.setCdDevice("DEV-EXISTS");
        request.setNaDevice("重复终端");
        request.setIdOrg("ORG001");

        BusinessException ex = assertThrows(BusinessException.class, () -> deviceService.save(request));

        assertEquals("设备编码已存在", ex.getMessage());
        verify(aiDeviceMapper, never()).insert(any(AiDevice.class));
    }

    @Test
    void saveShouldTranslateDatabaseUniqueConflictToBusinessError() {
        AiOrg org = buildOrg("ORG001", "REG001");
        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiDeviceMapper.selectOne(any())).thenReturn(null);
        when(aiDeviceMapper.insert(any(AiDevice.class))).thenThrow(new DuplicateKeyException("uk_c_ai_device_code_org_active"));

        AiDeviceSaveRequest request = new AiDeviceSaveRequest();
        request.setCdDevice("DEV-NEW");
        request.setNaDevice("新终端");
        request.setIdOrg("ORG001");

        BusinessException ex = assertThrows(BusinessException.class, () -> deviceService.save(request));

        assertEquals("设备编码已存在", ex.getMessage());
    }

    @Test
    void heartbeatShouldMarkDeviceOnlineAndPersistLastHeartbeat() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setSdStatus("0");

        deviceService.heartbeat(device, "192.168.1.23", "1.3.8");

        assertEquals("1", device.getSdStatus());
        assertEquals("192.168.1.23", device.getLastSeenIp());
        assertEquals("1.3.8", device.getClientVersion());
        assertTrue(device.getDtLastHeartbeat() != null);
        verify(aiDeviceMapper).updateById(device);
    }

    @Test
    void heartbeatShouldKeepLastSeenIpWhenClientIpUnknown() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setSdStatus("0");
        device.setLastSeenIp("192.168.1.23");
        device.setClientVersion("1.3.7");

        deviceService.heartbeat(device, "unknown", "  ");

        assertEquals("1", device.getSdStatus());
        assertEquals("192.168.1.23", device.getLastSeenIp());
        assertEquals("1.3.7", device.getClientVersion());
        assertTrue(device.getDtLastHeartbeat() != null);
        verify(aiDeviceMapper).updateById(device);
    }

    @Test
    void updateShouldKeepExistingStatusWhenRequestStatusBlank() {
        AiDevice existing = new AiDevice();
        existing.setIdDevice("DEV001");
        existing.setCdDevice("OLD-CODE");
        existing.setIdOrg("ORG001");
        existing.setFgActive("1");
        existing.setSdStatus("1");
        existing.setDeviceToken("1234567890abcdef1234567890abcdef");

        AiOrg targetOrg = buildOrg("ORG002", "REG002");
        when(aiDeviceMapper.selectById("DEV001")).thenReturn(existing);
        when(aiOrgMapper.selectOne(any())).thenReturn(targetOrg);
        when(aiDeviceMapper.selectOne(any())).thenReturn(null);

        AiDeviceSaveRequest request = new AiDeviceSaveRequest();
        request.setCdDevice(" NEW-CODE ");
        request.setNaDevice("改名终端");
        request.setIdOrg("ORG002");
        request.setSdStatus("");
        request.setClientVersion("2.0.0");
        request.setOsInfo("macOS");

        AiDeviceView view = deviceService.update("DEV001", request);

        assertEquals("NEW-CODE", existing.getCdDevice());
        assertEquals("REG002", existing.getIdRegion());
        assertEquals("1", existing.getSdStatus());
        assertEquals("ORG002", view.getIdOrg());
        assertEquals("REG002", view.getIdRegion());
        verify(aiDeviceMapper).updateById(existing);
    }

    @Test
    void invalidateShouldSoftDeleteAndTakeDeviceOffline() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setFgActive("1");
        device.setSdStatus("1");
        when(aiDeviceMapper.selectById("DEV001")).thenReturn(device);

        deviceService.invalidate("DEV001");

        assertEquals("0", device.getFgActive());
        assertEquals("0", device.getSdStatus());
        verify(aiDeviceMapper).updateById(device);
    }

    @Test
    void deleteForResetShouldPhysicallyRemoveDeviceRecord() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setFgActive("1");
        device.setSdStatus("1");
        when(aiDeviceMapper.selectById("DEV001")).thenReturn(device);

        deviceService.deleteForReset("DEV001");

        verify(aiDeviceMapper).deleteById("DEV001");
        verify(aiDeviceMapper, never()).updateById(any(AiDevice.class));
    }

    @Test
    void deleteForResetShouldRejectMissingDevice() {
        when(aiDeviceMapper.selectById("MISSING")).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> deviceService.deleteForReset("MISSING"));

        assertEquals("设备不存在", ex.getMessage());
        verify(aiDeviceMapper, never()).deleteById(any(String.class));
    }

    private AiOrg buildOrg(String idOrg, String idRegion) {
        AiOrg org = new AiOrg();
        org.setIdOrg(idOrg);
        org.setIdRegion(idRegion);
        org.setFgActive("1");
        return org;
    }
}
