package sh.zolt.explain.emit;

import sh.zolt.dependency.DependencyLane;
import sh.zolt.explain.maven.MavenAnnotationProcessorInspection;
import sh.zolt.explain.maven.MavenDependencyInspection;
import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenPlatformApiHostCandidate;
import sh.zolt.explain.maven.MavenProjectInspection;
import sh.zolt.explain.maven.MavenRepositoryInspection;
import sh.zolt.manifest.authored.AuthoredBuildConfiguration;
import sh.zolt.manifest.authored.AuthoredDependencyConstraints;
import sh.zolt.manifest.authored.AuthoredGeneratedSources;
import sh.zolt.manifest.authored.AuthoredManifest;
import sh.zolt.manifest.authored.AuthoredPackaging;
import sh.zolt.project.VersionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Maps a single-project {@link MavenInspectionResult} to a {@link DraftZoltToml}. */
final class MavenInspectionMapper {
    private static final String PLACEHOLDER_GROUP = "com.example";
    private static final String PLACEHOLDER_VERSION = "0.1.0";

    private MavenInspectionMapper() {
    }

    static DraftZoltToml map(MavenInspectionResult result) {
        List<String> notes = new ArrayList<>();
        MavenProjectInspection primary = result.projects().get(0);
        return mapProject(primary, null, Map.of(), DraftIdentityDefaults.none(), notes);
    }

    /** Maps one reactor member, rewriting sibling deps to {@code { workspace = true }} via the registry. */
    static DraftZoltToml mapMember(
            MavenProjectInspection project,
            WorkspaceMemberRegistry registry,
            Map<String, MavenProjectInspection> reactorProjects,
            DraftIdentityDefaults defaults) {
        return mapProject(project, registry, reactorProjects, defaults, new ArrayList<>());
    }

    private static DraftZoltToml mapProject(
            MavenProjectInspection primary,
            WorkspaceMemberRegistry registry,
            Map<String, MavenProjectInspection> reactorProjects,
            DraftIdentityDefaults defaults,
            List<String> notes) {
        // A standalone dependencyManagement BOM drafts a [bom] member. Reactor-member BOMs keep the
        // existing platform/pin routing so multi-module emit stays stable.
        if (registry == null && MavenBomDraftMapper.isBom(primary)) {
            return MavenBomDraftMapper.map(primary, notes);
        }
        DraftDependencies dependencies = new DraftDependencies(notes);

        MavenPlatformMapping platformMapping =
                MavenPlatformMapper.map(primary.importedBoms(), registry, reactorProjects, notes);
        platformMapping.platforms().forEach(dependencies::platform);
        List<MavenDependencyInspection> managedForConstraints = new ArrayList<>(primary.dependencyManagement());
        managedForConstraints.addAll(platformMapping.managedDependencies());
        Optional<AuthoredDependencyConstraints> constraints =
                MavenDependencyConstraintMapper.map(
                        managedForConstraints,
                        primary.dependencies(),
                        notes);
        MavenDependencySectionMapper dependencyMapper = new MavenDependencySectionMapper(
                registry,
                dependencies,
                platformMapping.managedPins(),
                notes);
        for (MavenDependencyInspection dependency : primary.dependencies()) {
            dependencyMapper.map(dependency);
        }
        for (MavenAnnotationProcessorInspection processor : primary.annotationProcessors()) {
            mapAnnotationProcessor(processor, dependencies, notes);
        }
        addRepositoryNotes(primary.repositories(), notes);
        addProfileNotes(primary, notes);

        Optional<String> group = defaults.group(
                primary.groupId(), () -> placeholderGroup(notes));
        Optional<String> version = defaults.version(
                primary.version(), () -> placeholderVersion(notes));
        version.filter(VersionPolicy::isSnapshot).ifPresent(value -> notes.add(snapshotNote(value)));
        Optional<Integer> javaRelease = defaults.javaRelease(
                JavaVersionNotation.featureRelease(primary.javaVersion()),
                () -> notes.add(unreadableJavaNote(primary.javaVersion())));
        addTestJavaVersionNote(primary, notes);
        boolean suggestJdkApiHost = MavenPlatformApiHostCandidate.applies(primary);
        if (suggestJdkApiHost) {
            notes.add(jdkApiHostNote(primary.javaVersion()));
        }

        AuthoredBuildConfiguration build = InspectionBuildSettingsMapper.fromRoots(
                primary.sourceRoots(),
                primary.testSourceRoots(),
                primary.resourceRoots(),
                primary.testResourceRoots(),
                notes);
        Optional<AuthoredGeneratedSources> generated =
                MavenExecStepDrafter.draft(primary.plugins(), notes);
        if (generated.isPresent()) {
            notes.add("Exec steps drafted from Maven exec-shaped plugins carry a placeholder input ("
                    + MavenExecStepDrafter.INPUT_PLACEHOLDER + ") and a conventional output path;"
                    + " declare the real declared-input closure and owned output for each before building.");
        }
        AuthoredManifest manifest = DraftManifests.project(
                DraftManifests.identity(
                        primary.artifactId(),
                        group,
                        version,
                        javaRelease,
                        notes),
                DraftManifests.metadata(Optional.empty(), notes),
                dependencies,
                constraints,
                build,
                generated,
                AuthoredPackaging.empty());
        return new DraftZoltToml(manifest, notes, suggestJdkApiHost);
    }

