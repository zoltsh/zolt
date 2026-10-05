package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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

final class KotlinCompilerToolchainResolverTest
        extends KotlinCompilerToolchainResolverTestSupport {
    @Test
    void resolvesVerifiedClosureInRelocatableDeterministicOrder() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION + "-release-123", true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "ordinary-runtime-content");
        VerifiedJar alpha = plainJar(ALPHA, "1.0.0", "alpha");
        VerifiedJar zeta = plainJar(ZETA, "2.0.0", "zeta");

        KotlinCompilerToolchain toolchain = resolver.resolve(
                validPackages(
                        root,
                        runtime,
                        dependency(
                                ZETA,
                                zeta,
                                "2.0.0",
                                DependencyScope.TOOL_KOTLIN,
                                false,
                                NestedArtifactIdentity.external(ZETA, "2.0.0")),
                        dependency(
                                ALPHA,
                                alpha,
                                "1.0.0",
                                DependencyScope.TOOL_KOTLIN,
                                false,
                                NestedArtifactIdentity.external(ALPHA, "1.0.0"))),
                "  " + VERSION + "  ");

        assertEquals(KotlinCompilerToolchain.COORDINATE, toolchain.coordinate());
        assertEquals(VERSION, toolchain.version());
        assertEquals(root.sha256(), toolchain.sha256());
        assertEquals(
                List.of(
                        root.path().toAbsolutePath().normalize(),
                        alpha.path().toAbsolutePath().normalize(),
                        zeta.path().toAbsolutePath().normalize()),
                toolchain.launcherClasspath().entries());
        assertTrue(toolchain.identity().startsWith(
                KotlinCompilerToolchain.COORDINATE + ":" + VERSION + "@sha256:"
                        + root.sha256() + "|launcher=sha256:"));
        assertFalse(toolchain.identity().contains(tempDir.toString()));
        assertNotEquals(root.sha256(), runtime.sha256());
    }

    @Test
    void resolvesVerifiedKaptPluginAsAnIsolatedToolRoot() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar kapt = kaptJar(VERSION + "-release-294", true);

        KotlinCompilerToolchain toolchain = resolver.resolve(
                validPackages(
                        root,
                        runtime,
                        dependency(
                                KAPT,
                                kapt,
                                VERSION,
                                DependencyScope.TOOL_KOTLIN,
                                true,
                                NestedArtifactIdentity.external(KAPT, VERSION))),
                VERSION);

        assertEquals(
                kapt.path().toAbsolutePath().normalize(),
                toolchain.kaptPluginJar().orElseThrow());
        assertTrue(toolchain.launcherClasspath().entries().contains(
                kapt.path().toAbsolutePath().normalize()));
        assertFalse(toolchain.identity().contains(tempDir.toString()));
    }

    @Test
    void rejectsMismatchedOrInvalidKaptPluginRoots() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar kapt = kaptJar(VERSION, true);
        VerifiedJar missingEntry = kaptJar(VERSION, false);

        assertMessageContains(
                () -> resolver.resolve(
                        validPackages(
                                root,
                                runtime,
                                dependency(
                                        KAPT,
                                        kapt,
                                        "2.2.1",
                                        DependencyScope.TOOL_KOTLIN,
                                        true,
                                        NestedArtifactIdentity.external(KAPT, "2.2.1"))),
                        VERSION),
                "does not match zolt.lock KAPT tool root version `2.2.1`");
        assertMessageContains(
                () -> resolver.resolve(
                        validPackages(
                                root,
                                runtime,
                                dependency(
                                        KAPT,
                                        missingEntry,
                                        VERSION,
                                        DependencyScope.TOOL_KOTLIN,
                                        true,
                                        NestedArtifactIdentity.external(KAPT, VERSION))),
                        VERSION),
                "KAPT plugin JAR does not contain " + KAPT_ENTRY);
    }

    @Test
    void acceptsExactManifestVersionAndRelocatesClosureIdentity() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar support = plainJar(ALPHA, "1.0.0", "stable-support");
        KotlinCompilerToolchain original = resolver.resolve(
                validPackages(
                        root,
                        runtime,
                        dependency(
                                ALPHA,
                                support,
                                "1.0.0",
                                DependencyScope.TOOL_KOTLIN,
                                false,
                                NestedArtifactIdentity.external(ALPHA, "1.0.0"))),
                VERSION);

        VerifiedJar movedRoot = verifiedCopy(root, COMPILER, VERSION, "compiler");
        VerifiedJar movedRuntime = verifiedCopy(runtime, STDLIB, VERSION, "stdlib");
        VerifiedJar movedSupport = verifiedCopy(support, ALPHA, "1.0.0", "support");
        KotlinCompilerToolchain relocated = resolver.resolve(
                validPackages(
                        movedRoot,
                        movedRuntime,
                        dependency(
                                ALPHA,
                                movedSupport,
                                "1.0.0",
                                DependencyScope.TOOL_KOTLIN,
                                false,
                                NestedArtifactIdentity.external(ALPHA, "1.0.0"))),
                VERSION);
        VerifiedJar changedSupport = plainJar(ALPHA, "1.0.0", "changed-support");
        KotlinCompilerToolchain changed = resolver.resolve(
                validPackages(
                        movedRoot,
                        movedRuntime,
                        dependency(
                                ALPHA,
                                changedSupport,
                                "1.0.0",
                                DependencyScope.TOOL_KOTLIN,
                                false,
                                NestedArtifactIdentity.external(ALPHA, "1.0.0"))),
                VERSION);

        assertEquals(original.identity(), relocated.identity());
        assertNotEquals(original.launcherClasspath(), relocated.launcherClasspath());
        assertNotEquals(original.identity(), changed.identity());
    }

    @Test
    void requiresConfiguredVersionAndExactlyOneDirectCompilerRoot() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar duplicate = verifiedCopy(root, COMPILER, VERSION, "duplicate");
        VerifiedJar extra = plainJar(ALPHA, "1.0.0", "extra");
        ResolvedClasspathPackage toolRoot = dependency(
                COMPILER,
                root,
                VERSION,
                DependencyScope.TOOL_KOTLIN,
                true,
                compilerIdentity(VERSION));
        ResolvedClasspathPackage ordinaryRuntime = dependency(
                STDLIB,
                runtime,
                VERSION,
                DependencyScope.COMPILE,
                false,
                stdlibIdentity(VERSION));

        assertMessageContains(
                () -> resolver.resolve(List.of(), " "),
                "`[toolchain.kotlin].version` is required");
        assertMessageContains(
                () -> resolver.resolve(List.of(ordinaryRuntime), VERSION),
                "no direct " + KotlinCompilerToolchain.COORDINATE + " root");
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                toolRoot,
                                dependency(
                                        ALPHA,
                                        extra,
                                        "1.0.0",
                                        DependencyScope.TOOL_KOTLIN,
                                        true,
                                        NestedArtifactIdentity.external(ALPHA, "1.0.0")),
                                ordinaryRuntime),
                        VERSION),
                "extra direct roots");
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                toolRoot,
                                dependency(
                                        COMPILER,
                                        duplicate,
                                        VERSION,
                                        DependencyScope.TOOL_KOTLIN,
                                        true,
                                        compilerIdentity(VERSION)),
                                ordinaryRuntime),
                        VERSION),
                "ambiguous direct " + KotlinCompilerToolchain.COORDINATE + " roots");
    }

    @Test
    void requiresConfiguredRootVersionAndExternalDefaultIdentity() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        ResolvedClasspathPackage ordinaryRuntime = dependency(
                STDLIB,
                runtime,
                VERSION,
                DependencyScope.COMPILE,
                false,
                stdlibIdentity(VERSION));
        NestedArtifactIdentity workspace = new NestedArtifactIdentity(
                COMPILER.groupId(),
                COMPILER.artifactId(),
                VERSION,
                "jar",
                Optional.empty(),
                SourceKind.WORKSPACE);
        NestedArtifactIdentity classified = new NestedArtifactIdentity(
                COMPILER.groupId(),
                COMPILER.artifactId(),
                VERSION,
                "jar",
                Optional.of("sources"),
                SourceKind.EXTERNAL);

        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                dependency(
                                        COMPILER,
                                        root,
                                        VERSION,
                                        DependencyScope.TOOL_KOTLIN,
                                        true,
                                        compilerIdentity(VERSION)),
                                ordinaryRuntime),
                        "2.2.1"),
                "configured version `2.2.1` does not match zolt.lock tool root version `2.2.0`");
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                dependency(
                                        COMPILER,
                                        root,
                                        VERSION,
                                        DependencyScope.TOOL_KOTLIN,
                                        true,
                                        workspace),
                                ordinaryRuntime),
                        VERSION),
                "workspace substitution");
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                dependency(
                                        COMPILER,
                                        root,
                                        VERSION,
                                        DependencyScope.TOOL_KOTLIN,
                                        true,
                                        classified),
                                ordinaryRuntime),
                        VERSION),
                "default unclassified JAR variant");
        assertMessageContains(
                () -> resolver.resolve(
                        List.of(
                                dependency(
                                        COMPILER,
                                        root,
                                        VERSION,
                                        DependencyScope.TOOL_KOTLIN,
                                        true,
                                        NestedArtifactIdentity.external(new PackageId("wrong", "identity"), VERSION)),
                                ordinaryRuntime),
                        VERSION),
                "resolved package and artifact identities disagree");
    }

    @Test
    void rejectsInvalidClosureArtifactsAndAmbiguousIdentity() throws IOException {
        VerifiedJar root = compilerJar("kotlin-compiler-embeddable", VERSION, true);
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");
        VerifiedJar support = plainJar(ALPHA, "1.0.0", "support-one");
        VerifiedJar changedCopy = plainJar(ALPHA, "1.0.0", "support-two");
        NestedArtifactIdentity supportIdentity = NestedArtifactIdentity.external(ALPHA, "1.0.0");
        NestedArtifactIdentity workspace = new NestedArtifactIdentity(
                ALPHA.groupId(),
                ALPHA.artifactId(),
                "1.0.0",
                "jar",
                Optional.empty(),
                SourceKind.WORKSPACE);
        NestedArtifactIdentity zip = new NestedArtifactIdentity(
                ALPHA.groupId(),
                ALPHA.artifactId(),
                "1.0.0",
                "zip",
                Optional.empty(),
                SourceKind.EXTERNAL);

        assertClosureFailure(root, runtime, support, workspace, "workspace substitution");
        assertClosureFailure(root, runtime, support, zip, "non-JAR artifact");
        assertClosureFailure(
                root,
                runtime,
                support,
                NestedArtifactIdentity.external(ZETA, "1.0.0"),
                "resolved package and artifact identities disagree");

        Path unverified = unverifiedPlainJar("unverified");
        VerifiedJar unverifiedArtifact = new VerifiedJar(unverified, sha256(unverified));
        assertClosureFailure(
                root,
                runtime,
                unverifiedArtifact,
                supportIdentity,
                "has no current checksum-verified artifact identity");

        assertMessageContains(
                () -> resolver.resolve(
                        validPackages(
                                root,
                                runtime,
                                dependency(
                                        ALPHA,
                                        support,
                                        "1.0.0",
                                        DependencyScope.TOOL_KOTLIN,
                                        false,
                                        supportIdentity),
                                dependency(
                                        ALPHA,
                                        changedCopy,
                                        "1.0.0",
                                        DependencyScope.TOOL_KOTLIN,
                                        false,
                                        supportIdentity)),
                        VERSION),
                "closure resolves com.example:alpha-support:1.0.0 ambiguously");
    }

    @Test
    void rejectsInvalidCompilerJarMetadata() throws IOException {
        VerifiedJar runtime = plainJar(STDLIB, VERSION, "stdlib");

        assertRootFailure(
                compilerJar("kotlin-compiler-embeddable", VERSION, false),
                runtime,
                "does not contain " + COMPILER_ENTRY);
        assertRootFailure(
                compilerJarWithoutManifest(true),
                runtime,
                "has no manifest");
        assertRootFailure(
                compilerJar(null, VERSION, true),
                runtime,
                "Implementation-Title `` instead of `kotlin-compiler-embeddable`");
        assertRootFailure(
                compilerJar("not-kotlin", VERSION, true),
                runtime,
                "Implementation-Title `not-kotlin`");
        assertRootFailure(
                compilerJar("kotlin-compiler-embeddable", null, true),
                runtime,
                "Implementation-Version ``");
        assertRootFailure(
                compilerJar("kotlin-compiler-embeddable", "2.2.1", true),
                runtime,
                "Implementation-Version `2.2.1`");
        assertRootFailure(
                compilerJar("kotlin-compiler-embeddable", VERSION + "-release-", true),
                runtime,
                "Implementation-Version `" + VERSION + "-release-`");
    }

    private void assertClosureFailure(
            VerifiedJar root,
            VerifiedJar runtime,
            VerifiedJar support,
            NestedArtifactIdentity identity,
            String expectedMessage) {
        assertMessageContains(
                () -> resolver.resolve(
                        validPackages(
                                root,
                                runtime,
                                dependency(
                                        ALPHA,
                                        support,
                                        "1.0.0",
                                        DependencyScope.TOOL_KOTLIN,
                                        false,
                                        identity)),
                        VERSION),
                expectedMessage);
    }

    private void assertRootFailure(
            VerifiedJar root,
            VerifiedJar runtime,
            String expectedMessage) {
        assertMessageContains(
                () -> resolver.resolve(validPackages(root, runtime), VERSION),
                expectedMessage);
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
