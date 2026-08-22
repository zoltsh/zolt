package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.EnvironmentVariableName;
import sh.zolt.manifest.LocalId;
import sh.zolt.manifest.ManifestRelativePath;
import sh.zolt.manifest.authored.AuthoredResources;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestResourceFields;
import sh.zolt.toml.schema.FinalManifestSchema;
import sh.zolt.toml.schema.ManifestField;

final class ManifestResourcesDecoderTest {
    @Test
    void preservesOmissionWithoutApplyingConventionalDefaults() {
        assertTrue(decode("").isEmpty());

        AuthoredResources resources = decode("""
                [resources]
                main = ["does/not/exist"]
                """).orElseThrow();
        assertEquals(List.of(path("does/not/exist")), resources.main());
        assertTrue(resources.test().isEmpty());
        assertTrue(resources.filter().isEmpty());
        assertTrue(resources.tokens().isEmpty());
    }

    @Test
    void decodesEveryResourceFieldAndTokenBranchCanonically() {
        AuthoredResources resources = decode("""
                [resources]
                main = ["src/z/resources", "src/a/resources"]
                test = ["src/test/resources"]

                [resources.filter]
                targets = ["test", "main"]
                include = ["**/*.yaml", "**/*.properties"]
                missing = "keep"

                [resources.tokens]
                project-version = { project = "version" }
                build-id = { env = "ZOLT_TEST_NEVER_READ_BUILD_ID" }
                channel = { value = "" }
                """).orElseThrow();

        assertEquals(
                List.of(path("src/z/resources"), path("src/a/resources")),
                resources.main());
        assertEquals(List.of(path("src/test/resources")), resources.test());
        AuthoredResources.Filter filter = resources.filter().orElseThrow();
        assertEquals(
                Optional.of(List.of(
                        AuthoredResources.Target.MAIN,
                        AuthoredResources.Target.TEST)),
                filter.targets());
        assertEquals(
                List.of("**/*.properties", "**/*.yaml"),
                filter.include().stream().map(Object::toString).toList());
        assertEquals(
                AuthoredResources.MissingTokenPolicy.KEEP,
                filter.missing().orElseThrow());
        assertEquals(
                List.of("build-id", "channel", "project-version"),
                resources.tokens().keySet().stream().map(LocalId::value).toList());
        assertInstanceOf(
                AuthoredResources.Token.Environment.class,
                token(resources, "build-id"));
        assertEquals(
                "",
                assertInstanceOf(
                                AuthoredResources.Token.Literal.class,
                                token(resources, "channel"))
                        .value());
        assertInstanceOf(
                AuthoredResources.Token.Project.class,
                token(resources, "project-version"));
        assertThrows(UnsupportedOperationException.class, () -> resources.main().clear());
        assertThrows(UnsupportedOperationException.class, () -> resources.tokens().clear());
    }

    @Test
    void childOnlyFilterAndTokensCreateTheImplicitResourceParent() {
        AuthoredResources filtered = decode("""
                [resources.filter]
                include = ["**/*.txt"]
                """).orElseThrow();
        assertTrue(filtered.main().isEmpty());
        assertTrue(filtered.test().isEmpty());
        assertTrue(filtered.filter().orElseThrow().targets().isEmpty());
        assertTrue(filtered.filter().orElseThrow().missing().isEmpty());

        AuthoredResources tokenOnly = decode("""
                [resources.tokens]
                project-main = { project = "main" }
                """).orElseThrow();
        assertTrue(tokenOnly.main().isEmpty());
        assertTrue(tokenOnly.filter().isEmpty());
        assertEquals(
                AuthoredResources.ProjectField.MAIN,
                assertInstanceOf(
                                AuthoredResources.Token.Project.class,
                                token(tokenOnly, "project-main"))
                        .field());
    }

