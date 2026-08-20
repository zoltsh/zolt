package sh.zolt.toml.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class ManifestObjectShapeTest {
    @Test
    void copiesAndCanonicallyOrdersClosedMembersAndPresenceGroups() {
        ManifestObjectMember first = member("first", 10);
        ManifestObjectMember second = member("second", 20);
        ArrayList<ManifestObjectMember> members = new ArrayList<>(List.of(second, first));
        ArrayList<ManifestObjectMember> presenceMembers = new ArrayList<>(List.of(second, first));
        var presence = new ManifestObjectShape.PresenceGroup(
                ManifestObjectShape.PresenceRule.EXACTLY_ONE, presenceMembers);
        ArrayList<ManifestObjectShape.PresenceGroup> groups = new ArrayList<>(List.of(presence));

        ManifestObjectShape shape = new ManifestObjectShape(members, groups);
        members.clear();
        presenceMembers.clear();
        groups.clear();

        assertEquals(List.of(first, second), shape.members());
        assertEquals(List.of(first, second), shape.presenceGroups().getFirst().members());
        assertThrows(UnsupportedOperationException.class, () -> shape.members().add(first));
    }

    @Test
    void rejectsInvalidMemberDescriptors() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectMember("Bad_name", ManifestValueKind.STRING, false, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectMember("nested", ManifestValueKind.INLINE_TABLE, false, 10));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectMember("name", ManifestValueKind.STRING, false, -1));
    }

    @Test
    void rejectsEmptyDuplicateAndInconsistentShapes() {
        ManifestObjectMember first = member("first", 10);
        ManifestObjectMember duplicateName = new ManifestObjectMember(
                "first", ManifestValueKind.BOOLEAN, false, 20);
        ManifestObjectMember duplicateOrder = member("second", 10);
        ManifestObjectMember second = member("second", 20);
        ManifestObjectMember unknown = member("unknown", 30);

        assertThrows(IllegalArgumentException.class, () -> new ManifestObjectShape(List.of(), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectShape(List.of(first, duplicateName), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectShape(List.of(first, duplicateOrder), List.of()));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectShape(
                        List.of(first, second),
                        List.of(new ManifestObjectShape.PresenceGroup(
                                ManifestObjectShape.PresenceRule.AT_LEAST_ONE,
                                List.of(first, unknown)))));
    }

    @Test
    void rejectsUndersizedAndDuplicatePresenceGroups() {
        ManifestObjectMember first = member("first", 10);
        ManifestObjectMember second = member("second", 20);
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectShape.PresenceGroup(
                        ManifestObjectShape.PresenceRule.EXACTLY_ONE, List.of(first)));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectShape.PresenceGroup(
                        ManifestObjectShape.PresenceRule.EXACTLY_ONE, List.of(first, first)));

        var group = new ManifestObjectShape.PresenceGroup(
                ManifestObjectShape.PresenceRule.EXACTLY_ONE, List.of(first, second));
        assertThrows(
                IllegalArgumentException.class,
                () -> new ManifestObjectShape(
                        List.of(first, second), List.of(group, group)));
    }

    @Test
    void fieldsAcceptShapesForSingularAndArrayInlineTableKinds() {
        ManifestObjectShape shape = new ManifestObjectShape(List.of(member("value", 10)), List.of());

        assertThrows(
                IllegalArgumentException.class,
                () -> manifestField(ManifestValueKind.STRING, shape));
        assertEquals(
                Optional.of(shape),
                manifestField(ManifestValueKind.INLINE_TABLE_ARRAY, shape).objectShape());
        assertEquals(
                Optional.of(shape),
                manifestField(ManifestValueKind.STRING_OR_INLINE_TABLE, shape).objectShape());
    }

    private static ManifestField manifestField(
            ManifestValueKind valueKind,
            ManifestObjectShape shape) {
        return new ManifestField(
                ManifestPath.of("project", "license"),
                valueKind,
                FormattingPolicy.ONE_LINE,
                MutationPolicy.NONE,
                10,
                Optional.empty(),
                ManifestValidationCategory.NONE,
                Map.of(),
                Optional.of(shape));
    }

    private static ManifestObjectMember member(String name, int canonicalOrder) {
        return new ManifestObjectMember(name, ManifestValueKind.STRING, false, canonicalOrder);
    }
}
