package sh.zolt.build.generatedsource;

import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.doctor.JdkStatus;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class ExecGeneratedSourceServiceTestSupport {
    private ExecGeneratedSourceServiceTestSupport() {
    }

    static void writeProjectFiles(Path projectDir) throws IOException {
        Files.createDirectories(projectDir.resolve("src/main/jooq"));
        Files.writeString(projectDir.resolve("src/main/jooq/config.xml"), "<configuration/>\n");
        Files.createDirectories(projectDir.resolve("cache/org/jooq/jooq-codegen/3.19.15"));
        Files.writeString(
                projectDir.resolve("cache/org/jooq/jooq-codegen/3.19.15/jooq-codegen-3.19.15.jar"), "tool\n");
        Files.createDirectories(projectDir.resolve("cache/com/example/app/1.0.0"));
        Files.writeString(projectDir.resolve("cache/com/example/app/1.0.0/app-1.0.0.jar"), "app\n");
    }

    static ExecGeneratedSourceService service(Path projectDir, ExecGeneratedSourceService.ProcessRunner runner) {
        return new ExecGeneratedSourceService(
                jdkChecker(projectDir),
                ":",
                runner);
    }

    /** Service whose curated environment reads ambient variables from the supplied map (inheritEnv/secretEnv). */
    static ExecGeneratedSourceService service(
            Path projectDir,
            ExecGeneratedSourceService.ProcessRunner runner,
            java.util.Map<String, String> ambientEnv) {
        return new ExecGeneratedSourceService(
                jdkChecker(projectDir),
                ":",
                runner,
                ambientEnv::get);
    }

    private static sh.zolt.doctor.JdkChecker jdkChecker(Path projectDir) {
        return requiredVersion -> new JdkStatus(
                Optional.empty(),
                Optional.of(projectDir.resolve("fake-java")),
                Optional.of(Path.of("javac")),
                Optional.of(Path.of("jar")),
                Optional.of(requiredVersion),
                requiredVersion);
    }

    /** A runner that writes one generated Java file into the step's ZOLT_OUTPUT_DIR and exits 0. */
    static ExecGeneratedSourceService.ProcessRunner generatingRunner(List<List<String>> commands) {
        return (command, directory, environment, timeout) -> {
            commands.add(command);
            try {
                Path output = Path.of(environment.get("ZOLT_OUTPUT_DIR"));
                Files.createDirectories(output.resolve("com/example/generated"));
                Files.writeString(output.resolve("com/example/generated/Model.java"), """
                        package com.example.generated;

                        public final class Model {
                        }
                        """);
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
            return new ExecGeneratedSourceService.ProcessResult(0, "generated\n", false);
        };
    }

    static ProjectConfig config() {
        return config("args = [\"src/main/jooq/config.xml\"]");
    }

    static ProjectConfig config(String stepExtras) {
        return new ManifestProjectConfigLoader().load("""
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [versions]
                jooq = "3.19.15"

                [generated.tools.jooq]
                kind = "jvm"
                coordinates = [{ coordinate = "org.jooq:jooq-codegen", versionRef = "jooq" }]
                mainClass = "com.example.GenerationTool"

                [generated.main.model]
                kind = "exec"
                tool = "jooq"
                inputs = ["src/main/jooq/config.xml"]
                output = "target/generated/sources/jooq"
                produces = "java-sources"
                %s
                """.formatted(stepExtras));
    }

    static List<ResolvedClasspathPackage> packages(Path projectDir) {
        return packages(projectDir, "jooq");
    }

    /** Fixture packages whose jvm tool jar is tagged for the named exec tool (its per-tool group). */
    static List<ResolvedClasspathPackage> packages(Path projectDir, String toolName) {
        return List.of(
                toolPackage(
                        projectDir,
                        "org.jooq",
                        "jooq-codegen",
                        "3.19.15",
                        "cache/org/jooq/jooq-codegen/3.19.15/jooq-codegen-3.19.15.jar",
                        List.of(toolName)),
                packageWithScope(
                        projectDir,
                        "com.example",
                        "app",
                        "1.0.0",
                        DependencyScope.COMPILE,
                        "cache/com/example/app/1.0.0/app-1.0.0.jar"));
    }

    private static ResolvedClasspathPackage packageWithScope(
            Path projectDir,
            String group,
            String artifact,
            String version,
            DependencyScope scope,
            String jar) {
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        new PackageId(group, artifact),
                        version,
                        false,
                        Path.of(""),
                        projectDir.resolve(jar)),
                scope);
    }

    /** A tool-exec package tagged with the named exec tool groups whose isolated closure it belongs to. */
    static ResolvedClasspathPackage toolPackage(
            Path projectDir,
            String group,
            String artifact,
            String version,
            String jar,
            List<String> toolGroups) {
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        new PackageId(group, artifact),
                        version,
                        false,
                        Path.of(""),
                        projectDir.resolve(jar)),
                DependencyScope.TOOL_EXEC,
                toolGroups);
    }
}
