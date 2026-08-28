package com.regionalai.floatingball.server.modules.user.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.common.db.DatabaseDialect;
import com.regionalai.floatingball.server.common.exception.BusinessException;
import com.regionalai.floatingball.server.common.util.PasswordUtils;
import com.regionalai.floatingball.server.modules.org.entity.AiOrg;
import com.regionalai.floatingball.server.modules.org.mapper.AiOrgMapper;
import com.regionalai.floatingball.server.modules.role.entity.AiRole;
import com.regionalai.floatingball.server.modules.role.mapper.AiRoleMapper;
import com.regionalai.floatingball.server.modules.user.dto.AdminUserSaveRequest;
import com.regionalai.floatingball.server.modules.user.dto.AdminUserView;
import com.regionalai.floatingball.server.modules.user.entity.AiUser;
import com.regionalai.floatingball.server.modules.user.entity.AiUserRole;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserMapper;
import com.regionalai.floatingball.server.modules.user.mapper.AiUserRoleMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock
    private AiUserMapper aiUserMapper;

    @Mock
    private AiUserRoleMapper aiUserRoleMapper;

    @Mock
    private AiRoleMapper aiRoleMapper;

    @Mock
    private AiOrgMapper aiOrgMapper;

    private UserService userService;

    @BeforeEach
    void setUp() {
        userService = new UserService(
            aiUserMapper,
            aiUserRoleMapper,
            aiRoleMapper,
            aiOrgMapper,
            new DatabaseDialect(DatabaseDialect.Kind.ORACLE)
        );
    }

    @Test
    void saveShouldHashPasswordPersistOrgAndInsertRoleMappings() {
        AiOrg org = activeOrg("ORG001", "默认机构");
        AiRole role = activeRole("ROLE001", "系统管理员");

        when(aiOrgMapper.selectOne(any())).thenReturn(org);
        when(aiUserMapper.selectOne(any())).thenReturn(null);
        when(aiRoleMapper.selectBatchIds(any())).thenReturn(Collections.singletonList(role));
        doAnswer(invocation -> {
            AiUser user = invocation.getArgument(0);
            user.setIdUser("USER001");
            return 1;
        }).when(aiUserMapper).insert(any(AiUser.class));
        when(aiUserMapper.selectById("USER001")).thenAnswer(invocation -> {
            AiUser user = new AiUser();
            user.setIdUser("USER001");
            user.setCdUser("zhangsan");
            user.setNaUser("张三");
            user.setIdOrg("ORG001");
            user.setSdStatus("1");
            user.setPasswordHash(PasswordUtils.sha256("123456"));
            user.setFgActive("1");
            return user;
        });

        AdminUserView result = userService.save(saveRequest());

        ArgumentCaptor<AiUser> userCaptor = ArgumentCaptor.forClass(AiUser.class);
        verify(aiUserMapper).insert(userCaptor.capture());
        assertEquals(PasswordUtils.sha256("123456"), userCaptor.getValue().getPasswordHash());
        assertEquals("ORG001", userCaptor.getValue().getIdOrg());
        assertEquals("USER001", result.getIdUser());

        ArgumentCaptor<AiUserRole> mappingCaptor = ArgumentCaptor.forClass(AiUserRole.class);
        verify(aiUserRoleMapper).insert(mappingCaptor.capture());
        assertEquals("USER001", mappingCaptor.getValue().getIdUser());
        assertEquals("ROLE001", mappingCaptor.getValue().getIdRole());
    }

    @Test
    void saveShouldRejectMissingRoles() {
        AdminUserSaveRequest request = saveRequest();
        request.setRoleIds(Collections.<String>emptyList());

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.save(request));

        assertEquals("至少选择一个角色", ex.getMessage());
        verify(aiUserMapper, never()).insert(any(AiUser.class));
        verify(aiOrgMapper, never()).selectOne(any());
    }

    @Test
    void saveShouldTranslateDatabaseUniqueConflictToBusinessError() {
        when(aiOrgMapper.selectOne(any())).thenReturn(activeOrg("ORG001", "默认机构"));
        when(aiUserMapper.selectOne(any())).thenReturn(null);
        when(aiRoleMapper.selectBatchIds(any()))
            .thenReturn(Collections.singletonList(activeRole("ROLE001", "系统管理员")));
        when(aiUserMapper.insert(any(AiUser.class)))
            .thenThrow(new DuplicateKeyException("uk_c_ai_user_code_active"));

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.save(saveRequest()));

        assertEquals("登录账号已存在", ex.getMessage());
        verify(aiUserRoleMapper, never()).insert(any(AiUserRole.class));
    }

    @Test
    void listShouldReturnRoleNamesAndOrgName() {
        AiUser user = new AiUser();
        user.setIdUser("USER001");
        user.setCdUser("zhangsan");
        user.setNaUser("张三");
        user.setIdOrg("ORG001");
        user.setSdStatus("1");
        user.setFgActive("1");

        Page<AiUser> mapperPage = new Page<AiUser>(1, 10, 1);
        mapperPage.setRecords(Collections.singletonList(user));

        AiUserRole userRole = new AiUserRole();
        userRole.setIdUser("USER001");
        userRole.setIdRole("ROLE001");
        userRole.setFgActive("1");

        when(aiUserMapper.selectPage(any(Page.class), any())).thenReturn(mapperPage);
        when(aiOrgMapper.selectBatchIds(any()))
            .thenReturn(Collections.singletonList(activeOrg("ORG001", "默认机构")));
        when(aiUserRoleMapper.selectList(any())).thenReturn(Collections.singletonList(userRole));
        when(aiRoleMapper.selectBatchIds(any()))
            .thenReturn(Collections.singletonList(activeRole("ROLE001", "系统管理员")));

        PageResponse<AdminUserView> response = userService.list(1, 10, "张三", "1", "ORG001", null);

        assertEquals(1L, response.getTotal());
        assertEquals("ORG001", response.getRecords().get(0).getIdOrg());
        assertEquals("默认机构", response.getRecords().get(0).getNaOrg());
        assertEquals(Arrays.asList("系统管理员"), response.getRecords().get(0).getRoleNames());
    }

    @Test
    void saveShouldRejectUnknownOrg() {
        when(aiOrgMapper.selectOne(any())).thenReturn(null);
        AdminUserSaveRequest request = saveRequest();
        request.setIdOrg("ORG404");

        BusinessException ex = assertThrows(BusinessException.class, () -> userService.save(request));

        assertEquals("所属机构不存在", ex.getMessage());
        verify(aiUserMapper, never()).insert(any(AiUser.class));
        verify(aiRoleMapper, never()).selectBatchIds(any());
    }

    @Test
    void disableShouldOnlyUpdateUserStatus() {
        AiUser user = activeUser("USER001", "1");
        when(aiUserMapper.selectById("USER001")).thenReturn(user);
        when(aiUserRoleMapper.selectList(any())).thenReturn(Collections.<AiUserRole>emptyList());

        AdminUserView result = userService.disable("USER001");

        assertEquals("0", user.getSdStatus());
        assertEquals("0", result.getSdStatus());
        verify(aiUserMapper).updateById(user);
        verify(aiUserMapper, never()).selectOne(any());
        verify(aiOrgMapper, never()).selectOne(any());
        verify(aiRoleMapper, never()).selectBatchIds(any());
        verify(aiUserRoleMapper, never()).insert(any(AiUserRole.class));
    }

    @Test
    void enableShouldOnlyUpdateUserStatus() {
        AiUser user = activeUser("USER001", "0");
        when(aiUserMapper.selectById("USER001")).thenReturn(user);
        when(aiUserRoleMapper.selectList(any())).thenReturn(Collections.<AiUserRole>emptyList());

        AdminUserView result = userService.enable("USER001");

        assertEquals("1", user.getSdStatus());
        assertEquals("1", result.getSdStatus());
        verify(aiUserMapper).updateById(user);
        verify(aiUserMapper, never()).selectOne(any());
        verify(aiOrgMapper, never()).selectOne(any());
        verify(aiRoleMapper, never()).selectBatchIds(any());
        verify(aiUserRoleMapper, never()).insert(any(AiUserRole.class));
    }

    private AdminUserSaveRequest saveRequest() {
        AdminUserSaveRequest request = new AdminUserSaveRequest();
        request.setCdUser("zhangsan");
        request.setNaUser("张三");
        request.setPassword("123456");
        request.setIdOrg("ORG001");
        request.setRoleIds(Collections.singletonList("ROLE001"));
        request.setSdStatus("1");
        return request;
    }

    private AiOrg activeOrg(String id, String name) {
        AiOrg org = new AiOrg();
        org.setIdOrg(id);
        org.setNaOrg(name);
        org.setFgActive("1");
        org.setSdStatus("1");
        return org;
    }

    private AiRole activeRole(String id, String name) {
        AiRole role = new AiRole();
        role.setIdRole(id);
        role.setNaRole(name);
        role.setSdStatus("1");
        role.setFgActive("1");
        return role;
    }

    private AiUser activeUser(String id, String status) {
        AiUser user = new AiUser();
        user.setIdUser(id);
        user.setCdUser("zhangsan");
        user.setNaUser("张三");
        user.setIdOrg("ORG001");
        user.setSdStatus(status);
        user.setFgActive("1");
        return user;
    }
}
