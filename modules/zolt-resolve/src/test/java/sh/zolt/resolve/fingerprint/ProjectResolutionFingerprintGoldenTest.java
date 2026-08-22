package sh.zolt.resolve.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Byte-exact pin of every {@link ProjectResolutionFingerprint} identity literal.
 *
 * <p>The fingerprint is checked-in lock identity: any drift in a category name, a legacy section
 * spelling, a frozen package-mode token, a framework label, or the generated-source field encoding
 * silently restates every lock in every repository that uses Zolt. This golden makes such a drift a
 * failing test rather than a silent re-resolve, so the schema version can only move deliberately.
 *
 * <p>The fixture is a workspace member so that the five workspace lane families and the shared
 * root-owned repository universe reach the fingerprint alongside the eight external lanes.
 */
final class ProjectResolutionFingerprintGoldenTest {
    private static final String GOLDEN = "/golden/project-resolution-fingerprint-v2.golden";

    private final ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();

    @Test
    void projectResolutionFingerprintV2MatchesGolden() {
        assertEquals(golden(), rendered(ProjectResolutionFingerprint.inputs(member())));
    }

    /**
     * The two frozen package-mode tokens are lock identity rather than display symbols, so the
     * golden pins {@code spring-boot} and this pins its only counterpart.
     */
    @Test
    void nonSpringBootArchiveModesFingerprintAsTheFrozenThinToken() {
        List<String> springBoot = ProjectResolutionFingerprint.inputs(member());
        List<String> thin = ProjectResolutionFingerprint.inputs(
                member(memberToml().replace("mode = \"spring-boot\"", "mode = \"uber-jar\"")));

        assertEquals(
                springBoot.stream().map(line -> line.replace("package\tmode\tspring-boot", "package\tmode\tthin")).toList(),
                thin);
    }

    private ProjectConfig member() {
        return member(memberToml());
    }

    /** Composes {@code memberToml} as the {@code apps/api} member of the golden workspace. */
    private ProjectConfig member(String memberToml) {
        Map<WorkspaceMemberPath, AuthoredManifest> members = new LinkedHashMap<>();
        WorkspaceMemberPath api = new WorkspaceMemberPath("apps/api");
        members.put(api, manifestLoader.document(memberToml).authored());
        members.put(new WorkspaceMemberPath("modules/core"), provider("core"));
        members.put(new WorkspaceMemberPath("modules/proc"), provider("proc"));
        members.put(new WorkspaceMemberPath("modules/testkit"), provider("testkit"));
        members.put(new WorkspaceMemberPath("modules/tools"), provider("tools"));
        EffectiveWorkspace workspace = new EffectiveManifestComposer()
                .composeWorkspace(manifestLoader.document(rootToml()).authored(), members);
        return new EffectiveProjectConfigAdapter().adapt(
                workspace.members().get(api),
                EffectiveProjectConfigAdapter.workspacePaths(workspace, api));
    }

    private AuthoredManifest provider(String name) {
        return manifestLoader.document("""
                [project]
                name = "%s"
                """.formatted(name)).authored();
    }

    private static String rendered(List<String> inputs) {
        return String.join("\n", inputs) + "\n";
    }

    private static String golden() {
        try (InputStream stream = ProjectResolutionFingerprintGoldenTest.class.getResourceAsStream(GOLDEN)) {
            if (stream == null) {
                throw new IllegalStateException("Missing golden resource " + GOLDEN);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String rootToml() {
        return """
                [workspace]
                name = "fingerprint-golden"

                [workspace.members]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21

                [repositories]
                central = false
                order = ["internal", "company"]

                [repositories.company]
                url = "https://repo.acme.example/company"
                credentials = "company-basic"

                [repositories.internal]
                url = "https://repo.acme.example/internal"
                credentials = "internal-token"

                [credentials.company-basic]
                usernameEnv = "ACME_REPO_USER"
                passwordEnv = "ACME_REPO_PASSWORD"

                [credentials.internal-token]
                tokenEnv = "ACME_INTERNAL_TOKEN"

                [versions]
                guava = "33.4.0-jre"
                netty = "4.1.119.Final"
                openapi = "7.11.0"
                spring = "3.3.6"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = { versionRef = "spring" }
                """;
    }

    static String memberToml() {
        return """
                [project]
                name = "api"

                [dependencies]
                "com.google.guava:guava" = { versionRef = "guava" }
                "io.netty:netty-transport-native-epoll" = { versionRef = "netty", classifier = "linux-x86_64" }
                "com.example:managed-impl" = { managed = true }
                "com.example:core" = { workspace = true }

                [dependencies.api]
                "com.example:api-dep" = { version = "1.0.0", type = "zip" }
                "com.example:explicit-jar" = { version = "1.1.0", type = "jar" }
                "com.example:tools" = { workspace = true }

                [dependencies.runtime]
                "com.example:runtime-dep" = { version = "2.0.0", classifier = "shaded", type = "tar.gz" }

                [dependencies.provided]
                "com.example:provided-dep" = { version = "3.0.0", exclude = ["commons-logging:commons-logging"] }

                [dependencies.dev]
                "com.example:dev-dep" = { version = "4.0.0", classifier = "tests" }

                [dependencies.test]
                "com.example:test-dep" = { version = "5.0.0", type = "test-jar" }
                "com.example:testkit" = { workspace = true }

                [dependencies.processor]
                "org.mapstruct:mapstruct-processor" = { version = "1.6.3", classifier = "jdk21" }
                "com.example:proc" = { workspace = true }

                [dependencies.test-processor]
                "com.example:test-proc-dep" = { version = "6.0.0", classifier = "shaded" }
                "com.example:proc" = { workspace = true }

                [dependencies.constraints]
                "com.example:pinned" = { version = "7.0.0", reason = "security floor" }

                [dependencies.policy]
                conflicts = "fail"
                deny = [{ coordinate = "log4j:log4j", reason = "known critical advisory" }]

                [generated.tools.openapi]
                versionRef = "openapi"

                [generated.tools.codegen]
                kind = "process"
                binary = "codegen"
                versionCommand = ["codegen", "--version"]
                allowUnpinnedTool = true

                [generated.main.public-api]
                kind = "openapi"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                apiPackage = "com.example.api"
                required = true

                [generated.main.protos]
                kind = "protobuf"
                inputs = ["src/main/proto/service.proto"]
                output = "target/generated/sources/protobuf/main"
                javaPackage = "com.example.proto"

                [generated.test.fixtures]
                kind = "exec"
                tool = "codegen"
                inputs = ["src/test/fixtures/schema.sql"]
                args = ["--out", "target/generated/sources/exec/fixtures"]
                output = "target/generated/sources/exec/fixtures"
                produces = "test-sources"

                [package]
                mode = "spring-boot"

                [framework.spring-boot]
                native = false
                """;
    }
}
