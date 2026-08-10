package com.regionalai.floatingball.server.modules.audit.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

        assertTrue(resolved.startsWith(sharedRoot));
        assertArrayEquals(audio, Files.readAllBytes(resolved));
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
}
