package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.manifest.WorkspaceMemberPath;
import sh.zolt.manifest.authored.AuthoredBom;
import sh.zolt.toml.ZoltConfigException;

final class ManifestBomMembersDecoderTest {
    private final ManifestBomMembersDecoder decoder = new ManifestBomMembersDecoder();

    @Test
    void preservesOmissionForAbsentAndCollectionOnlyBomDomains() {
        assertTrue(decode("").isEmpty());
        assertTrue(decode("[bom.versions]\n").isEmpty());
        assertTrue(decode("[bom.imports]\n").isEmpty());
    }

    @Test
    void decodesAllMembersAndCanonicalizesImmutableExclusions() {
        AuthoredBom.Members members = decode("""
                [bom]
                exclude = ["modules/zeta", ".", "apps/admin"]
                members = true
                """).orElseThrow();

        assertInstanceOf(AuthoredBom.AllMembers.class, members.selection());
        assertEquals(
                List.of(path("."), path("apps/admin"), path("modules/zeta")),
                members.exclude());
        assertThrows(UnsupportedOperationException.class, members.exclude()::clear);

        assertTrue(decode("[bom]\nmembers = true\nexclude = []\n")
                .orElseThrow()
                .exclude()
                .isEmpty());
    }

    @Test
    void decodesCanonicalImmutableExplicitMembers() {
        AuthoredBom.Members members = decode("""
                [bom]
                members = ["modules/zeta", ".", "apps/api"]
                """).orElseThrow();
        AuthoredBom.ExplicitMembers explicit = assertInstanceOf(
                AuthoredBom.ExplicitMembers.class, members.selection());

        assertEquals(
                List.of(path("."), path("apps/api"), path("modules/zeta")),
                explicit.paths());
        assertTrue(members.exclude().isEmpty());
        assertThrows(UnsupportedOperationException.class, explicit.paths()::clear);
    }

    @Test
    void rejectsFalseAndEmptyMemberSelectionsAtTheMembersField() {
        assertSemanticFailure(
                "[bom]\nmembers = false\n",
                "`bom.members`",
                "BOM members must be `true` or a nonempty array");
        assertSemanticFailure(
                "[bom]\nmembers = []\n",
                "`bom.members`",
                "Explicit BOM members must not be empty.");
    }

    @Test
    void anchorsDuplicateAndPortableMemberCollisionsToTheLaterItem() {
        assertSemanticFailure(
                "[bom]\nmembers = [\"apps/api\", \"apps/api\"]\n",
                "`bom.members[1]`",
                "duplicate");
        assertSemanticFailure(
                "[bom]\nmembers = [\"modules/Straße\", \"modules/STRASSE\"]\n",
                "`bom.members[1]`",
                "collide under Unicode case-fold comparison");
    }

    @Test
    void anchorsDuplicateAndPortableExclusionCollisionsToTheLaterItem() {
        assertSemanticFailure(
                "[bom]\nmembers = true\nexclude = [\"apps/api\", \"apps/api\"]\n",
                "`bom.exclude[1]`",
                "duplicate");
        assertSemanticFailure(
                "[bom]\nmembers = true\nexclude = [\"apps/Api\", \"apps/api\"]\n",
                "`bom.exclude[1]`",
                "collide under Unicode case-fold comparison");
    }

    @Test
    void rejectsEveryAuthoredExclusionWithExplicitMembers() {
        for (String exclude : List.of("[]", "[\"apps/legacy\"]")) {
            assertSemanticFailure(
                    "[bom]\nmembers = [\"apps/api\"]\nexclude = " + exclude + "\n",
                    "`bom.exclude`",
                    "BOM member exclusions are valid only with `members = true`.");
        }
    }

    @Test
    void requiresMembersBeforeDecodingExclusions() {
        ZoltConfigException failure = assertFailure("bom.exclude = []\n");
        assertEquals(
                "Missing required manifest field `bom.members`.",
                failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void followsCanonicalMemberThenExcludeOrderDespiteReverseAssignments() {
        ZoltConfigException failure = assertSemanticFailure(
                """
                [bom]
                exclude = ["apps/api", "apps/api"]
                members = false
                """,
                "`bom.members`",
                "BOM members must be `true` or a nonempty array");
        assertFalse(failure.getMessage().contains("bom.exclude"), failure.getMessage());
    }

    @Test
    void leavesEmptyTablesInvalidPathsAndWrongKindsToShapeValidation() {
        assertShapeFailure(
                "[bom]\n",
                "Manifest table `[bom]` must contain direct `members`");
        assertShapeFailure(
                "[bom]\nmembers = [\"../outside\"]\n",
                "`bom.members`");
        assertShapeFailure(
                "[bom]\nmembers = 42\n",
                "expected boolean or string array but found integer");
    }

    @Test
    void requiresANonNullDecodeIndexAndObserver() {
        assertThrows(
                NullPointerException.class,
                () -> decoder.decode(null, ignored -> { }));
        assertThrows(
                NullPointerException.class,
                () -> decoder.decode(ManifestSemanticTestSupport.index(""), null));
    }

    private Optional<AuthoredBom.Members> decode(String source) {
        return decoder.decode(ManifestSemanticTestSupport.index(source), ignored -> { });
    }

    private ZoltConfigException assertSemanticFailure(
            String source,
            String path,
            String detail) {
        ZoltConfigException failure = assertFailure(source);
        assertTrue(failure.getMessage().contains(path), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        return failure;
    }

    private void assertShapeFailure(String source, String detail) {
        ZoltConfigException failure = assertFailure(source);
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
        assertNull(failure.getCause());
    }

    private ZoltConfigException assertFailure(String source) {
        return assertThrows(ZoltConfigException.class, () -> decode(source));
    }

    private static WorkspaceMemberPath path(String value) {
        return new WorkspaceMemberPath(value);
    }
}
