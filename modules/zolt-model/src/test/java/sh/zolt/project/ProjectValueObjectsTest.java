package sh.zolt.project;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ProjectValueObjectsTest {
    @Test
    void openApiSettingsNormalizeNullsSortMapsAndProtectCopies() {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("zeta", "last");
        options.put("alpha", "first");

        OpenApiGenerationSettings settings = new OpenApiGenerationSettings(
                Optional.of("org.openapitools:openapi-generator-cli"),
                Optional.of("7.8.0"),
                Optional.of("openapi.generator"),
                null,
                Optional.of("java"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                options,
                Map.of("modelNameSuffix", "Dto"),
                Map.of("dateLibrary", "java8"),
                Map.of("apis", ""),
                Map.of("OffsetDateTime", "Instant"),
                Map.of("Pet", "com.example.Pet"));
        options.put("later", "ignored");

        assertEquals(Optional.of("openapi.generator"), settings.toolVersionRef());
        assertEquals(Optional.empty(), settings.preset());
        assertEquals(List.of("alpha", "zeta"), settings.options().keySet().stream().toList());
        assertEquals("Dto", settings.additionalProperties().get("modelNameSuffix"));
        assertThrows(UnsupportedOperationException.class, () -> settings.options().put("beta", "second"));
    }

    @Test
    void generatedSourceStepsDefaultNestedSettingsAndCopyInputs() {
        ArrayList<String> inputs = new ArrayList<>(List.of("src/openapi/petstore.yaml"));

        GeneratedSourceStep step = new GeneratedSourceStep(
                "openapi-client",
                null,
                "java",
                "target/generated/openapi",
                inputs,
                true,
                true,
                null,
                null);
        inputs.add("src/openapi/ignored.yaml");

        assertEquals(GeneratedSourceKind.DECLARED_ROOT, step.kind());
        assertEquals(List.of("src/openapi/petstore.yaml"), step.inputs());
        assertEquals(OpenApiGenerationSettings.empty(), step.openApi());
        assertEquals(ProtobufGenerationSettings.empty(), step.protobuf());
        assertThrows(UnsupportedOperationException.class, () -> step.inputs().add("src/openapi/new.yaml"));
    }

    @Test
    void generatedSourceStepsRejectBlankUserFacingFields() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> new GeneratedSourceStep(
                        "openapi-client",
                        GeneratedSourceKind.OPENAPI,
                        "java",
                        "target/generated/openapi",
                        List.of(" "),
                        true,
                        true));

        assertEquals("Generated source input must be a non-empty string.", exception.getMessage());
    }

    @Test
    void protobufSettingsDefaultToGrpcAndNormalizeNullOptionals() {
        ProtobufGenerationSettings settings = new ProtobufGenerationSettings(
                null,
                Optional.of("4.28.2"),
                null,
                null,
                Optional.of("1.68.1"),
                Optional.of("grpc.plugin"),
                null,
                false);

        assertEquals(Optional.empty(), settings.protocCoordinate());
        assertEquals(Optional.of("4.28.2"), settings.protocVersion());
        assertEquals(Optional.of("grpc.plugin"), settings.grpcPluginVersionRef());
        assertFalse(settings.grpc());
        assertTrue(ProtobufGenerationSettings.empty().grpc());
    }

    @Test
    void resourceTokensRequireExactlyOneSource() {
        assertEquals(Optional.of("SERVICE_TOKEN"), ResourceTokenSettings.env("SERVICE_TOKEN").env());
        assertEquals(Optional.of("service.api.token"), ResourceTokenSettings.project("service.api.token").project());
        assertEquals(Optional.of("literal"), ResourceTokenSettings.literal("literal").value());

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTokenSettings(
                        Optional.of("literal"), Optional.of("SERVICE_TOKEN"), Optional.empty()));
        assertEquals("Resource token must declare exactly one of value, env, or project.", duplicate.getMessage());

        IllegalArgumentException blank = assertThrows(
                IllegalArgumentException.class,
                () -> new ResourceTokenSettings(Optional.of(" "), Optional.empty(), Optional.empty()));
        assertEquals("Resource token must declare exactly one of value, env, or project.", blank.getMessage());
    }

    @Test
    void dependencyConstraintsDefaultStrictAndFilterBlankOptionalMetadata() {
        DependencyConstraint constraint = new DependencyConstraint(
                "com.example:library",
                "1.2.3",
                Optional.of(" "),
                null,
                Optional.of(" "));

        assertEquals("com.example:library", constraint.coordinate());
        assertEquals("1.2.3", constraint.version());
        assertEquals(Optional.empty(), constraint.versionRef());
        assertEquals(DependencyConstraintKind.STRICT, constraint.kind());
        assertEquals(Optional.empty(), constraint.reason());

        IllegalArgumentException missingCoordinate = assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyConstraint("", "1.2.3", DependencyConstraintKind.STRICT, Optional.empty()));
        assertEquals("Dependency constraint coordinate is required.", missingCoordinate.getMessage());

        IllegalArgumentException missingVersion = assertThrows(
                IllegalArgumentException.class,
                () -> new DependencyConstraint("com.example:library", " ", DependencyConstraintKind.STRICT, Optional.empty()));
        assertEquals("Dependency constraint version is required.", missingVersion.getMessage());
    }

    @Test
    void repositoryCredentialsRequireActionableEnvironmentFields() {
        RepositoryCredentialSettings settings = RepositoryCredentialSettings.basic(
                "internal", "INTERNAL_USER", "INTERNAL_PASSWORD");

        assertEquals("internal", settings.id());
        assertEquals("INTERNAL_USER", settings.usernameEnv().orElseThrow());
        assertEquals("INTERNAL_PASSWORD", settings.passwordEnv().orElseThrow());

        RepositoryCredentialSettings tokenSettings = RepositoryCredentialSettings.token("internal", "INTERNAL_TOKEN");
        assertEquals("INTERNAL_TOKEN", tokenSettings.tokenEnv().orElseThrow());

        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> RepositoryCredentialSettings.basic("internal", " ", "INTERNAL_PASSWORD"));
        assertEquals(
                "Repository credential `internal` must set either tokenEnv or both usernameEnv and passwordEnv.",
                missing.getMessage());

        IllegalArgumentException conflict = assertThrows(
                IllegalArgumentException.class,
                () -> new RepositoryCredentialSettings(
                        "internal",
                        Optional.of("INTERNAL_USER"),
                        Optional.of("INTERNAL_PASSWORD"),
                        Optional.of("INTERNAL_TOKEN")));
        assertEquals(
                "Repository credential `internal` cannot combine tokenEnv with usernameEnv or passwordEnv.",
                conflict.getMessage());
    }

    @Test
    void testRuntimeSettingsSortMapsRedactEnvironmentAndRejectRunnerOwnedProperties() {
        TestRuntimeSettings settings = new TestRuntimeSettings(
                List.of("-Dfile.encoding=UTF-8"),
                Map.of("zeta", "last", "alpha", "first"),
                Map.of("SECRET_TOKEN", "secret", "API_KEY", "key"),
                List.of("failed", "passed"));

        assertFalse(settings.defaultsOnly());
        assertEquals(List.of("alpha", "zeta"), settings.systemProperties().keySet().stream().toList());
        assertEquals(List.of("API_KEY", "SECRET_TOKEN"), settings.redactedEnvironment().keySet().stream().toList());
        assertEquals("<redacted>", settings.redactedEnvironment().get("SECRET_TOKEN"));
        assertThrows(UnsupportedOperationException.class, () -> settings.systemProperties().put("beta", "second"));

        IllegalArgumentException unsupportedEvent = assertThrows(
                IllegalArgumentException.class,
                () -> TestRuntimeSettings.validateEvent("test.runtime.events", "started"));
        assertEquals(
                "Unsupported test runtime event `started`. Supported test runtime events are: passed, skipped, failed.",
                unsupportedEvent.getMessage());

        IllegalArgumentException runnerOwnedProperty = assertThrows(
                IllegalArgumentException.class,
                () -> new TestRuntimeSettings(
                        List.of(), Map.of("user.dir", "/tmp/project"), Map.of(), List.of()));
        assertEquals(
                "Invalid [test.runtime].systemProperties.user.dir in zolt.toml. Zolt owns the test runner user.dir and classpath.",
                runnerOwnedProperty.getMessage());
    }

    @Test
    void buildSettingsDefaultPathsOrderSuitesAndCopyGeneratedSources() {
        GeneratedSourceStep generated = new GeneratedSourceStep(
                "protobuf-main",
                GeneratedSourceKind.PROTOBUF,
                "java",
                "target/generated/protobuf",
                List.of("src/main/proto/service.proto"),
                true,
                false);
        TestSuiteSettings smoke = new TestSuiteSettings(
                List.of("*SmokeTest"), List.of(), List.of(), List.of(), true, 2, Map.of());
        Map<String, TestSuiteSettings> testSuites = new LinkedHashMap<>();
        testSuites.put("smoke", smoke);
        testSuites.put("unit", null);

        BuildSettings settings = new BuildSettings(
                "src/main/ignored",
                List.of("src/generated/java", "src/main/java", "src/generated/java"),
                "src/test/java",
                null,
                "target/classes",
                "target/test-classes",
                null,
                List.of("src/test/groovy"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                testSuites,
                null,
                List.of(generated),
                null);

        assertEquals("src/generated/java", settings.source());
        assertEquals(List.of("src/generated/java", "src/main/java"), settings.sourceRoots());
        assertEquals("target", settings.outputRoot());
        assertEquals("target/integration-test-classes", settings.integrationTestOutput());
        assertEquals(List.of("src/integration-test/java"), settings.integrationTestSources());
        assertEquals(List.of("src/main/resources"), settings.resourceRoots());
        assertEquals(List.of("smoke", "unit"), settings.testSuites().keySet().stream().toList());
        assertEquals(TestSuiteSettings.empty(), settings.testSuites().get("unit"));
        assertEquals(List.of(generated), settings.generatedMainSources());
        assertEquals(List.of(), settings.generatedTestSources());
        assertThrows(UnsupportedOperationException.class, () -> settings.generatedMainSources().add(generated));

        BuildSettings integration = settings.asIntegrationTestBuild();
        assertEquals("src/integration-test/java", integration.test());
        assertEquals("target/integration-test-classes", integration.testOutput());
        assertEquals(List.of("src/integration-test/resources"), integration.testResourceRoots());
    }

    @Test
    void projectConfigsPopulateAllDependencySectionsAndPackageSettings() {
        PackageSettings packageSettings = new PackageSettings(
                PackageMode.WAR,
                true,
                true,
                true,
                new PublicationMetadata("demo", "Demo app", "https://example.com", "Apache-2.0", List.of("Zolt"), "", ""),
                Map.of("Implementation-Title", "demo"));

        ProjectConfig config = ProjectConfigs.withAllDependencySections(
                project(),
                Map.of("internal", "https://repo.example.com/maven"),
                Map.of("org.springframework.boot:spring-boot-dependencies", "3.4.1"),
                Map.of("com.example:api", "1.0.0"),
                Set.of("com.example:managed-api"),
                Map.of(":shared-api", "shared-api"),
                Map.of("com.example:main", "1.0.0"),
                Set.of("com.example:managed-main"),
                Map.of(":shared-main", "shared-main"),
                Map.of("com.example:runtime", "1.0.0"),
                Set.of("com.example:managed-runtime"),
                Map.of("jakarta.servlet:jakarta.servlet-api", "6.1.0"),
                Set.of("jakarta.servlet:jakarta.servlet-api"),
                Map.of("com.example:devtools", "1.0.0"),
                Set.of("com.example:managed-dev"),
                Map.of("org.junit.jupiter:junit-jupiter-api", "5.11.4"),
                Set.of("org.junit.jupiter:junit-jupiter-api"),
                Map.of(":test-fixtures", "test-fixtures"),
                Map.of("com.google.auto.service:auto-service", "1.1.1"),
                Set.of("com.google.auto.service:auto-service"),
                Map.of("com.example:test-processor", "1.0.0"),
                Set.of("com.example:test-processor"),
                BuildSettings.defaults(),
                NativeSettings.defaultsForOutputRoot("build/zolt"),
                CompilerSettings.defaultsForOutputRoot("build/zolt"),
                packageSettings);

        assertEquals("https://repo.example.com/maven", config.repositories().get("internal"));
        assertEquals("1.0.0", config.apiDependencies().get("com.example:api"));
        assertEquals(Set.of("com.example:managed-runtime"), config.managedRuntimeDependencies());
        assertEquals("6.1.0", config.providedDependencies().get("jakarta.servlet:jakarta.servlet-api"));
        assertEquals("1.0.0", config.devDependencies().get("com.example:devtools"));
        assertEquals("test-fixtures", config.workspaceTestDependencies().get(":test-fixtures"));
        assertEquals("1.1.1", config.annotationProcessors().get("com.google.auto.service:auto-service"));
        assertEquals(packageSettings, config.packageSettings());
        assertEquals("build/zolt/native", config.nativeSettings().output());
        assertEquals("build/zolt/generated/sources/annotations", config.compilerSettings().generatedSources());
    }

    @Test
    void enumConfigValuesStayStable() {
        assertEquals(Optional.of(GeneratedSourceKind.OPENAPI), GeneratedSourceKind.fromConfigValue("openapi"));
        assertEquals(Optional.empty(), GeneratedSourceKind.fromConfigValue("OpenAPI"));
        assertEquals("declared-root, openapi, protobuf, exec", GeneratedSourceKind.supportedValues());

        assertEquals(Optional.of(ProducesLane.RESOURCES), ProducesLane.fromConfigValue("resources"));
        assertEquals(Optional.of(ProducesLane.INTERMEDIATE), ProducesLane.fromConfigValue("intermediate"));
        assertEquals(Optional.of(ProducesLane.TEST_RESOURCES), ProducesLane.fromConfigValue("test-resources"));
        assertEquals(Optional.empty(), ProducesLane.fromConfigValue("sideways"));
        assertEquals("java-sources, test-sources, resources, test-resources, intermediate", ProducesLane.supportedValues());

        assertEquals(Optional.of(PackageMode.SPRING_BOOT_WAR), PackageMode.fromConfigValue("spring-boot-war"));
        assertEquals(Optional.empty(), PackageMode.fromConfigValue(null));
        assertEquals("thin, spring-boot, war, spring-boot-war, quarkus, uber, bom", PackageMode.supportedValues());
        assertEquals("jar", PackageMode.THIN.artifactType());
        assertEquals("jar", PackageMode.QUARKUS.artifactType());
        assertEquals("war", PackageMode.SPRING_BOOT_WAR.artifactType());
        assertEquals("pom", PackageMode.BOM.artifactType());

        assertEquals(Optional.of(DependencyConstraintKind.STRICT), DependencyConstraintKind.fromConfigValue("strict"));
        assertEquals(Optional.empty(), DependencyConstraintKind.fromConfigValue("STRICT"));
        assertEquals("strict", DependencyConstraintKind.supportedValues());

        assertEquals(
                List.of(
                        DependencySection.MAIN,
                        DependencySection.API,
                        DependencySection.RUNTIME,
                        DependencySection.PROVIDED,
                        DependencySection.DEV,
                        DependencySection.TEST,
                        DependencySection.PROCESSOR,
                        DependencySection.TEST_PROCESSOR),
                List.of(DependencySection.values()));
    }

    @Test
    void execGenerationSettingsNormalizeNullsSortEnvAndDefaultCache() {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("ZETA", "2");
        env.put("ALPHA", "1");

        ExecGenerationSettings settings = new ExecGenerationSettings(
                "jooq",
                new ExecToolSettings(
                        "jvm",
                        List.of(new ExecToolCoordinate("org.jooq:jooq-codegen", Optional.of("3.19.15"), Optional.of("jooq"))),
                        "org.jooq.codegen.GenerationTool"),
                List.of("src/main/jooq/config.xml"),
                ProducesLane.JAVA_SOURCES,
                null,
                env,
                "");

        assertEquals("jooq", settings.toolName());
        assertEquals("jvm", settings.tool().runner());
        assertEquals("org.jooq.codegen.GenerationTool", settings.tool().mainClass());
        assertEquals(List.of("ALPHA", "ZETA"), List.copyOf(settings.env().keySet()));
        assertEquals(Optional.empty(), settings.into());
        assertEquals("content", settings.cache());
        assertThrows(UnsupportedOperationException.class, () -> settings.args().add("x"));

        ExecGenerationSettings empty = ExecGenerationSettings.empty();
        assertEquals("content", empty.cache());
        assertTrue(empty.env().isEmpty());
        assertTrue(empty.tool().coordinates().isEmpty());
    }

    @Test
    void versionAliasRulesAcceptOnlyPortableNamesAndFixedReleasedValues() {
        assertTrue(VersionAliasRules.isValidName("spring.boot_3-4"));
        assertFalse(VersionAliasRules.isValidName(null));
        assertFalse(VersionAliasRules.isValidName(" spring"));
        assertFalse(VersionAliasRules.isValidName("spring boot"));
        assertFalse(VersionAliasRules.isValidName("spring:boot"));

        assertTrue(VersionAliasRules.isValidValue("3.4.1"));
        assertFalse(VersionAliasRules.isValidValue("latest.release"));
        assertFalse(VersionAliasRules.isValidValue("3.4.1-SNAPSHOT"));
    }

    @Test
    void packageSettingsDefaultModeMetadataAndManifestAttributes() {
        PackageSettings settings = new PackageSettings(
                null,
                true,
                false,
                true,
                null,
                Map.of("Main-Class", "com.example.Main"));

        assertEquals(PackageMode.THIN, settings.mode());
        assertTrue(settings.sources());
        assertFalse(settings.javadoc());
        assertTrue(settings.tests());
        assertEquals(PublicationMetadata.empty(), settings.metadata());
        assertEquals("com.example.Main", settings.manifestAttributes().get("Main-Class"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> settings.manifestAttributes().put("Implementation-Version", "1.0.0"));
    }

    @Test
    void resourceFilteringDefaultsMissingPolicyAndCopiesTokens() {
        ResourceTokenSettings token = ResourceTokenSettings.env("SERVICE_TOKEN");
        ResourceFilteringSettings settings = new ResourceFilteringSettings(
                true,
                true,
                List.of("**/*.properties"),
                null,
                Map.of("service.token", token));

        assertTrue(settings.enabled());
        assertTrue(settings.testEnabled());
        assertEquals(ResourceMissingTokenPolicy.FAIL, settings.missing());
        assertEquals(List.of("**/*.properties"), settings.includes());
        assertSame(token, settings.tokens().get("service.token"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> settings.tokens().put("other.token", ResourceTokenSettings.literal("literal")));
    }

    private static ProjectMetadata project() {
        return new ProjectMetadata("demo", "0.1.0", "com.example", "21", Optional.of("com.example.Main"));
    }
}
