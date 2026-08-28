package com.regionalai.floatingball.server.modules.userpermission.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.audit.entity.AiOpLog;
import com.regionalai.floatingball.server.modules.audit.mapper.AiOpLogMapper;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpPersonView;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUserPermissionAuditServiceTest {

    @Mock
    private AiOpLogMapper opLogMapper;

    @Test
    void shouldRecordPermissionOperatorAndTarget() {
        AiUserPermissionAuditService service = new AiUserPermissionAuditService(opLogMapper, new ObjectMapper());
        service.record(user(), "TENANT-1", "ORG-1", person(), true);

        ArgumentCaptor<AiOpLog> captor = ArgumentCaptor.forClass(AiOpLog.class);
        verify(opLogMapper).insert(captor.capture());
        AiOpLog log = captor.getValue();
        assertEquals("ai-user-permission", log.getNaModule());
        assertEquals("grant", log.getOpAction());
        assertEquals("ORG-1", log.getIdOrg());
        assertTrue(log.getPayloadJson().contains("\"operatorUserId\":\"ADMIN-1\""));
        assertTrue(log.getPayloadJson().contains("\"personId\":\"PERSON-1\""));
    }

    @Test
    void shouldFailPermissionChangeWhenAuditCannotBePersisted() {
        when(opLogMapper.insert(any())).thenThrow(new IllegalStateException("database unavailable"));
        AiUserPermissionAuditService service = new AiUserPermissionAuditService(opLogMapper, new ObjectMapper());

        BusinessException exception = assertThrows(BusinessException.class,
            () -> service.record(user(), "TENANT-1", "ORG-1", person(), false));

        assertEquals("AI 权限操作日志写入失败，请稍后重试", exception.getMessage());
    }

    private AdminCurrentUser user() {
        AdminCurrentUser user = new AdminCurrentUser();
        user.setIdUser("ADMIN-1");
        user.setCdUser("admin");
        user.setNaUser("系统管理员");
        return user;
    }

    private BbpPersonView person() {
        BbpPersonView person = new BbpPersonView();
        person.setId("PERSON-1");
        person.setPersonId("PERSON-1");
        person.setUserId("USER-1");
        person.setCode("D001");
        person.setName("张三");
        person.setOrgId("ORG-1");
        person.setOrgName("第一医院");
        return person;
    }
}
