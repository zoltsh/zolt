package sh.zolt.toml.manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.DependencySelector;
import sh.zolt.manifest.authored.AuthoredDependency;
import sh.zolt.toml.ZoltConfigException;
import sh.zolt.toml.schema.FinalManifestDependencyFields;

final class ManifestDependencyEntryDecoderTest {
    private static final Set<DependencyLane> OPTIONAL_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME);
    private static final Set<DependencyLane> PUBLISH_ONLY_LANES = EnumSet.of(
            DependencyLane.API,
            DependencyLane.IMPLEMENTATION,
            DependencyLane.RUNTIME,
            DependencyLane.PROVIDED);

    @Test
    void decodesEverySelectorWithoutResolvingReferencesOrSnapshots() {
        DependencySelector.FixedVersion shorthand = assertInstanceOf(
                DependencySelector.FixedVersion.class,
                decode("\"1.0-SNAPSHOT\"").selector());
        DependencySelector.FixedVersion fixed = assertInstanceOf(
                DependencySelector.FixedVersion.class,
                decode("{ version = \"2.0\" }").selector());
        DependencySelector.VersionReference reference = assertInstanceOf(
                DependencySelector.VersionReference.class,
                decode("{ versionRef = \"not-declared-here\" }").selector());

        assertEquals("1.0-SNAPSHOT", shorthand.value());
        assertEquals("2.0", fixed.value());
        assertEquals("not-declared-here", reference.alias().value());
        assertInstanceOf(
                DependencySelector.Managed.class,
                decode("{ managed = true }").selector());
        assertInstanceOf(
                DependencySelector.Workspace.class,
                decode("{ workspace = true }").selector());
    }

    @Test
    void rejectsFalseSelectorBooleansAndAnchorsSelectorValueFailures() {
        assertFailure(
                "{ managed = false }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.managed",
                "must be true");
        assertFailure(
                "{ workspace = false }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.workspace",
                "must be true");
        assertFailure(
                "\"LATEST\"",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo",
                "Invalid dependency version");
        assertFailure(
                "{ version = \"LATEST\" }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.version",
                "Invalid dependency version");
        assertFailure(
                "{ versionRef = \"Bad_Id\" }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.versionRef",
                "Invalid local ID");
    }

    @Test
    void decodesMetadataInCanonicalOrderAndPreservesObservableValues() {
        AuthoredDependency dependency = decode(
                "{ version = \"1.0\", optional = true, publishOnly = true, "
                        + "classifier = \"tests\", type = \"test-jar\", "
                        + "exclude = [\"legacy:logging\", \"legacy:bridge\"] }",
                DependencyLane.API);

        assertTrue(dependency.metadata().optional());
        assertTrue(dependency.metadata().publishOnly());
        assertEquals("tests", dependency.metadata().classifier().orElseThrow());
        assertEquals("test-jar", dependency.metadata().type().orElseThrow());
        assertEquals(
                List.of(
                        new DependencyCoordinate("legacy:logging"),
                        new DependencyCoordinate("legacy:bridge")),
                dependency.metadata().exclusions());
        assertThrows(
                UnsupportedOperationException.class,
                () -> dependency.metadata().exclusions().add(
                        new DependencyCoordinate("legacy:other")));
    }

    @Test
    void explicitFalseAndEmptyMetadataCollapseWithoutChangingTheDependency() {
        AuthoredDependency dependency = decode(
                "{ version = \"1.0\", optional = false, publishOnly = false, exclude = [] }",
                DependencyLane.TEST);

        assertFalse(dependency.metadata().optional());
        assertFalse(dependency.metadata().publishOnly());
        assertTrue(dependency.metadata().exclusions().isEmpty());
    }

    @Test
    void optionalityIsAcceptedOnlyInItsThreeObservableLanes() {
        for (DependencyLane lane : DependencyLane.values()) {
            String value = "{ workspace = true, optional = true }";
            if (OPTIONAL_LANES.contains(lane)) {
                assertTrue(decode(value, lane).metadata().optional(), lane.toString());
            } else {
                assertFailure(
                        value,
                        lane,
                        "dependencies.org.example:demo.optional",
                        "not meaningful");
            }
        }
    }

    @Test
    void publishOnlyRequiresAPublishedLaneAndFixedOrReferencedVersion() {
        for (DependencyLane lane : DependencyLane.values()) {
            String fixed = "{ version = \"1.0\", publishOnly = true }";
            if (PUBLISH_ONLY_LANES.contains(lane)) {
                assertTrue(decode(fixed, lane).metadata().publishOnly(), lane.toString());
            } else {
                assertFailure(
                        fixed,
                        lane,
                        "dependencies.org.example:demo.publishOnly",
                        "not allowed");
            }
        }
        assertTrue(decode(
                "{ versionRef = \"release\", publishOnly = true }",
                DependencyLane.PROVIDED).metadata().publishOnly());
        assertFailure(
                "{ managed = true, publishOnly = true }",
                DependencyLane.API,
                "dependencies.org.example:demo.publishOnly",
                "require a fixed version or version reference");
        assertFailure(
                "{ workspace = true, publishOnly = true }",
                DependencyLane.API,
                "dependencies.org.example:demo.publishOnly",
                "require a fixed version or version reference");
    }

    @Test
    void externalMetadataWorksForExternalSelectorsAndFailsAtWorkspaceMembers() {
        for (DependencyLane lane : DependencyLane.values()) {
            AuthoredDependency dependency = decode(
                    "{ managed = true, classifier = \"tests\", type = \"zip\", exclude = [\"a:b\"] }",
                    lane);
            assertEquals("tests", dependency.metadata().classifier().orElseThrow());
            assertEquals("zip", dependency.metadata().type().orElseThrow());
        }
        assertFailure(
                "{ workspace = true, classifier = \"tests\" }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.classifier",
                "Workspace dependencies cannot declare");
        assertFailure(
                "{ workspace = true, type = \"zip\" }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.type",
                "Workspace dependencies cannot declare");
        assertFailure(
                "{ workspace = true, exclude = [\"a:b\"] }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.exclude[0]",
                "Workspace dependencies cannot declare");
    }

    @Test
    void anchorsVariantAndExclusionValidationToExactNestedPaths() {
        assertFailure(
                "{ version = \"1.0\", classifier = \"bad|classifier\" }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.classifier",
                "must not contain `|`");
        assertFailure(
                "{ version = \"1.0\", type = \"bad|type\" }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.type",
                "must not contain `|`");
        assertFailure(
                "{ version = \"1.0\", exclude = [\"a:b\", \"invalid\"] }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.exclude[1]",
                "Invalid dependency coordinate");
        assertFailure(
                "{ version = \"1.0\", exclude = [\"a:b\", \"a:b\"] }",
                DependencyLane.IMPLEMENTATION,
                "dependencies.org.example:demo.exclude[1]",
                "declared more than once");
    }

    private static AuthoredDependency decode(String value) {
        return decode(value, DependencyLane.IMPLEMENTATION);
    }

    private static AuthoredDependency decode(
            String value,
            DependencyLane lane) {
        ManifestDecodeIndex index = ManifestSemanticTestSupport.index(
                "[dependencies]\n\"org.example:demo\" = " + value.strip() + "\n");
        ManifestDecodeIndex.Entry entry = index
                .entries(FinalManifestDependencyFields.DEPENDENCIES_ENTRY)
                .getFirst();
        return new ManifestDependencyEntryDecoder().decode(lane, entry);
    }

    private static void assertFailure(
            String value,
            DependencyLane lane,
            String path,
            String detail) {
        ZoltConfigException failure = assertThrows(
                ZoltConfigException.class,
                () -> decode(value, lane));
        assertTrue(failure.getMessage().contains("`" + path + "`"), failure.getMessage());
        assertTrue(failure.getMessage().contains(detail), failure.getMessage());
    }
}
