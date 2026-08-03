package sh.zolt.workspace.coverage;

import sh.zolt.build.CoverageException;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceBuildResult;
import sh.zolt.workspace.service.WorkspaceMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class WorkspaceCoverageExecution {
    private WorkspaceCoverageExecution() {
    }

    static List<WorkspaceMember> reportMembers(
            Workspace workspace,
            WorkspaceBuildResult buildResult) {
        Map<String, WorkspaceMember> membersByPath = new LinkedHashMap<>();
        for (WorkspaceMember member : workspace.members()) {
            membersByPath.put(member.path(), member);
        }
        List<WorkspaceMember> members = new ArrayList<>();
        for (WorkspaceBuildResult.MemberBuildResult memberBuild :
                buildResult.members()) {
            WorkspaceMember member = membersByPath.get(memberBuild.member());
            if (member != null) {
                members.add(member);
            }
        }
        if (members.isEmpty()) {
            throw new CoverageException(
                    "Workspace coverage requires at least one selected workspace member.");
        }
        return List.copyOf(members);
    }

    static WorkspaceMember reportMember(List<WorkspaceMember> members) {
        return members.stream()
                .sorted(Comparator
                        .comparingInt(WorkspaceCoverageExecution::javaFeature)
                        .reversed()
                        .thenComparing(WorkspaceMember::path))
                .findFirst()
                .orElseThrow();
    }

    static List<Path> classfileRoots(List<WorkspaceMember> members) {
        return members.stream()
                .map(member -> member.directory()
                        .resolve(member.config().build().output())
                        .toAbsolutePath()
                        .normalize())
                .toList();
    }

    static List<Path> sourceRoots(List<WorkspaceMember> members) {
        return members.stream()
                .flatMap(member -> member.config().build().sourceRoots().stream()
                        .map(root -> member.directory()
                                .resolve(root)
                                .toAbsolutePath()
                                .normalize()))
                .toList();
    }

    static void recreateExecFile(Path execFile) {
        try {
            Path parent = execFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.deleteIfExists(execFile);
            deleteWorkerExecFiles(execFile);
        } catch (IOException exception) {
            throw new CoverageException(
                    "Could not prepare workspace coverage execution data at "
                            + execFile
                            + ". Check coverage output permissions, then run `zolt coverage --workspace` again.",
                    exception);
        }
    }

    private static void deleteWorkerExecFiles(Path execFile)
            throws IOException {
        Path parent = execFile.getParent();
        if (parent == null) {
            return;
        }
        Path workersDirectory = parent.resolve("workers");
        if (!Files.isDirectory(workersDirectory)) {
            return;
        }
        List<Path> workerExecFiles;
        try (java.util.stream.Stream<Path> paths =
                Files.walk(workersDirectory)) {
            workerExecFiles = paths
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().equals(
                            execFile.getFileName()))
                    .toList();
        }
        for (Path workerExecFile : workerExecFiles) {
            Files.deleteIfExists(workerExecFile);
        }
    }

    private static int javaFeature(WorkspaceMember member) {
        try {
            return Integer.parseInt(member.config().project().java());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
