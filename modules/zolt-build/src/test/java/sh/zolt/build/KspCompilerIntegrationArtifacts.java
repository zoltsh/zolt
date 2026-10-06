package sh.zolt.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileReader;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;

/** Adds a real isolated KSP2 engine and processor closure to a Kotlin integration lock. */
final class KspCompilerIntegrationArtifacts {
    static final String KSP_VERSION = "2.2.0-2.0.2";
    static final String PROCESSOR_VERSION = "1.0.0";
    private static final String ENGINE_GROUP = "ksp:ksp:engine";
    private static final String PROCESSOR_GROUP = "ksp:ksp:processors";

    private static final ArtifactSpec ENGINE = artifact(
            "com.google.devtools.ksp", "symbol-processing-aa", KSP_VERSION,
            "com.google.devtools.ksp.cmdline.KSPJvmMain");
    private static final ArtifactSpec API = artifact(
            "com.google.devtools.ksp", "symbol-processing-api", KSP_VERSION,
            "com.google.devtools.ksp.processing.SymbolProcessorProvider");
    private static final ArtifactSpec COMMON = artifact(
            "com.google.devtools.ksp", "symbol-processing-common-deps", KSP_VERSION,
            "com.google.devtools.ksp.processing.KSPJvmConfig");
    private static final ArtifactSpec STDLIB = artifact(
            "org.jetbrains.kotlin", "kotlin-stdlib", KotlinCompilerIntegrationArtifacts.KOTLIN_VERSION,
            "kotlin.Unit");
    private static final ArtifactSpec COROUTINES = artifact(
            "org.jetbrains.kotlinx", "kotlinx-coroutines-core-jvm", "1.8.0",
            "kotlinx.coroutines.Job");
    private static final ArtifactSpec PROCESSOR = artifact(
            "com.example", "ksp-fixture-processor", PROCESSOR_VERSION, "");

    private KspCompilerIntegrationArtifacts() {
    }

    static KotlinCompilerIntegrationArtifacts.Prepared prepare(
            Path cacheRoot,
            Path lockfilePath,
            Path processorJar) throws IOException {
        KotlinCompilerIntegrationArtifacts.Prepared prepared =
                KotlinCompilerIntegrationArtifacts.prepare(cacheRoot, lockfilePath);
        CachedArtifact engine = cache(cacheRoot, ENGINE, markerJar(ENGINE.markerClass()));
        CachedArtifact api = cache(cacheRoot, API, markerJar(API.markerClass()));
        CachedArtifact common = cache(cacheRoot, COMMON, markerJar(COMMON.markerClass()));
        CachedArtifact stdlib = cache(cacheRoot, STDLIB, markerJar(STDLIB.markerClass()));
        CachedArtifact coroutines = cache(
                cacheRoot, COROUTINES, markerJar(COROUTINES.markerClass()));
        CachedArtifact processor = cache(cacheRoot, PROCESSOR, processorJar);

        ZoltLockfile base = new ZoltLockfileReader().read(lockfilePath);
        List<LockPackage> packages = new ArrayList<>(base.packages());
        packages.add(toolPackage(engine, ENGINE, true, List.of(
                edge(API), edge(COMMON), edge(STDLIB), edge(COROUTINES)), ENGINE_GROUP));
        packages.add(toolPackage(api, API, false, List.of(edge(STDLIB)), ENGINE_GROUP));
        packages.add(toolPackage(common, COMMON, false, List.of(edge(STDLIB)), ENGINE_GROUP));
        packages.add(toolPackage(stdlib, STDLIB, false, List.of(), ENGINE_GROUP));
        packages.add(toolPackage(coroutines, COROUTINES, false, List.of(), ENGINE_GROUP));
        packages.add(toolPackage(processor, PROCESSOR, true, List.of(), PROCESSOR_GROUP));
        new ZoltLockfileWriter().write(lockfilePath, new ZoltLockfile(
                base.version(),
                base.aliasFingerprint(),
                base.projectResolutionFingerprint(),
                base.projectResolutionInputFingerprints(),
                packages,
                base.conflicts(),
                base.policyEffects(),
                base.memberGraphs(),
                base.workspaceResolutionInputFingerprint(),
                base.dependencyRoots()));
        return prepared;
    }

    private static LockPackage toolPackage(
            CachedArtifact artifact,
            ArtifactSpec spec,
            boolean direct,
            List<String> dependencies,
            String group) {
        return new LockPackage(
                spec.packageId(), spec.version(), "central", DependencyScope.TOOL_EXEC, direct,
                Optional.of(artifact.relativePath()), Optional.empty(),
                Optional.of(artifact.sha256()), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                dependencies, List.of(), List.of(), List.of(), List.of(group));
    }

    private static CachedArtifact cache(
            Path cacheRoot,
            ArtifactSpec spec,
            Path source) throws IOException {
        String sha256 = sha256(source);
        Path relative = Path.of(
                "blobs", "v2", "sha256", sha256,
                spec.packageId().artifactId() + "-" + spec.version() + ".jar");
        Path target = cacheRoot.resolve(relative).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return new CachedArtifact(target, relative.toString().replace('\\', '/'), sha256);
    }

    private static Path markerJar(String markerClass) {
        try {
            Class<?> marker = Class.forName(
                    markerClass, false, KspCompilerIntegrationArtifacts.class.getClassLoader());
            Path location = Path.of(marker.getProtectionDomain().getCodeSource().getLocation().toURI())
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isRegularFile(location)) {
                throw new IllegalStateException("KSP integration marker is not loaded from a JAR: " + location);
            }
            return location;
        } catch (ClassNotFoundException | URISyntaxException exception) {
            throw new IllegalStateException(
                    "KSP integration marker is unavailable: " + markerClass, exception);
        }
    }

    private static String edge(ArtifactSpec spec) {
        return spec.packageId() + ":" + spec.version() + ":jar:tool-exec";
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                for (int read = input.read(buffer); read >= 0; read = input.read(buffer)) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    private static ArtifactSpec artifact(
            String group,
            String artifact,
            String version,
            String markerClass) {
        return new ArtifactSpec(new PackageId(group, artifact), version, markerClass);
    }

    private record ArtifactSpec(PackageId packageId, String version, String markerClass) {
    }

    private record CachedArtifact(Path path, String relativePath, String sha256) {
    }
}
