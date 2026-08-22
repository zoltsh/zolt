package sh.zolt.cli;

import static sh.zolt.cli.CliTestSupport.memberConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WorkspaceCommandFixture {
    private WorkspaceCommandFixture() {
    }

    public static WorkspaceApplicationFixture create(Path tempDir, String name) throws IOException {
        Path workspaceDir = tempDir.resolve(name);
        Path apiDir = workspaceDir.resolve("apps/api");
        Path coreDir = workspaceDir.resolve("modules/core");
        Files.createDirectories(apiDir);
        Files.createDirectories(coreDir);
        Files.writeString(workspaceDir.resolve("zolt.toml"), """
                [workspace]
                name = "workspace"

                [workspace.members]
                include = ["apps/api", "modules/core"]
                """);
        Files.writeString(coreDir.resolve("zolt.toml"), memberConfig("core"));
        Path coreSource = coreDir.resolve("src/main/java/com/example/core/Core.java");
        Files.createDirectories(coreSource.getParent());
        Files.writeString(coreSource, """
                package com.example.core;

                public final class Core {
                    private Core() {
                    }

                    public static String message() {
                        return "core";
                    }
                }
                """);
        Files.writeString(apiDir.resolve("zolt.toml"), memberConfig("api") + """
                main = "com.example.api.Api"

                [dependencies]
                "com.example:core" = { workspace = true }
                """);
        Path apiSource = apiDir.resolve("src/main/java/com/example/api/Api.java");
        Files.createDirectories(apiSource.getParent());
        Files.writeString(apiSource, """
                package com.example.api;

                import com.example.core.Core;

                public final class Api {
                    private Api() {
                    }

                    public static void main(String[] args) {
                        System.out.println(Core.message() + ":" + args[0]);
                    }
                }
                """);
        return new WorkspaceApplicationFixture(workspaceDir, apiDir, coreDir);
    }

    public record WorkspaceApplicationFixture(Path workspaceDir, Path apiDir, Path coreDir) {
    }
}
