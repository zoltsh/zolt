package sh.zolt.cli.nativeimage;

import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.build.compile.JavacRunner;
import sh.zolt.classpath.Classpath;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

final class NativeCommandTestSupport {
    private NativeCommandTestSupport() {
    }

    static void writeProjectConfigWithoutMain(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s

                [repositories.test]
                url = "%s"

                [dependencies]

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    static void writeProjectConfigWithMain(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [dependencies]

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    static void writeSpringBootProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "4.0.6"

                [dependencies]
                "org.springframework.boot:spring-boot-starter-webmvc" = {}

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    static void writeExplicitSpringBootNativeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [framework.spring-boot]
                native = true

                [dependencies]

                [dependencies.test]
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
    }

    static void writePersistentSpringBootNativeProjectConfig(Path projectDir, String repositoryUrl) throws IOException {
        Files.createDirectories(projectDir);
        Files.writeString(projectDir.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.Main"

                [repositories.test]
                url = "%s"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "3.3.6"

                [package]
                mode = "spring-boot"

                [framework.spring-boot]
                native = true
                """.formatted(currentJavaMajorVersion(), repositoryUrl));
        Path source = projectDir.resolve("src/main/java/com/example/Main.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package com.example;

                public final class Main {
                    public static void main(String[] args) {
                    }
                }
                """);
    }

    static byte[] fakeSpringBootLoaderJar() throws IOException {
        return jarWithEntry("org/springframework/boot/loader/launch/JarLauncher.class", new byte[] {0});
    }

    static byte[] fakeSpringBootAotJar(Path workDirectory) throws IOException {
        Path source = workDirectory.resolve("src/org/springframework/boot/SpringApplicationAotProcessor.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package org.springframework.boot;

                public final class SpringApplicationAotProcessor {
                    public static void main(String[] args) throws Exception {
                        String classpath = System.getProperty("java.class.path", "");
                        if (classpath.contains("spring-boot-loader")) {
                            throw new IllegalStateException(
                                    "native AOT classpath contains archive loader: " + classpath);
                        }
                        java.nio.file.Path generated = java.nio.file.Path.of(args[1])
                                .resolve("com/example/Main__BeanDefinitions.java");
                        java.nio.file.Files.createDirectories(generated.getParent());
                        java.nio.file.Files.writeString(
                                generated,
                                "package com.example; final class Main__BeanDefinitions {}\\n");
                    }
                }
                """);
        Path classes = workDirectory.resolve("classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                classes);
        Path compiled = classes.resolve("org/springframework/boot/SpringApplicationAotProcessor.class");
        return jarWithEntry(
                "org/springframework/boot/SpringApplicationAotProcessor.class",
                Files.readAllBytes(compiled));
    }

    static byte[] fakeSpringBootRecordingAotJar(Path workDirectory) throws IOException {
        Path source = workDirectory.resolve("src/org/springframework/boot/SpringApplicationAotProcessor.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, """
                package org.springframework.boot;

                public final class SpringApplicationAotProcessor {
                    public static void main(String[] args) throws Exception {
                        java.nio.file.Path sources = java.nio.file.Path.of(args[1]);
                        java.nio.file.Files.createDirectories(sources);
                        java.nio.file.Files.writeString(
                                sources.resolve("aot-classpath.txt"),
                                System.getProperty("java.class.path", ""));
                        java.nio.file.Path generated = sources
                                .resolve("com/example/Main__BeanDefinitions.java");
                        java.nio.file.Files.createDirectories(generated.getParent());
                        java.nio.file.Files.writeString(
                                generated,
                                "package com.example; final class Main__BeanDefinitions {}\\n");
                    }
                }
                """);
        Path classes = workDirectory.resolve("classes");
        new JavacRunner().compile(
                currentJavac(),
                List.of(source),
                new Classpath(List.of()),
                classes);
        return jarWithEntry(
                "org/springframework/boot/SpringApplicationAotProcessor.class",
                Files.readAllBytes(classes.resolve(
                        "org/springframework/boot/SpringApplicationAotProcessor.class")));
    }

    private static byte[] jarWithEntry(String name, byte[] content) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes)) {
            output.putNextEntry(new JarEntry(name));
            output.write(content);
            output.closeEntry();
        }
        return bytes.toByteArray();
    }

    static void writeWorkspaceNativeFixture(Path workspaceDir) throws IOException {
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace-native"

                [workspace.members]
                default = ["apps/api"]
                include = ["apps/api", "modules/core"]
                """);
        Files.writeString(coreDir.resolve("zolt.toml"), """
                [project]
                name = "core"
                version = "0.1.0"
                group = "com.example"
                java = %s
                """.formatted(currentJavaMajorVersion()));
        Files.createDirectories(coreDir.resolve("src/main/java/com/example/core"));
        Files.writeString(coreDir.resolve("src/main/java/com/example/core/Core.java"), """
                package com.example.core;

                public final class Core {
                    public static String message() {
                        return "core";
                    }
                }
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = %s
                main = "com.example.api.Api"

                [dependencies]
                "com.example:core" = { workspace = true }
                """.formatted(currentJavaMajorVersion()));
        Files.createDirectories(apiDir.resolve("src/main/java/com/example/api"));
        Files.writeString(apiDir.resolve("src/main/java/com/example/api/Api.java"), """
                package com.example.api;

                import com.example.core.Core;

                public final class Api {
                    public static void main(String[] args) {
                        System.out.println(Core.message());
                    }
                }
                """);
    }

    static Path writeFakeNativeImage(Path binary) throws IOException {
        Files.writeString(binary, """
                #!/usr/bin/env bash
                set -euo pipefail

                classpath=""
                output=""
                while [[ "$#" -gt 0 ]]; do
                  case "$1" in
                    -cp)
                      shift
                      classpath="$1"
                      ;;
                    -o)
                      shift
                      output="$1"
                      ;;
                  esac
                  shift || true
                done

                mkdir -p "$(dirname "$output")"
                printf 'native\\n' > "$output"
                chmod +x "$output"
                printf 'executable=%s\\n' "$0"
                printf 'classpath=%s\\n' "$classpath"
                printf 'output=%s\\n' "$output"
                """);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    static Path writeFakeNativeBinary(Path projectDir) throws IOException {
        Path binary = projectDir.resolve("target/native/zolt");
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, """
                #!/usr/bin/env bash
                set -euo pipefail

                command="${1:-}"
                shift || true

                option_value() {
                  local name="$1"
                  shift
                  while [[ "$#" -gt 0 ]]; do
                    if [[ "$1" == "$name" ]]; then
                      shift
                      printf '%s\\n' "$1"
                      return
                    fi
                    shift
                  done
                }

                case "$command" in
                  --version)
                    printf '0.1.0\\n'
                    ;;
                  help)
                    printf 'help\\n'
                    ;;
                  release-archive)
                    cwd="$(option_value --cwd "$@")"
                    output="$(option_value --output "$@")"
                    case "$output" in
                      /*)
                        printf 'Invalid --output path `%s`. Use a project-relative path.\\n' "$output" >&2
                        exit 7
                        ;;
                    esac
                    mkdir -p "$cwd/$output"
                    printf 'archive\\n' > "$cwd/$output/demo-0.1.0-linux-x64.tar.gz"
                    ;;
                  release-verify)
                    printf 'verified\\n'
                    ;;
                  init)
                    cwd="$(option_value --cwd "$@")"
                    mkdir -p "$cwd/hello-native"
                    printf '[project]\\n' > "$cwd/hello-native/zolt.toml"
                    ;;
                  version)
                    action="${1:-}"
                    shift || true
                    cwd="$(option_value --cwd "$@")"
                    if [[ "$action" == "set" ]]; then
                      printf '[project]\\n\\n[versions]\\nnative-smoke = "0.0.1"\\n' > "$cwd/zolt.toml"
                    else
                      printf '[project]\\n' > "$cwd/zolt.toml"
                    fi
                    ;;
                  resolve|build)
                    printf 'ok\\n'
                    ;;
                  package)
                    cwd="$(option_value --cwd "$@")"
                    mkdir -p "$cwd/target"
                    printf 'jar\\n' > "$cwd/target/hello-native-0.1.0.jar"
                    ;;
                  run|run-package)
                    printf 'Hello from hello-native!\\n'
                    ;;
                  *)
                    printf 'unexpected command: %s\\n' "$command" >&2
                    exit 2
                    ;;
                esac
                """);
        assertTrue(binary.toFile().setExecutable(true));
        return binary;
    }

    private static String currentJavaMajorVersion() {
        String version = System.getProperty("java.version");
        String[] parts = version.split("[._+-]", -1);
        if (parts.length >= 2 && "1".equals(parts[0])) {
            return parts[1];
        }
        return parts[0];
    }

    private static Path currentJavac() {
        return Path.of(System.getProperty("java.home"))
                .resolve("bin")
                .resolve(executable("javac"));
    }

    private static String executable(String name) {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? name + ".exe"
                : name;
    }
}