    @Test
    void distinguishesExplicitEmptyRootsAndTokenCollectionFromOmission() {
        AuthoredResources roots = decode("""
                [resources]
                main = []
                test = []
                """).orElseThrow();
        assertEquals(AuthoredResources.empty(), roots);

        AuthoredResources tokens = decode("""
                [resources.tokens]
                """).orElseThrow();
        assertEquals(AuthoredResources.empty(), tokens);
        AuthoredResources inlineTokens = decode(
                "resources = { tokens = {} }\n").orElseThrow();
        assertEquals(AuthoredResources.empty(), inlineTokens);
        assertTrue(decode("").isEmpty());
    }

    @Test
    void reportsFilterFailuresInTheFrozenSemanticOrder() {
        assertFailure("""
                [resources.filter]
                targets = []
                """, "Invalid value for `resources.filter.targets`", "omitted or nonempty");
        assertFailure("""
                [resources.filter]
                targets = ["main"]
                """, "Missing required manifest field `resources.filter.include`");
        assertFailure("""
                [resources.filter]
                missing = "keep"
                """, "Missing required manifest field `resources.filter.include`");
        assertFailure("""
                [resources.filter]
                include = []
                """, "Invalid value for `resources.filter.include`", "at least one include glob");
    }

    @Test
    void anchorsModelOwnedCollectionCollisionsToTheLaterArrayItem() {
        assertFailure("""
                [resources]
                main = ["src/resources", "src/resources"]
                """, "Invalid value for `resources.main[1]`", "duplicate");
        assertFailure("""
                [resources]
                test = ["src/test/resources", "src/test/resources"]
                """, "Invalid value for `resources.test[1]`", "duplicate");
        assertFailure("""
                [resources.filter]
                targets = ["main", "main"]
                include = ["**/*.txt"]
                """, "Invalid value for `resources.filter.targets[1]`", "duplicate");
        assertFailure("""
                [resources.filter]
                include = ["**/*.txt", "**/*.txt"]
                """, "Invalid value for `resources.filter.include[1]`", "duplicate");
    }

    @Test
    void mapsEveryClosedFilterSymbolWithoutApplyingDefaults() {
        AuthoredResources resources = decode("""
                [resources.filter]
                targets = ["test"]
                include = ["*"]
                missing = "fail"
                """).orElseThrow();
        assertEquals(
                Optional.of(List.of(AuthoredResources.Target.TEST)),
                resources.filter().orElseThrow().targets());
        assertEquals(
                Optional.of(AuthoredResources.MissingTokenPolicy.FAIL),
                resources.filter().orElseThrow().missing());

        assertFailure("""
                [resources.filter]
                targets = ["integration"]
                include = ["*"]
                """, "Invalid symbol `integration` for `resources.filter.targets`");
        assertFailure("""
                [resources.filter]
                include = ["*"]
                missing = "ignore"
                """, "Invalid symbol `ignore` for `resources.filter.missing`");
        assertModelSymbols(
                FinalManifestResourceFields.RESOURCES_FILTER_TARGETS,
                Arrays.asList(AuthoredResources.Target.values()),
                AuthoredResources.Target::configValue);
        assertModelSymbols(
                FinalManifestResourceFields.RESOURCES_FILTER_MISSING,
                Arrays.asList(AuthoredResources.MissingTokenPolicy.values()),
                AuthoredResources.MissingTokenPolicy::configValue);
    }

    @Test
    void decodesEveryProjectTokenFieldWithoutResolvingAProject() {
        AuthoredResources resources = decode("""
                [resources.tokens]
                name = { project = "name" }
                version = { project = "version" }
                group = { project = "group" }
                java = { project = "java" }
                main = { project = "main" }
                """).orElseThrow();

        for (AuthoredResources.ProjectField field : AuthoredResources.ProjectField.values()) {
            AuthoredResources.Token.Project token = assertInstanceOf(
                    AuthoredResources.Token.Project.class,
                    token(resources, field.configValue()));
            assertEquals(field, token.field());
        }
        assertFailure("""
                [resources.tokens]
                artifact = { project = "artifact" }
                """,
                "Invalid value for `resources.tokens.artifact.project`",
                "Unsupported resource token project field");
    }

