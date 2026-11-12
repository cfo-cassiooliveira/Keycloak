package com.workspace.keycloak.audit.gelf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AuditFallbackStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void append_CreatesMissingDirectoriesAndWritesOneMessagePerLine() throws IOException {
        Path file = tempDir.resolve("log/audit-fallback.log");
        AuditFallbackStore store = new AuditFallbackStore(file);

        store.append("{\"short_message\":\"one\"}");
        store.append("{\"short_message\":\"dois ç\"}");

        assertThat(Files.readAllLines(file)).containsExactly("{\"short_message\":\"one\"}",
                "{\"short_message\":\"dois ç\"}");
        assertThat(store.file()).isEqualTo(file);
    }

    @Test
    void append_WhenCalledConcurrently_NeverMixesLines() throws Exception {
        Path file = tempDir.resolve("audit-fallback.log");
        AuditFallbackStore store = new AuditFallbackStore(file);
        ExecutorService executor = Executors.newFixedThreadPool(8);

        IntStream.range(0, 200).forEach(i -> executor.submit(() -> {
            store.append("{\"n\":" + i + "}");
            return null;
        }));
        executor.shutdown();
        assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(200).allMatch(line -> line.matches("\\{\"n\":\\d+}"));
    }

    @Test
    void append_WhenPathIsNotWritable_Throws() throws IOException {
        Path directoryInsteadOfFile = Files.createDirectory(tempDir.resolve("not-a-file"));
        AuditFallbackStore store = new AuditFallbackStore(directoryInsteadOfFile);

        assertThatThrownBy(() -> store.append("{}")).isInstanceOf(IOException.class);
    }
}
