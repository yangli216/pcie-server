package com.regionalai.floatingball.server.modules.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioLogStorageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void independentNodesShouldReadTheSameSharedAudioFile() throws Exception {
        Path sharedRoot = tempDir.resolve("speech-audit");
        AudioLogStorageService writerNode = new AudioLogStorageService(sharedRoot.toString());
        AudioLogStorageService readerNode = new AudioLogStorageService(sharedRoot.toString());
        byte[] audio = "shared-audio".getBytes(StandardCharsets.UTF_8);

        String storedPath = writerNode.store(audio, "speech.webm", "LOG-001");
        Path resolved = readerNode.resolveExistingPath(storedPath);

        assertTrue(storedPath.matches("v1/\\d{8}/[A-Za-z0-9._-]+"));
        assertFalse(Paths.get(storedPath).isAbsolute());
        assertTrue(resolved.startsWith(sharedRoot.toRealPath()));
        assertArrayEquals(audio, Files.readAllBytes(resolved));
    }

    @Test
    void sameRelativeKeyShouldResolveUnderDifferentMountRoots() throws Exception {
        Path writerRoot = tempDir.resolve("node-a-mount");
        Path readerRoot = tempDir.resolve("node-b-mount");
        AudioLogStorageService writerNode = new AudioLogStorageService(writerRoot.toString());
        AudioLogStorageService readerNode = new AudioLogStorageService(readerRoot.toString());
        byte[] audio = "shared-audio".getBytes(StandardCharsets.UTF_8);

        String storedKey = writerNode.store(audio, "speech.webm", "LOG-002");
        Path writerPath = writerNode.resolveExistingPath(storedKey);
        Path readerPath = readerRoot.resolve(storedKey);
        Files.createDirectories(readerPath.getParent());
        Files.copy(writerPath, readerPath);

        assertArrayEquals(audio, Files.readAllBytes(readerNode.resolveExistingPath(storedKey)));
    }

    @Test
    void readerNodeShouldRejectAPathOutsideTheConfiguredSharedRoot() throws Exception {
        Path sharedRoot = tempDir.resolve("speech-audit");
        Files.createDirectories(sharedRoot);
        Path outside = Files.write(
            tempDir.resolve("outside.webm"),
            "audio".getBytes(StandardCharsets.UTF_8)
        );
        AudioLogStorageService readerNode = new AudioLogStorageService(sharedRoot.toString());

        assertThrows(IOException.class, () -> readerNode.resolveExistingPath(outside.toString()));
    }

    @Test
    void shouldRejectRelativeTraversalAndUnknownKeyVersions() {
        AudioLogStorageService storage = new AudioLogStorageService(tempDir.resolve("speech-audit").toString());

        assertThrows(IOException.class, () -> storage.resolveExistingPath("v1/20260811/../../outside.webm"));
        assertThrows(IOException.class, () -> storage.resolveExistingPath("v2/20260811/audio.webm"));
        assertThrows(IOException.class, () -> storage.resolveExistingPath("v1\\20260811\\audio.webm"));
    }

    @Test
    void deleteShouldIgnoreAPathThatEscapesTheConfiguredRoot() throws Exception {
        Path outside = Files.write(
            tempDir.resolve("outside.webm"),
            "outside".getBytes(StandardCharsets.UTF_8)
        );
        AudioLogStorageService storage = new AudioLogStorageService(tempDir.resolve("speech-audit").toString());

        storage.deleteQuietly("v1/20260811/../../outside.webm");
        storage.deleteQuietly(outside.toString());

        assertTrue(Files.isRegularFile(outside));
    }

    @Test
    void shouldKeepReadingLegacyAbsolutePathInsideCurrentRoot() throws Exception {
        Path sharedRoot = tempDir.resolve("speech-audit");
        Files.createDirectories(sharedRoot);
        Path legacyFile = Files.write(sharedRoot.resolve("legacy.webm"), "legacy".getBytes(StandardCharsets.UTF_8));
        AudioLogStorageService storage = new AudioLogStorageService(sharedRoot.toString());

        assertArrayEquals(
            "legacy".getBytes(StandardCharsets.UTF_8),
            Files.readAllBytes(storage.resolveExistingPath(legacyFile.toString()))
        );
    }
}
