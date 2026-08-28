package com.regionalai.floatingball.server.modules.adminui.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.FileCopyUtils;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class AdminUiResourcePackagingTest {

    private static final Pattern ADMIN_ASSET_REFERENCE = Pattern.compile(
        "(?:src|href)=[\\\"'](/admin/assets/[^\\\"'?#]+)[\\\"']"
    );

    @Test
    void adminIndexAndReferencedAssetsShouldBeAvailableOnClasspath() throws IOException {
        ClassPathResource adminIndex = new ClassPathResource("static/admin/index.html");

        assertThat(adminIndex.exists()).isTrue();

        String indexHtml;
        try (InputStreamReader reader = new InputStreamReader(
            adminIndex.getInputStream(), StandardCharsets.UTF_8)) {
            indexHtml = FileCopyUtils.copyToString(reader);
        }

        Set<String> assetReferences = new LinkedHashSet<>();
        Matcher matcher = ADMIN_ASSET_REFERENCE.matcher(indexHtml);
        while (matcher.find()) {
            assetReferences.add(matcher.group(1));
        }

        assertThat(assetReferences)
            .as("admin index should reference built /admin/assets/* resources")
            .isNotEmpty();
        assetReferences.forEach(assetPath -> assertThat(
            new ClassPathResource("static" + assetPath).exists())
            .as("admin asset should be available on classpath: %s", assetPath)
            .isTrue());
    }
}
