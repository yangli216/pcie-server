package com.regionalai.floatingball.server.common.cluster;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterStorageValidatorTest {

    @TempDir
    Path tempDir;

    @Test
    void springShouldCreateValidatorUsingInjectionConstructor() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.register(ClusterProperties.class, ClusterStorageValidator.class);
            context.refresh();

            assertNotNull(context.getBean(ClusterStorageValidator.class));
        }
    }

    @Test
    void validateShouldAcceptPreProvisionedReadableWritableStorage() throws Exception {
        Path release = storage("release", "clinic-cluster");
        Path speech = storage("speech", "clinic-cluster");
        ClusterStorageValidator validator = validator(release, speech, tempDir.resolve("unrelated-system-tmp"));

        assertDoesNotThrow(validator::afterPropertiesSet);
        assertTrue(Files.isRegularFile(release.resolve(".pcie-cluster-id")));
        assertTrue(Files.isRegularFile(speech.resolve(".pcie-cluster-id")));
        assertTrue(hasNoProbeFile(release));
    }

    @Test
    void validateShouldRejectMarkerFromAnotherCluster() throws Exception {
        Path release = storage("release", "wrong-cluster");
        Path speech = storage("speech", "clinic-cluster");
        ClusterStorageValidator validator = validator(release, speech, tempDir.resolve("unrelated-system-tmp"));

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void validateShouldRejectStorageUnderSystemTemporaryDirectory() throws Exception {
        Path release = storage("system-tmp/release", "clinic-cluster");
        Path speech = storage("speech", "clinic-cluster");
        ClusterStorageValidator validator = validator(release, speech, tempDir.resolve("system-tmp"));

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void validateShouldRejectClusterWhenSharedStorageRequirementIsDisabled() throws Exception {
        Path release = storage("release", "clinic-cluster");
        Path speech = storage("speech", "clinic-cluster");
        ClusterProperties properties = new ClusterProperties();
        properties.setEnabled(true);
        properties.setNodeId("node-1");
        properties.setClusterId("clinic-cluster");
        properties.setReleaseWriterNodeId("node-1");
        properties.setSharedStorageRequired(false);
        ClusterStorageValidator validator = new ClusterStorageValidator(
            properties,
            release.toAbsolutePath().toString(),
            speech.toAbsolutePath().toString(),
            tempDir.resolve("unrelated-system-tmp")
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void validateShouldRejectClusterWithoutOneConfiguredReleaseWriterNode() throws Exception {
        Path release = storage("release", "clinic-cluster");
        Path speech = storage("speech", "clinic-cluster");
        ClusterProperties properties = new ClusterProperties();
        properties.setEnabled(true);
        properties.setNodeId("node-1");
        properties.setClusterId("clinic-cluster");
        ClusterStorageValidator validator = new ClusterStorageValidator(
            properties,
            release.toAbsolutePath().toString(),
            speech.toAbsolutePath().toString(),
            tempDir.resolve("unrelated-system-tmp")
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    @Test
    void releaseWriterSelectionShouldUseSharedWriterNodeIdInsteadOfPerNodeBoolean() {
        ClusterProperties writer = clusterProperties("node-1", "node-1");
        writer.setReleaseWriterEnabled(false);
        ClusterProperties reader = clusterProperties("node-2", "node-1");
        reader.setReleaseWriterEnabled(true);

        assertTrue(writer.isReleaseWriterNode());
        assertFalse(reader.isReleaseWriterNode());
    }

    @Test
    void validateShouldRejectStorageChallengeTtlOutsideShortBound() throws Exception {
        Path release = storage("release", "clinic-cluster");
        Path speech = storage("speech", "clinic-cluster");
        ClusterProperties properties = clusterProperties("node-1", "node-1");
        properties.setStorageChallengeTtlSeconds(121);
        ClusterStorageValidator validator = new ClusterStorageValidator(
            properties,
            release.toAbsolutePath().toString(),
            speech.toAbsolutePath().toString(),
            tempDir.resolve("unrelated-system-tmp")
        );

        assertThrows(IllegalStateException.class, validator::validate);
    }

    private ClusterStorageValidator validator(Path release, Path speech, Path systemTemporaryRoot) {
        ClusterProperties properties = clusterProperties("node-1", "node-1");
        return new ClusterStorageValidator(
            properties,
            release.toAbsolutePath().toString(),
            speech.toAbsolutePath().toString(),
            systemTemporaryRoot
        );
    }

    private ClusterProperties clusterProperties(String nodeId, String writerNodeId) {
        ClusterProperties properties = new ClusterProperties();
        properties.setEnabled(true);
        properties.setNodeId(nodeId);
        properties.setClusterId("clinic-cluster");
        properties.setReleaseWriterNodeId(writerNodeId);
        return properties;
    }

    private Path storage(String name, String clusterId) throws Exception {
        Path path = tempDir.resolve(name);
        Files.createDirectories(path);
        Files.write(path.resolve(".pcie-cluster-id"), clusterId.getBytes(StandardCharsets.UTF_8));
        return path;
    }

    private boolean hasNoProbeFile(Path directory) throws Exception {
        try (Stream<Path> files = Files.list(directory)) {
            return files.noneMatch(path -> path.getFileName().toString().startsWith(".pcie-probe-"));
        }
    }
}
