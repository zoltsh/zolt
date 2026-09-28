package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import sh.zolt.build.GroovyCompileException;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.NestedArtifactIdentity.SourceKind;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.dependency.DependencyScope;

final class ConfiguredGroovyCompilerToolchainResolverTest extends GroovyCompilerToolchainResolverTestSupport {
    @Test
    void explicitToolchainUsesRootFirstDeterministicVerifiedClosure() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        VerifiedJar alpha = verifiedPlainJar(ALPHA, "1.0.0", "alpha");
        VerifiedJar zeta = verifiedPlainJar(ZETA, "2.0.0", "zeta");

        GroovyCompilerToolchain toolchain = resolver.resolve(
                List.of(
                        dependency(ZETA, zeta, "2.0.0", DependencyScope.TOOL_GROOVY, false,
                                NestedArtifactIdentity.external(ZETA, "2.0.0")),
                        dependency(root, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION)),
                        dependency(ALPHA, alpha, "1.0.0", DependencyScope.TOOL_GROOVY, false,
                                NestedArtifactIdentity.external(ALPHA, "1.0.0")),
                        dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, defaultIdentity(VERSION))),
                GroovyCompilerToolchainResolver.SourceSet.MAIN,
                " 4.0.22 ");

        assertEquals(List.of(
                root.path().toAbsolutePath().normalize(),
                alpha.path().toAbsolutePath().normalize(),
                zeta.path().toAbsolutePath().normalize()), toolchain.launcherClasspath().entries());
        assertEquals(root.sha256(), toolchain.sha256());
        assertTrue(toolchain.identity().startsWith(
                "org.apache.groovy:groovy:4.0.22@sha256:" + root.sha256() + "|launcher=sha256:"));
        assertFalse(toolchain.identity().contains(tempDir.toString()));
    }

    @Test
    void explicitIdentityIsRelocatableAndTracksClosureContent() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        VerifiedJar support = verifiedPlainJar(ALPHA, "1.0.0", "stable-content");
        GroovyCompilerToolchain first = explicitToolchain(root, support);

        VerifiedJar relocatedRoot = verifiedCopy(root, GROOVY, VERSION, "groovy");
        VerifiedJar relocatedSupport = verifiedCopy(support, ALPHA, "1.0.0", "alpha");
        GroovyCompilerToolchain relocated = explicitToolchain(relocatedRoot, relocatedSupport);

        VerifiedJar changedSupport = verifiedPlainJar(ALPHA, "1.0.0", "changed-content");
        GroovyCompilerToolchain changed = explicitToolchain(relocatedRoot, changedSupport);

        assertEquals(first.identity(), relocated.identity());
        assertNotEquals(first.launcherClasspath().entries(), relocated.launcherClasspath().entries());
        assertNotEquals(first.identity(), changed.identity());
    }

    @Test
    void blankConfiguredVersionPreservesCompatibilityFallback() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        List<ResolvedClasspathPackage> packages = List.of(
                dependency(root, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION)));

        GroovyCompilerToolchain legacy = resolver.resolve(
                packages,
                GroovyCompilerToolchainResolver.SourceSet.MAIN);
        GroovyCompilerToolchain blank = resolver.resolve(
                packages,
                GroovyCompilerToolchainResolver.SourceSet.MAIN,
                "  ");

        assertEquals(legacy.identity(), blank.identity());
        assertEquals(legacy.launcherClasspath(), blank.launcherClasspath());
    }

    @Test
    void explicitModeRequiresExactlyOneDirectGroovyToolRoot() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        VerifiedJar duplicateRoot = verifiedCopy(root, GROOVY, VERSION, "duplicate-groovy");
        VerifiedJar extra = verifiedPlainJar(ALPHA, "1.0.0", "extra-root");
        ResolvedClasspathPackage runtime =
                dependency(root, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION));
        ResolvedClasspathPackage toolRoot =
                dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, defaultIdentity(VERSION));

        GroovyCompileException missing = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(runtime),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(missing.getMessage().contains("no direct org.apache.groovy:groovy root"));

        GroovyCompileException extraRoot = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                runtime,
                                toolRoot,
                                dependency(ALPHA, extra, "1.0.0", DependencyScope.TOOL_GROOVY, true,
                                        NestedArtifactIdentity.external(ALPHA, "1.0.0"))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(extraRoot.getMessage().contains("extra direct roots"));

        GroovyCompileException ambiguous = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                runtime,
                                toolRoot,
                                dependency(duplicateRoot, VERSION, DependencyScope.TOOL_GROOVY, true,
                                        defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(ambiguous.getMessage().contains("ambiguous direct org.apache.groovy:groovy roots"));
    }

    @Test
    void explicitModeRequiresExternalDefaultToolRoot() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        ResolvedClasspathPackage runtime =
                dependency(root, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION));
        NestedArtifactIdentity workspace = new NestedArtifactIdentity(
                GROOVY.groupId(), GROOVY.artifactId(), VERSION, "jar", Optional.empty(), SourceKind.WORKSPACE);
        NestedArtifactIdentity classified = new NestedArtifactIdentity(
                GROOVY.groupId(), GROOVY.artifactId(), VERSION, "jar", Optional.of("indy"), SourceKind.EXTERNAL);

        GroovyCompileException workspaceFailure = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                runtime,
                                dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, workspace)),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(workspaceFailure.getMessage().contains("workspace substitution"));

        GroovyCompileException classifiedFailure = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                runtime,
                                dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, classified)),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(classifiedFailure.getMessage().contains("default unclassified JAR variant"));
    }

    @Test
    void explicitModeRejectsConfiguredAndRuntimeVersionSkew() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        ResolvedClasspathPackage toolRoot =
                dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, defaultIdentity(VERSION));

        GroovyCompileException configuredSkew = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                toolRoot,
                                dependency(root, VERSION, DependencyScope.COMPILE, true,
                                        defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        "4.0.23"));
        assertTrue(configuredSkew.getMessage().contains(
                "configured version `4.0.23` does not match zolt.lock tool root version `4.0.22`"));

        GroovyCompileException runtimeSkew = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                toolRoot,
                                dependency(root, "4.0.21", DependencyScope.COMPILE, true,
                                        defaultIdentity("4.0.21"))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(runtimeSkew.getMessage().contains(
                "ordinary runtime version `4.0.21` does not match configured version `4.0.22`"));
    }

    @Test
    void explicitModeRequiresMatchingRuntimeAndAcceptsTransitiveTestRuntime() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        ResolvedClasspathPackage toolRoot =
                dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, defaultIdentity(VERSION));

        GroovyCompileException missing = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(toolRoot),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(missing.getMessage().contains("no ordinary source-set-visible"));

        assertDoesNotThrow(() -> resolver.resolve(
                List.of(
                        toolRoot,
                        dependency(root, VERSION, DependencyScope.TEST, false, defaultIdentity(VERSION))),
                GroovyCompilerToolchainResolver.SourceSet.TEST,
                VERSION));

        VerifiedJar differentContent = verifiedPlainJar(GROOVY, VERSION, "not-the-compiler-core");
        GroovyCompileException contentSkew = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                toolRoot,
                                dependency(differentContent, VERSION, DependencyScope.COMPILE, false,
                                        defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(contentSkew.getMessage().contains(
                "does not have the same checksum-verified core content"));
    }

    @Test
    void explicitModeSelectsOneDefaultRuntimeAlongsideClassifiedVariants() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        VerifiedJar classified = verifiedPlainJar(GROOVY, VERSION, "classified-runtime");
        ResolvedClasspathPackage toolRoot =
                dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, defaultIdentity(VERSION));
        ResolvedClasspathPackage runtime =
                dependency(root, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION));
        NestedArtifactIdentity classifier = new NestedArtifactIdentity(
                GROOVY.groupId(), GROOVY.artifactId(), VERSION, "jar", Optional.of("indy"), SourceKind.EXTERNAL);

        assertDoesNotThrow(() -> resolver.resolve(
                List.of(
                        toolRoot,
                        runtime,
                        dependency(classified, VERSION, DependencyScope.COMPILE, false, classifier)),
                GroovyCompilerToolchainResolver.SourceSet.MAIN,
                VERSION));

        VerifiedJar duplicateRuntime = verifiedCopy(root, GROOVY, VERSION, "runtime-copy");
        GroovyCompileException ambiguous = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                toolRoot,
                                runtime,
                                dependency(duplicateRuntime, VERSION, DependencyScope.PROVIDED, false,
                                        defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(ambiguous.getMessage().contains("runtime is ambiguous"));
    }

    @Test
    void explicitModeRejectsInvalidClosureArtifacts() throws IOException {
        VerifiedJar root = verifiedJar(VERSION, VERSION, true);
        VerifiedJar support = verifiedPlainJar(ALPHA, "1.0.0", "support");
        List<ResolvedClasspathPackage> base = List.of(
                dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, defaultIdentity(VERSION)),
                dependency(root, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION)));

        NestedArtifactIdentity workspace = new NestedArtifactIdentity(
                ALPHA.groupId(), ALPHA.artifactId(), "1.0.0", "jar", Optional.empty(), SourceKind.WORKSPACE);
        GroovyCompileException workspaceFailure = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        append(base, dependency(ALPHA, support, "1.0.0", DependencyScope.TOOL_GROOVY, false,
                                workspace)),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(workspaceFailure.getMessage().contains("workspace substitution"));

        NestedArtifactIdentity nonJar = new NestedArtifactIdentity(
                ALPHA.groupId(), ALPHA.artifactId(), "1.0.0", "zip", Optional.empty(), SourceKind.EXTERNAL);
        GroovyCompileException nonJarFailure = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        append(base, dependency(ALPHA, support, "1.0.0", DependencyScope.TOOL_GROOVY, false,
                                nonJar)),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(nonJarFailure.getMessage().contains("non-JAR artifact"));

        Path unverifiedPath = writePlainJar("unverified");
        GroovyCompileException unverifiedFailure = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        append(base, dependency(
                                ALPHA,
                                new VerifiedJar(unverifiedPath, sha256(unverifiedPath)),
                                "1.0.0",
                                DependencyScope.TOOL_GROOVY,
                                false,
                                NestedArtifactIdentity.external(ALPHA, "1.0.0"))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN,
                        VERSION));
        assertTrue(unverifiedFailure.getMessage().contains(
                "has no current checksum-verified artifact identity"));
    }
}
