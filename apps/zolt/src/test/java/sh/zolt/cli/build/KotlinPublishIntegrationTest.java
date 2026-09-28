package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Publication canary for a real Kotlin compiler closure and a fresh Java consumer. */
final class KotlinPublishIntegrationTest {
    private static final String ARTIFACT_BASE =
            "/maven2/com/example/kotlin-cli/0.1.0/kotlin-cli-0.1.0";

    @TempDir
    private Path tempDir;

    @Test
    void publishesKotlinSourcesAndRuntimeMetadataThenRunsFreshConsumer() throws IOException {
        Path publisher = tempDir.resolve("publisher");
        Path publisherCache = tempDir.resolve("publisher-cache");
        Path consumer = tempDir.resolve("consumer");
        Path consumerCache = tempDir.resolve("consumer-cache");

        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            KotlinCompilerCliFixture.writeProject(publisher, repository.baseUri());
            enableSourcesAndPublishing(publisher, repository);

            run(publisher, publisherCache, "resolve");
            KotlinCompilerCliFixture.writeSource(publisher, "published");
            run(publisher, publisherCache, "package", "--no-build-cache");

            Path sourcesJar = publisher.resolve("target/kotlin-cli-0.1.0-sources.jar");
            assertEquals(List.of("com/example/Main.kt"), regularEntries(sourcesJar));

            run(publisher, publisherCache, "publish");
            assertArrayEquals(
                    Files.readAllBytes(sourcesJar),
                    repository.uploaded(ARTIFACT_BASE + "-sources.jar"));

            Path generatedPom = publisher.resolve("target/publish/kotlin-cli-0.1.0.pom");
            String pom = Files.readString(generatedPom);
            assertEquals(
                    pom,
                    new String(repository.uploaded(ARTIFACT_BASE + ".pom"), StandardCharsets.UTF_8));
            assertKotlinRuntimeOnlyPom(pom);

            repository.addArtifact(
                    "com.example",
                    "kotlin-cli",
                    "0.1.0",
                    pom,
                    repository.uploaded(ARTIFACT_BASE + ".jar"));
            writeConsumer(consumer, repository);

            run(consumer, consumerCache, "resolve");
            String consumerLock = Files.readString(consumer.resolve("zolt.lock"));
            assertTrue(consumerLock.contains("org.jetbrains.kotlin:kotlin-stdlib"), consumerLock);
            assertTrue(consumerLock.contains("org.jetbrains:annotations"), consumerLock);
            assertCompilerToolsAbsent(consumerLock);

            CommandResult consumerRun = executeIn(consumer, consumerCache, "run");
            assertEquals(0, consumerRun.exitCode(), consumerRun.stderr());
            assertTrue(consumerRun.stdout().contains("real-kotlin-published"), consumerRun.stdout());
        }
    }

    private static void enableSourcesAndPublishing(
            Path publisher,
            CliTestRepository repository) throws IOException {
        Path manifest = publisher.resolve("zolt.toml");
        Files.writeString(manifest, Files.readString(manifest) + """

                [package]
                sources = true

                [publish]
                release = "fixture"

                [publish.repositories.fixture]
                url = "%s"
                """.formatted(repository.baseUri()));
    }

    private static void writeConsumer(Path consumer, CliTestRepository repository) throws IOException {
        Files.createDirectories(consumer.resolve("src/main/java/com/example/consumer"));
        Files.writeString(consumer.resolve("zolt.toml"), """
                [project]
                name = "kotlin-consumer"
                version = "0.1.0"
                group = "com.example.consumer"
                java = %s
                main = "com.example.consumer.Consumer"

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"

                [dependencies]
                "com.example:kotlin-cli" = "0.1.0"
                """.formatted(Runtime.version().feature(), repository.baseUri()));
        Files.writeString(
                consumer.resolve("src/main/java/com/example/consumer/Consumer.java"),
                """
                package com.example.consumer;

                public final class Consumer {
                    private Consumer() {
                    }

                    public static void main(String[] args) {
                        com.example.Main.main(args);
                    }
                }
                """);
    }

    private static List<String> regularEntries(Path jarPath) throws IOException {
        try (JarFile jar = new JarFile(jarPath.toFile())) {
            return jar.stream()
                    .filter(entry -> !entry.isDirectory())
                    .map(entry -> entry.getName())
                    .toList();
        }
    }

    private static void assertKotlinRuntimeOnlyPom(String pom) {
        String dependency = dependency(pom, "kotlin-stdlib");
        assertTrue(dependency.contains("<groupId>org.jetbrains.kotlin</groupId>"), dependency);
        assertTrue(
                dependency.contains("<version>" + KotlinCompilerCliFixture.KOTLIN_VERSION + "</version>"),
                dependency);
        assertTrue(dependency.contains("<scope>runtime</scope>"), dependency);
        assertEquals(1, pom.split("<dependency>", -1).length - 1, pom);
        assertCompilerToolsAbsent(pom);
    }

    private static String dependency(String pom, String artifactId) {
        int artifact = pom.indexOf("<artifactId>" + artifactId + "</artifactId>");
        assertTrue(artifact >= 0, pom);
        int start = pom.lastIndexOf("<dependency>", artifact);
        int end = pom.indexOf("</dependency>", artifact);
        assertTrue(start >= 0 && end > artifact, pom);
        return pom.substring(start, end + "</dependency>".length());
    }

    private static void assertCompilerToolsAbsent(String content) {
        for (String artifact : List.of(
                "kotlin-compiler-embeddable",
                "kotlin-daemon-embeddable",
                "kotlin-reflect",
                "kotlin-script-runtime",
                "kotlinx-coroutines-core-jvm")) {
            assertFalse(content.contains(artifact), artifact + " leaked into published runtime metadata:\n" + content);
        }
    }

    private static void run(Path project, Path cache, String... command) {
        CommandResult result = executeIn(project, cache, command);
        assertEquals(
                0,
                result.exitCode(),
                String.join(" ", command) + " failed:\n" + result.stdout() + result.stderr());
    }

    private static CommandResult executeIn(Path project, Path cache, String... command) {
        String[] args = new String[command.length + 4];
        System.arraycopy(command, 0, args, 0, command.length);
        args[command.length] = "--cwd";
        args[command.length + 1] = project.toString();
        args[command.length + 2] = "--cache-root";
        args[command.length + 3] = cache.toString();
        return execute(args);
    }
}
