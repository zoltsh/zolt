package sh.zolt.cli.testcmd;

import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;
import static sh.zolt.cli.ContentAddressedLockTestSupport.write;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
        write(root.resolve("zolt.lock"), cache, lockfile(null, true, false, false));
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
                members = ["apps/app"]
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
        write(workspace.resolve("zolt.lock"), cache, lockfile("apps/app", true, false, false));
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

                        [generated.openapiTool]
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
        write(root.resolve("zolt.lock"), cache, lockfile(null, false, true, false));
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

                        [runtime.dependencies]
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
        write(root.resolve("zolt.lock"), cache, lockfile(null, false, false, true));
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
                java = "%s"

                """.formatted(name, currentJavaMajorVersion());
    }

    private static String jvmGeneratorConfig() {
        return """
                [generated.execTools.fixture-generator]
                runner = "jvm"
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

                [integrationTest]
                sources = ["src/integration-test/java"]
                output = "target/integration-test-classes"
                """;
    }

    private static String lockfile(
            String member,
            boolean jvm,
            boolean openApi,
            boolean runtime) {
        String membership = member == null
                ? ""
                : "members = [\"" + member + "\"]\n";
        StringBuilder lock = new StringBuilder(
                "version = " + (member == null ? "1" : "5") + "\n\n");
        dependency(
                lock,
                "org.junit.platform:junit-platform-console-standalone",
                "1.11.4",
                "test",
                JUNIT_JAR,
                "",
                membership);
        if (jvm) {
            dependency(
                    lock,
                    "com.example:fixture-generator",
                    "1.0.0",
                    "tool-exec",
                    JVM_TOOL_JAR,
                    "toolGroups = [\"fixture-generator\"]\n",
                    membership);
        }
        if (openApi) {
            dependency(
                    lock,
                    "com.example:openapi-generator",
                    "1.0.0",
                    "tool-openapi",
                    OPENAPI_TOOL_JAR,
                    "",
                    membership);
        }
        if (runtime) {
            dependency(
                    lock,
                    "com.example:generator-runtime",
                    "1.0.0",
                    "runtime",
                    RUNTIME_JAR,
                    "",
                    membership);
        }
        return lock.toString();
    }

    private static void dependency(
            StringBuilder lock,
            String id,
            String version,
            String scope,
            String jar,
            String extra,
            String membership) {
        lock.append("[[package]]\n")
                .append("id = \"").append(id).append("\"\n")
                .append("version = \"").append(version).append("\"\n")
                .append("source = \"maven-central\"\n")
                .append("scope = \"").append(scope).append("\"\n")
                .append("direct = true\n")
                .append("jar = \"").append(jar).append("\"\n")
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
