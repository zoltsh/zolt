package sh.zolt.explain.emit;

import sh.zolt.explain.maven.MavenInspectionResult;
import sh.zolt.explain.maven.MavenProfileInspection;
import sh.zolt.explain.maven.MavenProjectInspection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Maps a multi-module Maven reactor to a {@link DraftWorkspace}: the root aggregator becomes the
 * virtual workspace manifest; every non-root module becomes a member draft. A dependency whose
 * {@code groupId:artifactId} matches another module is rewritten to {@code { workspace = true }}, and
 * the aggregator's shared identity is hoisted into {@code [workspace.project]} so members that
 * inherit it author nothing (design §4.3).
 */
final class MavenWorkspaceMapper {
    private static final String ROOT_PATH = ".";

    private MavenWorkspaceMapper() {
    }

    /** True when the reactor has real modules to promote into a workspace. */
    static boolean isWorkspace(MavenInspectionResult result) {
        return result.projects().stream().anyMatch(project -> !ROOT_PATH.equals(project.path().toString()));
    }

    static DraftWorkspace map(MavenInspectionResult result) {
        List<MavenProjectInspection> projects = result.projects();
        MavenProjectInspection root = root(projects);

        WorkspaceMemberRegistry registry = new WorkspaceMemberRegistry();
        Map<String, MavenProjectInspection> projectsByCoordinate = new TreeMap<>();
        for (MavenProjectInspection project : projects) {
            String key = memberKey(project);
            registry.register(key, project.path().toString());
            if (key != null) {
                projectsByCoordinate.put(key, project);
            }
        }

        List<String> notes = new ArrayList<>();
        List<String> memberPaths = projects.stream()
                .map(project -> project.path().toString())
                .filter(path -> !ROOT_PATH.equals(path))
                .toList();
        // The reactor root's coordinates are the shared identity: in Maven every module either
        // inherits them from the parent or overrides them explicitly.
        DraftIdentityDefaults defaults = DraftWorkspaceRoot.sharedIdentity(
                List.of(nonBlank(root.groupId())),
                List.of(nonBlank(root.version())),
                List.of(JavaVersionNotation.featureRelease(root.javaVersion())));
        DraftWorkspaceRoot.Built built =
                DraftWorkspaceRoot.build(root.artifactId(), memberPaths, defaults, notes);

        List<DraftWorkspace.Member> members = new ArrayList<>();
        for (MavenProjectInspection project : projects) {
            String path = project.path().toString();
            if (!built.memberPaths().contains(path)) {
                continue;
            }
            members.add(new DraftWorkspace.Member(
                    path,
                    MavenInspectionMapper.mapMember(project, registry, projectsByCoordinate, defaults)));
        }

        if (!root.dependencies().isEmpty()) {
            notes.add(
                    "The root aggregator declares " + root.dependencies().size()
                            + " dependency(ies); a virtual workspace cannot carry dependencies. Move them"
                            + " into the member(s) that need them, or into a shared module.");
        }
        notes.addAll(profileModuleNotes(projects, built.memberPaths()));
        return new DraftWorkspace(built.manifest(), members, notes);
    }

    private static Optional<String> nonBlank(String value) {
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static MavenProjectInspection root(List<MavenProjectInspection> projects) {
        return projects.stream()
                .filter(project -> ROOT_PATH.equals(project.path().toString()))
                .findFirst()
                .orElse(projects.get(0));
    }

    private static String memberKey(MavenProjectInspection project) {
        String group = project.groupId();
        String artifact = project.artifactId();
        if (group == null || group.isBlank() || artifact == null || artifact.isBlank()) {
            return null;
        }
        return group + ":" + artifact;
    }

    private static List<String> profileModuleNotes(
            List<MavenProjectInspection> projects,
            List<String> memberPaths) {
        List<String> notes = new ArrayList<>();
        for (MavenProjectInspection project : projects) {
            for (MavenProfileInspection profile : project.profiles()) {
                List<String> omitted = profile.modules().stream()
                        .map(module -> profileModulePath(project.path().toString(), module))
                        .filter(module -> !memberPaths.contains(module))
                        .distinct()
                        .sorted()
                        .toList();
                if (!omitted.isEmpty()) {
                    notes.add(
                            "Maven profile `" + profile.id() + "` in project `"
                                    + project.path()
                                    + "` declares module(s) omitted from workspace members: "
                                    + String.join(", ", omitted)
                                    + ". Add them by hand if that profile is active for this migration.");
                }
            }
        }
        return notes;
    }

    private static String profileModulePath(String projectPath, String module) {
        String combined = ROOT_PATH.equals(projectPath) ? module : projectPath + "/" + module;
        return Path.of(combined).normalize().toString().replace('\\', '/');
    }
}
