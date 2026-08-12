package com.regionalai.floatingball.server.common.deployment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Status;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StorageHealthIndicatorTest {

    @TempDir
    Path tempDir;

    @Test
    void localModeCreatesRootsAtStartupAndUsesLightweightReadinessChecks() throws Exception {
        Path release = tempDir.resolve("release");
        Path audit = tempDir.resolve("audit");
        StorageHealthIndicator indicator = indicator(new StorageProperties(), release, audit);

        indicator.validateAtStartup();

        assertTrue(Files.isDirectory(release));
        assertTrue(Files.isDirectory(audit));
        assertEquals(Status.UP, indicator.health().getStatus());

        Files.delete(audit);
        assertEquals(Status.DOWN, indicator.health().getStatus());
        assertFalse(Files.exists(audit), "readiness must not recreate a missing root");
    }

    @Test
    void sharedPosixRequiresMatchingMarkersAndSupportsAtomicMoves() throws Exception {
        Path release = Files.createDirectories(tempDir.resolve("release"));
        Path audit = Files.createDirectories(tempDir.resolve("audit"));
        writeMarker(release, "site-a");
        writeMarker(audit, "site-a");

        StorageHealthIndicator indicator = indicator(shared("site-a"), release, audit);
        indicator.validateAtStartup();

        assertEquals(Status.UP, indicator.health().getStatus());
        assertEquals(1L, fileCount(release));
        assertEquals(1L, fileCount(audit));
    }

    @Test
    void sharedPosixRejectsMissingOrMismatchedMarkers() throws Exception {
        Path release = Files.createDirectories(tempDir.resolve("release"));
        Path audit = Files.createDirectories(tempDir.resolve("audit"));
        writeMarker(release, "site-a");
        writeMarker(audit, "site-b");

        StorageHealthIndicator mismatch = indicator(shared("site-a"), release, audit);
        IOException mismatchFailure = assertThrows(IOException.class, mismatch::validateAtStartup);
        assertTrue(mismatchFailure.getMessage().contains("marker mismatch"));

        Files.delete(audit.resolve(StorageHealthIndicator.MARKER_FILE_NAME));
        StorageHealthIndicator missing = indicator(shared("site-a"), release, audit);
        IOException missingFailure = assertThrows(IOException.class, missing::validateAtStartup);
        assertTrue(missingFailure.getMessage().contains("missing or unreadable"));
    }

    private StorageHealthIndicator indicator(StorageProperties properties, Path release, Path audit) {
        return new StorageHealthIndicator(properties, release.toString(), audit.toString());
    }

    private StorageProperties shared(String storageId) {
        StorageProperties properties = new StorageProperties();
        properties.setMode(StorageProperties.SHARED_POSIX);
        properties.setSharedStorageId(storageId);
        return properties;
    }

    private void writeMarker(Path root, String value) throws IOException {
        Files.write(
            root.resolve(StorageHealthIndicator.MARKER_FILE_NAME),
            value.getBytes(StandardCharsets.UTF_8)
        );
    }

    private long fileCount(Path root) throws IOException {
        try (Stream<Path> files = Files.list(root)) {
            return files.count();
        }
    }
}
