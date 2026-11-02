package sh.zolt.build.compile;

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
import org.junit.jupiter.api.io.TempDir;
import sh.zolt.build.lockfile.ArtifactIntegrityVerifier;
import sh.zolt.classpath.NestedArtifactIdentity;
import sh.zolt.classpath.ResolvedClasspathPackage;
import sh.zolt.classpath.ResolvedPackage;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;

abstract class GroovyCompilerToolchainResolverTestSupport {
    static final PackageId GROOVY = new PackageId("org.apache.groovy", "groovy");
    static final PackageId ALPHA = new PackageId("com.example", "alpha-support");
    static final PackageId ZETA = new PackageId("org.example", "zeta-support");
    static final String VERSION = "4.0.22";
    static final String COMPILER_ENTRY = "org/codehaus/groovy/tools/FileSystemCompiler.class";

    @TempDir
    Path tempDir;

    int jarSequence;

    final GroovyCompilerToolchainResolver resolver = new GroovyCompilerToolchainResolver();

    GroovyCompilerToolchain explicitToolchain(
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

    VerifiedJar verifiedPlainJar(
            PackageId packageId,
            String version,
            String content) throws IOException {
        return verifiedArtifact(writePlainJar(content), packageId, version);
    }

    VerifiedJar verifiedCopy(
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

    VerifiedJar verifiedArtifact(
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

    Path writePlainJar(String content) throws IOException {
        Path jar = tempDir.resolve("artifacts/plain-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(output, "payload.txt", content.getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    static List<ResolvedClasspathPackage> append(
            List<ResolvedClasspathPackage> base,
            ResolvedClasspathPackage dependency) {
        List<ResolvedClasspathPackage> result = new ArrayList<>(base);
        result.add(dependency);
        return List.copyOf(result);
    }

    VerifiedJar verifiedJar(
            String manifestVersion,
            String releaseInfoVersion,
            boolean includeCompiler) throws IOException {
        Path jar = writeJar(manifestVersion, releaseInfoVersion, includeCompiler);
        return verifiedArtifact(jar, GROOVY, VERSION);
    }

    Path writeJar(
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

    static void writeEntry(JarOutputStream output, String name, byte[] content) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }

    static ResolvedClasspathPackage dependency(
            VerifiedJar jar,
            String selectedVersion,
            DependencyScope scope,
            boolean direct,
            NestedArtifactIdentity identity) {
        return dependency(GROOVY, jar, selectedVersion, scope, direct, identity);
    }

    static ResolvedClasspathPackage dependency(
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

    static NestedArtifactIdentity defaultIdentity(String version) {
        return NestedArtifactIdentity.external(GROOVY, version);
    }

    static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    record VerifiedJar(Path path, String sha256) {
    }
}
