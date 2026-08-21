package sh.zolt.cli.insight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The committed `zolt tree` schema fixtures: a standalone project frozen at schema 1, and a two-member
 * workspace frozen at schema 3. Both locks are hand-written so the projections stay a pure read of
 * committed facts — no resolution, no network.
 */
final class TreeFixtures {
    static final String WORKSPACE_CONFIG = """
            [workspace]
            name = "demo-workspace"

            [workspace.members]
            include = ["modules/core", "apps/api"]
            """;

    private TreeFixtures() {
    }

    static String golden(String name) throws IOException {
        return new String(
                TreeFixtures.class.getResourceAsStream("/golden/" + name).readAllBytes(),
                StandardCharsets.UTF_8);
    }

    /** A standalone project whose lock carries a variant, a policy, a conflict, and a policy effect. */
    static Path standaloneProject(Path root) throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("zolt.toml"), """
                [project]
                name = "demo"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(root.resolve("zolt.lock"), """
                version = 7

                [[dependencyRoot]]
                member = "."
                id = "com.example:app"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[package]]
                id = "com.example:app"
                version = "1.0.0"
                source = "test"
                scope = "compile"
                direct = true
                jar = "com/example/app/1.0.0/app-1.0.0.jar"
                pom = "com/example/app/1.0.0/app-1.0.0.pom"
                jarSha256 = "aaaa"
                pomSha256 = "bbbb"
                policies = ["managed-version: com.example:app -> 1.0.0 from com.example:platform:1.0.0"]
                dependencies = ["com.example:lib:2.0.0:jar:compile", "org.example:agent:0.9.0:jar|runtime:compile"]

                [[package]]
                id = "com.example:lib"
                version = "2.0.0"
                source = "test"
                scope = "compile"
                direct = false
                jar = "com/example/lib/2.0.0/lib-2.0.0.jar"
                pom = "com/example/lib/2.0.0/lib-2.0.0.pom"
                jarSha256 = "cccc"
                pomSha256 = "dddd"
                dependencies = []

                [[package]]
                id = "org.example:agent"
                version = "0.9.0"
                source = "test"
                scope = "compile"
                direct = false
                jar = "org/example/agent/0.9.0/agent-0.9.0-runtime.jar"
                pom = "org/example/agent/0.9.0/agent-0.9.0.pom"
                jarSha256 = "eeee"
                pomSha256 = "ffff"
                dependencies = []

                [[conflict]]
                id = "com.example:lib"
                selected = "2.0.0"
                requested = ["1.0.0", "2.0.0"]
                reason = "newest version wins"

                [[policy]]
                kind = "global-exclusion"
                id = "commons-logging:commons-logging"
                requested = "1.2"
                source = "com.example:app:1.0.0"
                policy = "[dependencyPolicy].exclude commons-logging:commons-logging (Use jcl-over-slf4j)"
                """);
        return root;
    }

    /** The workspace members and root lock, without the workspace config the caller chooses to write. */
    static Path workspaceMembersAndLock(Path root) throws IOException {
        Path core = root.resolve("modules/core");
        Path api = root.resolve("apps/api");
        Files.createDirectories(core);
        Files.createDirectories(api);
        Files.writeString(core.resolve("zolt.toml"), """
                [project]
                name = "core"
                version = "0.1.0"
                group = "com.example"
                java = 21
                """);
        Files.writeString(api.resolve("zolt.toml"), """
                [project]
                name = "api"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [dependencies]
                "com.example:core" = { workspace = true }
                """);
        Files.writeString(root.resolve("zolt.lock"), workspaceLock());
        return root;
    }

    /**
     * One first-party member package, one coordinate in two scopes, a `jar|runtime` classified jar, a
     * `zip`-typed artifact, and an external whose child set differs between the two members.
     */
    static String workspaceLock() {
        return """
                version = 7

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.example:core"
                version = "0.1.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "apps/api"
                id = "org.example:shared"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "modules/core"
                id = "org.example:shared"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "modules/core"
                id = "org.example:shared"
                version = "1.0.0"
                variant = "jar|tests"
                lane = "test"
                resolvedScope = "test"

                [[dependencyRoot]]
                member = "apps/api"
                id = "org.example:bundle"
                version = "3.0.0"
                variant = "zip"
                lane = "runtime"
                resolvedScope = "runtime"

                [[package]]
                id = "com.example:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                members = ["apps/api"]
                dependencies = ["org.example:shared:1.0.0:jar:compile"]

                [[package]]
                id = "org.example:shared"
                version = "1.0.0"
                source = "test"
                scope = "compile"
                direct = true
                jar = "org/example/shared/1.0.0/shared-1.0.0.jar"
                pom = "org/example/shared/1.0.0/shared-1.0.0.pom"
                jarSha256 = "1111"
                pomSha256 = "2222"
                members = ["modules/core", "apps/api"]
                dependencies = ["org.example:extra:2.0.0:jar:compile"]

                [[package]]
                id = "org.example:shared"
                version = "1.0.0"
                source = "test"
                scope = "test"
                direct = true
                jar = "org/example/shared/1.0.0/shared-1.0.0-tests.jar"
                pom = "org/example/shared/1.0.0/shared-1.0.0.pom"
                jarSha256 = "1111"
                pomSha256 = "2222"
                members = ["modules/core"]
                dependencies = []

                [[package]]
                id = "org.example:extra"
                version = "2.0.0"
                source = "test"
                scope = "compile"
                direct = false
                jar = "org/example/extra/2.0.0/extra-2.0.0.jar"
                pom = "org/example/extra/2.0.0/extra-2.0.0.pom"
                jarSha256 = "3333"
                pomSha256 = "4444"
                members = ["apps/api"]
                dependencies = []

                [[package]]
                id = "org.example:agent"
                version = "0.9.0"
                source = "test"
                scope = "tool-coverage"
                direct = false
                jar = "org/example/agent/0.9.0/agent-0.9.0-runtime.jar"
                pom = "org/example/agent/0.9.0/agent-0.9.0.pom"
                jarSha256 = "5555"
                pomSha256 = "6666"
                members = ["modules/core", "apps/api"]
                dependencies = []

                [[package]]
                id = "org.example:bundle"
                version = "3.0.0"
                source = "test"
                scope = "runtime"
                direct = true
                pom = "org/example/bundle/3.0.0/bundle-3.0.0.pom"
                pomSha256 = "8888"
                artifact = "org/example/bundle/3.0.0/bundle-3.0.0.zip"
                artifactType = "zip"
                artifactSha256 = "7777"
                members = ["apps/api"]
                dependencies = []

                [[memberGraph]]
                member = "apps/api"
                id = "org.example:shared"
                version = "1.0.0"
                variant = "jar"
                scope = "compile"
                dependencies = ["org.example:extra:2.0.0:jar:compile"]

                [[memberGraph]]
                member = "modules/core"
                id = "org.example:shared"
                version = "1.0.0"
                variant = "jar"
                scope = "compile"
                dependencies = []
                """;
    }
}
