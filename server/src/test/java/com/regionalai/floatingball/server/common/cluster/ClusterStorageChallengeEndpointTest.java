package com.regionalai.floatingball.server.common.cluster;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClusterStorageChallengeEndpointTest {

    @TempDir
    Path tempDir;

    @Test
    void challengeShouldBeVisibleAndDeletableAcrossNodeInstancesForBothStorageRoots() throws Exception {
        Path release = Files.createDirectories(tempDir.resolve("release"));
        Path speech = Files.createDirectories(tempDir.resolve("speech"));
        ClusterStorageChallengeEndpoint nodeA = endpoint("node-a", release, speech);
        ClusterStorageChallengeEndpoint nodeB = endpoint("node-b", release, speech);
        ClusterStorageChallengeEndpoint nodeC = endpoint("node-c", release, speech);

        for (String storage : new String[] {"release", "speech"}) {
            String token = UUID.randomUUID().toString();
            String content = UUID.randomUUID().toString();

            assertEquals("CREATED", nodeA.write(storage, token, content).get("status"));
            assertEquals(content, nodeB.read(storage, token).get("content"));
            assertEquals(content, nodeC.read(storage, token).get("content"));
            assertEquals(Boolean.TRUE, nodeB.delete(storage, token).get("deleted"));
            assertFalse((Boolean) nodeA.read(storage, token).get("exists"));
            assertFalse((Boolean) nodeB.read(storage, token).get("exists"));
            assertFalse((Boolean) nodeC.read(storage, token).get("exists"));
        }
    }

    @Test
    void challengeShouldRejectArbitraryStorageAndNonUuidValues() throws Exception {
        Path release = Files.createDirectories(tempDir.resolve("release"));
        Path speech = Files.createDirectories(tempDir.resolve("speech"));
        ClusterStorageChallengeEndpoint endpoint = endpoint("node-a", release, speech);
        String token = UUID.randomUUID().toString();
        String content = UUID.randomUUID().toString();

        assertThrows(IllegalArgumentException.class, () -> endpoint.write("../release", token, content));
        assertThrows(IllegalArgumentException.class, () -> endpoint.write("release", "../../marker", content));
        assertThrows(IllegalArgumentException.class, () -> endpoint.write("release", token, "not-a-uuid"));
    }

    @Test
    void expiredChallengeShouldBeDeletedAndReportedAbsent() throws Exception {
        Path release = Files.createDirectories(tempDir.resolve("release"));
        Path speech = Files.createDirectories(tempDir.resolve("speech"));
        ClusterProperties properties = properties("node-a");
        properties.setStorageChallengeTtlSeconds(10);
        ClusterStorageChallengeEndpoint endpoint = new ClusterStorageChallengeEndpoint(
            properties,
            release,
            speech,
            new ObjectMapper(),
            Clock.systemUTC()
        );
        String token = UUID.randomUUID().toString();
        endpoint.write("release", token, UUID.randomUUID().toString());
        Path challenge = release.resolve(".pcie-cluster-challenges")
            .resolve(".pcie-challenge-" + token + ".json");
        Files.setLastModifiedTime(challenge, FileTime.fromMillis(System.currentTimeMillis() - 11_000L));

        Map<String, Object> response = endpoint.read("release", token);

        assertEquals(Boolean.FALSE, response.get("exists"));
        assertFalse(Files.exists(challenge));
    }

    @Test
    void challengeShouldBeUnavailableOutsideClusterMode() throws Exception {
        Path release = Files.createDirectories(tempDir.resolve("release"));
        Path speech = Files.createDirectories(tempDir.resolve("speech"));
        ClusterProperties properties = new ClusterProperties();
        ClusterStorageChallengeEndpoint endpoint = new ClusterStorageChallengeEndpoint(
            properties,
            release,
            speech,
            new ObjectMapper(),
            Clock.systemUTC()
        );

        assertThrows(
            IllegalStateException.class,
            () -> endpoint.read("release", UUID.randomUUID().toString())
        );
    }

    @Test
    void challengeShouldEnforceActiveFileLimit() throws Exception {
        Path release = Files.createDirectories(tempDir.resolve("release"));
        Path speech = Files.createDirectories(tempDir.resolve("speech"));
        ClusterStorageChallengeEndpoint endpoint = endpoint("node-a", release, speech);
        for (int index = 0; index < 32; index += 1) {
            endpoint.write("release", UUID.randomUUID().toString(), UUID.randomUUID().toString());
        }

        assertThrows(
            IllegalStateException.class,
            () -> endpoint.write("release", UUID.randomUUID().toString(), UUID.randomUUID().toString())
        );
        assertTrue(Files.isDirectory(release.resolve(".pcie-cluster-challenges")));
    }

    private ClusterStorageChallengeEndpoint endpoint(String nodeId, Path release, Path speech) {
        return new ClusterStorageChallengeEndpoint(
            properties(nodeId),
            release,
            speech,
            new ObjectMapper(),
            Clock.systemUTC()
        );
    }

    private ClusterProperties properties(String nodeId) {
        ClusterProperties properties = new ClusterProperties();
        properties.setEnabled(true);
        properties.setNodeId(nodeId);
        properties.setClusterId("clinic-cluster");
        properties.setReleaseWriterNodeId("node-a");
        properties.setStorageChallengeTtlSeconds(60);
        return properties;
    }
}
