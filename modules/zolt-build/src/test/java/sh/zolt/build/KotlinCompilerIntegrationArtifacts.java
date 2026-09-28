package sh.zolt.build;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import sh.zolt.dependency.DependencyLane;
import sh.zolt.dependency.DependencyScope;
import sh.zolt.dependency.PackageId;
import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockDependencyRoot;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.ZoltLockfileWriter;

/** Seeds a real checksum-verified Kotlin compiler closure for BuildService integration tests. */
final class KotlinCompilerIntegrationArtifacts {
    static final String KOTLIN_VERSION = "2.2.0";

    private static final ArtifactSpec COMPILER = artifact(
            "org.jetbrains.kotlin",
            "kotlin-compiler-embeddable",
            KOTLIN_VERSION,
            "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler");
    private static final ArtifactSpec DAEMON = artifact(
            "org.jetbrains.kotlin",
            "kotlin-daemon-embeddable",
            KOTLIN_VERSION,
            "org.jetbrains.kotlin.daemon.common.CompileService");
    private static final ArtifactSpec REFLECT = artifact(
            "org.jetbrains.kotlin",
            "kotlin-reflect",
            "1.6.10",
            "kotlin.reflect.jvm.internal.ReflectionFactoryImpl");
    private static final ArtifactSpec SCRIPT_RUNTIME = artifact(
            "org.jetbrains.kotlin",
            "kotlin-script-runtime",
            KOTLIN_VERSION,
            "kotlin.script.templates.standard.ScriptTemplateWithArgs");
    private static final ArtifactSpec STDLIB = artifact(
            "org.jetbrains.kotlin",
            "kotlin-stdlib",
            KOTLIN_VERSION,
            "kotlin.Unit");
    private static final ArtifactSpec COROUTINES = artifact(
            "org.jetbrains.kotlinx",
            "kotlinx-coroutines-core-jvm",
            "1.8.0",
            "kotlinx.coroutines.Job");
    private static final ArtifactSpec ANNOTATIONS = artifact(
            "org.jetbrains",
            "annotations",
            "13.0",
            "org.jetbrains.annotations.NotNull");

    private static final List<ArtifactSpec> ARTIFACTS = List.of(
            COMPILER,
            DAEMON,
            REFLECT,
            SCRIPT_RUNTIME,
            STDLIB,
            COROUTINES,
            ANNOTATIONS);

    private KotlinCompilerIntegrationArtifacts() {}

    static Prepared prepare(Path cacheRoot, Path lockfilePath) throws IOException {
        Map<PackageId, CachedArtifact> cached = new LinkedHashMap<>();
        for (ArtifactSpec spec : ARTIFACTS) {
            cached.put(spec.packageId(), cache(cacheRoot, spec));
        }

        List<LockPackage> packages = List.of(
                lockPackage(cached, STDLIB, DependencyScope.COMPILE, true,
                        List.of(edge(ANNOTATIONS, DependencyScope.COMPILE))),
                lockPackage(cached, ANNOTATIONS, DependencyScope.COMPILE, false, List.of()),
                lockPackage(cached, COMPILER, DependencyScope.TOOL_KOTLIN, true, List.of(
                        edge(DAEMON, DependencyScope.TOOL_KOTLIN),
                        edge(REFLECT, DependencyScope.TOOL_KOTLIN),
                        edge(SCRIPT_RUNTIME, DependencyScope.TOOL_KOTLIN),
                        edge(STDLIB, DependencyScope.TOOL_KOTLIN),
                        edge(COROUTINES, DependencyScope.TOOL_KOTLIN))),
                lockPackage(cached, DAEMON, DependencyScope.TOOL_KOTLIN, false, List.of()),
                lockPackage(cached, REFLECT, DependencyScope.TOOL_KOTLIN, false, List.of()),
                lockPackage(cached, SCRIPT_RUNTIME, DependencyScope.TOOL_KOTLIN, false, List.of()),
                lockPackage(cached, STDLIB, DependencyScope.TOOL_KOTLIN, false,
                        List.of(edge(ANNOTATIONS, DependencyScope.TOOL_KOTLIN))),
                lockPackage(cached, COROUTINES, DependencyScope.TOOL_KOTLIN, false, List.of()),
                lockPackage(cached, ANNOTATIONS, DependencyScope.TOOL_KOTLIN, false, List.of()));
        LockDependencyRoot runtimeRoot = new LockDependencyRoot(
                ".",
                STDLIB.packageId(),
                STDLIB.version(),
                LockArtifactVariant.defaultVariant(),
                DependencyLane.IMPLEMENTATION,
                Optional.of(DependencyScope.COMPILE),
                false,
                false);
        new ZoltLockfileWriter().write(lockfilePath, new ZoltLockfile(
                ZoltLockfile.CURRENT_VERSION,
                Optional.empty(),
                Optional.empty(),
                List.of(),
                packages,
                List.of(),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(runtimeRoot)));

        return new Prepared(
                List.of(cached.get(STDLIB.packageId()).path(), cached.get(ANNOTATIONS.packageId()).path()),
                List.of(
                        cached.get(COMPILER.packageId()).path(),
                        cached.get(DAEMON.packageId()).path(),
                        cached.get(REFLECT.packageId()).path(),
                        cached.get(SCRIPT_RUNTIME.packageId()).path(),
                        cached.get(STDLIB.packageId()).path(),
                        cached.get(COROUTINES.packageId()).path(),
                        cached.get(ANNOTATIONS.packageId()).path()));
    }

