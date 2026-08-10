package com.regionalai.floatingball.server.common.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.io.AtomicFileWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.endpoint.annotation.DeleteOperation;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

@Component
@Endpoint(id = "clusterStorageChallenge")
public class ClusterStorageChallengeEndpoint {

    private static final String CHALLENGE_DIRECTORY = ".pcie-cluster-challenges";
    private static final String FILE_PREFIX = ".pcie-challenge-";
    private static final String FILE_SUFFIX = ".json";
    private static final int MAX_ACTIVE_CHALLENGES_PER_STORAGE = 32;
    private static final Pattern UUID_PATTERN = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"
    );

    private final ClusterProperties properties;
    private final Path releaseRoot;
    private final Path speechRoot;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Autowired
    public ClusterStorageChallengeEndpoint(
        ClusterProperties properties,
        @Value("${floating-ball.release.storage-dir:${java.io.tmpdir}/floating-ball-server/releases}") String releaseStorage,
        @Value("${floating-ball.audit.speech-file-dir:${java.io.tmpdir}/floating-ball-server/speech-audit}") String speechStorage,
        ObjectMapper objectMapper
    ) {
        this(
            properties,
            Paths.get(releaseStorage),
            Paths.get(speechStorage),
            objectMapper,
            Clock.systemUTC()
        );
    }

    ClusterStorageChallengeEndpoint(ClusterProperties properties,
                                    Path releaseRoot,
                                    Path speechRoot,
                                    ObjectMapper objectMapper,
                                    Clock clock) {
        this.properties = properties;
        this.releaseRoot = releaseRoot.toAbsolutePath().normalize();
        this.speechRoot = speechRoot.toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @WriteOperation
    public synchronized Map<String, Object> write(@Selector String storage,
                                                   String token,
                                                   String content) {
        requireClusterMode();
        String normalizedToken = normalizeUuid(token, "token");
        String normalizedContent = normalizeUuid(content, "content");
        Path directory = challengeDirectory(storage);
        try {
            cleanupExpired(directory);
            if (countActiveChallenges(directory) >= MAX_ACTIVE_CHALLENGES_PER_STORAGE) {
                throw new IllegalStateException("too many active cluster storage challenges");
            }
            Path target = challengePath(directory, normalizedToken);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("cluster storage challenge token already exists");
            }
            ChallengeRecord record = new ChallengeRecord();
            record.setToken(normalizedToken);
            record.setContent(normalizedContent);
            record.setCreatedAt(clock.millis());
            AtomicFileWriter.writeJson(target, objectMapper, record);
            return response(storage, normalizedToken, true, normalizedContent, "CREATED");
        } catch (IOException ex) {
            throw new IllegalStateException("cluster storage challenge write failed", ex);
        }
    }

    @ReadOperation
    public synchronized Map<String, Object> read(@Selector String storage, String token) {
        requireClusterMode();
        String normalizedToken = normalizeUuid(token, "token");
        Path directory = challengeDirectory(storage);
        try {
            cleanupExpired(directory);
            Path target = challengePath(directory, normalizedToken);
            if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return response(storage, normalizedToken, false, null, "ABSENT");
            }
            requireRegularChallengeFile(target);
            ChallengeRecord record = objectMapper.readValue(target.toFile(), ChallengeRecord.class);
            if (!normalizedToken.equals(record.getToken())
                || !isUuid(record.getContent())
                || isExpired(target)) {
                Files.deleteIfExists(target);
                return response(storage, normalizedToken, false, null, "ABSENT");
            }
            return response(storage, normalizedToken, true, record.getContent(), "FOUND");
        } catch (IOException ex) {
            throw new IllegalStateException("cluster storage challenge read failed", ex);
        }
    }

    @DeleteOperation
    public synchronized Map<String, Object> delete(@Selector String storage, String token) {
        requireClusterMode();
        String normalizedToken = normalizeUuid(token, "token");
        Path directory = challengeDirectory(storage);
        try {
            cleanupExpired(directory);
            Path target = challengePath(directory, normalizedToken);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireRegularChallengeFile(target);
            }
            boolean deleted = Files.deleteIfExists(target);
            Map<String, Object> response = response(storage, normalizedToken, false, null, "DELETED");
            response.put("deleted", deleted);
            return response;
        } catch (IOException ex) {
            throw new IllegalStateException("cluster storage challenge delete failed", ex);
        }
    }

    private Path challengeDirectory(String storage) {
        Path root = storageRoot(storage);
        Path directory = root.resolve(CHALLENGE_DIRECTORY).normalize();
        if (!directory.getParent().equals(root)) {
            throw new IllegalStateException("invalid cluster storage challenge directory");
        }
        try {
            Files.createDirectories(directory);
            if (Files.isSymbolicLink(directory)
                || !directory.toRealPath().startsWith(root.toRealPath())) {
                throw new IllegalStateException("invalid cluster storage challenge directory");
            }
            return directory;
        } catch (IOException ex) {
            throw new IllegalStateException("cluster storage challenge directory is unavailable", ex);
        }
    }

    private Path storageRoot(String storage) {
        if ("release".equals(storage)) {
            return releaseRoot;
        }
        if ("speech".equals(storage)) {
            return speechRoot;
        }
        throw new IllegalArgumentException("storage must be release or speech");
    }

    private Path challengePath(Path directory, String token) {
        Path target = directory.resolve(FILE_PREFIX + token + FILE_SUFFIX).normalize();
        if (!target.getParent().equals(directory)) {
            throw new IllegalArgumentException("invalid cluster storage challenge token");
        }
        return target;
    }

    private void cleanupExpired(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            for (Path path : (Iterable<Path>) files::iterator) {
                if (isManagedChallengeFile(path) && isExpired(path)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private int countActiveChallenges(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return (int) files.filter(this::isManagedChallengeFile).count();
        }
    }

    private boolean isManagedChallengeFile(Path path) {
        String fileName = path.getFileName().toString();
        if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) {
            return false;
        }
        String token = fileName.substring(FILE_PREFIX.length(), fileName.length() - FILE_SUFFIX.length());
        return isUuid(token) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS);
    }

    private boolean isExpired(Path path) {
        try {
            long ageMillis = clock.millis() - Files.getLastModifiedTime(path, LinkOption.NOFOLLOW_LINKS).toMillis();
            return ageMillis > properties.getStorageChallengeTtlSeconds() * 1000L;
        } catch (IOException ex) {
            return true;
        }
    }

    private void requireRegularChallengeFile(Path path) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalStateException("invalid cluster storage challenge file");
        }
    }

    private void requireClusterMode() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("cluster storage challenge is only available in cluster mode");
        }
    }

    private String normalizeUuid(String value, String field) {
        if (!isUuid(value)) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    private boolean isUuid(String value) {
        return value != null && UUID_PATTERN.matcher(value).matches();
    }

    private Map<String, Object> response(String storage,
                                         String token,
                                         boolean exists,
                                         String content,
                                         String status) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("storage", storage);
        response.put("token", token);
        response.put("exists", exists);
        if (content != null) {
            response.put("content", content);
        }
        response.put("status", status);
        response.put("nodeId", properties.getNodeId());
        return response;
    }

    public static class ChallengeRecord {
        private String token;
        private String content;
        private long createdAt;

        public String getToken() {
            return token;
        }

        public void setToken(String token) {
            this.token = token;
        }

        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        public long getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(long createdAt) {
            this.createdAt = createdAt;
        }
    }
}
