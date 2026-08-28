package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.dto.BbpAdminAccessUpdateRequest;
import com.regionalai.floatingball.server.modules.auth.dto.BbpAdminAccessView;
import com.regionalai.floatingball.server.modules.auth.dto.BbpRoleView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BbpAdminAccessServiceTest {

    @Mock
    private BbpAdminGrantMapper grantMapper;

    @Mock
    private BbpAdminGrantSchemaService schemaService;

    @Mock
    private BbpDirectoryService directoryService;

    @Mock
    private BbpAdminAccessAuditService auditService;

    private BbpAdminAccessService service;
    private AdminCurrentUser systemAdmin;

    @BeforeEach
    void setUp() {
        service = new BbpAdminAccessService(grantMapper, schemaService, directoryService,
            new BbpAdminScope(), auditService);
        systemAdmin = new AdminCurrentUser();
        systemAdmin.setIdUser("ADMIN-1");
        systemAdmin.setNaUser("系统管理员");
        systemAdmin.setAuthProvider("BBP");
        systemAdmin.setBbpTenantId("TENANT-A");
        systemAdmin.setRoles(Collections.singletonList("SYSTEM_ADMIN"));
    }

    @Test
    void listShouldMergeDirectoryAndSurfaceOrphanedGrantRisk() {
        BbpPersonView active = person("USER-1", "PERSON-1", "张三", true);
        BbpPersonView inactive = person("USER-2", "PERSON-2", "李四", false);
        BbpAdminGrant activeGrant = grant("GRANT-1", "USER-1", "张三", "1");
        BbpAdminGrant orphanedGrant = grant("GRANT-3", "USER-3", "已移出人员", "1");
        when(directoryService.persons(systemAdmin, "ORG-A")).thenReturn(Arrays.asList(active, inactive));
        when(grantMapper.selectList(any())).thenReturn(Arrays.asList(activeGrant, orphanedGrant));

        List<BbpAdminAccessView> result = service.list(systemAdmin, "ORG-A", "ORG_ADMIN");

        assertEquals(3, result.size());
        assertEquals("USER-3", result.get(0).getBbpUserId());
        assertTrue(result.get(0).isGrantRisk());
        BbpAdminAccessView activeView = result.stream()
            .filter(item -> "USER-1".equals(item.getBbpUserId())).findFirst().orElseThrow(AssertionError::new);
        assertTrue(activeView.isAdminAccessEnabled());
        assertFalse(activeView.isGrantRisk());
        BbpAdminAccessView inactiveView = result.stream()
            .filter(item -> "USER-2".equals(item.getBbpUserId())).findFirst().orElseThrow(AssertionError::new);
        assertFalse(inactiveView.isAdminAccessEnabled());
    }

    @Test
    void grantShouldValidateBbpPersonAndWriteAudit() {
        BbpPersonView person = person("USER-1", "PERSON-1", "张三", true);
        when(directoryService.persons(systemAdmin, "ORG-A")).thenReturn(Collections.singletonList(person));
        when(grantMapper.selectOne(any())).thenReturn(null);
        BbpAdminAccessUpdateRequest request = request("ORG-A", true);

        BbpAdminAccessView result = service.update(systemAdmin, "USER-1", request);

        ArgumentCaptor<BbpAdminGrant> captor = ArgumentCaptor.forClass(BbpAdminGrant.class);
        verify(grantMapper).insert(captor.capture());
        BbpAdminGrant saved = captor.getValue();
        assertEquals("TENANT-A", saved.getTenantId());
        assertEquals("ORG-A", saved.getOrgId());
        assertEquals("USER-1", saved.getBbpUserId());
        assertEquals("ORG_ADMIN", saved.getRoleCode());
        assertEquals("1", saved.getSdStatus());
        verify(auditService).record(systemAdmin, saved, true);
        assertTrue(result.isAdminAccessEnabled());
    }

    @Test
    void grantShouldSupportOrganizationAnalystRole() {
        BbpPersonView person = person("USER-1", "PERSON-1", "张三", true);
        when(directoryService.persons(systemAdmin, "ORG-A")).thenReturn(Collections.singletonList(person));
        when(grantMapper.selectOne(any())).thenReturn(null);
        BbpAdminAccessUpdateRequest request = request("ORG-A", true);
        request.setRoleCode("ORG_ANALYST");

        service.update(systemAdmin, "USER-1", request);

        ArgumentCaptor<BbpAdminGrant> captor = ArgumentCaptor.forClass(BbpAdminGrant.class);
        verify(grantMapper).insert(captor.capture());
        assertEquals("ORG_ANALYST", captor.getValue().getRoleCode());
    }

    @Test
    void grantShouldFailClosedWhenBbpActiveStateIsUnknown() {
        BbpPersonView person = person("USER-1", "PERSON-1", "张三", true);
        person.setActive(null);
        when(directoryService.persons(systemAdmin, "ORG-A")).thenReturn(Collections.singletonList(person));
        when(grantMapper.selectOne(any())).thenReturn(null);

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.update(systemAdmin, "USER-1", request("ORG-A", true)));

        assertEquals("仅 BBP 明确处于启用状态的人员可以授予 PCIE 后台访问权限", exception.getMessage());
        verify(grantMapper, never()).insert(any());
    }

    @Test
    void revokeShouldAllowPersonMissingFromCurrentDirectory() {
        BbpAdminGrant existing = grant("GRANT-1", "USER-1", "张三", "1");
        when(grantMapper.selectOne(any())).thenReturn(existing);

        BbpAdminAccessView result = service.update(systemAdmin, "USER-1", request("ORG-A", false));

        verify(directoryService, never()).persons(any(), any());
        verify(grantMapper).updateById(existing);
        verify(auditService).record(systemAdmin, existing, false);
        assertEquals("0", existing.getSdStatus());
        assertFalse(result.isAdminAccessEnabled());
    }

    @Test
    void nonSystemAdminShouldNotDelegatePeerAdministrator() {
        AdminCurrentUser orgAdmin = new AdminCurrentUser();
        orgAdmin.setAuthProvider("BBP");
        orgAdmin.setBbpTenantId("TENANT-A");
        orgAdmin.setBbpOrgId("ORG-A");
        orgAdmin.setRoles(Collections.singletonList("ORG_ADMIN"));

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.update(orgAdmin, "USER-1", request("ORG-A", true)));

        assertEquals("仅系统管理员可以维护 PCIE 后台访问权限", exception.getMessage());
    }

    @Test
    void loginLookupShouldRequireExactTenantOrganizationAndStableUserId() {
        when(schemaService.isReady()).thenReturn(true);
        BbpAdminGrant grant = grant("GRANT-1", "USER-1", "张三", "1");
        when(grantMapper.selectList(any())).thenReturn(Collections.singletonList(grant));
        BbpRoleView role = new BbpRoleView();
        role.setTenantId("TENANT-A");
        role.setOrgId("ORG-A");
        role.setUserId("USER-1");

        assertEquals(Collections.singletonList("ORG_ADMIN"), service.findActiveRoleCodes(role));
    }

    private BbpAdminAccessUpdateRequest request(String orgId, boolean enabled) {
        BbpAdminAccessUpdateRequest request = new BbpAdminAccessUpdateRequest();
        request.setOrgId(orgId);
        request.setEnabled(enabled);
        return request;
    }

    private BbpPersonView person(String userId, String personId, String name, boolean active) {
        BbpPersonView person = new BbpPersonView();
        person.setUserId(userId);
        person.setPersonId(personId);
        person.setName(name);
        person.setCode("D-" + userId);
        person.setOrgId("ORG-A");
        person.setOrgName("第一医院");
        person.setActive(active);
        return person;
    }

    private BbpAdminGrant grant(String id, String userId, String name, String status) {
        BbpAdminGrant grant = new BbpAdminGrant();
        grant.setIdGrant(id);
        grant.setTenantId("TENANT-A");
        grant.setOrgId("ORG-A");
        grant.setBbpUserId(userId);
        grant.setPersonName(name);
        grant.setRoleCode("ORG_ADMIN");
        grant.setSdStatus(status);
        grant.setFgActive("1");
        return grant;
    }
}
