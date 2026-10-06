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

public abstract class KotlinCompilerToolchainResolverTestSupport {
    protected static final PackageId COMPILER =
            new PackageId("org.jetbrains.kotlin", "kotlin-compiler-embeddable");
    protected static final PackageId STDLIB =
            new PackageId("org.jetbrains.kotlin", "kotlin-stdlib");
    protected static final PackageId KAPT =
            new PackageId("org.jetbrains.kotlin", "kotlin-annotation-processing-embeddable");
    protected static final PackageId SERIALIZATION_PLUGIN = new PackageId(
            "org.jetbrains.kotlin", "kotlin-serialization-compiler-plugin-embeddable");
    protected static final PackageId ALPHA = new PackageId("com.example", "alpha-support");
    protected static final PackageId ZETA = new PackageId("org.example", "zeta-support");
    protected static final String VERSION = "2.2.0";
    protected static final String COMPILER_ENTRY =
            "org/jetbrains/kotlin/cli/jvm/K2JVMCompiler.class";
    protected static final String KAPT_ENTRY =
            "org/jetbrains/kotlin/kapt/KaptCommandLineProcessor.class";
    @TempDir
    protected Path tempDir;

    protected int jarSequence;

    protected final KotlinCompilerToolchainResolver resolver = new KotlinCompilerToolchainResolver();

    protected List<ResolvedClasspathPackage> validPackages(
            VerifiedJar root,
            VerifiedJar runtime,
            ResolvedClasspathPackage... closure) {
        List<ResolvedClasspathPackage> packages = new ArrayList<>();
        packages.add(dependency(
                COMPILER,
                root,
                VERSION,
                DependencyScope.TOOL_KOTLIN,
                true,
                compilerIdentity(VERSION)));
        packages.addAll(List.of(closure));
        packages.add(dependency(
                STDLIB,
                runtime,
                VERSION,
                DependencyScope.COMPILE,
                false,
                stdlibIdentity(VERSION)));
        return List.copyOf(packages);
    }

    protected VerifiedJar compilerJar(
            String implementationTitle,
            String implementationVersion,
            boolean includeCompiler) throws IOException {
        return verifiedArtifact(
                writeCompilerJar(true, implementationTitle, implementationVersion, includeCompiler),
                COMPILER,
                VERSION);
    }

    protected VerifiedJar compilerJarWithoutManifest(boolean includeCompiler) throws IOException {
        return verifiedArtifact(
                writeCompilerJar(false, null, null, includeCompiler),
                COMPILER,
                VERSION);
    }

    protected VerifiedJar kaptJar(
            String implementationVersion,
            boolean includePlugin) throws IOException {
        Path jar = tempDir.resolve("artifacts/kotlin-kapt-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(
                Attributes.Name.IMPLEMENTATION_TITLE,
                "kotlin-annotation-processing-embeddable");
        manifest.getMainAttributes().put(
                Attributes.Name.IMPLEMENTATION_VERSION,
                implementationVersion);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (includePlugin) {
                writeEntry(output, KAPT_ENTRY, new byte[] {0});
            }
        }
        return verifiedArtifact(jar, KAPT, VERSION);
    }

    protected VerifiedJar plainJar(
            PackageId packageId,
            String version,
            String content) throws IOException {
        Path jar = tempDir.resolve("artifacts/plain-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(output, "payload.txt", content.getBytes(StandardCharsets.UTF_8));
        }
        return verifiedArtifact(jar, packageId, version);
    }

    protected VerifiedJar verifiedCopy(
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

    protected VerifiedJar verifiedArtifact(
            Path jar,
            PackageId packageId,
            String version) throws IOException {
        String hash = sha256(jar);
        String relative = tempDir.relativize(jar).toString().replace('\\', '/');
        LockPackage lockPackage = new LockPackage(
                packageId,
                version,
                "central",
                DependencyScope.TOOL_KOTLIN,
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

    protected Path unverifiedPlainJar(String content) throws IOException {
        Path jar = tempDir.resolve("unverified/plain-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            writeEntry(output, "payload.txt", content.getBytes(StandardCharsets.UTF_8));
        }
        return jar;
    }

    protected Path writeCompilerJar(
            boolean includeManifest,
            String implementationTitle,
            String implementationVersion,
            boolean includeCompiler) throws IOException {
        Path jar = tempDir.resolve("artifacts/kotlin-compiler-" + jarSequence++ + ".jar");
        Files.createDirectories(jar.getParent());
        Manifest manifest = null;
        if (includeManifest) {
            manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            if (implementationTitle != null) {
                manifest.getMainAttributes().put(
                        Attributes.Name.IMPLEMENTATION_TITLE,
                        implementationTitle);
            }
            if (implementationVersion != null) {
                manifest.getMainAttributes().put(
                        Attributes.Name.IMPLEMENTATION_VERSION,
                        implementationVersion);
            }
        }
        try (JarOutputStream output = manifest == null
                ? new JarOutputStream(Files.newOutputStream(jar))
                : new JarOutputStream(Files.newOutputStream(jar), manifest)) {
            if (includeCompiler) {
                writeEntry(output, COMPILER_ENTRY, new byte[] {0});
            }
        }
        return jar;
    }

    protected static void writeEntry(
            JarOutputStream output,
            String name,
            byte[] content) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(content);
        output.closeEntry();
    }

    protected static ResolvedClasspathPackage dependency(
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

    protected static NestedArtifactIdentity compilerIdentity(String version) {
        return NestedArtifactIdentity.external(COMPILER, version);
    }

    protected static NestedArtifactIdentity stdlibIdentity(String version) {
        return NestedArtifactIdentity.external(STDLIB, version);
    }

    protected static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    protected record VerifiedJar(Path path, String sha256) {
    }
}
