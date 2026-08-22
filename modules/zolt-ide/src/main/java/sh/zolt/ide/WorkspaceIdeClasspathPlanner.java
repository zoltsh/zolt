package sh.zolt.ide;

import sh.zolt.classpath.Classpath;
import sh.zolt.classpath.ClasspathSet;
import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.project.ProjectPathException;
import sh.zolt.project.ProjectPaths;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceClasspathService;
import sh.zolt.workspace.service.WorkspaceMember;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class WorkspaceIdeClasspathPlanner {
    private final WorkspaceClasspathService workspaceClasspathService;

    WorkspaceIdeClasspathPlanner(WorkspaceClasspathService workspaceClasspathService) {
        this.workspaceClasspathService = workspaceClasspathService;
    }

    Map<String, IdeModel.ClasspathInfo> classpaths(
            Workspace workspace,
            Path cacheRoot,
            ZoltLockfile lockfile) {
        Map<String, IdeModel.ClasspathInfo> classpathsByMember = new LinkedHashMap<>();
        if (lockfile == null) {
            for (WorkspaceMember member : workspace.members()) {
                classpathsByMember.put(member.path(), emptyClasspaths());
            }
            return Collections.unmodifiableMap(classpathsByMember);
        }
        Map<String, ClasspathSet> zoltClasspathsByMember = workspaceClasspathService.classpathsForMembers(
                workspace,
                lockfile,
                cacheRoot,
                workspace.members().stream()
                        .map(WorkspaceMember::path)
                        .toList());
        for (WorkspaceMember member : workspace.members()) {
            ClasspathSet classpaths = zoltClasspathsByMember.get(member.path());
            Optional<Path> mainOutput = outputPath(member, "[build.output].main", member.config().build().output());
            Optional<Path> testOutput = outputPath(member, "[build.output].test", member.config().build().testOutput());
            classpathsByMember.put(
                    member.path(),
                    new IdeModel.ClasspathInfo(
                            absoluteEntries(classpaths.compile()),
                            withOutputs(mainOutput.stream().toList(), classpaths.runtime()),
                            withOutputs(
                                    java.util.stream.Stream.concat(mainOutput.stream(), testOutput.stream()).toList(),
                                    classpaths.test()),
                            absoluteEntries(classpaths.processor()),
                            absoluteEntries(classpaths.testProcessor()),
                            absoluteEntries(classpaths.quarkusDeployment())));
        }
        return Collections.unmodifiableMap(classpathsByMember);
    }

    private static IdeModel.ClasspathInfo emptyClasspaths() {
        return new IdeModel.ClasspathInfo(List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static Optional<Path> outputPath(WorkspaceMember member, String key, String configuredPath) {
        try {
            return Optional.of(ProjectPaths.output(
                    ProjectPaths.root(member.directory()),
                    key,
                    configuredPath));
        } catch (ProjectPathException exception) {
            return Optional.empty();
        }
    }

    private static List<Path> withOutputs(List<Path> outputs, Classpath classpath) {
        List<Path> entries = new ArrayList<>(outputs);
        entries.addAll(absoluteEntries(classpath));
        return entries;
    }

    private static List<Path> absoluteEntries(Classpath classpath) {
        return classpath.entries().stream()
                .map(path -> path.toAbsolutePath().normalize())
                .toList();
    }
}
