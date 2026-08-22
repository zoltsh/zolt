package sh.zolt.cli.workspace;

import static sh.zolt.cli.CliTestSupport.writeFakeConsoleJar;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarOutputStream;
import sh.zolt.cli.ContentAddressedLockTestSupport;

/**
 * A two-member workspace whose ONLY lockfile is the root one, built so a member-directory command has
 * something to get wrong.
 *
 * <p>{@code apps/api} depends on {@code libs/core} as a workspace member and on an external of its
 * own; {@code libs/core} depends on a second external that {@code apps/api} must never see. The root
 * lock attributes each external to exactly one member, so a projection that ignores
 * {@code LockPackage.members} is visible as a sibling library on the wrong classpath rather than as a
 * subtle ordering difference.
 */
final class MemberDirectoryFixture {
    static final String API_MEMBER = "apps/api";
    static final String CORE_MEMBER = "libs/core";
    static final String API_ONLY = "com.example:api-only";
    static final String SIBLING_ONLY = "com.example:sibling-only";

    private static final String API_ONLY_JAR = "com/example/api-only/1.0.0/api-only-1.0.0.jar";
    private static final String SIBLING_ONLY_JAR = "com/example/sibling-only/1.0.0/sibling-only-1.0.0.jar";
    private static final String CONSOLE_JAR =
            "org/junit/platform/junit-platform-console-standalone/1.11.4/"
                    + "junit-platform-console-standalone-1.11.4.jar";

    private MemberDirectoryFixture() {
    }

    record Fixture(Path workspaceDir, Path apiDir, Path coreDir, Path cacheRoot) {
        Path rootLock() {
            return workspaceDir.resolve("zolt.lock");
        }

        Path memberLock() {
            return apiDir.resolve("zolt.lock");
        }

        Path apiClass() {
            return apiDir.resolve("target/classes/com/example/api/Api.class");
        }

        Path coreClass() {
            return coreDir.resolve("target/classes/com/example/core/Core.class");
        }
    }

    static Fixture create(Path tempDir) throws IOException {
        Path workspaceDir = tempDir.resolve("workspace");
        Path apiDir = workspaceDir.resolve(API_MEMBER);
        Path coreDir = workspaceDir.resolve(CORE_MEMBER);
        Path cacheRoot = tempDir.resolve("cache");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        writeEmptyJar(cacheRoot.resolve(API_ONLY_JAR));
        writeEmptyJar(cacheRoot.resolve(SIBLING_ONLY_JAR));
        writeFakeConsoleJar(cacheRoot.resolve(CONSOLE_JAR));

        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "platform"

                [workspace.members]
                include = ["apps/*", "libs/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = %s
                """.formatted(Runtime.version().feature()));

        Files.writeString(coreDir.resolve("zolt.toml"), """
                [project]
                name = "core"

                [dependencies]
                "%s" = "1.0.0"
                """.formatted(SIBLING_ONLY));
        write(coreDir.resolve("src/main/java/com/example/core/Core.java"), """
                package com.example.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);

        Files.writeString(apiDir.resolve("zolt.toml"), """
                [project]
                name = "api"
                main = "com.example.api.Api"

                [dependencies]
                "com.example:core" = { workspace = true }
                "%s" = "1.0.0"
                """.formatted(API_ONLY));
        write(apiDir.resolve("src/main/java/com/example/api/Api.java"), """
                package com.example.api;

                import com.example.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static String message() {
                        return Core.message();
                    }

                    public static void main(String[] args) {
                        System.out.println("api:" + message());
                    }
                }
                """);
        write(apiDir.resolve("src/test/java/com/example/api/ApiTest.java"), """
                package com.example.api;

                public final class ApiTest {
                    public String message() {
                        return Api.message();
                    }
                }
                """);
        write(apiDir.resolve("src/integration-test/java/com/example/api/ApiIT.java"), """
                package com.example.api;

                public final class ApiIT {
                    public String message() {
                        return Api.message();
                    }
                }
                """);

        writeRootLock(workspaceDir, cacheRoot);
        return new Fixture(workspaceDir, apiDir, coreDir, cacheRoot);
    }

    /**
     * The workspace's one authoritative lock. Every external names the single member that declared it,
     * which is what a member projection must read.
     */
    private static void writeRootLock(Path workspaceDir, Path cacheRoot) throws IOException {
        ContentAddressedLockTestSupport.write(workspaceDir.resolve("zolt.lock"), cacheRoot, """
                version = 7

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.example:api-only"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.example:core"
                version = "0.1.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "libs/core"
                id = "com.example:sibling-only"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:api-only"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "%s"
                dependencies = []
                members = ["apps/api"]

                [[package]]
                id = "com.example:sibling-only"
                version = "1.0.0"
                source = "maven-central"
                scope = "compile"
                direct = true
                jar = "%s"
                dependencies = []
                members = ["libs/core"]

                [[package]]
                id = "com.example:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "libs/core"
                workspaceOutput = "target/classes"
                dependencies = []
                members = ["apps/api"]

                [[package]]
                id = "org.junit.platform:junit-platform-console-standalone"
                version = "1.11.4"
                source = "maven-central"
                scope = "test"
                direct = false
                jar = "%s"
                dependencies = []
                members = ["apps/api", "libs/core"]
                """.formatted(API_ONLY_JAR, SIBLING_ONLY_JAR, CONSOLE_JAR));
    }

    /** A real (empty) archive, so a locked external on a javac classpath opens as a jar. */
    static void writeEmptyJar(Path jar) throws IOException {
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.flush();
        }
    }

    static Path writeFakeNativeImage(Path binary) throws IOException {
        Files.createDirectories(binary.getParent());
        Files.writeString(binary, """
                #!/usr/bin/env bash
                set -euo pipefail

                output=""
                while [[ "$#" -gt 0 ]]; do
                  case "$1" in
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
                """);
        if (!binary.toFile().setExecutable(true)) {
            throw new IOException("Could not mark the fake native-image executable: " + binary);
        }
        return binary;
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
    }
}