    @Test
    void validatesTokenMembersWithoutLookingUpEnvironmentValues() {
        AuthoredResources resources = decode("""
                [resources.tokens]
                environment = { env = "ZOLT_VARIABLE_THAT_NEED_NOT_EXIST" }
                literal = { value = "line one\\nline two" }
                """).orElseThrow();
        assertEquals(
                new EnvironmentVariableName("ZOLT_VARIABLE_THAT_NEED_NOT_EXIST"),
                assertInstanceOf(
                                AuthoredResources.Token.Environment.class,
                                token(resources, "environment"))
                        .env());
        assertEquals(
                "line one\nline two",
                assertInstanceOf(
                                AuthoredResources.Token.Literal.class,
                                token(resources, "literal"))
                        .value());

        assertFailure("""
                [resources.tokens]
                invalid = { env = "BAD-NAME" }
                """, "Invalid value for `resources.tokens.invalid.env`", "Invalid environment-variable name");
        String nulEscape = "\\" + "u0000";
        assertFailure(
                "[resources.tokens]\ninvalid = { value = \"" + nulEscape + "\" }\n",
                "Invalid value for `resources.tokens.invalid.value`",
                "must not contain NUL");
    }

    @Test
    void anchorsTheFirstEnvironmentCaseCollisionToItsIntroducingMember() {
        assertFailure("""
                [resources.tokens]
                zeta = { env = "BUILD_ID" }
                alpha = { env = "build_id" }
                """, "Invalid value for `resources.tokens.alpha.env`",
                "environment-variable names `build_id` and `BUILD_ID` differ only by ASCII case");

        AuthoredResources resources = decode("""
                [resources.tokens]
                first = { env = "SHARED_BUILD_ID" }
                second = { env = "SHARED_BUILD_ID" }
                """).orElseThrow();
        assertEquals(2, resources.tokens().size());
    }

    @Test
    void leavesTokenClosedShapeAndDynamicIdsAtTheShapeBoundary() {
        assertFailure("""
                [resources.tokens]
                empty = { }
                """, "must declare exactly one of `project` or `env` or `value`");
        assertFailure("""
                [resources.tokens]
                mixed = { env = "TOKEN", value = "literal" }
                """, "must declare exactly one of `project` or `env` or `value`");
        assertFailure("""
                [resources.tokens]
                Bad_Id = { value = "literal" }
                """, "Invalid dynamic key `Bad_Id`");
        assertFailure("""
                [resources.tokens]
                literal = { vlaue = "literal" }
                """, "Unknown manifest field `resources.tokens.literal.vlaue`");
    }

    private static Optional<AuthoredResources> decode(String source) {
        return new ManifestResourcesDecoder().decode(
                ManifestSemanticTestSupport.index(source), ignored -> {});
    }

    private static ManifestRelativePath path(String value) {
        return new ManifestRelativePath(value);
    }

    private static AuthoredResources.Token token(
            AuthoredResources resources,
            String id) {
        return resources.tokens().get(new LocalId(id));
    }

    private static void assertFailure(String source, String... details) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class, () -> decode(source));
        for (String detail : details) {
            assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        }
    }

    private static <T> void assertModelSymbols(
            ManifestField field,
            List<T> modelValues,
            Function<T, String> configValue) {
        String family = field.symbolFamily().orElseThrow();
        List<String> schemaValues = FinalManifestSchema.registry()
                .symbols()
                .family(family)
                .orElseThrow()
                .values();
        Set<String> modelSymbols = Set.copyOf(
                modelValues.stream().map(configValue).toList());
        assertEquals(Set.copyOf(schemaValues), modelSymbols);
    }
}
