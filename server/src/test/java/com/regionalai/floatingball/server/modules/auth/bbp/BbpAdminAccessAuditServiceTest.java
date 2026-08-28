package com.regionalai.floatingball.server.modules.auth.bbp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class BbpAdminAccessAuditServiceTest {

    @Mock
    private AiOpLogMapper opLogMapper;

    @Test
    void shouldWriteStructuredAdministratorGrantAudit() {
        BbpAdminAccessAuditService service = new BbpAdminAccessAuditService(opLogMapper, new ObjectMapper());
        AdminCurrentUser operator = new AdminCurrentUser();
        operator.setIdUser("ADMIN-1");
        operator.setNaUser("系统管理员");
        BbpAdminGrant grant = new BbpAdminGrant();
        grant.setTenantId("TENANT-A");
        grant.setOrgId("ORG-A");
        grant.setOrgName("第一医院");
        grant.setBbpUserId("USER-1");
        grant.setPersonId("PERSON-1");
        grant.setPersonName("张三");
        grant.setRoleCode("ORG_ADMIN");

        service.record(operator, grant, true);

        ArgumentCaptor<AiOpLog> captor = ArgumentCaptor.forClass(AiOpLog.class);
        verify(opLogMapper).insert(captor.capture());
        AiOpLog log = captor.getValue();
        assertEquals("bbp-admin-access", log.getSceneCode());
        assertEquals("grant", log.getOpAction());
        assertTrue(log.getPayloadJson().contains("\"bbpUserId\":\"USER-1\""));
        assertTrue(log.getPayloadJson().contains("\"roleCode\":\"ORG_ADMIN\""));
    }

    @Test
    void shouldNameOrganizationAnalystInAuditTitle() {
        BbpAdminAccessAuditService service = new BbpAdminAccessAuditService(opLogMapper, new ObjectMapper());
        AdminCurrentUser operator = new AdminCurrentUser();
        operator.setIdUser("ADMIN-1");
        BbpAdminGrant grant = new BbpAdminGrant();
        grant.setOrgId("ORG-A");
        grant.setBbpUserId("USER-1");
        grant.setPersonName("张三");
        grant.setRoleCode("ORG_ANALYST");

        service.record(operator, grant, true);

        ArgumentCaptor<AiOpLog> captor = ArgumentCaptor.forClass(AiOpLog.class);
        verify(opLogMapper).insert(captor.capture());
        assertEquals("授予 PCIE 机构统计员权限：张三", captor.getValue().getOpTitle());
    }
}
