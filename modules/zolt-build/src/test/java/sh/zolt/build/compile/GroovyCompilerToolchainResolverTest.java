package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private VerifiedJar verifiedJar(
            String manifestVersion,
            String releaseInfoVersion,
            boolean includeCompiler) throws IOException {
        Path jar = writeJar(manifestVersion, releaseInfoVersion, includeCompiler);
        String hash = sha256(jar);
        String relative = tempDir.relativize(jar).toString().replace('\\', '/');
        LockPackage lockPackage = new LockPackage(
                GROOVY,
                VERSION,
                "central",
                DependencyScope.COMPILE,
                true,
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
        return new ResolvedClasspathPackage(
                new ResolvedPackage(
                        GROOVY,
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
