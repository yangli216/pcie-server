package com.regionalai.floatingball.server.modules.auth.bbp;

import com.regionalai.floatingball.server.modules.auth.config.AdminAuthMode;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BbpDirectoryServiceTest {

    @Mock
    private AdminAuthMode authMode;

    @Mock
    private BbpSessionRegistry sessionRegistry;

    @Mock
    private BbpHttpClient httpClient;

    private BbpDirectoryService directoryService;
    private BbpCookieJar cookies;
    private AdminCurrentUser currentUser;

    @BeforeEach
    void setUp() {
        directoryService = new BbpDirectoryService(
            authMode, sessionRegistry, httpClient, new BbpAdminScope());
        cookies = new BbpCookieJar();
        currentUser = new AdminCurrentUser();
        currentUser.setAuthProvider("BBP");
        currentUser.setAuthSessionId("SESSION-1");
        currentUser.setBbpTenantId("TENANT-A");
        currentUser.setRoles(Collections.singletonList("SYSTEM_ADMIN"));
        when(sessionRegistry.requireSession("SESSION-1"))
            .thenReturn(new BbpSessionRegistry.Session(
                "TENANT-A", cookies, System.currentTimeMillis() + 60000L));
    }

    @Test
    void personsShouldExcludeDifferentTenantAndOrganization() {
        when(httpClient.findPersons(cookies, "ORG-A")).thenReturn(Arrays.asList(
            person("LOCAL", "TENANT-A", "ORG-A", "DEPT-A"),
            person("LEGACY", null, "ORG-A", "DEPT-A"),
            person("OTHER-TENANT", "TENANT-B", "ORG-A", "DEPT-A"),
            person("OTHER-ORG", "TENANT-A", "ORG-B", "DEPT-A")
        ));

        List<String> ids = directoryService.persons(currentUser, "ORG-A").stream()
            .map(BbpPersonView::getId)
            .collect(Collectors.toList());

        assertEquals(Arrays.asList("LOCAL", "LEGACY"), ids);
    }

    @Test
    void departmentPersonsShouldExcludeDifferentTenantAndDepartment() {
        when(httpClient.findPersonsByDepartment(cookies, "DEPT-A")).thenReturn(Arrays.asList(
            person("LOCAL", "TENANT-A", "ORG-A", "DEPT-A"),
            person("LEGACY", "", "ORG-A", null),
            person("OTHER-TENANT", "TENANT-B", "ORG-A", "DEPT-A"),
            person("OTHER-DEPT", "TENANT-A", "ORG-A", "DEPT-B")
        ));

        List<String> ids = directoryService.personsByDepartment(currentUser, "DEPT-A").stream()
            .map(BbpPersonView::getId)
            .collect(Collectors.toList());

        assertEquals(Arrays.asList("LOCAL", "LEGACY"), ids);
    }

    private BbpPersonView person(String id, String tenantId, String orgId, String deptId) {
        BbpPersonView person = new BbpPersonView();
        person.setId(id);
        person.setTenantId(tenantId);
        person.setOrgId(orgId);
        person.setDepartmentId(deptId);
        return person;
    }
}
