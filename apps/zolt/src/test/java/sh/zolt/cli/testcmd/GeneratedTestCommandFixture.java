package sh.zolt.cli.testcmd;

import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static sh.zolt.cli.CliTestSupport.sha256;
import static sh.zolt.cli.ContentAddressedLockTestSupport.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class GeneratedTestCommandFixture {
    private static final String JUNIT_JAR =
            "org/junit/platform/junit-platform-console-standalone/"
                    + "1.11.4/junit-platform-console-standalone-1.11.4.jar";
    private static final String JVM_TOOL_JAR =
            "com/example/fixture-generator/1.0.0/"
                    + "fixture-generator-1.0.0.jar";
    private static final String OPENAPI_TOOL_JAR =
            "com/example/openapi-generator/1.0.0/"
                    + "openapi-generator-1.0.0.jar";
    private static final String RUNTIME_JAR =
            "com/example/generator-runtime/1.0.0/"
                    + "generator-runtime-1.0.0.jar";

    private GeneratedTestCommandFixture() {
    }

    static Path jvmProject(
            Path root,
            Path cache,
            String name,
            boolean integration,
            boolean packageTests) throws IOException {
        Files.createDirectories(root);
        writeCommonTooling(cache);
        Files.writeString(
                root.resolve("zolt.toml"),
                project(name)
                        + jvmGeneratorConfig()
                        + (packageTests
                                ? "\n[package]\ntests = true\n"
                                : "")
                        + (integration
                                ? integrationConfig()
                                : ""));
        source(
                root,
                integration
                        ? "src/integration-test/java/com/example/GeneratedIT.java"
                        : "src/test/java/com/example/GeneratedTest.java",
                """
                package com.example;

                final class GeneratedTest {
                    private final com.example.generated.GeneratedFixture fixture =
                            new com.example.generated.GeneratedFixture();
                }
                """.replace(
                        "GeneratedTest",
                        integration
                                ? "GeneratedIT"
                                : "GeneratedTest"));
        Files.writeString(root.resolve("fixtures.txt"), "seed\n");
        write(root.resolve("zolt.lock"), cache, lockfile(cache, null, true, false, false));
        return root;
    }

    static Path jvmWorkspace(
            Path workspace,
            Path cache,
            boolean integration) throws IOException {
        Path member = workspace.resolve("apps/app");
        Files.createDirectories(member);
        writeCommonTooling(cache);
        Files.writeString(workspace.resolve("zolt.toml"), """
                [workspace]
                name = "generated-test-workspace"

                [workspace.members]
                include = ["apps/app"]
                """);
        Files.writeString(
                member.resolve("zolt.toml"),
                project("app")
                        + jvmGeneratorConfig()
                        + (integration
                                ? integrationConfig()
                                : ""));
        source(
                member,
                integration
                        ? "src/integration-test/java/com/example/GeneratedIT.java"
                        : "src/test/java/com/example/GeneratedTest.java",
                """
                package com.example;

                final class GeneratedTest {
                    private final com.example.generated.GeneratedFixture fixture =
                            new com.example.generated.GeneratedFixture();
                }
                """.replace(
                        "GeneratedTest",
                        integration
                                ? "GeneratedIT"
                                : "GeneratedTest"));
        Files.writeString(member.resolve("fixtures.txt"), "seed\n");
        write(workspace.resolve("zolt.lock"), cache, lockfile(cache, "apps/app", true, false, false));
        return member;
    }

    static Path openApiProject(
            Path root,
            Path cache) throws IOException {
        Files.createDirectories(root);
        writeFakeConsoleJar(cache.resolve(JUNIT_JAR));
        GeneratedTestToolJarFixture.writeOpenApiGenerator(
                cache.resolve(OPENAPI_TOOL_JAR),
                root.resolve("openapi-tool-work"));
        Files.writeString(
                root.resolve("zolt.toml"),
                project("openapi-test")
                        + """

                        [generated.tools.openapi]
                        kind = "openapi"
                        coordinate = "com.example:openapi-generator"
                        version = "1.0.0"

                        [generated.test.api]
                        kind = "openapi"
                        language = "java"
                        input = "src/test/openapi/api.yaml"
                        output = "target/generated/test-openapi"
                        generator = "java"
                        additionalProperties = { sourceFolder = "." }
                        required = false
                        """);
        source(
                root,
                "src/test/java/com/example/OpenApiTest.java",
                """
                package com.example;

                final class OpenApiTest {
                    private final com.example.generated.GeneratedApi api =
                            new com.example.generated.GeneratedApi();
                }
                """);
        source(
                root,
                "src/test/openapi/api.yaml",
                "openapi: 3.1.0\ninfo:\n  title: Test\n  version: 1\npaths: {}\n");
        write(root.resolve("zolt.lock"), cache, lockfile(cache, null, false, true, false));
        return root;
    }

    static Path projectRunnerProject(
            Path root,
            Path cache) throws IOException {
        Files.createDirectories(root);
        writeFakeConsoleJar(cache.resolve(JUNIT_JAR));
        GeneratedTestToolJarFixture.writeRuntimeMarker(
                cache.resolve(RUNTIME_JAR),
                root.resolve("runtime-tool-work"));
        Files.writeString(
                root.resolve("zolt.toml"),
                project("project-runner-test")
                        + """

                        [dependencies.runtime]
                        "com.example:generator-runtime" = "1.0.0"

                        [generated.test.fixtures]
                        kind = "exec"
                        tool = "project"
                        mainClass = "com.example.ProjectGenerator"
                        inputs = ["target/classes"]
                        output = "target/generated/test-resources"
                        produces = "test-resources"
                        required = false
                        """);
        source(
                root,
                "src/main/java/com/example/ProjectGenerator.java",
                """
                package com.example;

                import java.nio.file.Files;
                import java.nio.file.Path;

                public final class ProjectGenerator {
                    public static void main(String[] args) throws Exception {
                        Class.forName(
                                "com.example.runtime.GeneratorRuntime");
                        Path output = Path.of(
                                System.getenv("ZOLT_OUTPUT_DIR"))
                                .resolve("runtime.txt");
                        Files.createDirectories(output.getParent());
                        Files.writeString(output, "runtime-present\\n");
                    }
                }
                """);
        source(
                root,
                "src/test/java/com/example/ProjectRunnerTest.java",
                "package com.example; final class ProjectRunnerTest {}\n");
        write(root.resolve("zolt.lock"), cache, lockfile(cache, null, false, false, true));
        return root;
    }

    private static void writeCommonTooling(Path cache)
            throws IOException {
        writeFakeConsoleJar(cache.resolve(JUNIT_JAR));
        GeneratedTestToolJarFixture.writeJvmGenerator(
                cache.resolve(JVM_TOOL_JAR),
                cache.resolve("fixture-generator-work"));
    }

    private static String project(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.example"
                java = %s
                """.formatted(name, currentJavaMajorVersion());
    }

    private static String jvmGeneratorConfig() {
        return """
                [generated.tools.fixture-generator]
                kind = "jvm"
                coordinates = [
                  { coordinate = "com.example:fixture-generator", version = "1.0.0" }
                ]
                mainClass = "com.example.tool.FixtureGenerator"

                [generated.test.fixtures]
                kind = "exec"
                tool = "fixture-generator"
                inputs = ["fixtures.txt"]
                args = [
                  "com/example/generated/GeneratedFixture.java",
                  "package com.example.generated; public final class GeneratedFixture {}"
                ]
                output = "target/generated/test-fixtures"
                produces = "test-sources"
                required = false
                """;
    }

    private static String integrationConfig() {
        return """

                [test.integration]
                sources = ["src/integration-test/java"]
                """;
    }

    private static String lockfile(
            Path cache,
            String member,
            boolean jvm,
            boolean openApi,
            boolean runtime) throws IOException {
        String membership = member == null
                ? ""
                : "members = [\"" + member + "\"]\n";
        StringBuilder lock = new StringBuilder("version = 7\n\n");
        if (runtime) {
            lock.append("""
                    [[dependencyRoot]]
                    member = "%s"
                    id = "com.example:generator-runtime"
                    version = "1.0.0"
                    lane = "runtime"
                    resolvedScope = "runtime"

                    """.formatted(member == null ? "." : member));
        }
        dependency(
                lock,
                cache,
                "org.junit.platform:junit-platform-console-standalone",
                "1.11.4",
                "test",
                JUNIT_JAR,
                "",
                membership,
                false);
        if (jvm) {
            dependency(
                    lock,
                    cache,
                    "com.example:fixture-generator",
                    "1.0.0",
                    "tool-exec",
                    JVM_TOOL_JAR,
                    "toolGroups = [\"fixture-generator\"]\n",
                    membership,
                    true);
        }
        if (openApi) {
            dependency(
                    lock,
                    cache,
                    "com.example:openapi-generator",
                    "1.0.0",
                    "tool-openapi",
                    OPENAPI_TOOL_JAR,
                    "",
                    membership,
                    true);
        }
        if (runtime) {
            dependency(
                    lock,
                    cache,
                    "com.example:generator-runtime",
                    "1.0.0",
                    "runtime",
                    RUNTIME_JAR,
                    "",
                    membership,
                    true);
        }
        return lock.toString();
    }

    private static void dependency(
            StringBuilder lock,
            Path cache,
            String id,
            String version,
            String scope,
            String jar,
            String extra,
            String membership,
            boolean direct) throws IOException {
        Path source = cache.resolve(jar);
        String digest = sha256(source);
        String relative = "blobs/v2/sha256/" + digest + "/" + source.getFileName();
        Path target = cache.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        lock.append("[[package]]\n")
                .append("id = \"").append(id).append("\"\n")
                .append("version = \"").append(version).append("\"\n")
                .append("source = \"maven-central\"\n")
                .append("scope = \"").append(scope).append("\"\n")
                .append("direct = ").append(direct).append('\n')
                .append("jar = \"").append(relative).append("\"\n")
                .append("jarSha256 = \"").append(digest).append("\"\n")
                .append(extra)
                .append("dependencies = []\n")
                .append(membership)
                .append('\n');
    }

    private static void source(
            Path root,
            String relative,
            String content) throws IOException {
        Path path = root.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        return parts.length >= 2 && "1".equals(parts[0])
                ? parts[1]
                : parts[0];
    }
}
