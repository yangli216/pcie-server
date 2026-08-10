package com.regionalai.floatingball.server.common.cluster;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;

@Component
public class ClusterStorageValidator implements InitializingBean {

    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z0-9._-]+");

    private final ClusterProperties properties;
    private final String releaseStorage;
    private final String speechStorage;
    private final Path temporaryRoot;

    @Autowired
    public ClusterStorageValidator(
        ClusterProperties properties,
        @Value("${floating-ball.release.storage-dir:${java.io.tmpdir}/floating-ball-server/releases}") String releaseStorage,
        @Value("${floating-ball.audit.speech-file-dir:${java.io.tmpdir}/floating-ball-server/speech-audit}") String speechStorage
    ) {
        this(properties, releaseStorage, speechStorage, Paths.get(System.getProperty("java.io.tmpdir")));
    }

    ClusterStorageValidator(ClusterProperties properties,
                            String releaseStorage,
                            String speechStorage,
                            Path temporaryRoot) {
        this.properties = properties;
        this.releaseStorage = releaseStorage;
        this.speechStorage = speechStorage;
        this.temporaryRoot = resolveRealPath(temporaryRoot);
    }

    @Override
    public void afterPropertiesSet() {
        validate();
    }

    public void validate() {
        if (!properties.isEnabled()) {
            return;
        }
        requireIdentity("node-id", properties.getNodeId());
        requireIdentity("cluster-id", properties.getClusterId());
        requireIdentity("release-writer-node-id", properties.getReleaseWriterNodeId());
        if (properties.getStorageChallengeTtlSeconds() < 10
            || properties.getStorageChallengeTtlSeconds() > 120) {
            throw new IllegalStateException(
                "floating-ball.cluster.storage-challenge-ttl-seconds must be between 10 and 120"
            );
        }
        validateMarkerFileName();
        if (!properties.isSharedStorageRequired()) {
            throw new IllegalStateException(
                "floating-ball.cluster.shared-storage-required must remain true in cluster mode"
            );
        }
        validateStorage("release", Paths.get(releaseStorage));
        validateStorage("speech-audit", Paths.get(speechStorage));
    }

    private void requireIdentity(String propertyName, String value) {
        if (!StringUtils.hasText(value)
            || "standalone".equals(value.trim())
            || !SAFE_IDENTIFIER.matcher(value.trim()).matches()) {
            throw new IllegalStateException("floating-ball.cluster." + propertyName + " must identify this cluster deployment");
        }
    }

    private void validateMarkerFileName() {
        String markerFileName = properties.getMarkerFileName();
        if (!StringUtils.hasText(markerFileName)) {
            throw new IllegalStateException("floating-ball.cluster.marker-file-name must not be blank");
        }
        String normalizedMarkerFileName = markerFileName.trim();
        Path markerPath = Paths.get(normalizedMarkerFileName);
        if (markerPath.isAbsolute()
            || markerPath.getNameCount() != 1
            || ".".equals(normalizedMarkerFileName)
            || "..".equals(normalizedMarkerFileName)
            || !SAFE_IDENTIFIER.matcher(normalizedMarkerFileName).matches()) {
            throw new IllegalStateException("floating-ball.cluster.marker-file-name must be one safe file name");
        }
    }

    private void validateStorage(String purpose, Path configuredPath) {
        if (!configuredPath.isAbsolute()) {
            throw new IllegalStateException("cluster " + purpose + " storage must be an absolute path: " + configuredPath);
        }
        Path root = resolveRealPath(configuredPath);
        if (root.startsWith(temporaryRoot)) {
            throw new IllegalStateException("cluster " + purpose + " storage must not be under java.io.tmpdir: " + root);
        }
        if (!Files.isDirectory(root) || !Files.isReadable(root) || !Files.isWritable(root)) {
            throw new IllegalStateException("cluster " + purpose + " storage must already exist and be readable/writable: " + root);
        }

        Path marker = root.resolve(properties.getMarkerFileName().trim()).normalize();
        if (!marker.getParent().equals(root) || !Files.isRegularFile(marker)) {
            throw new IllegalStateException("cluster " + purpose + " storage marker is missing: " + marker);
        }
        try {
            String actualClusterId = new String(Files.readAllBytes(marker), StandardCharsets.UTF_8).trim();
            if (!properties.getClusterId().trim().equals(actualClusterId)) {
                throw new IllegalStateException(
                    "cluster " + purpose + " storage marker does not match floating-ball.cluster.cluster-id"
                );
            }
            verifyReadWriteProbe(root);
        } catch (IOException ex) {
            throw new IllegalStateException("cluster " + purpose + " storage probe failed: " + root, ex);
        }
    }

    private void verifyReadWriteProbe(Path root) throws IOException {
        Path probe = Files.createTempFile(root, ".pcie-probe-" + properties.getNodeId() + "-", ".tmp");
        Path movedProbe = root.resolve(probe.getFileName().toString() + ".moved");
        try {
            byte[] expected = properties.getNodeId().getBytes(StandardCharsets.UTF_8);
            Files.write(probe, expected);
            Files.move(
                probe,
                movedProbe,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            );
            byte[] actual = Files.readAllBytes(movedProbe);
            if (!java.util.Arrays.equals(expected, actual)) {
                throw new IOException("probe content mismatch");
            }
        } finally {
            Files.deleteIfExists(probe);
            Files.deleteIfExists(movedProbe);
        }
    }

    private static Path resolveRealPath(Path path) {
        try {
            return path.toRealPath();
        } catch (IOException ex) {
            return path.toAbsolutePath().normalize();
        }
    }
}
