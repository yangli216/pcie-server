package com.regionalai.floatingball.server.modules.adminui.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

class AdminElementComponentRegistrationTest {

    @Test
    void rolePermissionCheckboxesShouldBeRegisteredWithElementUi() throws IOException {
        Path pluginSource = Paths.get(
            "src", "main", "admin", "src", "plugins", "element.js"
        );
        String source = new String(
            Files.readAllBytes(pluginSource),
            StandardCharsets.UTF_8
        );

        assertThat(source)
            .contains("import Checkbox from 'element-ui/lib/checkbox'")
            .contains("import CheckboxGroup from 'element-ui/lib/checkbox-group'")
            .containsPattern("(?s)const components = \\[.*\\bCheckbox,.*\\bCheckboxGroup,.*]");
    }
}
