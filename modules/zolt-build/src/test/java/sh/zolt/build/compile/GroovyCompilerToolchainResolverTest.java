package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.GroovyCompileException;
import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.NestedArtifactIdentity.SourceKind;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

final class GroovyCompilerToolchainResolverTest {
    private static final PackageId GROOVY = new PackageId("org.apache.groovy", "groovy");
    private static final PackageId ALPHA = new PackageId("com.example", "alpha-support");
    private static final PackageId ZETA = new PackageId("org.example", "zeta-support");
    private static final String VERSION = "4.0.22";
    private static final String COMPILER_ENTRY = "org/codehaus/groovy/tools/FileSystemCompiler.class";

    @TempDir
    private Path tempDir;

    private int jarSequence;

    private final GroovyCompilerToolchainResolver resolver = new GroovyCompilerToolchainResolver();

    @Test
    void resolvesRelocatableIdentityFromVerifiedDirectDefaultJar() throws IOException {
        VerifiedJar jar = verifiedJar(VERSION, VERSION, true);

        GroovyCompilerToolchain toolchain = resolver.resolve(
                List.of(dependency(jar, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION))),
                GroovyCompilerToolchainResolver.SourceSet.MAIN);

        assertEquals("org.apache.groovy:groovy", toolchain.coordinate());
        assertEquals(VERSION, toolchain.version());
        assertEquals(jar.sha256(), toolchain.sha256());
        assertEquals(
                "org.apache.groovy:groovy:4.0.22@sha256:" + jar.sha256(),
                toolchain.identity());
        assertEquals(
                List.of(jar.path().toAbsolutePath().normalize()),
                toolchain.launcherClasspath().entries());
    }

    @Test
    void appliesExplicitMainAndTestVisibilityPolicies() throws IOException {
        VerifiedJar jar = verifiedJar(VERSION, VERSION, true);
        for (DependencyScope scope : List.of(DependencyScope.COMPILE, DependencyScope.PROVIDED)) {
            assertDoesNotThrow(() -> resolver.resolve(
                    List.of(dependency(jar, VERSION, scope, true, defaultIdentity(VERSION))),
                    GroovyCompilerToolchainResolver.SourceSet.MAIN));
        }
        for (DependencyScope scope : List.of(
                DependencyScope.COMPILE,
                DependencyScope.RUNTIME,
                DependencyScope.TEST,
                DependencyScope.PROVIDED)) {
            assertDoesNotThrow(() -> resolver.resolve(
                    List.of(dependency(jar, VERSION, scope, true, defaultIdentity(VERSION))),
                    GroovyCompilerToolchainResolver.SourceSet.TEST));
        }

        GroovyCompileException mainTestOnly = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(
                                jar,
                                VERSION,
                                DependencyScope.TEST,
                                true,
                                defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(mainTestOnly.getMessage().contains("not visible to the main compile source set"));

        GroovyCompileException testDevOnly = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(
                                jar,
                                VERSION,
                                DependencyScope.DEV,
                                true,
                                defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.TEST));
        assertTrue(testDevOnly.getMessage().contains("not visible to the test compile source set"));
    }

    @Test
    void acceptsDuplicateScopeMetadataForTheSameArtifact() throws IOException {
        VerifiedJar jar = verifiedJar(VERSION, VERSION, true);

        GroovyCompilerToolchain toolchain = resolver.resolve(
                List.of(
                        dependency(jar, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION)),
                        dependency(jar, VERSION, DependencyScope.PROVIDED, true, defaultIdentity(VERSION))),
                GroovyCompilerToolchainResolver.SourceSet.MAIN);

        assertEquals(jar.sha256(), toolchain.sha256());
        assertEquals(1, toolchain.launcherClasspath().entries().size());
    }

    @Test
    void rejectsMissingOrTransitiveOnlyCompilerPackages() throws IOException {
        GroovyCompileException missing = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(List.of(), GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(missing.getMessage().contains("not present in the verified resolved packages"));
        assertTrue(missing.getMessage().contains("[dependencies]"));

        VerifiedJar jar = verifiedJar(VERSION, VERSION, true);
        GroovyCompileException transitive = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(
                                jar,
                                VERSION,
                                DependencyScope.COMPILE,
                                false,
                                defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(transitive.getMessage().contains("present only transitively"));
    }

    @Test
    void rejectsWorkspaceAndNonDefaultArtifactVariants() throws IOException {
        VerifiedJar jar = verifiedJar(VERSION, VERSION, true);
        NestedArtifactIdentity workspace = new NestedArtifactIdentity(
                GROOVY.groupId(), GROOVY.artifactId(), VERSION, "jar", Optional.empty(), SourceKind.WORKSPACE);
        GroovyCompileException workspaceFailure = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(jar, VERSION, DependencyScope.COMPILE, true, workspace)),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(workspaceFailure.getMessage().contains("workspace substitution"));

        NestedArtifactIdentity classifier = new NestedArtifactIdentity(
                GROOVY.groupId(), GROOVY.artifactId(), VERSION, "jar", Optional.of("sources"), SourceKind.EXTERNAL);
        GroovyCompileException classifierFailure = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(jar, VERSION, DependencyScope.COMPILE, true, classifier)),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(classifierFailure.getMessage().contains("default unclassified JAR variant"));

        NestedArtifactIdentity nonJar = new NestedArtifactIdentity(
                GROOVY.groupId(), GROOVY.artifactId(), VERSION, "zip", Optional.empty(), SourceKind.EXTERNAL);
        GroovyCompileException extensionFailure = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(jar, VERSION, DependencyScope.COMPILE, true, nonJar)),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(extensionFailure.getMessage().contains("default unclassified JAR variant"));
    }

    @Test
    void rejectsInconsistentOrAmbiguousResolvedIdentities() throws IOException {
        VerifiedJar first = verifiedJar(VERSION, VERSION, true);
        NestedArtifactIdentity wrongVersion = defaultIdentity("4.0.21");
        GroovyCompileException inconsistent = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(
                                first,
                                VERSION,
                                DependencyScope.COMPILE,
                                true,
                                wrongVersion)),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(inconsistent.getMessage().contains("package and artifact identities disagree"));

        VerifiedJar second = verifiedJar(VERSION, VERSION, true);
        GroovyCompileException ambiguous = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(
                                dependency(
                                        first,
                                        VERSION,
                                        DependencyScope.COMPILE,
                                        true,
                                        defaultIdentity(VERSION)),
                                dependency(
                                        second,
                                        VERSION,
                                        DependencyScope.PROVIDED,
                                        true,
                                        defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(ambiguous.getMessage().contains("ambiguous direct org.apache.groovy:groovy"));
    }

    @Test
    void rejectsMissingOrUnverifiedArtifactContent() throws IOException {
        Path missingPath = tempDir.resolve("missing/groovy.jar");
        ResolvedClasspathPackage missingPackage = dependency(
                new VerifiedJar(missingPath, "0".repeat(64)),
                VERSION,
                DependencyScope.COMPILE,
                true,
                defaultIdentity(VERSION));
        GroovyCompileException missing = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(missingPackage),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(missing.getMessage().contains("not a regular file"));

        Path unverifiedPath = writeJar(VERSION, VERSION, true);
        VerifiedJar unverifiedJar = new VerifiedJar(unverifiedPath, sha256(unverifiedPath));
        GroovyCompileException unverified = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(
                                unverifiedJar,
                                VERSION,
                                DependencyScope.COMPILE,
                                true,
                                defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(unverified.getMessage().contains("no current checksum-verified artifact identity"));
    }

    @Test
    void rejectsJarWithoutCompilerEntrypoint() throws IOException {
        VerifiedJar jar = verifiedJar(VERSION, VERSION, false);

        GroovyCompileException exception = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(
                                jar,
                                VERSION,
                                DependencyScope.COMPILE,
                                true,
                                defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));

        assertTrue(exception.getMessage().contains(COMPILER_ENTRY));
    }

    @Test
    void rejectsMissingOrMismatchedGroovyVersionMetadata() throws IOException {
        VerifiedJar missingMetadata = verifiedJar(null, null, true);
        GroovyCompileException missing = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(
                                missingMetadata,
                                VERSION,
                                DependencyScope.COMPILE,
                                true,
                                defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(missing.getMessage().contains("no Groovy implementation version metadata"));

        VerifiedJar mismatched = verifiedJar(VERSION, "4.0.21", true);
        GroovyCompileException mismatch = assertThrows(
                GroovyCompileException.class,
                () -> resolver.resolve(
                        List.of(dependency(
                                mismatched,
                                VERSION,
                                DependencyScope.COMPILE,
                                true,
                                defaultIdentity(VERSION))),
                        GroovyCompilerToolchainResolver.SourceSet.MAIN));
        assertTrue(mismatch.getMessage().contains("ImplementationVersion `4.0.21`"));
        assertTrue(mismatch.getMessage().contains("zolt.lock selected `4.0.22`"));
    }

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
                GROOVY.groupId(),
                GROOVY.artifactId(),
                VERSION,
                "jar",
                Optional.of("indy"),
                SourceKind.EXTERNAL);

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

    private GroovyCompilerToolchain explicitToolchain(
            VerifiedJar root,
            VerifiedJar support) {
        return resolver.resolve(
                List.of(
                        dependency(root, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION)),
                        dependency(ALPHA, support, "1.0.0", DependencyScope.TOOL_GROOVY, false,
                                NestedArtifactIdentity.external(ALPHA, "1.0.0")),
                        dependency(root, VERSION, DependencyScope.TOOL_GROOVY, true, defaultIdentity(VERSION))),
                GroovyCompilerToolchainResolver.SourceSet.MAIN,
                VERSION);
    }

    private VerifiedJar verifiedPlainJar(
            PackageId packageId,
            String version,
            String content) throws IOException {
        return verifiedArtifact(writePlainJar(content), packageId, version);
    }

    private VerifiedJar verifiedCopy(
            VerifiedJar source,
            PackageId packageId,
            String version,
            String name) throws IOException {
        Path target = tempDir.resolve("relocated")
                .resolve(jarSequence++ + "-" + name + ".jar");
        Files.createDirectories(target.getParent());
        Files.copy(source.path(), target);
        return verifiedArtifact(target, packageId, version);
    }

    private VerifiedJar verifiedArtifact(
            Path jar,
            PackageId packageId,
            String version) throws IOException {
        String hash = sha256(jar);
        String relative = tempDir.relativize(jar).toString().replace('\\', '/');
        LockPackage lockPackage = new LockPackage(
                packageId,
                version,
                "central",
                DependencyScope.TOOL_GROOVY,
                false,
                Optional.of(relative),
                Optional.empty(),
                Optional.of(hash),
                Optional.empty(),
                List.of());
        new ArtifactIntegrityVerifier().verify(
                new ZoltLockfile(ZoltLockfile.CURRENT_VERSION, List.of(lockPackage), List.of()),
                tempDir);
        return new VerifiedJar(jar, hash);
    }

    private Path writePlainJar(String content) throws IOException {
        Path jar = tempDir.resolve("artifacts/plain-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(output, "payload.txt", content.getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    private static List<ResolvedClasspathPackage> append(
            List<ResolvedClasspathPackage> base,
            ResolvedClasspathPackage dependency) {
        List<ResolvedClasspathPackage> result = new ArrayList<>(base);
        result.add(dependency);
        return List.copyOf(result);
    }

    private VerifiedJar verifiedJar(
            String manifestVersion,
            String releaseInfoVersion,
            boolean includeCompiler) throws IOException {
        Path jar = writeJar(manifestVersion, releaseInfoVersion, includeCompiler);
        return verifiedArtifact(jar, GROOVY, VERSION);
    }

    private Path writeJar(
            String manifestVersion,
            String releaseInfoVersion,
            boolean includeCompiler) throws IOException {
        Path jar = tempDir.resolve("artifacts/groovy-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        Manifest manifest = null;
        if (manifestVersion != null) {
            manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, manifestVersion);
        }
        try (JarOutputStream output = manifest == null
                ? new JarOutputStream(Files.newOutputStream(jar))
                : new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (includeCompiler) {
                writeEntry(output, COMPILER_ENTRY, new byte[] {0});
            }
            if (releaseInfoVersion != null) {
                writeEntry(
                        output,
                        "META-INF/groovy-release-info.properties",
                        ("ImplementationVersion=" + releaseInfoVersion + "\n")
                                .getBytes(StandardCharsets.ISO_8859_1));
            }
        }
        return jar;
    }

    private static void writeEntry(JarOutputStream output, String name, byte[] content) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }

    private static ResolvedClasspathPackage dependency(
            VerifiedJar jar,
            String selectedVersion,
            DependencyScope scope,
            boolean direct,
            NestedArtifactIdentity identity) {
        return dependency(GROOVY, jar, selectedVersion, scope, direct, identity);
    }

    private static ResolvedClasspathPackage dependency(
            PackageId packageId,
            VerifiedJar jar,
            String selectedVersion,
            DependencyScope scope,
            boolean direct,
            NestedArtifactIdentity identity) {
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        packageId,
                        selectedVersion,
                        direct,
                        Path.of(""),
                        jar.path(),
                        identity),
                scope);
    }

    private static NestedArtifactIdentity defaultIdentity(String version) {
        return NestedArtifactIdentity.external(GROOVY, version);
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    private record VerifiedJar(Path path, String sha256) {
    }
}
