package com.regionalai.floatingball.server.common.io;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

public final class AtomicFileWriter {

    private AtomicFileWriter() {
    }

    public static void write(Path target, InputStream inputStream) throws IOException {
        write(target, inputStream, AtomicFileWriter::moveAtomically);
    }

    static void write(Path target, InputStream inputStream, AtomicMover mover) throws IOException {
        writeInternal(target, channel -> {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = inputStream.read(buffer)) >= 0) {
                if (read > 0) {
                    channel.write(ByteBuffer.wrap(buffer, 0, read));
                }
            }
        }, mover);
    }

    public static void writeJson(Path target, ObjectMapper objectMapper, Object value) throws IOException {
        byte[] payload = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        writeInternal(target, channel -> {
            ByteBuffer buffer = ByteBuffer.wrap(payload);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        }, AtomicFileWriter::moveAtomically);
    }

    private static void writeInternal(Path target, ChannelWriter writer, AtomicMover mover) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path directory = normalizedTarget.getParent();
        if (directory == null) {
            throw new IOException("target file must have a parent directory");
        }
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, "." + normalizedTarget.getFileName() + ".", ".tmp");
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
            )) {
                writer.write(channel);
                channel.force(true);
            }
            mover.move(temporary, normalizedTarget);
            moved = true;
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        Files.move(
            source,
            target,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        );
    }

    private interface ChannelWriter {
        void write(FileChannel channel) throws IOException;
    }

    interface AtomicMover {
        void move(Path source, Path target) throws IOException;
    }
}
