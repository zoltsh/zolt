package sh.zolt.explain.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.maven.MavenDependencyExclusion;
import sh.zolt.explain.maven.MavenDependencyInspection;
import sh.zolt.manifest.DependencyConstraintSelector;
import sh.zolt.manifest.DependencyCoordinate;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredDependencyConstraint;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredPackaging;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class MavenDependencyMapperEdgeTest {
    @Test
    void sectionMapperNotesUnsupportedScopesAndKeepsMappableEdges() {
        WorkspaceMemberRegistry registry = new WorkspaceMemberRegistry();
        registry.register("com.acme:lib", "lib");
        registry.register("com.acme:test-lib", "test-lib");
        List<String> notes = new ArrayList<>();
        DraftDependencies dependencies = new DraftDependencies(notes);
        // A platform import is what turns a version-less dependency into a { managed = true } entry.
        dependencies.platform("com.acme:bom", "1.0.0");
        MavenDependencySectionMapper mapper = new MavenDependencySectionMapper(
                registry,
                dependencies,
                Map.of("com.acme:pinned", "1.2.3"),
                notes);

        mapper.map(dependency("runtime", "com.acme:pinned", ""));
        mapper.map(dependency(
                "provided",
                "com.acme:managed",
                "",
                List.of(new MavenDependencyExclusion("org.legacy", "legacy-api"))));
        mapper.map(dependency("compile", "com.acme:lib", "1.0.0"));
        mapper.map(dependency("test", "com.acme:test-lib", "1.0.0"));
        mapper.map(dependency("test", "com.acme:lib", "1.0.0"));
        mapper.map(dependency("runtime", "com.acme:lib", "1.0.0"));
        mapper.map(dependency("integrationTest", "com.acme:custom", "1.0.0"));
        mapper.map(dependency("integrationTest", "com.acme:managed-custom", ""));
        mapper.map(dependency("compile", "com.acme:placeholder", "${revision}"));

        DraftManifestSubject subject = subjectOf(dependencies, notes);

        assertEquals("1.2.3", subject.fixed(DependencyLane.RUNTIME).get("com.acme:pinned"),
                () -> subject.fixed(DependencyLane.RUNTIME).toString());
        assertTrue(subject.managed(DependencyLane.PROVIDED).contains("com.acme:managed"),
                () -> subject.managed(DependencyLane.PROVIDED).toString());
        assertEquals(
                List.of("org.legacy:legacy-api"),
                subject.dependency(DependencyLane.PROVIDED, "com.acme:managed").metadata().exclusions()
                        .stream()
                        .map(DependencyCoordinate::value)
                        .toList());
        assertTrue(subject.workspaceMembers(DependencyLane.IMPLEMENTATION).contains("com.acme:lib"),
                () -> subject.workspaceMembers(DependencyLane.IMPLEMENTATION).toString());
        assertTrue(subject.workspaceMembers(DependencyLane.TEST).contains("com.acme:test-lib"),
                () -> subject.workspaceMembers(DependencyLane.TEST).toString());
        // One coordinate resolves to one lane, so the second sibling edge is reported, not emitted.
        assertFalse(subject.workspaceMembers(DependencyLane.TEST).contains("com.acme:lib"),
                () -> subject.workspaceMembers(DependencyLane.TEST).toString());
        assertTrue(notes.stream().anyMatch(note -> note.contains("com.acme:lib")
                && note.contains("declared in both the implementation and test lanes")), () -> notes.toString());
        assertFalse(subject.coordinates(DependencyLane.RUNTIME).contains("com.acme:lib"),
                () -> subject.coordinates(DependencyLane.RUNTIME).toString());
        assertTrue(notes.stream().anyMatch(note -> note.contains("Maven scope `runtime`")
                && note.contains("cannot express as a workspace edge")), () -> notes.toString());
        assertTrue(notes.stream().anyMatch(note -> note.contains("com.acme:custom")
                && note.contains("Maven scope `integrationTest`")
                && note.contains("has no direct Zolt lane")), () -> notes.toString());
        assertTrue(notes.stream().anyMatch(note -> note.contains("managed-custom")
                && note.contains("has no direct Zolt lane")), () -> notes.toString());
        assertTrue(notes.stream().anyMatch(note -> note.contains("placeholder")
                && note.contains("property the static audit could not resolve")), () -> notes.toString());
    }

    @Test
    void constraintMapperSkipsDirectCoordinatesAndNotesUnmappableManagedEntries() {
        List<String> notes = new ArrayList<>();
        AuthoredDependencyConstraints constraints = MavenDependencyConstraintMapper.map(
                List.of(
                        dependency("compile", "com.acme:direct", "1.0.0"),
                        dependency("compile", "com.acme:classifier", "1.0.0", "jar", "tests"),
                        dependency("compile", "com.acme:test-jar", "1.0.0", "test-jar", ""),
                        dependency("compile", "com.acme:property", "${revision}"),
                        dependency("compile", "com.acme:blank", ""),
                        dependency("compile", "com.acme:constraint", "2.0.0")),
                List.of(dependency("compile", "com.acme:direct", "1.0.0")),
                notes).orElseThrow();

        assertEquals(
                List.of("com.acme:constraint"),
                constraints.entries().keySet().stream().map(DependencyCoordinate::value).toList());
        AuthoredDependencyConstraint constraint =
                constraints.entries().get(new DependencyCoordinate("com.acme:constraint"));
        // [dependencies.constraints] entries are strict by construction; the selector carries the version.
        assertEquals(new DependencyConstraintSelector.FixedVersion("2.0.0"), constraint.selector());
        assertEquals("Imported from Maven dependencyManagement.", constraint.reason().orElseThrow());
        assertTrue(notes.stream().anyMatch(note -> note.contains("com.acme:classifier")
                && note.contains("classifier `tests`")), () -> notes.toString());
        assertTrue(notes.stream().anyMatch(note -> note.contains("com.acme:test-jar")
                && note.contains("Maven type `test-jar`")), () -> notes.toString());
        assertTrue(notes.stream().anyMatch(note -> note.contains("com.acme:property")
                && note.contains("property the static audit could not resolve")), () -> notes.toString());
        assertFalse(notes.stream().anyMatch(note -> note.contains("com.acme:direct")), () -> notes.toString());
        assertFalse(notes.stream().anyMatch(note -> note.contains("com.acme:blank")), () -> notes.toString());
    }

    /** Wraps the drafted lanes in a minimal authored manifest so the shared subject can read them. */
    private static DraftManifestSubject subjectOf(DraftDependencies dependencies, List<String> notes) {
        return DraftManifestSubject.of(DraftManifests.project(
                DraftManifests.identity(
                        "edge-demo",
                        Optional.of("com.acme"),
                        Optional.of("1.0.0"),
                        Optional.empty(),
                        notes),
                DraftManifests.metadata(Optional.empty(), notes),
                dependencies,
                Optional.empty(),
                AuthoredBuildConfiguration.empty(),
                Optional.empty(),
                AuthoredPackaging.empty()));
    }

    private static MavenDependencyInspection dependency(String scope, String coordinate, String version) {
        return dependency(scope, coordinate, version, "jar", "");
    }

    private static MavenDependencyInspection dependency(
            String scope,
            String coordinate,
            String version,
            List<MavenDependencyExclusion> exclusions) {
        return new MavenDependencyInspection(
                scope,
                coordinate,
                version,
                "jar",
                false,
                false,
                false,
                true,
                "",
                exclusions);
    }

    private static MavenDependencyInspection dependency(
            String scope,
            String coordinate,
            String version,
            String type,
            String classifier) {
        return new MavenDependencyInspection(
                scope,
                coordinate,
                version,
                type,
                false,
                false,
                false,
                true,
                classifier,
                List.of());
    }
}
