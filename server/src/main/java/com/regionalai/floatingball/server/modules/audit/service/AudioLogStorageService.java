package com.regionalai.floatingball.server.modules.audit.service;

import com.regionalai.floatingball.server.common.io.AtomicFileWriter;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Service
public class AudioLogStorageService {

    private static final Logger log = LoggerFactory.getLogger(AudioLogStorageService.class);

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String STORAGE_KEY_VERSION = "v1";

    private final Path storageRoot;

    public AudioLogStorageService(@Value("${floating-ball.audit.speech-file-dir:${java.io.tmpdir}/floating-ball-server/speech-audit}") String storageRoot) {
        this.storageRoot = Paths.get(storageRoot).toAbsolutePath().normalize();
    }

    public String store(byte[] audioBytes, String originalFileName, String logId) throws IOException {
        if (audioBytes == null || audioBytes.length == 0) {
            return null;
        }
        String dateSegment = LocalDate.now().format(DATE_FORMATTER);
        Path targetDirectory = storageRoot.resolve(STORAGE_KEY_VERSION).resolve(dateSegment);
        Files.createDirectories(targetDirectory);

        String storedFileName = buildStoredFileName(originalFileName, logId);
        Path targetPath = targetDirectory.resolve(storedFileName).normalize();
        ensureInsideStorage(targetPath);
        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(audioBytes)) {
            AtomicFileWriter.write(targetPath, inputStream);
        }
        return STORAGE_KEY_VERSION + "/" + dateSegment + "/" + storedFileName;
    }

    public void deleteQuietly(String storedPath) {
        if (!StringUtils.hasText(storedPath)) {
            return;
        }
        try {
            Path path = resolveStoredPath(storedPath);
            Files.deleteIfExists(path);
        } catch (IOException | InvalidPathException ex) {
            log.warn("audio log file deletion failed. path={}, error={}", storedPath, ex.getMessage());
        }
    }

    public Path resolveExistingPath(String storedPath) throws IOException {
        if (!StringUtils.hasText(storedPath)) {
            throw new IOException("音频文件路径为空");
        }
        Path path = resolveStoredPath(storedPath);
        if (!Files.isRegularFile(path)) {
            throw new IOException("音频文件不存在");
        }
        Path realRoot = storageRoot.toRealPath();
        Path realPath = path.toRealPath();
        if (!realPath.startsWith(realRoot)) {
            throw new IOException("音频文件路径不在允许目录内");
        }
        return realPath;
    }

    private Path resolveStoredPath(String storedPath) throws IOException {
        String value = storedPath == null ? "" : storedPath.trim();
        if (!StringUtils.hasText(value)) {
            throw new IOException("音频文件路径为空");
        }
        if (value.indexOf('\\') >= 0) {
            throw new IOException("音频文件路径非法");
        }

        final Path candidate;
        try {
            Path parsed = Paths.get(value);
            if (parsed.isAbsolute()) {
                // Compatibility is intentionally limited to files under the currently
                // configured root. Other historical roots must be migrated explicitly.
                candidate = parsed.toAbsolutePath().normalize();
            } else {
                validateRelativeStorageKey(value);
                candidate = storageRoot.resolve(parsed).normalize();
            }
        } catch (InvalidPathException ex) {
            throw new IOException("音频文件路径非法", ex);
        }
        ensureInsideStorage(candidate);
        return candidate;
    }

    private void validateRelativeStorageKey(String value) throws IOException {
        String[] segments = value.split("/", -1);
        if (segments.length != 3
            || !STORAGE_KEY_VERSION.equals(segments[0])
            || !segments[1].matches("\\d{8}")
            || !segments[2].matches("[A-Za-z0-9._-]+")
            || ".".equals(segments[2])
            || "..".equals(segments[2])) {
            throw new IOException("音频文件路径非法");
        }
    }

    private void ensureInsideStorage(Path path) throws IOException {
        if (!path.toAbsolutePath().normalize().startsWith(storageRoot)) {
            throw new IOException("音频文件路径不在允许目录内");
        }
    }

    private String buildStoredFileName(String originalFileName, String logId) {
        String normalizedFileName = normalizeFileName(originalFileName);
        int dotIndex = normalizedFileName.lastIndexOf('.');
        String extension = dotIndex >= 0 ? normalizedFileName.substring(dotIndex) : ".bin";
        String baseName = dotIndex >= 0 ? normalizedFileName.substring(0, dotIndex) : normalizedFileName;
        return baseName + "-" + sanitizeSegment(logId) + "-"
            + UUID.randomUUID().toString().replace("-", "") + extension;
    }

    private String normalizeFileName(String originalFileName) {
        String fallback = "speech-audio.bin";
        if (!StringUtils.hasText(originalFileName)) {
            return fallback;
        }
        try {
            String fileName = Paths.get(originalFileName.trim()).getFileName().toString();
            String sanitized = sanitizeSegment(fileName);
            return StringUtils.hasText(sanitized) ? sanitized : fallback;
        } catch (InvalidPathException ex) {
            return fallback;
        }
    }

    private String sanitizeSegment(String value) {
        if (!StringUtils.hasText(value)) {
            return "audio";
        }
        return value.trim().replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
