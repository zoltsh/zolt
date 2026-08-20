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
    void attachesOnlyTheFourActivatedFieldsToTheirExactShapes() {
        Map<String, ManifestObjectShape> attached = registry.fields().stream()
                .filter(field -> field.objectShape().isPresent())
                .collect(Collectors.toMap(
                        field -> field.path().toString(),
                        field -> field.objectShape().orElseThrow()));

        assertEquals(Set.of(
                "workspace.project.license",
                "project.license",
                "repositories.central",
                "platforms.<coordinate>"), attached.keySet());
        assertSame(FinalManifestObjectShapes.LICENSE, attached.get("workspace.project.license"));
        assertSame(FinalManifestObjectShapes.LICENSE, attached.get("project.license"));
        assertSame(
                FinalManifestObjectShapes.CENTRAL_REPLACEMENT,
                attached.get("repositories.central"));
        assertSame(
                FinalManifestObjectShapes.PLATFORM_SELECTOR,
                attached.get("platforms.<coordinate>"));
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
        return new Member(name, ManifestValueKind.STRING, required, canonicalOrder);
    }

    private record Member(
            String name,
            ManifestValueKind valueKind,
            boolean required,
            int canonicalOrder) {
    }
}
