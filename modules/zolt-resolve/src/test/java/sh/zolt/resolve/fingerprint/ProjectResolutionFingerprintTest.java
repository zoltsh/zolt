package sh.zolt.resolve.fingerprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.adapter.EffectiveProjectConfigAdapter;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.effective.EffectiveManifestComposer;
import sh.zolt.manifest.effective.EffectiveWorkspace;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ProjectResolutionFingerprintTest {
    private final ManifestProjectConfigLoader manifestLoader = new ManifestProjectConfigLoader();

    @Test
    void sameResolutionInputsProduceSameFingerprintWhenTomlOrderChanges() {
        assertEquals(
                ProjectResolutionFingerprint.fingerprint(parse(baseToml())),
                ProjectResolutionFingerprint.fingerprint(parse(reorderedToml())));
    }

    @Test
    void fingerprintUsesSha256Prefix() {
        String fingerprint = ProjectResolutionFingerprint.fingerprint(parse(baseToml()));

        assertTrue(fingerprint.matches("sha256:[0-9a-f]{64}"));
    }

    @Test
    void fingerprintChangesWhenResolutionInputsChange() {
        String baseFingerprint = ProjectResolutionFingerprint.fingerprint(parse(baseToml()));

        List<Case> cases = List.of(
                new Case("repository", baseToml().replace("https://repo.acme.example/maven", "https://mirror.acme.example/maven")),
                new Case("repository credentials", baseToml().replace("ACME_REPO_PASSWORD", "ACME_REPO_SECRET")),
                new Case("dependency", baseToml().replace("33.4.0-jre", "33.4.1-jre")),
                new Case("platform", baseToml().replace("3.3.6", "3.3.7")),
                new Case("dependency conflict policy", baseToml().replace(
                        "[dependencies]",
                        "[dependencies.policy]\nconflicts = \"fail\"\n\n[dependencies]")),
                new Case("processor", baseToml().replace("1.6.3", "1.6.4")),
                new Case("generated source tool", baseToml().replace("7.11.0", "7.12.0")),
                new Case("generated source required flag", baseToml().replace("required = true", "required = false")),
                new Case("package tooling mode", baseToml().replace("mode = \"spring-boot\"", "mode = \"jar\"")),
                new Case("spring boot native setting", baseToml().replace("native = false", "native = true")),
                new Case("quarkus setting", baseToml().replace("mode = \"spring-boot\"", "mode = \"quarkus\"")),
                new Case("java input", baseToml().replace("java = 21", "java = 22")));

        for (Case testCase : cases) {
            assertNotEquals(
                    baseFingerprint,
                    ProjectResolutionFingerprint.fingerprint(parse(testCase.toml())),
                    testCase.name());
        }
    }

    @Test
    void archiveModesWithoutResolutionToolingShareTheThinFingerprint() {
        String thin = baseToml().replace("mode = \"spring-boot\"", "mode = \"jar\"");
        String uber = baseToml().replace("mode = \"spring-boot\"", "mode = \"uber-jar\"");
        assertEquals(
                ProjectResolutionFingerprint.fingerprint(parse(thin)),
                ProjectResolutionFingerprint.fingerprint(parse(uber)));
    }

    @Test
    void springBootJarAndWarShareTheSameResolutionFingerprint() {
        assertEquals(
                ProjectResolutionFingerprint.fingerprint(parse(baseToml())),
                ProjectResolutionFingerprint.fingerprint(parse(
                        baseToml().replace("mode = \"spring-boot\"", "mode = \"spring-boot-war\""))));
    }

    @Test
    void inputFingerprintsNameChangedInputCategories() {
        List<String> baseInputs = ProjectResolutionFingerprint.inputFingerprints(parse(baseToml()));
        List<String> changedRepositoryInputs = ProjectResolutionFingerprint.inputFingerprints(parse(
                baseToml().replace("https://repo.acme.example/maven", "https://mirror.acme.example/maven")));
        List<String> changedDependencyInputs = ProjectResolutionFingerprint.inputFingerprints(parse(
                baseToml().replace("33.4.0-jre", "33.4.1-jre")));

        assertNotEquals(valueFor(baseInputs, "repositories"), valueFor(changedRepositoryInputs, "repositories"));
        assertEquals(
                valueFor(baseInputs, "dependencies.compile"),
                valueFor(changedRepositoryInputs, "dependencies.compile"));
        assertNotEquals(
                valueFor(baseInputs, "dependencies.compile"),
                valueFor(changedDependencyInputs, "dependencies.compile"));
    }

    /**
     * The fingerprint hashes five workspace lane families, so a member's {@code workspace = true}
     * edges have to reach it: adding or moving one changes what the member resolves even though no
     * external coordinate moved (design §9.8).
     */
    @Test
    void workspaceDependencyLanesReachTheFingerprint() {
        ProjectConfig member = workspaceMember("""
                [project]
                name = "api"

                [dependencies]
                "com.example:core" = { workspace = true }
                """);

        List<String> inputs = ProjectResolutionFingerprint.inputs(member);

        assertTrue(
                inputs.stream().anyMatch(input ->
                        input.startsWith("workspaceCompile\t")
                                && input.contains("com.example:core")
                                && input.endsWith("modules/core")),
                () -> "expected the workspace compile edge and its provider path: " + inputs);
    }

    @Test
    void movingAWorkspaceEdgeToAnotherLaneChangesTheFingerprint() {
        String compile = ProjectResolutionFingerprint.fingerprint(workspaceMember("""
                [project]
                name = "api"

                [dependencies]
                "com.example:core" = { workspace = true }
                """));
        String test = ProjectResolutionFingerprint.fingerprint(workspaceMember("""
                [project]
                name = "api"

                [dependencies.test]
                "com.example:core" = { workspace = true }
                """));
        String none = ProjectResolutionFingerprint.fingerprint(workspaceMember("""
                [project]
                name = "api"
                """));

        assertNotEquals(compile, test);
        assertNotEquals(compile, none);
        assertNotEquals(test, none);
    }

    /** Composes {@code memberToml} as the {@code apps/api} member of a two-member workspace. */
    private ProjectConfig workspaceMember(String memberToml) {
        Map<WorkspaceMemberPath, AuthoredManifest> members = new LinkedHashMap<>();
        WorkspaceMemberPath api = new WorkspaceMemberPath("apps/api");
        members.put(api, manifestLoader.document(memberToml).authored());
        members.put(
                new WorkspaceMemberPath("modules/core"),
                manifestLoader.document("""
                        [project]
                        name = "core"
                        """).authored());
        AuthoredManifest root = manifestLoader.document("""
                [workspace]
                name = "fingerprint"

                [workspace.members]
                include = ["apps/*", "modules/*"]

                [workspace.project]
                group = "com.example"
                version = "0.1.0"
                java = 21
                """).authored();
        EffectiveWorkspace workspace = new EffectiveManifestComposer().composeWorkspace(root, members);
        return new EffectiveProjectConfigAdapter().adapt(
                workspace.members().get(api),
                EffectiveProjectConfigAdapter.workspacePaths(workspace, api));
    }

    private ProjectConfig parse(String toml) {
        return manifestLoader.load(toml);
    }

    private static String valueFor(List<String> inputs, String category) {
        return inputs.stream()
                .filter(input -> input.startsWith(category + "="))
                .findFirst()
                .orElseThrow();
    }

    private static String baseToml() {
        return """
                [project]
                name = "fingerprint-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [repositories]
                central = false

                [repositories.company]
                url = "https://repo.acme.example/maven"
                credentials = "company-artifactory"

                [credentials.company-artifactory]
                usernameEnv = "ACME_REPO_USER"
                passwordEnv = "ACME_REPO_PASSWORD"

                [versions]
                guava = "33.4.0-jre"
                mapstruct = "1.6.3"
                openapi = "7.11.0"
                spring = "3.3.6"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = { versionRef = "spring" }

                [dependencies]
                "com.google.guava:guava" = { versionRef = "guava" }

                [dependencies.processor]
                "org.mapstruct:mapstruct-processor" = { versionRef = "mapstruct" }

                [generated.tools.openapi]
                versionRef = "openapi"

                [generated.main.public-api]
                kind = "openapi"
                language = "java"
                input = "src/main/openapi/public-api.yaml"
                output = "target/generated/sources/openapi/public-api"
                generator = "spring"
                required = true

                [package]
                mode = "spring-boot"

                [framework.spring-boot]
                native = false
                """;
    }

    private static String reorderedToml() {
        return """
                [project]
                name = "fingerprint-demo"
                version = "0.1.0"
                group = "com.example"
                java = 21

                [framework.spring-boot]
                native = false

                [package]
                mode = "spring-boot"

                [versions]
                spring = "3.3.6"
                openapi = "7.11.0"
                mapstruct = "1.6.3"
                guava = "33.4.0-jre"

                [generated.tools.openapi]
                versionRef = "openapi"

                [generated.main.public-api]
                output = "target/generated/sources/openapi/public-api"
                input = "src/main/openapi/public-api.yaml"
                required = true
                language = "java"
                generator = "spring"
                kind = "openapi"

                [dependencies.processor]
                "org.mapstruct:mapstruct-processor" = { versionRef = "mapstruct" }

                [dependencies]
                "com.google.guava:guava" = { versionRef = "guava" }

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = { versionRef = "spring" }

                [credentials.company-artifactory]
                passwordEnv = "ACME_REPO_PASSWORD"
                usernameEnv = "ACME_REPO_USER"

                [repositories]
                central = false

                [repositories.company]
                credentials = "company-artifactory"
                url = "https://repo.acme.example/maven"
                """;
    }

    private record Case(String name, String toml) {
    }
}
