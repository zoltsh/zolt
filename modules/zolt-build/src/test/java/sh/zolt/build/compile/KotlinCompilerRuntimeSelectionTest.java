package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import sh.zolt.build.KotlinCompileException;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.NestedArtifactIdentity.SourceKind;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;

final class KotlinCompilerRuntimeSelectionTest
        extends KotlinCompilerToolchainResolverTestSupport {
    @Test
    void requiresOneMainCompileVisibleRuntimeAtConfiguredVersion() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        ResolvedClasspathPackage toolRoot = toolRoot(root);

        assertMessageContains(
                () -> resolver.resolve(List.of(toolRoot), VERSION),
                "no ordinary main-source-set-visible external default JAR");
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                toolRoot,
                                dependency(
                                        STDLIB,
                                        runtime,
                                        VERSION,
                                        DependencyScope.TEST,
                                        false,
                                        stdlibIdentity(VERSION))),
                        VERSION),
                "no ordinary main-source-set-visible external default JAR");
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                toolRoot,
                                dependency(
                                        STDLIB,
                                        runtime,
                                        "2.1.0",
                                        DependencyScope.COMPILE,
                                        false,
                                        stdlibIdentity("2.1.0"))),
                        VERSION),
                "ordinary runtime version `2.1.0` does not match configured version `2.2.0`");
    }

    @Test
    void testCompilationAcceptsATestVisibleRuntimeWithoutMakingItMainVisible() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "test-stdlib");
        List<ResolvedClasspathPackage> packages = List.of(
                toolRoot(root),
                dependency(
                        STDLIB,
                        runtime,
                        VERSION,
                        DependencyScope.TEST,
                        true,
                        stdlibIdentity(VERSION)));

        assertDoesNotThrow(() -> resolver.resolve(
                packages,
                VERSION,
                KotlinCompilationScope.TEST));
        assertMessageContains(
                () -> resolver.resolve(packages, VERSION),
                "no ordinary main-source-set-visible external default JAR");
    }

    @Test
    void acceptsOneExternalDefaultRuntimeAndIgnoresOtherVariants() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib-is-not-compiler-root");
        VerifiedJar sources = plainJar(STDLIB, VERSION, "sources");
        NestedArtifactIdentity classified = new NestedArtifactIdentity(
                STDLIB.groupId(),
                STDLIB.artifactId(),
                VERSION,
                "jar",
                Optional.of("sources"),
                SourceKind.EXTERNAL);

        assertDoesNotThrow(() -> resolver.resolve(
                List.of(
                        toolRoot(root),
                        dependency(
                                STDLIB,
                                runtime,
                                VERSION,
                                DependencyScope.PROVIDED,
                                false,
                                stdlibIdentity(VERSION)),
                        dependency(
                                STDLIB,
                                sources,
                                VERSION,
                                DependencyScope.COMPILE,
                                false,
                                classified)),
                VERSION));
    }

    @Test
    void rejectsAmbiguousDistinctDefaultRuntimes() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar first = plainJar(STDLIB, VERSION, "first");
        VerifiedJar second = plainJar(STDLIB, VERSION, "second");

        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                toolRoot(root),
                                dependency(
                                        STDLIB,
                                        first,
                                        VERSION,
                                        DependencyScope.COMPILE,
                                        false,
                                        stdlibIdentity(VERSION)),
                                dependency(
                                        STDLIB,
                                        second,
                                        VERSION,
                                        DependencyScope.PROVIDED,
                                        false,
                                        stdlibIdentity(VERSION))),
                        VERSION),
                "runtime is ambiguous");
    }

    @Test
    void deduplicatesRepeatedSelectionOfSameRuntimePath() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");

        assertDoesNotThrow(() -> resolver.resolve(
                List.of(
                        toolRoot(root),
                        dependency(
                                STDLIB,
                                runtime,
                                VERSION,
                                DependencyScope.COMPILE,
                                false,
                                stdlibIdentity(VERSION)),
                        dependency(
                                STDLIB,
                                runtime,
                                VERSION,
                                DependencyScope.PROVIDED,
                                false,
                                stdlibIdentity(VERSION))),
                VERSION));
    }

    @Test
    void rejectsWorkspaceClassifiedAndIdentityMismatchedRuntimeOnly() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        NestedArtifactIdentity workspace = new NestedArtifactIdentity(
                STDLIB.groupId(),
                STDLIB.artifactId(),
                VERSION,
                "jar",
                Optional.empty(),
                SourceKind.WORKSPACE);
        NestedArtifactIdentity classified = new NestedArtifactIdentity(
                STDLIB.groupId(),
                STDLIB.artifactId(),
                VERSION,
                "jar",
                Optional.of("sources"),
                SourceKind.EXTERNAL);

        assertMissingRuntime(root, runtime, workspace);
        assertMissingRuntime(root, runtime, classified);
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                toolRoot(root),
                                dependency(
                                        STDLIB,
                                        runtime,
                                        VERSION,
                                        DependencyScope.COMPILE,
                                        false,
                                        NestedArtifactIdentity.external(
                                                new PackageId("wrong", "identity"), VERSION))),
                        VERSION),
                "resolved package and artifact identities disagree");
    }

    @Test
    void rejectsRuntimeWithoutCurrentVerifiedHash() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        Path runtimePath = unverifiedPlainJar("stdlib");
        VerifiedJar runtime = new VerifiedJar(runtimePath, sha256(runtimePath));

        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                toolRoot(root),
                                dependency(
                                        STDLIB,
                                        runtime,
                                        VERSION,
                                        DependencyScope.COMPILE,
                                        false,
                                        stdlibIdentity(VERSION))),
                        VERSION),
                "has no current checksum-verified artifact identity");
    }

    private ResolvedClasspathPackage toolRoot(VerifiedJar root) {
        return dependency(
                COMPILER,
                root,
                VERSION,
                DependencyScope.TOOL_KOTLIN,
                true,
                compilerIdentity(VERSION));
    }

    private void assertMissingRuntime(
            VerifiedJar root,
            VerifiedJar runtime,
            NestedArtifactIdentity identity) {
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                toolRoot(root),
                                dependency(
                                        STDLIB,
                                        runtime,
                                        VERSION,
                                        DependencyScope.COMPILE,
                                        false,
                                        identity)),
                        VERSION),
                "no ordinary main-source-set-visible external default JAR");
    }

    private static void assertMessageContains(
            ThrowingAction action,
            String expectedMessage) {
        KotlinCompileException failure = assertThrows(KotlinCompileException.class, action::run);
        assertTrue(failure.getMessage().contains(expectedMessage), failure.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run();
    }
}
