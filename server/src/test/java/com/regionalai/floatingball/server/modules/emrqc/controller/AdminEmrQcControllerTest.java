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
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AdminEmrQcControllerTest {
    @Test
    void forwardsIndependentDepartmentAndDoctorFilters() throws Exception {
        EmrQcService service = mock(EmrQcService.class);
        AdminCurrentUser user = new AdminCurrentUser();
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
}
