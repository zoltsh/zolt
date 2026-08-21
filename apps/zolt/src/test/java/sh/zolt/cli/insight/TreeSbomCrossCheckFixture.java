package sh.zolt.cli.insight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A member-sensitive two-member workspace whose root lock exercises everything the dependency
 * submission action cross-checks: a first-party member package, one coordinate in two scopes, a
 * shared external whose children differ per member, a collapsed child list that is strictly larger
 * than the union of the member graphs, a classified jar, a non-default artifact type, a legacy
 * bare-GAV edge, and one occurrence in every SBOM scope group.
 */
final class TreeSbomCrossCheckFixture {
    static final String WORKSPACE_NAME = "cross-check-workspace";

    private TreeSbomCrossCheckFixture() {
    }

    static Path write(Path root) throws IOException {
        Path core = root.resolve("modules/core");
        Path api = root.resolve("apps/api");
        Files.createDirectories(core);
        Files.createDirectories(api);
        Files.writeString(root.resolve("zolt.toml"), """
                [workspace]
                name = "%s"
                members = ["modules/core", "apps/api"]
                """.formatted(WORKSPACE_NAME));
        Files.writeString(core.resolve("zolt.toml"), memberConfig("core"));
        Files.writeString(api.resolve("zolt.toml"), memberConfig("api") + """

                [dependencies]
                "com.acme:core" = { workspace = "modules/core" }
                """);
        Files.writeString(root.resolve("zolt.lock"), lock());
        return root;
    }

    private static String memberConfig(String name) {
        return """
                [project]
                name = "%s"
                version = "0.1.0"
                group = "com.acme"
                java = "21"
                """.formatted(name);
    }

    /**
     * No entry carries a {@code pom}, so the SBOM never consults the artifact cache for licenses and
     * the cross-check stays a pure read of the two projections.
     */
    private static String lock() {
        return "version = 7\n"
                + dependencyRoots()
                + workspacePackage()
                + sharedPackages()
                + toolPackages()
                + testPackages()
                + optionalScopePackages()
                + memberGraphs();
    }

    private static String dependencyRoots() {
        return """

                [[dependencyRoot]]
                member = "apps/api"
                id = "com.acme:core"
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
                variant = "jar|tests"
                lane = "test"
                resolvedScope = "test"

                [[dependencyRoot]]
                member = "modules/core"
                id = "org.example:shared"
                version = "1.0.0"
                lane = "implementation"
                resolvedScope = "compile"

                [[dependencyRoot]]
                member = "modules/core"
                id = "org.example:harness"
                version = "5.0.0"
                lane = "test"
                resolvedScope = "test"

                [[dependencyRoot]]
                member = "apps/api"
                id = "org.example:harness"
                version = "5.0.0"
                lane = "test"
                resolvedScope = "test"

                [[dependencyRoot]]
                member = "apps/api"
                id = "org.example:shim"
                version = "7.0.0"
                lane = "provided"
                resolvedScope = "provided"

                [[dependencyRoot]]
                member = "apps/api"
                id = "org.example:devtool"
                version = "8.0.0"
                lane = "dev"
                resolvedScope = "dev"
                """;
    }

    private static String workspacePackage() {
        return """

                [[package]]
                id = "com.acme:core"
                version = "0.1.0"
                source = "workspace"
                scope = "compile"
                direct = true
                workspace = "modules/core"
                workspaceOutput = "target/classes"
                members = ["apps/api"]
                dependencies = ["org.example:shared:1.0.0:jar:compile"]
                """;
    }

    /**
     * `shared` is consumed by both members with different children, and its collapsed list also names
     * `orphan`, which no member graph reaches. The SBOM sources children per member, so the tree must
     * not surface that collapsed-only edge.
     */
    private static String sharedPackages() {
        return jar("org.example:shared", "1.0.0", "compile", true, "shared-1.0.0.jar",
                        "[\"modules/core\", \"apps/api\"]",
                        "[\"org.example:extra:2.0.0:jar:compile\", \"org.example:other:4.0.0:jar:compile\", "
                                + "\"org.example:orphan:9.0.0:jar:compile\"]")
                + jar("org.example:shared", "1.0.0", "test", true, "shared-1.0.0-tests.jar",
                        "[\"modules/core\"]", "[]")
                + jar("org.example:extra", "2.0.0", "compile", false, "extra-2.0.0.jar",
                        "[\"apps/api\"]", "[]")
                + jar("org.example:other", "4.0.0", "compile", false, "other-4.0.0.jar",
                        "[\"modules/core\"]", "[\"org.example:bundle:3.0.0:zip:runtime\"]")
                + jar("org.example:orphan", "9.0.0", "compile", false, "orphan-9.0.0.jar",
                        "[\"modules/core\"]", "[]")
                + """

                [[package]]
                id = "org.example:bundle"
                version = "3.0.0"
                source = "test"
                scope = "runtime"
                direct = false
                artifact = "org/example/bundle/3.0.0/bundle-3.0.0.zip"
                artifactType = "zip"
                artifactSha256 = "7777"
                members = ["modules/core"]
                dependencies = []
                """;
    }

    /** A `runtime`-classified jar with a child, so the variant appears on an edge's source side. */
    private static String toolPackages() {
        return jar("org.example:agent", "0.9.0", "tool-coverage", false, "agent-0.9.0-runtime.jar",
                        "[\"modules/core\", \"apps/api\"]",
                        "[\"org.example:agent-core:0.9.0:jar:tool-coverage\"]")
                + jar("org.example:agent-core", "0.9.0", "tool-coverage", false, "agent-core-0.9.0.jar",
                        "[\"modules/core\", \"apps/api\"]", "[]");
    }

    /** `harness` reaches `fixtures` through a legacy version-1 bare-GAV edge. */
    private static String testPackages() {
        return jar("org.example:harness", "5.0.0", "test", true, "harness-5.0.0.jar",
                        "[\"modules/core\", \"apps/api\"]", "[\"org.example:fixtures:6.0.0\"]")
                + jar("org.example:fixtures", "6.0.0", "test", false, "fixtures-6.0.0.jar",
                        "[\"modules/core\", \"apps/api\"]", "[]");
    }

    private static String optionalScopePackages() {
        return jar("org.example:shim", "7.0.0", "provided", true, "shim-7.0.0.jar",
                        "[\"apps/api\"]", "[]")
                + jar("org.example:devtool", "8.0.0", "dev", true, "devtool-8.0.0.jar",
                        "[\"apps/api\"]", "[]");
    }

    private static String memberGraphs() {
        return """

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
                dependencies = ["org.example:other:4.0.0:jar:compile"]
                """;
    }

    private static String jar(
            String id,
            String version,
            String scope,
            boolean direct,
            String fileName,
            String members,
            String dependencies) {
        String artifactId = id.substring(id.indexOf(':') + 1);
        String path = id.substring(0, id.indexOf(':')).replace('.', '/')
                + "/" + artifactId + "/" + version + "/" + fileName;
        return """

                [[package]]
                id = "%s"
                version = "%s"
                source = "test"
                scope = "%s"
                direct = %s
                jar = "%s"
                jarSha256 = "sha-%s-%s"
                members = %s
                dependencies = %s
                """.formatted(
                id, version, scope, direct, path, artifactId, version, members, dependencies);
    }
}
