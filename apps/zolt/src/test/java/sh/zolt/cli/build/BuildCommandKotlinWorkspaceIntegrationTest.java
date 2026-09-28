package sh.zolt.cli.build;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static sh.zolt.cli.CliTestSupport.execute;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.cli.CliTestRepository;
import sh.zolt.cli.CliTestSupport.CommandResult;

/** Real-compiler CLI coverage for Kotlin members under workspace scheduling and diagnostics. */
final class BuildCommandKotlinWorkspaceIntegrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void attributesKotlinCompilerFailureToItsWorkspaceMember() throws Exception {
        Path workspace = tempDir.resolve("compiler-failure");
        Path cache = tempDir.resolve("compiler-failure-cache");
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            writeWorkspace(workspace, repository.baseUri(), List.of("apps/good", "apps/bad"));
            writeKotlinMember(workspace.resolve("apps/good"), "good", """
                    package probe
                    object Good { fun value(): String = "good" }
                    """, "");
            writeKotlinMember(workspace.resolve("apps/bad"), "bad", """
                    package probe
                    object Bad { fun value(): String = 42 }
                    """, "");

            assertResolveSucceeds(workspace, cache);
            CommandResult build = build(workspace, cache);

            assertEquals(1, build.exitCode(), build.stderr());
            assertTrue(build.stderr().contains("Kotlin main compilation failed"), build.stderr());
            assertTrue(
                    build.stderr().contains("Workspace member `apps/bad` failed to compile."),
                    build.stderr());
            assertFalse(build.stderr().contains("\tat "), build.stderr());
        }
    }

    @Test
    void compilesBothWorkspaceDependencyScopesAndRecoversMetadataEditsOffline() throws Exception {
        Path onlineCache = tempDir.resolve("online-cache");
        Path offlineCache = tempDir.resolve("offline-cache");
        List<WorkspaceCase> cases = List.of(
                new WorkspaceCase(tempDir.resolve("implementation"), false),
                new WorkspaceCase(tempDir.resolve("api"), true));
        try (CliTestRepository repository = CliTestRepository.start()) {
            KotlinCompilerCliFixture.publish(repository);
            for (WorkspaceCase workspaceCase : cases) {
                writeKotlinWorkspace(workspaceCase, repository.baseUri());
                assertResolveSucceeds(workspaceCase.directory(), onlineCache);
            }
            Files.move(onlineCache, offlineCache);
            repository.clearAuthorizations();

            for (WorkspaceCase workspaceCase : cases) {
                verifyMetadataPropagation(workspaceCase, offlineCache);
            }
            assertEquals(
                    Map.of(),
                    repository.authorizations(),
                    "post-seed workspace builds must not contact the repository");
        }
    }

    private static void verifyMetadataPropagation(WorkspaceCase workspaceCase, Path cache)
            throws Exception {
        Path workspace = workspaceCase.directory();
        Path providerOutput = workspace.resolve("modules/provider/target/classes/probe/provider");
        Path providerApi = providerOutput.resolve("ProviderApi.class");
        Path providerMetadata = providerOutput.resolve("ProviderKt.class");
        Path providerModule = workspace.resolve(
                "modules/provider/target/classes/META-INF/provider_main.kotlin_module");
        Path consumerClass = workspace.resolve(
                "apps/consumer/target/classes/probe/consumer/ConsumerApi.class");

        CommandResult first = build(workspace, cache);
        assertEquals(0, first.exitCode(), first.stderr());
        assertTrue(Files.isRegularFile(providerApi));
        assertTrue(Files.isRegularFile(providerMetadata));
        assertTrue(Files.isRegularFile(providerModule));
        assertTrue(Files.isRegularFile(consumerClass));
        byte[] stableProviderApi = Files.readAllBytes(providerApi);
        byte[] stringMetadata = Files.readAllBytes(providerMetadata);
        byte[] stringModule = Files.readAllBytes(providerModule);
        FileTime providerTime = Files.getLastModifiedTime(providerApi);
        FileTime consumerTime = Files.getLastModifiedTime(consumerClass);

        CommandResult warm = build(workspace, cache);
        assertEquals(0, warm.exitCode(), warm.stderr());
        workspaceCase.members().forEach(member -> assertSkipped(warm, member));
        assertEquals(providerTime, Files.getLastModifiedTime(providerApi));
        assertEquals(consumerTime, Files.getLastModifiedTime(consumerClass));

        writeProvider(workspace.resolve("modules/provider"), "Int");
        CommandResult incompatible = build(workspace, cache);
        assertEquals(1, incompatible.exitCode(), incompatible.stderr());
        assertTrue(incompatible.stderr().contains("Kotlin main compilation failed"), incompatible.stderr());
        assertTrue(
                incompatible.stderr().contains("Workspace member `apps/consumer` failed to compile."),
                incompatible.stderr());
        assertTrue(
                incompatible.stderr().contains("String") && incompatible.stderr().contains("Int"),
                incompatible.stderr());
        assertArrayEquals(stableProviderApi, Files.readAllBytes(providerApi));
        assertFalse(java.util.Arrays.equals(stringMetadata, Files.readAllBytes(providerMetadata)));

        writeProvider(workspace.resolve("modules/provider"), "String");
        CommandResult repaired = build(workspace, cache);
        assertEquals(0, repaired.exitCode(), repaired.stderr());
        workspaceCase.members().forEach(
                member -> assertFalse(skipped(repaired, member), repaired.stdout()));
        assertArrayEquals(stableProviderApi, Files.readAllBytes(providerApi));
        assertArrayEquals(stringMetadata, Files.readAllBytes(providerMetadata));
        assertArrayEquals(stringModule, Files.readAllBytes(providerModule));
        assertTrue(Files.isRegularFile(consumerClass));

        CommandResult settled = build(workspace, cache);
        assertEquals(0, settled.exitCode(), settled.stderr());
        workspaceCase.members().forEach(member -> assertSkipped(settled, member));
    }

    private static void assertSkipped(CommandResult result, String member) {
        assertTrue(skipped(result, member), result.stdout());
    }

    private static boolean skipped(CommandResult result, String member) {
        return result.stdout().contains(
                "Skipped main compilation in " + member + "; inputs are unchanged");
    }

    private static void assertResolveSucceeds(Path workspace, Path cache) {
        CommandResult resolve = execute(
                "resolve",
                "--workspace",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
        assertEquals(0, resolve.exitCode(), resolve.stderr());
    }

    private static CommandResult build(Path workspace, Path cache) {
        return execute(
                "build",
                "--workspace",
                "--all",
                "--offline",
                "--no-build-cache",
                "--cwd", workspace.toString(),
                "--cache-root", cache.toString());
    }

    private static void writeWorkspace(Path workspace, URI repository, List<String> members)
            throws Exception {
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "kotlin-workspace"

                [workspace.members]
                include = %s

                [repositories]
                central = false

                [repositories.fixture]
                url = "%s"
                """.formatted(tomlArray(members), repository));
    }

    private static void writeKotlinWorkspace(WorkspaceCase workspaceCase, URI repository)
            throws Exception {
        Path workspace = workspaceCase.directory();
        writeWorkspace(workspace, repository, workspaceCase.members());
        writeKotlinMember(
                workspace.resolve("modules/provider"),
                "provider",
                workspaceDependency("", ""));
        writeProvider(workspace.resolve("modules/provider"), "String");
        String consumerDependency = "provider";
        if (workspaceCase.viaApiBridge()) {
            writeKotlinMember(
                    workspace.resolve("modules/bridge"),
                    "bridge",
                    """
                            package probe.bridge

                            object Bridge
                            """,
                    workspaceDependency("dependencies.api", "provider"));
            consumerDependency = "bridge";
        }
        writeKotlinMember(
                workspace.resolve("apps/consumer"),
                "consumer",
                workspaceDependency("dependencies", consumerDependency));
        Files.writeString(workspace.resolve("apps/consumer/src/main/kotlin/probe/consumer/Consumer.kt"), """
                package probe.consumer

                import probe.provider.ProviderApi
                import probe.provider.SharedValue

                object ConsumerApi {
                    @JvmStatic
                    fun value(): SharedValue = ProviderApi.value()
                }
                """);
    }

    private static void writeKotlinMember(
            Path directory,
            String name,
            String workspaceDependency) throws Exception {
        Path sourceDirectory = directory.resolve("src/main/kotlin/probe/" + name);
        Files.createDirectories(sourceDirectory);
        Files.writeString(directory.resolve("zolt.toml"), project(name) + """

                [toolchain.kotlin]
                version = "%s"

                [build]
                sources = ["src/main/kotlin"]

                [dependencies]
                "org.jetbrains.kotlin:kotlin-stdlib" = "%s"
                %s
                """.formatted(
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                KotlinCompilerCliFixture.KOTLIN_VERSION,
                workspaceDependency));
    }

    private static void writeKotlinMember(
            Path directory,
            String name,
            String sourceContent,
            String workspaceDependency) throws Exception {
        writeKotlinMember(directory, name, workspaceDependency);
        String className = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        Path source = directory.resolve(
                "src/main/kotlin/probe/" + name + "/" + className + ".kt");
        Files.writeString(source, sourceContent);
    }

    private static void writeProvider(Path directory, String alias) throws Exception {
        Files.writeString(directory.resolve("src/main/kotlin/probe/provider/Provider.kt"), """
                package probe.provider

                typealias SharedValue = %s

                object ProviderApi {
                    @JvmStatic
                    fun value(): String = "value"
                }
                """.formatted(alias));
    }

    private static String project(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "probe"
                java = %s
                """.formatted(name, Runtime.version().feature());
    }

    private static String workspaceDependency(String section, String dependency) {
        if (section.isEmpty()) {
            return "";
        }
        if ("dependencies".equals(section)) {
            return "\"probe:%s\" = { workspace = true }".formatted(dependency);
        }
        return "\n[dependencies.api]\n\"probe:%s\" = { workspace = true }"
                .formatted(dependency);
    }

    private static String tomlArray(List<String> values) {
        return values.stream()
                .map(value -> "\"" + value + "\"")
                .collect(java.util.stream.Collectors.joining(", ", "[", "]"));
    }

    private record WorkspaceCase(Path directory, boolean viaApiBridge) {
        private List<String> members() {
            return viaApiBridge
                    ? List.of("modules/provider", "modules/bridge", "apps/consumer")
                    : List.of("modules/provider", "apps/consumer");
        }
    }
}
