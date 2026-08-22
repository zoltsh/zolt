package sh.zolt.explain.emit;

import sh.zolt.explain.gradle.GradleInspectionResult;
import sh.zolt.explain.gradle.GradleProjectInspection;
import java.util.ArrayList;
import java.util.List;

/**
 * Maps a Gradle multi-project build to a {@link DraftWorkspace}: the root project becomes the virtual
 * workspace manifest; every included subproject becomes a member draft. A {@code project(":lib")} edge
 * is rewritten to {@code "group:name" = { workspace = true }}, and identity every subproject agrees on
 * is hoisted into {@code [workspace.project]} (design §4.3).
 */
final class GradleWorkspaceMapper {
    private static final String ROOT_PATH = ".";

    private GradleWorkspaceMapper() {
    }

    /** True when the build includes subprojects to promote into a workspace. */
    static boolean isWorkspace(GradleInspectionResult result) {
        return result.projects().stream().anyMatch(project -> !ROOT_PATH.equals(project.path().toString()));
    }

    static DraftWorkspace map(GradleInspectionResult result) {
        List<GradleProjectInspection> projects = result.projects();
        GradleProjectInspection root = root(projects);

        WorkspaceMemberRegistry registry = new WorkspaceMemberRegistry();
        for (GradleProjectInspection project : projects) {
            String path = project.path().toString();
            if (!ROOT_PATH.equals(path)) {
                registry.register(path, path, GradleInspectionMapper.emittedCoordinate(project));
            }
        }

        List<GradleProjectInspection> subprojects = projects.stream()
                .filter(project -> !ROOT_PATH.equals(project.path().toString()))
                .toList();
        // Gradle has no parent-POM inheritance, so a value is shared only when every subproject that
        // declares one agrees; anything else stays authored per member.
        DraftIdentityDefaults defaults = DraftWorkspaceRoot.sharedIdentity(
                subprojects.stream().map(project -> project.group().filter(value -> !value.isBlank())).toList(),
                subprojects.stream().map(project -> project.version().filter(value -> !value.isBlank())).toList(),
                subprojects.stream()
                        .map(project -> JavaVersionNotation.featureRelease(project.javaVersion()))
                        .toList());

        List<String> notes = new ArrayList<>(GradleInspectionMapper.skippedIncludedProjectNotes(result));
        DraftWorkspaceRoot.Built built = DraftWorkspaceRoot.build(
                root.name(),
                subprojects.stream().map(project -> project.path().toString()).toList(),
                defaults,
                notes);

        List<DraftWorkspace.Member> members = new ArrayList<>();
        for (GradleProjectInspection project : subprojects) {
            String path = project.path().toString();
            if (!built.memberPaths().contains(path)) {
                continue;
            }
            members.add(new DraftWorkspace.Member(
                    path,
                    GradleInspectionMapper.mapMember(
                            project, registry, result.versionCatalogAliases(), defaults)));
        }

        if (!root.dependencies().isEmpty()) {
            notes.add(
                    "The root project declares " + root.dependencies().size()
                            + " dependency(ies); a virtual workspace cannot carry dependencies. Move them"
                            + " into the member(s) that need them, or into a shared module.");
        }
        return new DraftWorkspace(built.manifest(), members, notes);
    }

    private static GradleProjectInspection root(List<GradleProjectInspection> projects) {
        return projects.stream()
                .filter(project -> ROOT_PATH.equals(project.path().toString()))
                .findFirst()
                .orElse(projects.get(0));
    }
}
