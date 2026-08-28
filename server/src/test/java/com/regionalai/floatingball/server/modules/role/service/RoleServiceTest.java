package com.regionalai.floatingball.server.modules.role.service;

import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.modules.role.dto.AdminRoleSaveRequest;
import com.regionalai.floatingball.server.modules.role.entity.AiRole;
import com.regionalai.floatingball.server.modules.role.mapper.AiRoleMapper;
import com.regionalai.floatingball.server.modules.user.entity.AiUserRole;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RoleServiceTest {

    @Mock
    private AiRoleMapper aiRoleMapper;

    @Mock
    private AiUserRoleMapper aiUserRoleMapper;

    private RoleService roleService;

    @BeforeEach
    void setUp() {
        roleService = new RoleService(
            aiRoleMapper,
            aiUserRoleMapper,
            new DatabaseDialect(DatabaseDialect.Kind.ORACLE)
        );
    }

    @Test
    void saveShouldRejectBlankRoleCode() {
        AdminRoleSaveRequest request = new AdminRoleSaveRequest();
        request.setCdRole(" ");
        request.setNaRole("系统管理员");

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.save(request));

        assertEquals("角色编码不能为空", ex.getMessage());
        verify(aiRoleMapper, never()).insert(any(AiRole.class));
    }

    @Test
    void saveShouldTranslateDatabaseUniqueConflictToBusinessError() {
        when(aiRoleMapper.selectOne(any())).thenReturn(null);
        when(aiRoleMapper.insert(any(AiRole.class))).thenThrow(new DuplicateKeyException("uk_c_ai_role_code_active"));

        AdminRoleSaveRequest request = new AdminRoleSaveRequest();
        request.setCdRole("ADMIN");
        request.setNaRole("系统管理员");

        BusinessException ex = assertThrows(BusinessException.class, () -> roleService.save(request));

        assertEquals("角色编码已存在", ex.getMessage());
    }

    @Test
    void invalidateShouldDisableRoleAndMappings() {
        AiRole role = new AiRole();
        role.setIdRole("ROLE001");
        role.setFgActive("1");
        role.setSdStatus("1");

        AiUserRole mapping = new AiUserRole();
        mapping.setIdUserRole("UR001");
        mapping.setIdRole("ROLE001");
        mapping.setFgActive("1");

        when(aiRoleMapper.selectById("ROLE001")).thenReturn(role);
        when(aiUserRoleMapper.selectList(any())).thenReturn(Collections.singletonList(mapping));
        roleService.invalidate("ROLE001");

        assertEquals("0", role.getFgActive());
        assertEquals("0", role.getSdStatus());
        assertEquals("0", mapping.getFgActive());
        verify(aiRoleMapper).updateById(role);
        verify(aiUserRoleMapper).updateById(mapping);
    }
}
