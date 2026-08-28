package com.regionalai.floatingball.server.modules.adminui.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUserEditorOrgSelectionTest {

    @Test
    void userEditorShouldSelectFromEnabledPlatformOrganizations() throws IOException {
        String userView = readAdminSource("views", "UserView.vue");
        String editorDialog = readAdminSource("components", "user", "UserEditorDialog.vue");

        assertThat(userView)
            .contains("fetchOrgs({ sdStatus: '1' })")
            .contains(":org-options=\"orgOptions\"")
            .doesNotContain("fetchHisOrgOptions()")
            .doesNotContain("hisOrgId");
        assertThat(editorDialog)
            .contains("<el-form-item label=\"所属机构\" prop=\"idOrg\">")
            .contains("v-model=\"form.idOrg\"")
            .contains(":label=\"resolveOrgLabel(item)\"")
            .contains(":value=\"item.idOrg\"")
            .doesNotContain("hisOrgId");
    }

    private String readAdminSource(String... relativeParts) throws IOException {
        Path path = Paths.get("src", "main", "admin", "src");
        for (String part : relativeParts) {
            path = path.resolve(part);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
