package sh.zolt.build.compile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

final class GroovyCompilerToolchainResolverTest extends GroovyCompilerToolchainResolverTestSupport {
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
                                dependency(first, VERSION, DependencyScope.COMPILE, true, defaultIdentity(VERSION)),
                                dependency(second, VERSION, DependencyScope.PROVIDED, true, defaultIdentity(VERSION))),
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
                () -> resolver.resolve(List.of(missingPackage), GroovyCompilerToolchainResolver.SourceSet.MAIN));
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
}
