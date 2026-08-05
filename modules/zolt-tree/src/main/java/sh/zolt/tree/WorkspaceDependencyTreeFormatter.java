package sh.zolt.tree;

import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.TreeSet;

/**
 * The workspace text view of {@code zolt tree}: the workspace name, then one section per member
 * rendering that member's own graph view of the root lock, then the workspace-wide policy effects.
 *
 * <p>Members are visited in sorted path order and every section is drawn from committed lock facts,
 * so the document is byte-stable across runs.
 */
public final class WorkspaceDependencyTreeFormatter {
    public String format(String workspaceName, List<String> memberPaths, ZoltLockfile lockfile) {
        WorkspaceTreeProjection projection = WorkspaceTreeProjection.of(lockfile, memberPaths);
        List<String> members = List.copyOf(new TreeSet<>(memberPaths));

        StringBuilder output = new StringBuilder();
        output.append(workspaceName).append('\n');
        for (int index = 0; index < members.size(); index++) {
            String member = members.get(index);
            output.append(member).append('\n');
            new DependencyTreeLines(
                    projection.index(),
                    DependencyTreeLines.conflictsByPackage(lockfile),
                    projection.viewFor(member),
                    WorkspaceTreeProjection.REGENERATE_COMMAND)
                    .write(output, projection.directPackagesFor(member));
            if (index + 1 < members.size()) {
                output.append('\n');
            }
        }
        DependencyTreeFormatter.writePolicyEffects(output, lockfile);
        return output.toString();
    }
}
