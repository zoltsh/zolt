package sh.zolt.workspace.kotlin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.discovery.SourceDiscoverer;
import sh.zolt.build.incremental.GeneratedOutputAttribution;
import sh.zolt.build.incremental.IncrementalCompileState;
import sh.zolt.build.incremental.IncrementalCompileStateCodec;
import sh.zolt.build.incremental.IncrementalCompileStateRecorder;
import sh.zolt.build.incremental.IncrementalCompileSummaryReader;
import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.workspace.discovery.ManifestWorkspaceLoader;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceBuildService;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Kotlin module metadata is part of the ABI identity observed across workspace members. */
final class WorkspaceKotlinMetadataAbiPropagationTest {
    private static final String PROVIDER = "modules/provider";
    private static final String CONSUMER = "apps/consumer";

    private final WorkspaceBuildService service = new WorkspaceBuildService();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void createWorkspace() throws IOException {
        Files.writeString(tempDir.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-metadata-abi"

                [workspace.members]
                include = ["modules/provider", "apps/consumer"]
                """);
        member(PROVIDER, "provider", "");
        source(PROVIDER + "/src/main/java/com/example/provider/Provider.java", """
                package com.example.provider;

                public final class Provider {
                    public static String value() {
                        return "provider";
                    }
                }
                """);
        member(CONSUMER, "consumer", """

                [dependencies]
                "com.example:provider" = { workspace = true }
                """);
        source(CONSUMER + "/src/main/java/com/example/consumer/Consumer.java", """
                package com.example.consumer;

                import com.example.provider.Provider;

                public final class Consumer {
                    public static String value() {
                        return Provider.value();
                    }
                }
                """);
    }

    @Test
    void metadataByteAndPathChangesRecompileOnlyTheConsumer() throws IOException {
        build();
        String classOnlyAbi = providerAbi();
        Path metadata = providerOutput().resolve("META-INF/provider.kotlin_module");
        Files.createDirectories(metadata.getParent());
        Files.write(metadata, new byte[] {0x01, 0x23, 0x45});

        String initialMetadataAbi = recordProviderState();
        assertNotEquals(classOnlyAbi, initialMetadataAbi);
        assertConsumerOnlyRecompiled(build());
        assertAllSkipped(build());

        Files.write(metadata, new byte[] {0x01, 0x23, 0x46});
        String changedBytesAbi = recordProviderState();
        assertNotEquals(initialMetadataAbi, changedBytesAbi);
        assertConsumerOnlyRecompiled(build());

        Path renamed = providerOutput().resolve("META-INF/provider-renamed.kotlin_module");
        Files.delete(metadata);
        Files.write(renamed, new byte[] {0x01, 0x23, 0x46});
        String changedPathAbi = recordProviderState();
        assertNotEquals(changedBytesAbi, changedPathAbi);
        assertConsumerOnlyRecompiled(build());
    }

    private String recordProviderState() {
        WorkspaceMember provider = provider();
        Path output = providerOutput();
        IncrementalCompileState prior = new IncrementalCompileStateCodec()
                .read(IncrementalCompileState.mainStatePath(output))
                .orElseThrow();
        var sources = new SourceDiscoverer()
                .discover(provider.directory(), provider.config().build());
        new IncrementalCompileStateRecorder().recordMain(
                provider.directory(),
                provider.config(),
                sources,
                emptyClasspaths(),
                output,
                provider.directory().resolve(
                        provider.config().compilerSettings().generatedSources()),
                prior.compilerIdentity(),
                GeneratedOutputAttribution.absent(),
                sources.allMainSources());
        return providerAbi();
    }

    private String providerAbi() {
        return new IncrementalCompileSummaryReader()
                .readMain(providerOutput())
                .orElseThrow()
                .compileAbiDigest();
    }

    private WorkspaceMember provider() {
        Workspace workspace = new ManifestWorkspaceLoader().load(tempDir);
        return workspace.members().stream()
                .filter(member -> PROVIDER.equals(member.path()))
                .findFirst()
                .orElseThrow();
    }

    private WorkspaceBuildResult build() {
        return service.build(tempDir, tempDir.resolve("cache"), false);
    }

    private Path providerOutput() {
        return tempDir.resolve(PROVIDER).resolve("target/classes");
    }

    private static ClasspathSet emptyClasspaths() {
        Classpath empty = new Classpath(List.of());
        return new ClasspathSet(empty, empty, empty, empty, empty, empty, empty);
    }

    private static void assertConsumerOnlyRecompiled(WorkspaceBuildResult result) {
        Map<String, Boolean> skipped = skippedByMember(result);
        assertTrue(skipped.get(PROVIDER), "provider inputs did not change");
        assertFalse(skipped.get(CONSUMER), "metadata ABI changes must reach the consumer");
    }

    private static void assertAllSkipped(WorkspaceBuildResult result) {
        skippedByMember(result).values().forEach(skipped -> assertTrue(skipped));
    }

    private static Map<String, Boolean> skippedByMember(WorkspaceBuildResult result) {
        return result.members().stream()
                .collect(Collectors.toMap(
                        WorkspaceBuildResult.MemberBuildResult::member,
                        member -> member.result().mainCompilationSkipped()));
    }

    private void member(String path, String name, String extraToml) throws IOException {
        Path directory = tempDir.resolve(path);
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("zolt.toml"), """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = %s
                %s""".formatted(name, Runtime.version().feature(), extraToml));
    }

    private void source(String path, String content) throws IOException {
        Path source = tempDir.resolve(path);
        Files.createDirectories(source.getParent());
        Files.writeString(source, content);
    }
}
