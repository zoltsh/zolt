package sh.zolt.sbom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.LockDependencyGraphException;

final class LockSbomAssemblerTest extends SbomTestSupport {
    private final LockSbomAssembler assembler = new LockSbomAssembler();

    @Test
    void excludesOptionalScopesByDefault() {
        SbomModel model = assemble(
                SbomScopeSelection.requiredOnly(),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()),
                maven("org.example", "test-lib", "2.0.0", DependencyScope.TEST, true, SHA_B, List.of()));

        assertEquals(List.of("pkg:maven/org.example/lib-a@1.0.0?type=jar"), purls(model));
    }

    @Test
    void includesTestScopeAsOptionalWhenSelected() {
        SbomModel model = assemble(
                new SbomScopeSelection(false, false, true, false),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()),
                maven("org.example", "test-lib", "2.0.0", DependencyScope.TEST, true, SHA_B, List.of()));

        assertEquals(SbomComponentScope.REQUIRED, scopeOf(model, "lib-a"));
        assertEquals(SbomComponentScope.OPTIONAL, scopeOf(model, "test-lib"));
    }

    @Test
    void dedupsMultiScopePackagesWithRequiredWinning() {
        SbomModel model = assemble(
                new SbomScopeSelection(false, true, false, false),
                maven("org.example", "dup", "1.0.0", DependencyScope.DEV, false, SHA_A, List.of()),
                maven("org.example", "dup", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        assertEquals(List.of("pkg:maven/org.example/dup@1.0.0?type=jar"), purls(model));
        assertEquals(SbomComponentScope.REQUIRED, scopeOf(model, "dup"));
    }

    @Test
    void twoVariantsOfOneGavAreDistinctComponentsWithVariantResolvedEdges() {
        // lib-a depends on both the plain jar and the linux-x86_64 classified jar of one GAV. The edges are
        // variant-qualified, so each must resolve to its OWN component purl rather than collapsing to one.
        SbomModel model = assemble(
                SbomScopeSelection.requiredOnly(),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A,
                        List.of("io.netty:netty:4.1.0", "io.netty:netty:4.1.0:jar|linux-x86_64")),
                maven("io.netty", "netty", "4.1.0", DependencyScope.COMPILE, false, SHA_B, List.of()),
                classified("io.netty", "netty", "4.1.0", "linux-x86_64", "jar", SHA_C, List.of()));

        assertTrue(purls(model).contains("pkg:maven/io.netty/netty@4.1.0?type=jar"));
        assertTrue(purls(model).contains("pkg:maven/io.netty/netty@4.1.0?classifier=linux-x86_64&type=jar"));
        assertEquals(
                List.of(
                        "pkg:maven/io.netty/netty@4.1.0?classifier=linux-x86_64&type=jar",
                        "pkg:maven/io.netty/netty@4.1.0?type=jar"),
                dependsOn(model, "pkg:maven/org.example/lib-a@1.0.0?type=jar"));
    }

    @Test
    void filtersEdgesToSurvivingEndpoints() {
        SbomModel included = assemble(
                new SbomScopeSelection(false, false, true, false),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A,
                        List.of("org.example:test-only:9.0.0")),
                maven("org.example", "test-only", "9.0.0", DependencyScope.TEST, false, SHA_C, List.of()));
        assertEquals(
                List.of("pkg:maven/org.example/test-only@9.0.0?type=jar"),
                dependsOn(included, "pkg:maven/org.example/lib-a@1.0.0?type=jar"));

        SbomModel excluded = assemble(
                SbomScopeSelection.requiredOnly(),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A,
                        List.of("org.example:test-only:9.0.0")),
                maven("org.example", "test-only", "9.0.0", DependencyScope.TEST, false, SHA_C, List.of()));
        assertTrue(dependsOn(excluded, "pkg:maven/org.example/lib-a@1.0.0?type=jar").isEmpty());
        assertFalse(purls(excluded).contains("pkg:maven/org.example/test-only@9.0.0?type=jar"));
    }

    @Test
    void rootDependsOnDirectIncludedPackagesOnly() {
        SbomModel model = assemble(
                SbomScopeSelection.requiredOnly(),
                maven("org.example", "direct-lib", "1.0.0", DependencyScope.COMPILE, true, SHA_A,
                        List.of("org.example:transitive:2.0.0")),
                maven("org.example", "transitive", "2.0.0", DependencyScope.COMPILE, false, SHA_B, List.of()));

        assertEquals(
                List.of("pkg:maven/org.example/direct-lib@1.0.0?type=jar"),
                dependsOn(model, "pkg:maven/com.example/demo@0.1.0?type=jar"));
    }

    @Test
    void derivesClassifierAndTypeFromArtifactFilename() {
        SbomModel model = assemble(
                SbomScopeSelection.requiredOnly(),
                classified("io.netty", "netty-transport", "4.1.0", "linux-x86_64", "jar", SHA_A, List.of()));

        assertEquals(
                List.of("pkg:maven/io.netty/netty-transport@4.1.0?classifier=linux-x86_64&type=jar"),
                purls(model));
    }

    @Test
    void usesFallbackSeedWhenNoFingerprint() {
        SbomModel model = assemble(
                SbomScopeSelection.requiredOnly(),
                maven("org.example", "lib-a", "1.0.0", DependencyScope.COMPILE, true, SHA_A, List.of()));

        String expectedSeed = SbomSerialNumber.fallbackSeed(
                "com.example:demo:0.1.0", List.of("pkg:maven/org.example/lib-a@1.0.0?type=jar"));
        assertEquals(SbomSerialNumber.serialNumber(expectedSeed), model.serialNumber());
    }

    @Test
    void refusesAmbiguousLegacyEdgesInsteadOfSilentlyDroppingThem() {
        ZoltLockfile legacy = new ZoltLockfile(
                2,
                List.of(
                        maven(
                                "org.example",
                                "parent",
                                "1.0.0",
                                DependencyScope.COMPILE,
                                true,
                                SHA_A,
                                List.of("org.example:shared:1.0.0")),
                        maven(
                                "org.example",
                                "shared",
                                "1.0.0",
                                DependencyScope.COMPILE,
                                false,
                                SHA_B,
                                List.of()),
                        maven(
                                "org.example",
                                "shared",
                                "1.0.0",
                                DependencyScope.RUNTIME,
                                false,
                                SHA_B,
                                List.of())),
                List.of());

        LockDependencyGraphException exception = assertThrows(
                LockDependencyGraphException.class,
                () -> assembler.assemble(
                        config(),
                        legacy,
                        SbomScopeSelection.requiredOnly(),
                        Optional.empty(),
                        TOOL_VERSION));

        assertTrue(exception.getMessage().contains("Run `zolt resolve`"));
        assertTrue(exception.getMessage().contains("version 5"));
    }

    @Test
    void validatesLegacyAmbiguityBeforeFilteringProvidedTestAndProcessorScopes() {
        for (DependencyScope hiddenScope : List.of(
                DependencyScope.PROVIDED,
                DependencyScope.TEST,
                DependencyScope.PROCESSOR)) {
            ZoltLockfile legacy = new ZoltLockfile(
                    2,
                    List.of(
                            maven(
                                    "org.example",
                                    "parent",
                                    "1.0.0",
                                    DependencyScope.COMPILE,
                                    true,
                                    SHA_A,
                                    List.of("org.example:shared:1.0.0")),
                            maven(
                                    "org.example",
                                    "shared",
                                    "1.0.0",
                                    DependencyScope.COMPILE,
                                    false,
                                    SHA_B,
                                    List.of()),
                            maven(
                                    "org.example",
                                    "shared",
                                    "1.0.0",
                                    hiddenScope,
                                    false,
                                    SHA_B,
                                    List.of())),
                    List.of());

            LockDependencyGraphException exception = assertThrows(
                    LockDependencyGraphException.class,
                    () -> assembler.assemble(
                            config(),
                            legacy,
                            SbomScopeSelection.requiredOnly(),
                            Optional.empty(),
                            TOOL_VERSION),
                    hiddenScope::lockfileName);

            assertTrue(exception.getMessage().contains("ambiguous"), hiddenScope::lockfileName);
            assertTrue(exception.getMessage().contains("version 5"), hiddenScope::lockfileName);
        }
    }

    private SbomModel assemble(SbomScopeSelection selection, sh.zolt.lockfile.LockPackage... packages) {
        ZoltLockfile lockfile = lockfile(Optional.empty(), packages);
        return assembler.assemble(config(), lockfile, selection, Optional.empty(), TOOL_VERSION);
    }

    private static List<String> purls(SbomModel model) {
        return model.components().stream().map(SbomComponent::purl).toList();
    }

    private static SbomComponentScope scopeOf(SbomModel model, String name) {
        return model.components().stream()
                .filter(component -> component.name().equals(name))
                .map(SbomComponent::scope)
                .findFirst()
                .orElseThrow();
    }

    private static List<String> dependsOn(SbomModel model, String ref) {
        return model.dependencies().stream()
                .filter(dependency -> dependency.ref().equals(ref))
                .map(SbomDependency::dependsOn)
                .findFirst()
                .orElseThrow();
    }
}
