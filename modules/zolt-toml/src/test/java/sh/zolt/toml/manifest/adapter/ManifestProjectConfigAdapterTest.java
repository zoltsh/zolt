package sh.zolt.toml.manifest.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyMetadata;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.VersionConflictPolicy;

/**
 * Identity, dependency-lane, repository, and policy manifests asserted to reach the expected
 * {@link ProjectConfig} through the final boundary.
 */
final class ManifestProjectConfigAdapterTest {
    @Test
    void minimalApplicationCarriesIdentityAndTheTestLane() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "hello"
                version = "0.1.0"
                group = "com.example"
                java = 21
                main = "com.example.Main"

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = "5.13.4"
                """);

        assertEquals("hello", adapted.project().name());
        assertEquals("0.1.0", adapted.project().version());
        assertEquals("com.example", adapted.project().group());
        assertEquals("21", adapted.project().java());
        assertEquals(Optional.of("com.example.Main"), adapted.project().main());
        // §14.4: the POM display name has no authored spelling and is derived from project identity.
        assertEquals("hello", adapted.packageSettings().metadata().name());
        assertEquals(
                Map.of("org.junit.jupiter:junit-jupiter", "5.13.4"), adapted.testDependencies());
        assertEquals(Map.of(), adapted.dependencies());
    }

    @Test
    void everyDependencyLaneReachesItsOwnProjectConfigMap() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "lanes"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [versions]
                junit = "5.13.4"

                [platforms]
                "org.springframework.boot:spring-boot-dependencies" = "4.0.6"

                [dependencies]
                "com.fasterxml.jackson.core:jackson-databind" = "2.19.0"
                "org.springframework.boot:spring-boot-starter-webmvc" = { managed = true }

                [dependencies.api]
                "org.slf4j:slf4j-api" = "2.0.17"

                [dependencies.runtime]
                "ch.qos.logback:logback-classic" = "1.5.18"

                [dependencies.provided]
                "jakarta.servlet:jakarta.servlet-api" = "6.1.0"

                [dependencies.dev]
                "org.springframework.boot:spring-boot-devtools" = { managed = true }

                [dependencies.test]
                "org.junit.jupiter:junit-jupiter" = { versionRef = "junit" }

                [dependencies.processor]
                "org.mapstruct:mapstruct-processor" = "1.6.3"
                "org.springframework.boot:spring-boot-configuration-processor" = { managed = true }

                [dependencies.test-processor]
                "org.projectlombok:lombok" = "1.18.38"
                "org.springframework.boot:spring-boot-autoconfigure-processor" = { managed = true }
                """);

        assertEquals(Map.of("junit", "5.13.4"), adapted.versionAliases());
        assertEquals(
                Map.of("org.springframework.boot:spring-boot-dependencies", "4.0.6"),
                adapted.platforms());
        assertEquals(Map.of("org.slf4j:slf4j-api", "2.0.17"), adapted.apiDependencies());
        assertEquals(
                "2.19.0", adapted.dependencies().get("com.fasterxml.jackson.core:jackson-databind"));
        assertEquals(Map.of("ch.qos.logback:logback-classic", "1.5.18"), adapted.runtimeDependencies());
        assertEquals(
                Map.of("jakarta.servlet:jakarta.servlet-api", "6.1.0"), adapted.providedDependencies());
        assertEquals("5.13.4", adapted.testDependencies().get("org.junit.jupiter:junit-jupiter"));
        assertEquals(Map.of("org.mapstruct:mapstruct-processor", "1.6.3"), adapted.annotationProcessors());
        assertEquals(
                Map.of("org.projectlombok:lombok", "1.18.38"), adapted.testAnnotationProcessors());
        assertEquals(
                Set.of("org.springframework.boot:spring-boot-starter-webmvc"),
                Set.copyOf(adapted.managedDependencies()));
        assertEquals(
                Set.of("org.springframework.boot:spring-boot-devtools"),
                Set.copyOf(adapted.managedDevDependencies()));
        // Both processor lanes route `managed = true` to their own managed set, not to the versioned map.
        assertEquals(
                Set.of("org.springframework.boot:spring-boot-configuration-processor"),
                Set.copyOf(adapted.managedAnnotationProcessors()));
        assertEquals(
                Set.of("org.springframework.boot:spring-boot-autoconfigure-processor"),
                Set.copyOf(adapted.managedTestAnnotationProcessors()));
        assertTrue(adapted.workspaceDependencies().isEmpty(), "no workspace selectors were authored");
    }

    @Test
    void dependencyMetadataSurvivesTheLaneMove() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "metadata"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [versions]
                netty = "4.1.119.Final"

                [dependencies]
                "io.netty:netty-handler" = { versionRef = "netty", classifier = "linux-x86_64", type = "jar" }
                "com.example:publish-only" = { version = "2.0.0", publishOnly = true }

                [dependencies.api]
                "com.example:client" = { version = "1.4.0", optional = true, exclude = ["commons-logging:commons-logging"] }
                """);

        assertEquals("4.1.119.Final", adapted.dependencies().get("io.netty:netty-handler"));
        assertFalse(
                adapted.dependencies().containsKey("com.example:publish-only"),
                "publish-only dependencies stay out of the resolved lane map");
        assertEquals("1.4.0", adapted.apiDependencies().get("com.example:client"));

        DependencyMetadata netty = metadata(adapted, "dependencies", "io.netty:netty-handler");
        assertEquals("netty", netty.versionRef());
        assertEquals("linux-x86_64", netty.classifier());
        assertNull(
                netty.type(),
                "an authored type = \"jar\" is the default variant the canonical writer omits, so the "
                        + "engine model erases it rather than carrying a second spelling to the POM");

        DependencyMetadata publishOnly = metadata(adapted, "dependencies", "com.example:publish-only");
        assertTrue(publishOnly.publishOnly());
        assertEquals("2.0.0", publishOnly.version());

        DependencyMetadata client = metadata(adapted, "api.dependencies", "com.example:client");
        assertTrue(client.optional());
        assertEquals(
                List.of("commons-logging:commons-logging"),
                client.exclusions().stream()
                        .map(exclusion -> exclusion.group() + ":" + exclusion.artifact())
                        .toList());
    }

    @Test
    void repositoriesCredentialsAndPolicyReachTheProjectConfig() {
        ProjectConfig adapted = FinalManifests.load(
                """
                [project]
                name = "enterprise-library"
                version = "1.0.0"
                group = "com.example"
                java = 21

                [repositories.company]
                url = "https://repo.example.com/maven"
                credentials = "company"

                [credentials.company]
                usernameEnv = "MAVEN_USERNAME"
                passwordEnv = "MAVEN_PASSWORD"

                [credentials.github]
                tokenEnv = "GITHUB_TOKEN"

                [versions]
                jackson = "2.19.0"

                [platforms]
                "com.fasterxml.jackson:jackson-bom" = { versionRef = "jackson" }

                [dependencies.constraints]
                "io.netty:netty-handler" = { version = "4.1.119.Final", reason = "Security baseline" }

                [dependencies.policy]
                conflicts = "fail"
                deny = [{ coordinate = "commons-logging:commons-logging", reason = "Use SLF4J" }]

                [dependencies.policy.licenses]
                allow = ["Apache-2.0", "MIT"]
                deny = ["GPL-3.0-only"]
                unknown = "fail"

                [dependencies.license-exceptions."org.example:matchit"]
                allow = ["BSD-3-Clause"]
                version = "0.8.4"
                reason = "Reviewed transitive dependency"
                """);

        assertEquals("https://repo.example.com/maven", adapted.repositories().get("company"));
        assertEquals(
                Optional.of("MAVEN_USERNAME"),
                adapted.repositoryCredentials().get("company").usernameEnv());
        assertEquals(
                Optional.of("MAVEN_PASSWORD"),
                adapted.repositoryCredentials().get("company").passwordEnv());
        assertEquals(
                Optional.of("GITHUB_TOKEN"), adapted.repositoryCredentials().get("github").tokenEnv());
        assertEquals("2.19.0", adapted.platforms().get("com.fasterxml.jackson:jackson-bom"));

        assertTrue(adapted.dependencyPolicy().failOnVersionConflict());
        assertEquals(
                List.of("commons-logging:commons-logging"),
                adapted.dependencyPolicy().exclusions().stream()
                        .map(DependencyPolicyExclusion::coordinate)
                        .toList());
        assertEquals(
                Optional.of("Use SLF4J"),
                adapted.dependencyPolicy().exclusions().getFirst().reason());
        DependencyConstraint constraint =
                adapted.dependencyPolicy().constraints().get("io.netty:netty-handler");
        assertEquals("4.1.119.Final", constraint.version());
        assertEquals(List.of("Apache-2.0", "MIT"), adapted.dependencyPolicy().licenses().allow());
        assertEquals(List.of("GPL-3.0-only"), adapted.dependencyPolicy().licenses().deny());
        assertEquals(
                List.of("BSD-3-Clause"),
                adapted.dependencyPolicy().licenses().exceptions().get("org.example:matchit").allow());
    }

    /**
     * Design §9.11: the three conflict symbols mean three different things, so the boundary must carry
     * the symbol rather than a fail/not-fail boolean that would make {@code warn} indistinguishable
     * from {@code resolve}.
     */
    @Test
    void everyConflictSymbolSurvivesTheBoundaryDistinctly() {
        assertEquals(VersionConflictPolicy.RESOLVE, policy("").conflicts(), "omitted conflicts");
        for (VersionConflictPolicy expected : VersionConflictPolicy.values()) {
            assertEquals(
                    expected,
                    policy("\n[dependencies.policy]\nconflicts = \"" + expected.configValue() + "\"\n")
                            .conflicts(),
                    expected.configValue());
        }

        assertTrue(policy("\n[dependencies.policy]\nconflicts = \"fail\"\n").failOnVersionConflict());
        assertFalse(policy("\n[dependencies.policy]\nconflicts = \"fail\"\n").warnOnVersionConflict());
        assertTrue(policy("\n[dependencies.policy]\nconflicts = \"warn\"\n").warnOnVersionConflict());
        assertFalse(policy("\n[dependencies.policy]\nconflicts = \"warn\"\n").failOnVersionConflict());
        assertFalse(policy("\n[dependencies.policy]\nconflicts = \"resolve\"\n").warnOnVersionConflict());
    }

    private static DependencyPolicySettings policy(String authoredPolicy) {
        return FinalManifests.load("""
                [project]
                name = "policy"
                version = "1.0.0"
                group = "com.example"
                java = 21
                """ + authoredPolicy).dependencyPolicy();
    }

    @Test
    void tasksAndAliasesDoNotPerturbTheProjectConfig() {
        String identity =
                """
                [project]
                name = "commands"
                version = "1.0.0"
                group = "com.example"
                java = 21
                """;

        assertEquals(
                FinalManifests.load(identity),
                FinalManifests.load(identity
                        + """

                        [tasks.release-notes]
                        description = "Generate release notes"
                        run = ["zolt", "run", "--", "release-notes"]

                        [aliases]
                        ci = ["check", "--all"]
                        """),
                "design §15 keeps the command domain out of the project domain");
    }

    private static DependencyMetadata metadata(
            ProjectConfig config, String section, String coordinate) {
        DependencyMetadata metadata =
                config.dependencyMetadata().get(DependencyMetadata.key(section, coordinate));
        assertTrue(
                metadata != null,
                () -> "no metadata for " + section + "|" + coordinate + " in "
                        + config.dependencyMetadata().keySet());
        return metadata;
    }
}
