package sh.zolt.selfhost;

import sh.zolt.build.packaging.PackageResult;
import sh.zolt.build.packaging.PackageService;
import sh.zolt.project.ProjectConfig;
import sh.zolt.toml.manifest.adapter.ManifestProjectConfigLoader;
import sh.zolt.workspace.packaging.WorkspacePackageService;
import sh.zolt.workspace.service.WorkspaceSelectionRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipFile;

public final class SelfHostingParityService {
    private static final String MANIFEST_ENTRY = "META-INF/MANIFEST.MF";
    private static final Set<String> LOCAL_BUILD_FINGERPRINTS = Set.of(
            ".zolt-build-main.fingerprint",
            ".zolt-build-main.fingerprint.state",
            ".zolt-build-test.fingerprint",
            ".zolt-build-test.fingerprint.state",
            ".zolt-incremental-main.state",
            ".zolt-incremental-test.state");
    private static final Set<String> LOCAL_RUNTIME_RESOURCES = Set.of(
            "META-INF/services/io.quarkus.test.junit.buildchain.TestBuildChainCustomizerProducer");

    private final ManifestProjectConfigLoader manifestLoader;
    private final ProjectPackager projectPackager;
    private final WorkspaceDetector workspaceDetector;
    private final WorkspacePackager workspacePackager;

    public SelfHostingParityService() {
        this(
                new ManifestProjectConfigLoader(),
                (projectDirectory, config, cacheRoot) -> new PackageService()
                        .packageJar(projectDirectory, config, cacheRoot),
                WorkspaceSelfCheckService::usesRealWorkspace,
                SelfHostingParityService::packageWorkspaceDefaultMember);
    }

    SelfHostingParityService(
            ManifestProjectConfigLoader manifestLoader,
            ProjectPackager projectPackager,
            WorkspaceDetector workspaceDetector,
            WorkspacePackager workspacePackager) {
        this.manifestLoader = manifestLoader;
        this.projectPackager = projectPackager;
        this.workspaceDetector = workspaceDetector;
        this.workspacePackager = workspacePackager;
    }

    public SelfHostingParityResult compare(Path projectDirectory, Path cacheRoot, Path bootstrapJar) {
        Path root = projectDirectory.toAbsolutePath().normalize();
        Path resolvedBootstrapJar = root.resolve(bootstrapJar).normalize();
        if (!Files.isRegularFile(resolvedBootstrapJar)) {
            throw new SelfHostingParityException(
                    "Self-hosting parity requires bootstrap jar at "
                            + resolvedBootstrapJar
                            + ". Build the bootstrap jar or pass --bootstrap-jar <path>.");
        }

        PackageResult packageResult;
        if (workspaceDetector.usesRealWorkspace(root)) {
            packageResult = workspacePackager.packageJar(root, cacheRoot);
        } else {
            ProjectConfig config = manifestLoader.loadProject(root);
            packageResult = projectPackager.packageJar(root, config, cacheRoot);
        }
        Path zoltJar = packageResult.jarPath();
        if (!Files.isRegularFile(zoltJar)) {
            throw new SelfHostingParityException(
                    "Self-hosting parity expected Zolt-built jar at "
                            + zoltJar
                            + ". Run zolt package and retry.");
        }

        Set<String> bootstrapEntries = comparableJarEntries(resolvedBootstrapJar);
        Set<String> zoltEntries = comparablePackageEntries(packageResult, root);
        Set<String> missing = new TreeSet<>(bootstrapEntries);
        missing.removeAll(zoltEntries);
        Set<String> extra = new TreeSet<>(zoltEntries);
        extra.removeAll(bootstrapEntries);
        return new SelfHostingParityResult(resolvedBootstrapJar, zoltJar, missing, extra);
    }

    private static Set<String> comparablePackageEntries(PackageResult packageResult, Path projectRoot) {
        Set<String> entries = new TreeSet<>(comparableJarEntries(packageResult.jarPath()));
        packageResult.runtimeClasspathPath()
                .filter(Files::isRegularFile)
                .ifPresent(path -> entries.addAll(comparableRuntimeClasspathEntries(path, projectRoot)));
        return entries;
    }

    private static Set<String> comparableRuntimeClasspathEntries(Path runtimeClasspathPath, Path projectRoot) {
        try {
            Set<String> entries = new TreeSet<>();
            for (String line : Files.readAllLines(runtimeClasspathPath)) {
                if (line.isBlank()) {
                    continue;
                }
                Path entry = Path.of(line);
                Path resolved = entry.isAbsolute()
                        ? entry.normalize()
                        : projectRoot.resolve(entry).normalize();
                if (Files.isDirectory(resolved)) {
                    entries.addAll(comparableDirectoryEntries(resolved));
                }
            }
            return entries;
        } catch (IOException exception) {
            throw new SelfHostingParityException(
                    "Could not read runtime classpath sidecar "
                            + runtimeClasspathPath
                            + ". Check that the file is readable.",
                    exception);
        }
    }

    private static Set<String> comparableDirectoryEntries(Path root) {
        try (var stream = Files.walk(root)) {
            Set<String> entries = new TreeSet<>();
            stream.filter(Files::isRegularFile)
                    .filter(path -> !LOCAL_BUILD_FINGERPRINTS.contains(path.getFileName().toString()))
                    .sorted(Comparator.comparing(path -> root.relativize(path).normalize().toString()))
                    .map(path -> root.relativize(path).normalize().toString().replace('\\', '/'))
                    .filter(SelfHostingParityService::isComparableEntry)
                    .forEach(entries::add);
            return entries;
        } catch (IOException exception) {
            throw new SelfHostingParityException(
                    "Could not read runtime classpath directory "
                            + root
                            + ". Check that the directory is readable.",
                    exception);
        }
    }

    private static PackageResult packageWorkspaceDefaultMember(Path projectDirectory, Path cacheRoot) {
        return new WorkspacePackageService()
                .packageJars(projectDirectory, cacheRoot, WorkspaceSelectionRequest.defaults())
                .members()
                .stream()
                .findFirst()
                .orElseThrow(() -> new SelfHostingParityException(
                        "Self-hosting parity expected workspace package to produce a selected member. "
                                + "Check [workspace.members].default or pass a single-project Zolt checkout."))
                .result();
    }

    private static Set<String> comparableJarEntries(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Set<String> entries = new TreeSet<>();
            zip.entries().asIterator().forEachRemaining(entry -> {
                if (!entry.isDirectory()
                        && !MANIFEST_ENTRY.equals(entry.getName())
                        && isComparableEntry(entry.getName())) {
                    entries.add(entry.getName());
                }
            });
            return entries;
        } catch (IOException exception) {
            throw new SelfHostingParityException(
                    "Could not read jar entries from "
                            + jar
                            + ". Check that the jar is readable and not corrupt.",
                    exception);
        }
    }

    private static boolean isComparableEntry(String entry) {
        return !LOCAL_RUNTIME_RESOURCES.contains(entry);
    }

    @FunctionalInterface
    interface ProjectPackager {
        PackageResult packageJar(Path projectDirectory, ProjectConfig config, Path cacheRoot);
    }

    @FunctionalInterface
    interface WorkspaceDetector {
        boolean usesRealWorkspace(Path projectDirectory);
    }

    @FunctionalInterface
    interface WorkspacePackager {
        PackageResult packageJar(Path projectDirectory, Path cacheRoot);
    }
}
