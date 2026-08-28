package com.regionalai.floatingball.server.modules.adminui.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class AdminXiaoshanFunctionUsageViewTest {

    @Test
    void functionUsageRouteShouldUseDedicatedXiaoshanViewAndKeepStandardViewAvailable() throws IOException {
        String router = readAdminSource("router", "index.js");
        String xiaoshanView = readAdminSource("views", "XiaoshanFunctionUsageView.vue");
        String standardView = readAdminSource("views", "FunctionUsageView.vue");

        assertThat(router)
            .contains("const XiaoshanFunctionUsageView = () => import('../views/XiaoshanFunctionUsageView.vue')")
            .contains("{ path: '/function-usage', component: XiaoshanFunctionUsageView")
            .contains("{ path: '/standard-function-usage', component: FunctionUsageView");

        assertThat(xiaoshanView)
            .contains("/admin/api/xiaoshan-analytics/function-modules")
            .contains("/admin/api/xiaoshan-analytics/function-usage")
            .contains("/admin/api/xiaoshan-analytics/function-usage/export")
            .contains("isBbpOrganizationScopedUser")
            .contains("bbpOrganizationOption")
            .contains("v-if=\"!organizationLocked\"")
            .contains(":disabled=\"organizationLocked\"")
            .contains("const hisOrgId = this.organizationLocked ? this.query.hisOrgId : ''")
            .contains("萧山辅诊功能统计_")
            .doesNotContain("/admin/api/analytics/function-usage");

        assertThat(standardView)
            .contains("/admin/api/analytics/function-modules")
            .contains("/admin/api/analytics/function-usage")
            .contains("/admin/api/analytics/function-usage/export")
            .doesNotContain("/admin/api/xiaoshan-analytics/");
    }

    private String readAdminSource(String... relativeParts) throws IOException {
        Path path = Paths.get("src", "main", "admin", "src");
        for (String part : relativeParts) {
            path = path.resolve(part);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
