package com.regionalai.floatingball.server.modules.userpermission.service;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpAdminScope;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpDirectoryService;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpPersonView;
import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.config.AdminSecurityProperties;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionListView;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionBatchUpdateRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionBatchUpdateResponse;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionOrgRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionRecordView;
import com.regionalai.floatingball.server.modules.userpermission.dto.AiUserPermissionUpdateRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.PhisAiPermissionCheckRequest;
import com.regionalai.floatingball.server.modules.userpermission.dto.PhisAiPermissionCheckResponse;
import com.regionalai.floatingball.server.modules.userpermission.entity.AiUserAiPermission;
import com.regionalai.floatingball.server.modules.userpermission.mapper.AiUserAiPermissionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUserPermissionServiceTest {

    @Mock
    private AiUserAiPermissionMapper permissionMapper;

    @Mock
    private BbpDirectoryService directoryService;

    @Mock
    private AiUserPermissionAuditService auditService;

    @Mock
    private AiUserPermissionSchemaService schemaService;

    private BbpAdminScope adminScope;
    private AiUserPermissionService service;
    private AdminCurrentUser systemAdmin;

    @BeforeEach
    void setUp() {
        AdminSecurityProperties properties = new AdminSecurityProperties();
        properties.getAuth().setMode("bbp");
        properties.getAuth().getBbp().setBaseUrl("https://phis.example/phis");
        properties.getAuth().getBbp().setTenantId("TENANT-1");
        adminScope = new BbpAdminScope();
        service = new AiUserPermissionService(permissionMapper, directoryService, adminScope,
            new AdminAuthMode(properties), auditService, schemaService);

        systemAdmin = new AdminCurrentUser();
        systemAdmin.setIdUser("ADMIN-1");
        systemAdmin.setCdUser("admin");
        systemAdmin.setNaUser("系统管理员");
        systemAdmin.setAuthProvider("BBP");
        systemAdmin.setBbpTenantId("TENANT-1");
        systemAdmin.setRoles(Collections.singletonList("SYSTEM_ADMIN"));
    }

    @Test
    void shouldMergeBbpPersonsWithPciePermissionState() {
        BbpPersonView person = person();
        AiUserAiPermission permission = permission("1");
        when(directoryService.persons(systemAdmin, "ORG-1")).thenReturn(Collections.singletonList(person));
        when(permissionMapper.selectList(any())).thenReturn(Collections.singletonList(permission));

        AiUserPermissionListView result = service.list(systemAdmin, "ORG-1", null, null,
            null, null, 1, 20);

        assertEquals(1, result.getTotal());
        assertEquals(1, result.getConfiguredCount());
        assertEquals(0, result.getUnconfiguredCount());
        assertTrue(result.isManageable());
        assertTrue(result.getRecords().get(0).isConfigured());
        assertEquals("D001", result.getRecords().get(0).getPersonCd());
    }

    @Test
    void shouldPersistPermissionWithoutChangingBbpPerson() {
        when(directoryService.persons(systemAdmin, "ORG-1")).thenReturn(Collections.singletonList(person()));
        when(permissionMapper.selectList(any())).thenReturn(Collections.emptyList());
        AiUserPermissionUpdateRequest request = new AiUserPermissionUpdateRequest();
        request.setOrgId("ORG-1");
        request.setEnabled(true);

        AiUserPermissionRecordView result = service.update(systemAdmin, "PERSON-1", request);

        assertTrue(result.isConfigured());
        verify(permissionMapper).insert(any(AiUserAiPermission.class));
        verify(auditService).record(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void phisCheckShouldDefaultDenyAndAllowOnlyEnabledPermission() {
        PhisAiPermissionCheckRequest request = checkRequest();
        when(permissionMapper.selectList(any())).thenReturn(Collections.emptyList());

        PhisAiPermissionCheckResponse denied = service.check(request);
        assertFalse(denied.isAllowed());
        assertEquals("NOT_AUTHORIZED", denied.getReason());

        when(permissionMapper.selectList(any())).thenReturn(Collections.singletonList(permission("1")));
        PhisAiPermissionCheckResponse allowed = service.check(request);
        assertTrue(allowed.isAllowed());
        assertEquals("AUTHORIZED", allowed.getReason());
    }

    @Test
    void organizationAdministratorShouldNotCrossOrganizationBoundary() {
        AdminCurrentUser orgAdmin = new AdminCurrentUser();
        orgAdmin.setAuthProvider("BBP");
        orgAdmin.setBbpTenantId("TENANT-1");
        orgAdmin.setBbpOrgId("ORG-1");
        orgAdmin.setRoles(Collections.singletonList("ORG_ADMIN"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.list(orgAdmin, "ORG-2", null, null, null, null, 1, 20));

        assertEquals("当前账号无权访问其他机构人员", exception.getMessage());
    }

    @Test
    void ordinaryOrganizationUserShouldOnlyViewPermissions() {
        AdminCurrentUser viewer = organizationUser("DOCTOR");
        when(directoryService.persons(viewer, "ORG-1")).thenReturn(Collections.singletonList(person()));
        when(permissionMapper.selectList(any())).thenReturn(Collections.emptyList());

        AiUserPermissionListView list = service.list(viewer, "ORG-1", null, null,
            null, null, 1, 20);
        assertFalse(list.isManageable());

        AiUserPermissionUpdateRequest request = new AiUserPermissionUpdateRequest();
        request.setOrgId("ORG-1");
        request.setEnabled(true);
        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.update(viewer, "PERSON-1", request));

        assertEquals("当前账号仅可查看本机构 AI 使用权限，无权授予或撤销", exception.getMessage());
        verify(permissionMapper, never()).insert(any(AiUserAiPermission.class));
    }

    @Test
    void organizationAdministratorShouldManageOwnOrganization() {
        AdminCurrentUser orgAdmin = organizationUser("ORG_ADMIN");
        when(directoryService.persons(orgAdmin, "ORG-1")).thenReturn(Collections.singletonList(person()));
        when(permissionMapper.selectList(any())).thenReturn(Collections.emptyList());
        AiUserPermissionUpdateRequest request = new AiUserPermissionUpdateRequest();
        request.setOrgId("ORG-1");
        request.setEnabled(true);

        AiUserPermissionRecordView result = service.update(orgAdmin, "PERSON-1", request);

        assertTrue(result.isConfigured());
        verify(permissionMapper).insert(any(AiUserAiPermission.class));
    }

    @Test
    void batchUpdateShouldDeduplicateAndWriteAllValidatedPersons() {
        BbpPersonView first = person();
        BbpPersonView second = person("PERSON-2", true);
        when(directoryService.persons(systemAdmin, "ORG-1")).thenReturn(Arrays.asList(first, second));
        when(permissionMapper.selectList(any())).thenReturn(Collections.emptyList());
        AiUserPermissionBatchUpdateRequest request = batchRequest(true, "PERSON-1", "PERSON-2", "PERSON-1");

        AiUserPermissionBatchUpdateResponse result = service.batchUpdate(systemAdmin, request);

        assertEquals(2, result.getRequestedCount());
        assertEquals(2, result.getChangedCount());
        assertEquals(0, result.getUnchangedCount());
        verify(permissionMapper, times(2)).insert(any(AiUserAiPermission.class));
        verify(auditService, times(2)).record(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void listShouldFilterAndPageWithDepartmentOptions() {
        BbpPersonView first = person();
        BbpPersonView second = person("PERSON-2", true);
        second.setDepartmentId("DEPT-2");
        second.setDepartmentName("外科");
        when(directoryService.persons(systemAdmin, "ORG-1")).thenReturn(Arrays.asList(first, second));
        when(permissionMapper.selectList(any())).thenReturn(Collections.emptyList());

        AiUserPermissionListView result = service.list(systemAdmin, "ORG-1", null, false,
            "DEPT-2", true, 1, 1);

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getFilteredTotal());
        assertEquals(1, result.getRecords().size());
        assertEquals("PERSON-2", result.getRecords().get(0).getPersonId());
        assertEquals(2, result.getDepartments().size());
        assertEquals(1, result.getCurrent());
        assertEquals(1, result.getSize());
    }

    @Test
    void listShouldCountInactiveUnknownAndMissingDirectoryPermissionsAsRisk() {
        BbpPersonView inactive = person();
        inactive.setActive(false);
        BbpPersonView unknown = person("PERSON-2", true);
        unknown.setActive(null);
        when(directoryService.persons(systemAdmin, "ORG-1")).thenReturn(Arrays.asList(inactive, unknown));
        when(permissionMapper.selectList(any())).thenReturn(Arrays.asList(
            permission("PERSON-1", "1"), permission("PERSON-2", "1"), permission("PERSON-MISSING", "1")));

        AiUserPermissionListView result = service.list(systemAdmin, "ORG-1", null, null,
            null, null, 1, 20);

        assertEquals(3, result.getRiskAuthorizedCount());
    }

    @Test
    void revokeRiskShouldDisableInactiveUnknownAndMissingDirectoryPermissions() {
        BbpPersonView active = person();
        BbpPersonView inactive = person("PERSON-2", false);
        BbpPersonView unknown = person("PERSON-3", true);
        unknown.setActive(null);
        when(directoryService.persons(systemAdmin, "ORG-1")).thenReturn(Arrays.asList(active, inactive, unknown));
        when(permissionMapper.selectList(any())).thenReturn(Arrays.asList(
            permission("PERSON-1", "1"),
            permission("PERSON-2", "1"),
            permission("PERSON-3", "1"),
            permission("PERSON-MISSING", "1")));
        AiUserPermissionOrgRequest request = new AiUserPermissionOrgRequest();
        request.setOrgId("ORG-1");

        AiUserPermissionBatchUpdateResponse result = service.revokeRiskPermissions(systemAdmin, request);

        assertEquals(3, result.getRequestedCount());
        assertEquals(3, result.getChangedCount());
        verify(permissionMapper, times(3)).updateById(any(AiUserAiPermission.class));
        verify(auditService, times(3)).record(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void grantShouldRejectPersonWhoseBbpActiveStateIsUnknown() {
        BbpPersonView unknown = person();
        unknown.setActive(null);
        when(directoryService.persons(systemAdmin, "ORG-1")).thenReturn(Collections.singletonList(unknown));
        AiUserPermissionUpdateRequest request = new AiUserPermissionUpdateRequest();
        request.setOrgId("ORG-1");
        request.setEnabled(true);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.update(systemAdmin, "PERSON-1", request));

        assertTrue(exception.getMessage().contains("未明确处于启用状态"));
        verify(permissionMapper, never()).insert(any(AiUserAiPermission.class));
        verify(auditService, never()).record(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void batchGrantShouldValidateEveryPersonBeforeWriting() {
        BbpPersonView inactive = person("PERSON-2", false);
        inactive.setName("停用人员");
        when(directoryService.persons(systemAdmin, "ORG-1")).thenReturn(Arrays.asList(person(), inactive));
        AiUserPermissionBatchUpdateRequest request = batchRequest(true, "PERSON-1", "PERSON-2");

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.batchUpdate(systemAdmin, request));

        assertTrue(exception.getMessage().contains("整批未处理"));
        verify(permissionMapper, never()).insert(any(AiUserAiPermission.class));
        verify(auditService, never()).record(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void phisCheckShouldRequireAtLeastOnePersonIdentity() {
        PhisAiPermissionCheckRequest request = new PhisAiPermissionCheckRequest();
        request.setTenantId("TENANT-1");
        request.setOrgId("ORG-1");

        assertThrows(BusinessException.class, () -> service.check(request));
    }

    @Test
    void phisCheckShouldRejectPersonCodeWithoutStableIdentity() {
        PhisAiPermissionCheckRequest request = new PhisAiPermissionCheckRequest();
        request.setTenantId("TENANT-1");
        request.setOrgId("ORG-1");
        request.setPersonCd("D001");

        BusinessException exception = assertThrows(BusinessException.class, () -> service.check(request));

        assertTrue(exception.getMessage().contains("personCd 不能单独用于判权"));
        verify(permissionMapper, never()).selectList(any());
    }

    private BbpPersonView person() {
        return person("PERSON-1", true);
    }

    private BbpPersonView person(String personId, boolean active) {
        BbpPersonView person = new BbpPersonView();
        person.setId(personId);
        person.setPersonId(personId);
        person.setUserId("PERSON-1".equals(personId) ? "USER-1" : "USER-2");
        person.setCode("PERSON-1".equals(personId) ? "D001" : "D002");
        person.setName("张三");
        person.setOrgId("ORG-1");
        person.setOrgName("第一医院");
        person.setDepartmentId("DEPT-1");
        person.setDepartmentName("内科");
        person.setActive(active);
        return person;
    }

    private AdminCurrentUser organizationUser(String role) {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setIdUser("ORG-USER-1");
        user.setCdUser("org-user");
        user.setNaUser("机构用户");
        user.setAuthProvider("BBP");
        user.setBbpTenantId("TENANT-1");
        user.setBbpOrgId("ORG-1");
        user.setRoles(Collections.singletonList(role));
        return user;
    }

    private AiUserPermissionBatchUpdateRequest batchRequest(boolean enabled, String... personIds) {
        AiUserPermissionBatchUpdateRequest request = new AiUserPermissionBatchUpdateRequest();
        request.setOrgId("ORG-1");
        request.setEnabled(enabled);
        request.setPersonIds(Arrays.asList(personIds));
        return request;
    }

    private AiUserAiPermission permission(String status) {
        return permission("PERSON-1", status);
    }

    private AiUserAiPermission permission(String personId, String status) {
        AiUserAiPermission permission = new AiUserAiPermission();
        permission.setIdPermission("PERM-" + personId);
        permission.setTenantId("TENANT-1");
        permission.setOrgId("ORG-1");
        permission.setPersonId(personId);
        permission.setUserId("USER-" + personId);
        permission.setPersonCd("CD-" + personId);
        permission.setPersonName("人员-" + personId);
        permission.setSubjectKey("P:" + personId);
        permission.setSdStatus(status);
        permission.setFgActive("1");
        return permission;
    }

    private PhisAiPermissionCheckRequest checkRequest() {
        PhisAiPermissionCheckRequest request = new PhisAiPermissionCheckRequest();
        request.setTenantId("TENANT-1");
        request.setOrgId("ORG-1");
        request.setPersonId("PERSON-1");
        request.setPersonCd("D001");
        return request;
    }
}
