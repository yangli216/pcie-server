package com.regionalai.floatingball.server.modules.emrqc.controller;

import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.emrqc.service.EmrQcService;
import com.regionalai.floatingball.server.modules.emrqc.service.EmrQcTaskService;
import com.regionalai.floatingball.server.security.AdminContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
import com.regionalai.floatingball.server.common.exception.ForbiddenException;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminEmrQcControllerTest {
    @Test
    void forwardsIndependentDepartmentAndDoctorFilters() throws Exception {
        EmrQcService service = mock(EmrQcService.class);
        AdminCurrentUser user = new AdminCurrentUser();
        user.setRoles(Collections.singletonList("ORG_ADMIN"));
        when(service.list(user, null, null, null, null, "D-1", "all", 1, 10, "内科"))
            .thenReturn(new PageResponse<>(1, 10, 0, Collections.emptyList()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminEmrQcController(service, mock(EmrQcTaskService.class))).build();
        AdminContextHolder.set(user);
        try {
            mvc.perform(get("/admin/api/emr-qc/records").param("deptName", "内科")
                .param("doctorUserId", "D-1").param("qcStatus", "all"))
                .andExpect(status().isOk());
            verify(service).list(user, null, null, null, null, "D-1", "all", 1, 10, "内科");
        } finally {
            AdminContextHolder.clear();
        }
    }

    @Test
    void emrReviewerShouldBeAcceptedAndAnalystShouldBeRejected() {
        EmrQcService service = mock(EmrQcService.class);
        AdminEmrQcController controller = new AdminEmrQcController(service, mock(EmrQcTaskService.class));
        AdminCurrentUser reviewer = new AdminCurrentUser();
        reviewer.setRoles(Collections.singletonList("EMR_QC_REVIEWER"));
        AdminContextHolder.set(reviewer);
        try {
            controller.session(mock(javax.servlet.http.HttpServletRequest.class));
            verify(service).session(reviewer);
        } finally {
            AdminContextHolder.clear();
        }

        AdminCurrentUser analyst = new AdminCurrentUser();
        analyst.setRoles(Collections.singletonList("ORG_ANALYST"));
        AdminContextHolder.set(analyst);
        try {
            ForbiddenException exception = assertThrows(ForbiddenException.class,
                () -> controller.session(mock(javax.servlet.http.HttpServletRequest.class)));
            assertEquals("AUTH-403", exception.getCode());
        } finally {
            AdminContextHolder.clear();
        }
    }
}