    private static LockPackage lockPackage(
            Map<PackageId, CachedArtifact> cached,
            ArtifactSpec spec,
            DependencyScope scope,
            boolean direct,
            List<String> dependencies) {
        CachedArtifact artifact = cached.get(spec.packageId());
        return new LockPackage(
                spec.packageId(),
                spec.version(),
                "central",
                scope,
                direct,
                Optional.of(artifact.relativePath()),
                Optional.empty(),
                Optional.of(artifact.sha256()),
                Optional.empty(),
                dependencies);
    }

    private static CachedArtifact cache(Path cacheRoot, ArtifactSpec spec) throws IOException {
        Path source = markerJar(spec.markerClass());
        String sha256 = sha256(source);
        Path relative = Path.of("blobs", "v2", "sha256", sha256, spec.fileName());
        Path target = cacheRoot.resolve(relative).toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        return new CachedArtifact(
                target,
                relative.toString().replace('\\', '/'),
                sha256);
    }

    private static Path markerJar(String markerClass) {
        try {
            Class<?> marker = Class.forName(
                    markerClass,
                    false,
                    KotlinCompilerIntegrationArtifacts.class.getClassLoader());
            Path location = Path.of(marker.getProtectionDomain()
                            .getCodeSource()
                            .getLocation()
                            .toURI())
                    .toAbsolutePath()
                    .normalize();
            if (!Files.isRegularFile(location) || !location.getFileName().toString().endsWith(".jar")) {
                throw new IllegalStateException(
                        "Kotlin integration marker " + markerClass + " is not loaded from a JAR: " + location);
            }
            return location;
        } catch (ClassNotFoundException | URISyntaxException exception) {
            throw new IllegalStateException(
                    "Kotlin integration marker is unavailable on the test runtime: " + markerClass,
                    exception);
        }
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

    private static String edge(ArtifactSpec spec, DependencyScope scope) {
        return spec.packageId() + ":" + spec.version() + ":jar:" + scope.lockfileName();
    }

    private static ArtifactSpec artifact(
            String groupId,
            String artifactId,
            String version,
            String markerClass) {
        return new ArtifactSpec(new PackageId(groupId, artifactId), version, markerClass);
    }

    record Prepared(List<Path> applicationClasspath, List<Path> compilerClasspath) {
        Prepared {
            applicationClasspath = List.copyOf(applicationClasspath);
            compilerClasspath = List.copyOf(compilerClasspath);
        }
    }

    private record ArtifactSpec(PackageId packageId, String version, String markerClass) {
        private String fileName() {
            return packageId.artifactId() + "-" + version + ".jar";
        }
    }

    private record CachedArtifact(Path path, String relativePath, String sha256) {}
}
