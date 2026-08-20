package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class FinalManifestObjectShapesTest {
    private final ManifestSchemaRegistry registry = FinalManifestSchema.registry();

    @Test
    void recordsExactClosedLicenseShape() {
        assertEquals(
                List.of(
                        member("id", false, 10),
                        member("name", false, 20),
                        member("url", false, 30)),
                members(FinalManifestObjectShapes.LICENSE));
        assertSame(
                FinalManifestObjectShapes.LICENSE_ID,
                FinalManifestObjectShapes.LICENSE.members().getFirst());
        assertEquals(
                List.of("id", "name"),
                FinalManifestObjectShapes.LICENSE.presenceGroups().getFirst().members().stream()
                        .map(ManifestObjectMember::name)
                        .toList());
        assertEquals(
                ManifestObjectShape.PresenceRule.AT_LEAST_ONE,
                FinalManifestObjectShapes.LICENSE.presenceGroups().getFirst().rule());
    }

    @Test
    void recordsExactClosedCentralAndPlatformShapes() {
        assertEquals(
                List.of(member("url", true, 10), member("credentials", false, 20)),
                members(FinalManifestObjectShapes.CENTRAL_REPLACEMENT));
        assertSame(
                FinalManifestObjectShapes.CENTRAL_URL,
                FinalManifestObjectShapes.CENTRAL_REPLACEMENT.members().getFirst());
        assertEquals(List.of(), FinalManifestObjectShapes.CENTRAL_REPLACEMENT.presenceGroups());
        assertEquals(
                List.of(member("version", false, 10), member("versionRef", false, 20)),
                members(FinalManifestObjectShapes.PLATFORM_SELECTOR));
        assertSame(
                FinalManifestObjectShapes.PLATFORM_VERSION_REF,
                FinalManifestObjectShapes.PLATFORM_SELECTOR.members().get(1));
        assertEquals(
                ManifestObjectShape.PresenceRule.EXACTLY_ONE,
                FinalManifestObjectShapes.PLATFORM_SELECTOR.presenceGroups().getFirst().rule());
    }

    @Test
    void recordsExactClosedDependencyShape() {
        assertEquals(
                List.of(
                        member("version", ManifestValueKind.STRING, false, 10),
                        member("versionRef", ManifestValueKind.STRING, false, 20),
                        member("managed", ManifestValueKind.BOOLEAN, false, 30),
                        member("workspace", ManifestValueKind.BOOLEAN, false, 40),
                        member("optional", ManifestValueKind.BOOLEAN, false, 50),
                        member("publishOnly", ManifestValueKind.BOOLEAN, false, 60),
                        member("classifier", ManifestValueKind.STRING, false, 70),
                        member("type", ManifestValueKind.STRING, false, 80),
                        member("exclude", ManifestValueKind.STRING_ARRAY, false, 90)),
                members(FinalManifestObjectShapes.DEPENDENCY));
        assertMemberIdentity(
                FinalManifestObjectShapes.DEPENDENCY,
                List.of(
                        FinalManifestObjectShapes.DEPENDENCY_VERSION,
                        FinalManifestObjectShapes.DEPENDENCY_VERSION_REF,
                        FinalManifestObjectShapes.DEPENDENCY_MANAGED,
                        FinalManifestObjectShapes.DEPENDENCY_WORKSPACE,
                        FinalManifestObjectShapes.DEPENDENCY_OPTIONAL,
                        FinalManifestObjectShapes.DEPENDENCY_PUBLISH_ONLY,
                        FinalManifestObjectShapes.DEPENDENCY_CLASSIFIER,
                        FinalManifestObjectShapes.DEPENDENCY_TYPE,
                        FinalManifestObjectShapes.DEPENDENCY_EXCLUDE));
        assertPresence(
                FinalManifestObjectShapes.DEPENDENCY,
                List.of("version", "versionRef", "managed", "workspace"));
    }

    @Test
    void recordsExactClosedConstraintAndDenyEntryShapes() {
        assertEquals(
                List.of(
                        member("version", false, 10),
                        member("versionRef", false, 20),
                        member("reason", false, 30)),
                members(FinalManifestObjectShapes.CONSTRAINT));
        assertMemberIdentity(
                FinalManifestObjectShapes.CONSTRAINT,
                List.of(
                        FinalManifestObjectShapes.CONSTRAINT_VERSION,
                        FinalManifestObjectShapes.CONSTRAINT_VERSION_REF,
                        FinalManifestObjectShapes.CONSTRAINT_REASON));
        assertPresence(
                FinalManifestObjectShapes.CONSTRAINT,
                List.of("version", "versionRef"));

        assertEquals(
                List.of(member("coordinate", true, 10), member("reason", false, 20)),
                members(FinalManifestObjectShapes.DENY_ENTRY));
        assertMemberIdentity(
                FinalManifestObjectShapes.DENY_ENTRY,
                List.of(
                        FinalManifestObjectShapes.DENY_ENTRY_COORDINATE,
                        FinalManifestObjectShapes.DENY_ENTRY_REASON));
        assertEquals(List.of(), FinalManifestObjectShapes.DENY_ENTRY.presenceGroups());
    }

    @Test
    void recordsExactClosedResourceTokenShape() {
        assertEquals(
                List.of(
                        member("project", false, 10),
                        member("env", false, 20),
                        member("value", false, 30)),
                members(FinalManifestObjectShapes.RESOURCE_TOKEN));
        assertMemberIdentity(
                FinalManifestObjectShapes.RESOURCE_TOKEN,
                List.of(
                        FinalManifestObjectShapes.RESOURCE_TOKEN_PROJECT,
                        FinalManifestObjectShapes.RESOURCE_TOKEN_ENV,
                        FinalManifestObjectShapes.RESOURCE_TOKEN_VALUE));
        assertPresence(
                FinalManifestObjectShapes.RESOURCE_TOKEN,
                List.of("project", "env", "value"));
    }

    @Test
    void recordsExactClosedGeneratedArtifactRequestShape() {
        assertEquals(
                List.of(
                        member("coordinate", true, 10),
                        member("version", false, 20),
                        member("versionRef", false, 30)),
                members(FinalManifestObjectShapes.GENERATED_ARTIFACT_REQUEST));
        assertMemberIdentity(
                FinalManifestObjectShapes.GENERATED_ARTIFACT_REQUEST,
                List.of(
                        FinalManifestObjectShapes.GENERATED_ARTIFACT_COORDINATE,
                        FinalManifestObjectShapes.GENERATED_ARTIFACT_VERSION,
                        FinalManifestObjectShapes.GENERATED_ARTIFACT_VERSION_REF));
        assertPresence(
                FinalManifestObjectShapes.GENERATED_ARTIFACT_REQUEST,
                List.of("version", "versionRef"));
    }

    @Test
    void recordsExactClosedTestSuiteLockShape() {
        assertEquals(
                List.of(
                        member("class", true, 10),
                        member("resources", ManifestValueKind.STRING_ARRAY, true, 20)),
                members(FinalManifestObjectShapes.TEST_SUITE_LOCK));
        assertMemberIdentity(
                FinalManifestObjectShapes.TEST_SUITE_LOCK,
                List.of(
                        FinalManifestObjectShapes.TEST_SUITE_LOCK_CLASS,
                        FinalManifestObjectShapes.TEST_SUITE_LOCK_RESOURCES));
        assertEquals(List.of(), FinalManifestObjectShapes.TEST_SUITE_LOCK.presenceGroups());
    }

    @Test
    void attachesOnlyTheSeventeenActivatedFieldsToTheirExactShapes() {
        Map<String, ManifestObjectShape> attached = registry.fields().stream()
                .filter(field -> field.objectShape().isPresent())
                .collect(Collectors.toMap(
                        field -> field.path().toString(),
                        field -> field.objectShape().orElseThrow()));

        assertEquals(17, attached.size());
        assertEquals(Set.of(
                "workspace.project.license",
                "project.license",
                "repositories.central",
                "platforms.<coordinate>",
                "dependencies.<coordinate>",
                "dependencies.api.<coordinate>",
                "dependencies.runtime.<coordinate>",
                "dependencies.provided.<coordinate>",
                "dependencies.dev.<coordinate>",
                "dependencies.test.<coordinate>",
                "dependencies.processor.<coordinate>",
                "dependencies.test-processor.<coordinate>",
                "dependencies.constraints.<coordinate>",
                "dependencies.policy.deny",
                "resources.tokens.<id>",
                "generated.tools.<id>.coordinates",
                "test.suites.<id>.locks"), attached.keySet());
        assertSame(FinalManifestObjectShapes.LICENSE, attached.get("workspace.project.license"));
        assertSame(FinalManifestObjectShapes.LICENSE, attached.get("project.license"));
        assertSame(
                FinalManifestObjectShapes.CENTRAL_REPLACEMENT,
                attached.get("repositories.central"));
        assertSame(
                FinalManifestObjectShapes.PLATFORM_SELECTOR,
                attached.get("platforms.<coordinate>"));
        List.of(
                        "dependencies.<coordinate>",
                        "dependencies.api.<coordinate>",
                        "dependencies.runtime.<coordinate>",
                        "dependencies.provided.<coordinate>",
                        "dependencies.dev.<coordinate>",
                        "dependencies.test.<coordinate>",
                        "dependencies.processor.<coordinate>",
                        "dependencies.test-processor.<coordinate>")
                .forEach(path -> assertSame(FinalManifestObjectShapes.DEPENDENCY, attached.get(path)));
        assertSame(
                FinalManifestObjectShapes.CONSTRAINT,
                attached.get("dependencies.constraints.<coordinate>"));
        assertSame(
                FinalManifestObjectShapes.DENY_ENTRY,
                attached.get("dependencies.policy.deny"));
        assertSame(
                FinalManifestObjectShapes.RESOURCE_TOKEN,
                attached.get("resources.tokens.<id>"));
        assertSame(
                FinalManifestObjectShapes.GENERATED_ARTIFACT_REQUEST,
                attached.get("generated.tools.<id>.coordinates"));
        assertSame(
                FinalManifestObjectShapes.TEST_SUITE_LOCK,
                attached.get("test.suites.<id>.locks"));
        ManifestField tokenEntry = registry
                .field(FinalManifestResourceFields.RESOURCES_TOKENS_ENTRY.path())
                .orElseThrow();
        assertSame(FinalManifestResourceFields.RESOURCES_TOKENS_ENTRY, tokenEntry);
        assertEquals(ManifestPath.of("resources", "tokens", "<id>"), tokenEntry.path());
        assertEquals(ManifestValueKind.INLINE_TABLE, tokenEntry.valueKind());
        assertEquals(FormattingPolicy.ONE_LINE, tokenEntry.formatting());
        assertEquals(MutationPolicy.NONE, tokenEntry.mutation());
        assertEquals(6_221, tokenEntry.canonicalOrder());
        assertEquals(
                Map.of("id", ManifestDynamicKeyGrammar.LOCAL_ID),
                tokenEntry.dynamicKeyGrammars());
        ManifestField artifactRequests = registry
                .field(ManifestPath.of("generated", "tools", "<id>", "coordinates"))
                .orElseThrow();
        assertEquals(
                ManifestPath.of("generated", "tools", "<id>", "coordinates"),
                artifactRequests.path());
        assertEquals(ManifestValueKind.INLINE_TABLE_ARRAY, artifactRequests.valueKind());
        assertEquals(FormattingPolicy.DEFAULT, artifactRequests.formatting());
        assertEquals(MutationPolicy.NONE, artifactRequests.mutation());
        assertEquals(6_311, artifactRequests.canonicalOrder());
        assertEquals(Optional.empty(), artifactRequests.symbolFamily());
        assertEquals(ManifestValidationCategory.NONE, artifactRequests.validation());
        assertEquals(
                Map.of("id", ManifestDynamicKeyGrammar.LOCAL_ID),
                artifactRequests.dynamicKeyGrammars());
        ManifestField suiteLocks = registry
                .field(FinalManifestTestFields.TEST_SUITE_LOCKS.path())
                .orElseThrow();
        assertSame(FinalManifestTestFields.TEST_SUITE_LOCKS, suiteLocks);
        assertEquals(ManifestPath.of("test", "suites", "<id>", "locks"), suiteLocks.path());
        assertEquals(ManifestValueKind.INLINE_TABLE_ARRAY, suiteLocks.valueKind());
        assertEquals(FormattingPolicy.DEFAULT, suiteLocks.formatting());
        assertEquals(MutationPolicy.NONE, suiteLocks.mutation());
        assertEquals(6_736, suiteLocks.canonicalOrder());
        assertEquals(Optional.empty(), suiteLocks.symbolFamily());
        assertEquals(ManifestValidationCategory.NONE, suiteLocks.validation());
        assertEquals(
                Map.of("id", ManifestDynamicKeyGrammar.LOCAL_ID),
                suiteLocks.dynamicKeyGrammars());
        assertSame(
                FinalManifestObjectShapes.TEST_SUITE_LOCK,
                suiteLocks.objectShape().orElseThrow());
    }

    private static List<Member> members(ManifestObjectShape shape) {
        return shape.members().stream()
                .map(member -> new Member(
                        member.name(),
                        member.valueKind(),
                        member.required(),
                        member.canonicalOrder()))
                .toList();
    }

    private static Member member(String name, boolean required, int canonicalOrder) {
        return member(name, ManifestValueKind.STRING, required, canonicalOrder);
    }

    private static Member member(
            String name,
            ManifestValueKind valueKind,
            boolean required,
            int canonicalOrder) {
        return new Member(name, valueKind, required, canonicalOrder);
    }

    private static void assertMemberIdentity(
            ManifestObjectShape shape,
            List<ManifestObjectMember> expected) {
        assertEquals(expected.size(), shape.members().size());
        for (int index = 0; index < expected.size(); index++) {
            assertSame(expected.get(index), shape.members().get(index));
        }
    }

    private static void assertPresence(
            ManifestObjectShape shape,
            List<String> members) {
        assertEquals(1, shape.presenceGroups().size());
        ManifestObjectShape.PresenceGroup group = shape.presenceGroups().getFirst();
        assertEquals(ManifestObjectShape.PresenceRule.EXACTLY_ONE, group.rule());
        assertEquals(
                members,
                group.members().stream().map(ManifestObjectMember::name).toList());
    }

    private record Member(
            String name,
            ManifestValueKind valueKind,
            boolean required,
            int canonicalOrder) {
    }
}
