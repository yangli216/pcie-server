package com.regionalai.floatingball.server.common.io;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeShouldReplaceExistingPackageWithoutLeavingTemporaryFiles() throws Exception {
        Path target = tempDir.resolve("pcie.zip");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));

        AtomicFileWriter.write(
            target,
            new ByteArrayInputStream("complete-new-package".getBytes(StandardCharsets.UTF_8))
        );

        assertEquals("complete-new-package", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertTrue(hasNoTemporaryFile());
    }

    @Test
    void writeJsonShouldReplaceExistingJsonWithParseableDocument() throws Exception {
        Path target = tempDir.resolve("latest.json");
        Files.write(target, "truncated".getBytes(StandardCharsets.UTF_8));
        ObjectMapper objectMapper = new ObjectMapper();

        AtomicFileWriter.writeJson(target, objectMapper, Collections.singletonMap("version", "1.4.0"));

        assertEquals("1.4.0", objectMapper.readTree(target.toFile()).get("version").asText());
        assertTrue(hasNoTemporaryFile());
    }

    @Test
    void writeShouldFailClosedAndPreserveTargetWhenAtomicMoveIsUnavailable() throws Exception {
        Path target = tempDir.resolve("pcie.zip");
        Files.write(target, "known-good-package".getBytes(StandardCharsets.UTF_8));

        assertThrows(
            AtomicMoveNotSupportedException.class,
            () -> AtomicFileWriter.write(
                target,
                new ByteArrayInputStream("replacement".getBytes(StandardCharsets.UTF_8)),
                (source, destination) -> {
                    throw new AtomicMoveNotSupportedException(
                        source.toString(),
                        destination.toString(),
                        "shared file system does not guarantee atomic rename"
                    );
                }
            )
        );

        assertEquals("known-good-package", new String(Files.readAllBytes(target), StandardCharsets.UTF_8));
        assertTrue(hasNoTemporaryFile());
    }

    private boolean hasNoTemporaryFile() throws Exception {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp"));
        }
    }
}
