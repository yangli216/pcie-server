package com.regionalai.floatingball.server.modules.emrqc.controller;

import com.regionalai.floatingball.server.common.api.PageResponse;
import com.regionalai.floatingball.server.modules.auth.dto.AdminCurrentUser;
import com.regionalai.floatingball.server.modules.auth.bbp.BbpStatisticsScope;
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
    void proxySourceIsExposedAsAnExplicitListAndAnalysisMarkerForExistingRoles() throws Exception {
        EmrQcService service = mock(EmrQcService.class);
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP"); user.setRoles(Collections.singletonList("EMR_QC_REVIEWER"));
        com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcRecordVO row =
            new com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcRecordVO();
        row.setResultSource("proxy_refill"); row.setScore(100); row.setFindingCount(0);
        when(service.list(user, null, null, null, null, null, null, 1, 10, null, null))
            .thenReturn(new PageResponse<>(1, 10, 1, Collections.singletonList(row)));
        com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO analysis =
            new com.regionalai.floatingball.server.modules.emrqc.dto.EmrQcAnalysisVO();
        analysis.setResultSource("proxy_refill"); analysis.setScore(100); analysis.setFindingCount(0);
        when(service.analyze(eq(user), eq("A"), eq(false), any())).thenReturn(analysis);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminEmrQcController(
            service, mock(EmrQcTaskService.class), new BbpStatisticsScope())).build();
        AdminContextHolder.set(user);
        try {
            mvc.perform(get("/admin/api/emr-qc/records"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.records[0].proxyRefill").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.records[0].score").value(100));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/admin/api/emr-qc/records/A/analyze"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.proxyRefill").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.findings").isEmpty());
        } finally { AdminContextHolder.clear(); }
    }

    @Test
    void forwardsIndependentDepartmentAndDoctorFilters() throws Exception {
        EmrQcService service = mock(EmrQcService.class);
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP");
        user.setRoles(Collections.singletonList("ORG_ADMIN"));
        when(service.list(user, null, null, null, null, "D-1", "all", 1, 10, "内科", null))
            .thenReturn(new PageResponse<>(1, 10, 0, Collections.emptyList()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminEmrQcController(
            service, mock(EmrQcTaskService.class), new BbpStatisticsScope())).build();
        AdminContextHolder.set(user);
        try {
            mvc.perform(get("/admin/api/emr-qc/records").param("deptName", "内科")
                .param("doctorUserId", "D-1").param("qcStatus", "all"))
                .andExpect(status().isOk());
            verify(service).list(user, null, null, null, null, "D-1", "all", 1, 10, "内科", null);
        } finally {
            AdminContextHolder.clear();
        }
    }


    @Test
    void forwardsDraftFilterForAnExplicitReviewer() throws Exception {
        EmrQcService service = mock(EmrQcService.class);
        AdminCurrentUser user = new AdminCurrentUser();
        user.setAuthProvider("BBP"); user.setRoles(Collections.singletonList("EMR_QC_REVIEWER"));
        when(service.list(user, null, null, null, null, null, null, 2, 100, null, "draft"))
            .thenReturn(new PageResponse<>(2, 100, 101, Collections.emptyList()));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminEmrQcController(
            service, mock(EmrQcTaskService.class), new BbpStatisticsScope())).build();
        AdminContextHolder.set(user);
        try {
            mvc.perform(get("/admin/api/emr-qc/records").param("recordStatus", "draft").param("current", "2").param("size", "100"))
                .andExpect(status().isOk());
            verify(service).list(user, null, null, null, null, null, null, 2, 100, null, "draft");
        } finally { AdminContextHolder.clear(); }
    }

    @Test
    void emrReviewerShouldBeAcceptedAndAnalystShouldBeRejected() {
        EmrQcService service = mock(EmrQcService.class);
        AdminEmrQcController controller = new AdminEmrQcController(
            service, mock(EmrQcTaskService.class), new BbpStatisticsScope());
        AdminCurrentUser reviewer = new AdminCurrentUser();
        reviewer.setAuthProvider("BBP");
        reviewer.setRoles(Collections.singletonList("EMR_QC_REVIEWER"));
        AdminContextHolder.set(reviewer);
        try {
            controller.session(mock(javax.servlet.http.HttpServletRequest.class));
            verify(service).session(reviewer);
        } finally {
            AdminContextHolder.clear();
        }

        AdminCurrentUser analyst = new AdminCurrentUser();
        analyst.setAuthProvider("BBP");
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

    @Test
    void analystAndEmrReviewerRoleCombinationShouldBeAccepted() {
        EmrQcService service = mock(EmrQcService.class);
        AdminEmrQcController controller = new AdminEmrQcController(
            service, mock(EmrQcTaskService.class), new BbpStatisticsScope());
        AdminCurrentUser mixed = new AdminCurrentUser();
        mixed.setAuthProvider("BBP");
        mixed.setRoles(java.util.Arrays.asList("ORG_ANALYST", "EMR_QC_REVIEWER"));
        AdminContextHolder.set(mixed);
        try {
            controller.session(mock(javax.servlet.http.HttpServletRequest.class));
            verify(service).session(mixed);
        } finally {
            AdminContextHolder.clear();
        }
    }
}
