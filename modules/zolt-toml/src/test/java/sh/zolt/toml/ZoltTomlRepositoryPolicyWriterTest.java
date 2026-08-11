package sh.zolt.toml;

import static sh.zolt.toml.ProjectConfigFixture.config;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.project.DependencyConstraint;
import sh.zolt.project.DependencyConstraintKind;
import sh.zolt.project.DependencyPolicyExclusion;
import sh.zolt.project.DependencyPolicySettings;
import sh.zolt.project.LicensePolicyException;
import sh.zolt.project.LicensePolicySettings;
import sh.zolt.project.ProjectConfig;
import sh.zolt.project.RepositoryCredentialSettings;
import sh.zolt.project.RepositorySettings;
import sh.zolt.project.UnknownLicensePolicy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ZoltTomlRepositoryPolicyWriterTest {
    private final ZoltTomlParser parser = new ZoltTomlParser();
    private final ZoltTomlWriter writer = new ZoltTomlWriter();

    @Test
    void writesCredentialedRepositories() {
        ProjectConfig config = config()
                .project("enterprise", "com.acme", "17", Optional.empty())
                .repositorySettings(Map.of(
                        "central", RepositorySettings.unauthenticated("central", ProjectConfig.MAVEN_CENTRAL),
                        "company", new RepositorySettings(
                                "company",
                                "https://repo.acme.example/maven",
                                Optional.of("company-artifactory"))))
                .repositoryCredentials(Map.of(
                        "company-artifactory",
                        RepositoryCredentialSettings.basic(
                                "company-artifactory",
                                "ARTIFACTORY_USERNAME",
                                "ARTIFACTORY_ACCESS_TOKEN")))
                .build();

        String toml = writer.write(config);
        ProjectConfig parsed = parser.parse(toml);

        assertTrue(toml.contains("\"company\" = { url = \"https://repo.acme.example/maven\", credentials = \"company-artifactory\" }"));
        assertTrue(toml.contains("[repositoryCredentials.\"company-artifactory\"]"));
        assertFalse(toml.contains("ReadPermanent"));
        assertEquals(
                "company-artifactory",
                parsed.repositorySettings().get("company").credentials().orElseThrow());
        assertEquals(
                "ARTIFACTORY_ACCESS_TOKEN",
                parsed.repositoryCredentials().get("company-artifactory").passwordEnv().orElseThrow());
    }

    @Test
    void writesDependencyPolicyAndConstraints() {
        ProjectConfig config = writer.defaultApplicationConfig("enterprise", "com.acme", "com.acme.Main")
                .withDependencyPolicy(new DependencyPolicySettings(
                        List.of(new DependencyPolicyExclusion(
                                "commons-logging",
                                "commons-logging",
                                Optional.of("Use jcl-over-slf4j"))),
                        Map.of(
                                "org.apache.tomcat.embed:tomcat-embed-core",
                                new DependencyConstraint(
                                        "org.apache.tomcat.embed:tomcat-embed-core",
                                        "10.1.40",
                                        DependencyConstraintKind.STRICT,
                                        Optional.of("Container baseline"))),
                        true));

        String toml = writer.write(config);
        ProjectConfig parsed = parser.parse(toml);

        assertTrue(toml.contains("[dependencyPolicy]"));
        assertTrue(toml.contains("failOnVersionConflict = true"));
        assertTrue(toml.contains("exclude = [{ group = \"commons-logging\", artifact = \"commons-logging\", reason = \"Use jcl-over-slf4j\" }]"));
        assertTrue(toml.contains("[dependencyConstraints]"));
        assertTrue(toml.contains("\"org.apache.tomcat.embed:tomcat-embed-core\" = { version = \"10.1.40\", kind = \"strict\", reason = \"Container baseline\" }"));
        assertEquals(config.dependencyPolicy(), parsed.dependencyPolicy());
    }

    @Test
    void writesFailOnVersionConflictWithoutExclusionsOrConstraints() {
        ProjectConfig config = writer.defaultApplicationConfig("enterprise", "com.acme", "com.acme.Main")
                .withDependencyPolicy(new DependencyPolicySettings(List.of(), Map.of(), true));

        String toml = writer.write(config);
        ProjectConfig parsed = parser.parse(toml);

        assertTrue(toml.contains("[dependencyPolicy]"));
        assertTrue(toml.contains("failOnVersionConflict = true"));
        assertFalse(toml.contains("exclude ="));
        assertTrue(parsed.dependencyPolicy().failOnVersionConflict());
        assertTrue(parsed.dependencyPolicy().exclusions().isEmpty());
        assertTrue(parsed.dependencyPolicy().constraints().isEmpty());
        assertEquals(config.dependencyPolicy(), parsed.dependencyPolicy());
    }

    @Test
    void writesDependencyConstraintVersionRefsWhenPresent() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [versions]
                tomcat = "10.1.40"

                [dependencyConstraints]
                "org.apache.tomcat.embed:tomcat-embed-core" = { versionRef = "tomcat", kind = "strict", reason = "Container baseline" }
                """);

        String toml = writer.write(config);
        ProjectConfig parsed = parser.parse(toml);

        assertTrue(toml.contains("\"org.apache.tomcat.embed:tomcat-embed-core\" = { versionRef = \"tomcat\", kind = \"strict\", reason = \"Container baseline\" }"));
        DependencyConstraint constraint = parsed.dependencyPolicy()
                .constraints()
                .get("org.apache.tomcat.embed:tomcat-embed-core");
        assertEquals("10.1.40", constraint.version());
        assertEquals("tomcat", constraint.versionRef().orElseThrow());
    }

    @Test
    void writesAndParsesLicensePolicyRoundTrip() {
        ProjectConfig config = writer.defaultApplicationConfig("enterprise", "com.acme", "com.acme.Main")
                .withDependencyPolicy(new DependencyPolicySettings(
                        List.of(),
                        Map.of(),
                        false,
                        new LicensePolicySettings(
                                List.of("Apache-2.0", "MIT"),
                                List.of("GPL-3.0-only"),
                                UnknownLicensePolicy.FAIL)));

        String toml = writer.write(config);
        ProjectConfig parsed = parser.parse(toml);

        assertTrue(toml.contains("[dependencyPolicy.licenses]"));
        assertTrue(toml.contains("allow = [\"Apache-2.0\", \"MIT\"]"));
        assertTrue(toml.contains("deny = [\"GPL-3.0-only\"]"));
        assertTrue(toml.contains("unknown = \"fail\""));
        assertEquals(config.dependencyPolicy(), parsed.dependencyPolicy());
        assertEquals(UnknownLicensePolicy.FAIL, parsed.dependencyPolicy().licenses().unknown());
    }

    @Test
    void defaultsUnknownLicensePolicyToWarnAndOmitsItFromOutput() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                deny = ["GPL-3.0-only"]
                """);

        assertEquals(UnknownLicensePolicy.WARN, config.dependencyPolicy().licenses().unknown());
        assertFalse(writer.write(config).contains("unknown ="));
    }

    @Test
    void rejectsUnknownLicensePolicyKey() {
        assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                bogus = ["x"]
                """));
    }

    @Test
    void rejectsUnsupportedUnknownStrictnessValue() {
        assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                unknown = "explode"
                """));
    }

    @Test
    void writesAndParsesScopedLicenseExceptionsInCoordinateOrder() {
        ProjectConfig config = writer.defaultApplicationConfig("enterprise", "com.acme", "com.acme.Main")
                .withDependencyPolicy(new DependencyPolicySettings(
                        List.of(),
                        Map.of(),
                        false,
                        new LicensePolicySettings(
                                List.of("Apache-2.0", "MIT"),
                                List.of("GPL-3.0-only"),
                                UnknownLicensePolicy.FAIL,
                                Map.of(
                                        "org.example:matchit",
                                        new LicensePolicyException(
                                                "org.example:matchit",
                                                List.of("BSD-3-Clause"),
                                                Optional.of("0.8.4"),
                                                "Reviewed transitive dependency")))));

        String toml = writer.write(config);
        ProjectConfig parsed = parser.parse(toml);

        assertTrue(toml.contains(
                "[dependencyPolicy.licenses.exceptions.\"org.example:matchit\"]"), toml);
        assertTrue(toml.contains("allow = [\"BSD-3-Clause\"]"), toml);
        assertTrue(toml.contains("version = \"0.8.4\""), toml);
        assertTrue(toml.contains("reason = \"Reviewed transitive dependency\""), toml);
        assertEquals(config.dependencyPolicy(), parsed.dependencyPolicy());
    }

    @Test
    void canonicalizesGlobalSpdxTermsButPreservesRawLabels() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["mit", "Weird License"]
                deny = ["gpl-2.0-with-classpath-exception"]
                """);

        assertEquals(List.of("MIT", "Weird License"), config.dependencyPolicy().licenses().allow());
        assertEquals(
                List.of("GPL-2.0-only WITH Classpath-exception-2.0"),
                config.dependencyPolicy().licenses().deny());
    }

    @Test
    void preservesRawGlobalLabelsWithNonSpdxExpressionMarkers() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["Custom Internal License (2025)"]
                deny = ["License With Restrictions"]
                """);

        assertEquals(
                List.of("Custom Internal License (2025)"),
                config.dependencyPolicy().licenses().allow());
        assertEquals(
                List.of("License With Restrictions"),
                config.dependencyPolicy().licenses().deny());
    }

    @Test
    void preservesUnsupportedAtomicSpdxLikeGlobalLabels() {
        ProjectConfig config = parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["Net-SNMP", "LicenseRef-Internal", "AdditionRef-Custom"]
                deny = ["DocumentRef-upstream:LicenseRef-Custom", "GPL-2.0+"]
                """);

        assertEquals(
                List.of("Net-SNMP", "LicenseRef-Internal", "AdditionRef-Custom"),
                config.dependencyPolicy().licenses().allow());
        assertEquals(
                List.of("DocumentRef-upstream:LicenseRef-Custom", "GPL-2.0+"),
                config.dependencyPolicy().licenses().deny());
    }

    @Test
    void rejectsCompoundGlobalAllowWorkaround() {
        ZoltConfigException exception = assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["MIT AND BSD-3-Clause"]
                """));

        assertTrue(exception.getMessage().contains("Expected one SPDX license term"), exception.getMessage());
    }

    @Test
    void rejectsMalformedExpressionShapedGlobalTerm() {
        ZoltConfigException exception = assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["MIT And BSD-3-Clause"]
                """));

        assertTrue(exception.getMessage().contains("Invalid SPDX license term"), exception.getMessage());
    }

    @Test
    void rejectsExceptionWithoutRestrictiveGlobalAllowList() {
        ZoltConfigException exception = assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                unknown = "fail"

                [dependencyPolicy.licenses.exceptions."org.example:matchit"]
                allow = ["BSD-3-Clause"]
                reason = "Reviewed"
                """));

        assertTrue(exception.getMessage().contains("allow must be non-empty"), exception.getMessage());
    }

    @Test
    void rejectsNonCanonicalOrGlobbedExceptionEntries() {
        ZoltConfigException nonCanonical = assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["MIT"]

                [dependencyPolicy.licenses.exceptions."org.example:matchit"]
                allow = ["bsd-3-clause"]
                reason = "Reviewed"
                """));
        ZoltConfigException wildcard = assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["MIT"]

                [dependencyPolicy.licenses.exceptions."org.example:*"]
                allow = ["BSD-3-Clause"]
                reason = "Reviewed"
                """));

        assertTrue(nonCanonical.getMessage().contains("Use `BSD-3-Clause`"), nonCanonical.getMessage());
        assertTrue(wildcard.getMessage().contains("without whitespace or wildcards"), wildcard.getMessage());
    }

    @Test
    void rejectsExceptionThatAttemptsToOverrideGlobalDeny() {
        ZoltConfigException exception = assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["MIT"]
                deny = ["GPL-2.0-only"]

                [dependencyPolicy.licenses.exceptions."org.example:lib"]
                allow = ["GPL-2.0-only WITH Classpath-exception-2.0"]
                reason = "Reviewed"
                """));

        assertTrue(exception.getMessage().contains("cannot be overridden"), exception.getMessage());
    }

    @Test
    void rejectsBlankExceptionVersionInsteadOfTreatingItAsUnversioned() {
        ZoltConfigException exception = assertThrows(ZoltConfigException.class, () -> parser.parse("""
                [project]
                name = "enterprise"
                version = "0.1.0"
                group = "com.acme"
                java = "21"

                [dependencyPolicy.licenses]
                allow = ["MIT"]

                [dependencyPolicy.licenses.exceptions."org.example:lib"]
                allow = ["BSD-3-Clause"]
                version = ""
                reason = "Reviewed"
                """));

        assertTrue(exception.getMessage().contains("non-empty exact version"), exception.getMessage());
    }
}
