package sh.zolt.resolve.lockfile.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;

import sh.zolt.dependency.ConflictSelectionReason;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.resolve.graph.ResolutionGraph;
import sh.zolt.resolve.request.DependencyRequest;
import sh.zolt.resolve.request.RequestOrigin;
import sh.zolt.resolve.version.VersionConflict;
import sh.zolt.resolve.version.VersionSelectionResult;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class LockfileResolutionEvidenceTest {
    @Test
    void attributesSamePackageConflictsToMainGroovyAndKotlinResolutions() {
        PackageId shared = new PackageId("com.example", "shared");
        VersionConflict main = conflict(shared, DependencyScope.COMPILE, "1.5.0");
        VersionConflict groovy = conflict(shared, DependencyScope.TOOL_GROOVY, "2.0.0");
        VersionConflict kotlin = conflict(shared, DependencyScope.TOOL_KOTLIN, "3.0.0");

        var conflicts = LockfileResolutionEvidence.conflicts(
                selection(main),
                List.of(
                        compiler(
                                DependencyScope.TOOL_KOTLIN,
                                "Kotlin",
                                new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable"),
                                "2.2.0",
                                kotlin),
                        compiler(
                                DependencyScope.TOOL_GROOVY,
                                "Groovy",
                                new PackageId("org.apache.groovy", "groovy"),
                                "4.0.23",
                                groovy)),
                List.of());

        assertEquals(
                List.of(
                        Optional.empty(),
                        Optional.of("compiler:tool-groovy:b3JnLmFwYWNoZS5ncm9vdnk6Z3Jvb3Z5.NC4wLjIz.amFy"),
                        Optional.of("compiler:tool-kotlin:b3JnLmpldGJyYWlucy5rb3RsaW46a290bGluLWNvbXBpbGVyLWVtYmVkZGFibGU.Mi4yLjA.amFy")),
                conflicts.stream().map(conflict -> conflict.toolGroup()).toList());
        assertEquals(List.of("1.5.0", "2.0.0", "3.0.0"),
                conflicts.stream().map(conflict -> conflict.selectedVersion()).toList());
    }

    @Test
    void ignoresLegacyCompilerClosuresWithoutConflictsOrDirectRequests() {
        CompilerToolResolution legacy = new CompilerToolResolution(
                DependencyScope.TOOL_GROOVY,
                "Groovy",
                new ResolutionGraph(List.of(), List.of(), List.of()),
                new VersionSelectionResult(List.of(), List.of()),
                List.of());

        assertEquals(
                List.of(),
                LockfileResolutionEvidence.conflicts(
                        new VersionSelectionResult(List.of(), List.of()),
                        List.of(legacy),
                        List.of()));
    }

    private static CompilerToolResolution compiler(
            DependencyScope scope,
            String name,
            PackageId root,
            String rootVersion,
            VersionConflict conflict) {
        return new CompilerToolResolution(
                scope,
                name,
                new ResolutionGraph(List.of(), List.of(), List.of(conflict)),
                selection(conflict),
                List.of(new DependencyRequest(root, rootVersion, scope, RequestOrigin.DIRECT)));
    }

    private static VersionSelectionResult selection(VersionConflict conflict) {
        return new VersionSelectionResult(List.of(), List.of(conflict));
    }

    private static VersionConflict conflict(PackageId packageId, DependencyScope scope, String selected) {
        return new VersionConflict(
                packageId,
                List.of(
                        new DependencyRequest(packageId, "1.0.0", scope, RequestOrigin.TRANSITIVE),
                        new DependencyRequest(packageId, selected, scope, RequestOrigin.TRANSITIVE)),
                selected,
                ConflictSelectionReason.NEWEST_VERSION);
    }
}
