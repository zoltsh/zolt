package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Map;
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
    void attachesOnlyTheFourteenActivatedFieldsToTheirExactShapes() {
        Map<String, ManifestObjectShape> attached = registry.fields().stream()
                .filter(field -> field.objectShape().isPresent())
                .collect(Collectors.toMap(
                        field -> field.path().toString(),
                        field -> field.objectShape().orElseThrow()));

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
                "dependencies.policy.deny"), attached.keySet());
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
