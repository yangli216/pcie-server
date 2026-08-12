package com.regionalai.floatingball.server.common.deployment;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

@Component("storage")
public class StorageHealthIndicator implements HealthIndicator, SmartInitializingSingleton {

    static final String MARKER_FILE_NAME = ".pcie-storage-id";
    private static final Logger log = LoggerFactory.getLogger(StorageHealthIndicator.class);

    private final StorageProperties storageProperties;
    private final Path releaseRoot;
    private final Path speechAuditRoot;

    public StorageHealthIndicator(
        StorageProperties storageProperties,
        @Value("${floating-ball.release.storage-dir:${java.io.tmpdir}/floating-ball-server/releases}")
        String releaseRoot,
        @Value("${floating-ball.audit.speech-file-dir:${java.io.tmpdir}/floating-ball-server/speech-audit}")
        String speechAuditRoot
    ) {
        this.storageProperties = storageProperties;
        this.releaseRoot = Paths.get(releaseRoot).toAbsolutePath().normalize();
        this.speechAuditRoot = Paths.get(speechAuditRoot).toAbsolutePath().normalize();
    }

    @Override
    public void afterSingletonsInstantiated() {
        try {
            validateAtStartup();
            log.info(
                "storage startup validation passed. mode={}, releaseRoot={}, speechAuditRoot={}",
                storageProperties.normalizedMode(),
                releaseRoot,
                speechAuditRoot
            );
        } catch (IOException ex) {
            throw new IllegalStateException("storage startup validation failed: " + ex.getMessage(), ex);
        }
    }

    @Override
    public Health health() {
        try {
            validateLightweight();
            return Health.up()
                .withDetail("mode", storageProperties.normalizedMode())
                .build();
        } catch (Exception ex) {
            return Health.down()
                .withDetail("mode", storageProperties.normalizedMode())
                .withDetail("reason", ex.getMessage())
                .build();
        }
    }

    void validateAtStartup() throws IOException {
        validateMode();
        prepareAndValidateDirectory("release", releaseRoot);
        prepareAndValidateDirectory("speech-audit", speechAuditRoot);
        if (storageProperties.isSharedPosix()) {
            validateSharedStorageId();
            verifyAtomicMove("release", releaseRoot);
            verifyAtomicMove("speech-audit", speechAuditRoot);
        }
    }

    void validateLightweight() throws IOException {
        validateMode();
        validateExistingDirectory("release", releaseRoot);
        validateExistingDirectory("speech-audit", speechAuditRoot);
        if (storageProperties.isSharedPosix()) {
            validateSharedStorageId();
        }
    }

    private void validateMode() throws IOException {
        String mode = storageProperties.normalizedMode();
        if (!StorageProperties.LOCAL.equals(mode) && !StorageProperties.SHARED_POSIX.equals(mode)) {
            throw new IOException(
                "floating-ball.storage.mode must be local or shared-posix, but was '" + mode + "'"
            );
        }
    }

    private void prepareAndValidateDirectory(String label, Path root) throws IOException {
        Files.createDirectories(root);
        validateExistingDirectory(label, root);
    }

    private void validateExistingDirectory(String label, Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IOException(label + " storage root is not a directory: " + root);
        }
        if (!Files.isReadable(root)) {
            throw new IOException(label + " storage root is not readable: " + root);
        }
        if (!Files.isWritable(root)) {
            throw new IOException(label + " storage root is not writable: " + root);
        }
    }

    private void validateSharedStorageId() throws IOException {
        String expected = storageProperties.getSharedStorageId();
        if (!StringUtils.hasText(expected)) {
            throw new IOException("floating-ball.storage.shared-storage-id must not be blank in shared-posix mode");
        }
        String normalizedExpected = expected.trim();
        verifyMarker("release", releaseRoot, normalizedExpected);
        verifyMarker("speech-audit", speechAuditRoot, normalizedExpected);
    }

    private void verifyMarker(String label, Path root, String expected) throws IOException {
        Path marker = root.resolve(MARKER_FILE_NAME);
        if (!Files.isRegularFile(marker) || !Files.isReadable(marker)) {
            throw new IOException(label + " storage marker is missing or unreadable: " + marker);
        }
        String actual = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
        if (!expected.equals(actual)) {
            throw new IOException(
                label + " storage marker mismatch: expected '" + expected + "' but was '" + actual + "'"
            );
        }
    }

    private void verifyAtomicMove(String label, Path root) throws IOException {
        byte[] payload = UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8);
        Path source = Files.createTempFile(root, ".pcie-storage-probe-", ".tmp");
        Path target = root.resolve(".pcie-storage-probe-" + UUID.randomUUID() + ".moved");
        try {
            Files.write(source, payload, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            byte[] moved = Files.readAllBytes(target);
            if (!java.util.Arrays.equals(payload, moved)) {
                throw new IOException(label + " storage atomic move probe content mismatch");
            }
        } catch (IOException ex) {
            throw new IOException(label + " storage does not support a same-directory atomic move", ex);
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(target);
        }
    }
}