    private static String jdkApiHostNote(String javaVersion) {
        return "This POM set source/target " + javaVersion + " below the build JDK, so Maven compiled"
                + " against the host JDK's API surface. Zolt defaults to the reproducible `--release "
                + javaVersion + "`. If the strict build fails because a dependency or annotation processor"
                + " uses a newer-than-" + javaVersion + " platform API, uncomment `jdkApi = \"host\"`"
                + " under [compiler]; note that host mode forfeits cross-JDK reproducibility — prefer"
                + " raising [project].java or a multi-release JAR.";
    }

    /**
     * The review note for an unreadable Java version. The final language types {@code [project].java}
     * as an integer, so an unreadable value cannot be drafted at all — not even commented out — and
     * the raw notation goes into the note instead.
     */
    static String unreadableJavaNote(String inspected) {
        return "Project Java version could not be determined from the static audit (`"
                + JavaVersionNotation.reviewValue(inspected)
                + "`); add `[project].java` and set it to the Java feature version before"
                + " resolving or building.";
    }

    static String snapshotNote(String version) {
        return "Project version `" + version + "` is a SNAPSHOT, a documented non-determinism"
                + " signal (version-policy rule: snapshot-version); it resolves as-is but"
                + " pin it to a fixed release before relying on the draft.";
    }

    private static void mapAnnotationProcessor(
            MavenAnnotationProcessorInspection processor,
            DraftDependencies dependencies,
            List<String> notes) {
        String coordinate = coordinateOf(processor.coordinate());
        if (processor.version().isBlank()) {
            notes.add(
                    "Annotation processor `" + coordinate + "` has no static version; add it under"
                            + " [dependencies.processor] before resolving.");
            return;
        }
        if (processor.version().contains("${")) {
            notes.add(
                    "Annotation processor `" + coordinate + "` uses version `" + processor.version()
                            + "`, which references a property the static audit could not resolve. Replace it"
                            + " with a fixed version under [dependencies.processor] before resolving.");
            return;
        }
        dependencies.fixed(DependencyLane.PROCESSOR, coordinate, processor.version());
    }

    private static void addRepositoryNotes(List<MavenRepositoryInspection> repositories, List<String> notes) {
        for (MavenRepositoryInspection repository : repositories) {
            notes.add(
                    "Custom Maven repository `" + repository.url() + "` was declared; Zolt defaults to"
                            + " Maven Central only. Add it under [repositories] if your build needs it.");
        }
    }

    private static void addProfileNotes(MavenProjectInspection project, List<String> notes) {
        if (!project.profiles().isEmpty()) {
            notes.add(
                    "Maven profiles were detected and are not translated; Zolt has no profile concept."
                            + " Fold any required profile config into this zolt.toml by hand.");
        }
    }

    private static void addTestJavaVersionNote(MavenProjectInspection project, List<String> notes) {
        if (project.testJavaVersion().isBlank() || project.testJavaVersion().equals(project.javaVersion())) {
            return;
        }
        notes.add(
                "Maven test Java version `" + project.testJavaVersion()
                        + "` differs from main Java version `" + project.javaVersion()
                        + "`; declare it under [toolchain.java.test] after review.");
    }

    /** The group to author for a standalone project, with a placeholder plus note when unreadable. */
    static String group(MavenProjectInspection project, List<String> notes) {
        String groupId = project.groupId();
        if (groupId != null && !groupId.isBlank()) {
            return groupId;
        }
        return placeholderGroup(notes);
    }

    /** The version to author for a standalone project, with a placeholder plus note when unreadable. */
    static String version(MavenProjectInspection project, List<String> notes) {
        String version = project.version();
        if (version != null && !version.isBlank()) {
            if (VersionPolicy.isSnapshot(version)) {
                notes.add(snapshotNote(version));
            }
            return version;
        }
        return placeholderVersion(notes);
    }

    private static String placeholderGroup(List<String> notes) {
        notes.add(
                "Project group could not be read from the static audit; `group` is a placeholder."
                        + " Set it to your real Maven groupId.");
        return PLACEHOLDER_GROUP;
    }

    private static String placeholderVersion(List<String> notes) {
        notes.add(
                "Project version could not be read from the static audit; `version` is a placeholder."
                        + " Set it to your real release before publishing.");
        return PLACEHOLDER_VERSION;
    }

    static String coordinateOf(String coordinate) {
        String[] parts = coordinate.split(":");
        if (parts.length >= 2) {
            return parts[0] + ":" + parts[1];
        }
        return coordinate;
    }
}
