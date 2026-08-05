package sh.zolt.tree;

import static sh.zolt.tree.DependencyJsonFields.booleanField;
import static sh.zolt.tree.DependencyJsonFields.comma;
import static sh.zolt.tree.DependencyJsonFields.indent;
import static sh.zolt.tree.DependencyJsonFields.intField;
import static sh.zolt.tree.DependencyJsonFields.stringArrayField;
import static sh.zolt.tree.DependencyJsonFields.stringField;

import sh.zolt.lockfile.LockArtifactVariant;
import sh.zolt.lockfile.LockPackage;
import sh.zolt.lockfile.ZoltLockfile;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * The schema-version-2 workspace projection of {@code zolt tree --format json}.
 *
 * <p>Schema 1 describes one standalone project and stays exactly as it is; schema 2 describes a whole
 * workspace and is emitted only for {@code --workspace}. Both share this package's coordinate, variant,
 * and dependency-edge spellings, so one consumer-side edge parser reads either version.
 *
 * <p>Schema 2 projects the lock in full: every {@code sh.zolt.dependency.DependencyScope} the lock
 * records is emitted, each occurrence carrying its own {@code scope} and each dependency edge carrying
 * the scope of the occurrence it names. {@code roots} lists coordinates ({@code id:version[:variant]}),
 * not edges, because a direct declaration is a coordinate the members share.
 *
 * <p>Every array is sorted and deduplicated, so repeated runs over one lock are byte-identical
 * regardless of member iteration order.
 */
public final class WorkspaceDependencyJsonFormatter {
    private static final int SCHEMA_VERSION = 2;

    public String tree(String workspaceName, List<String> memberPaths, ZoltLockfile lockfile) {
        WorkspaceTreeProjection projection = WorkspaceTreeProjection.of(lockfile, memberPaths);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        intField(json, 1, "schemaVersion", SCHEMA_VERSION, true);
        stringField(json, 1, "command", "tree", true);
        stringField(json, 1, "mode", "workspace", true);
        intField(json, 1, "lockVersion", lockfile.version(), true);
        workspace(json, workspaceName, memberPaths);
        comma(json);
        packages(json, projection);
        comma(json);
        stringArrayField(json, 1, "roots", projection.roots(), false);
        json.append("}\n");
        return json.toString();
    }

    private static void workspace(StringBuilder json, String workspaceName, List<String> memberPaths) {
        indent(json, 1).append("\"workspace\": {\n");
        stringField(json, 2, "name", workspaceName, true);
        stringArrayField(json, 2, "members", List.copyOf(new TreeSet<>(memberPaths)), false);
        indent(json, 1).append("}");
    }

    private static void packages(StringBuilder json, WorkspaceTreeProjection projection) {
        indent(json, 1).append("\"packages\": [");
        List<LockPackage> packages = projection.packages();
        if (!packages.isEmpty()) {
            json.append('\n');
            for (int index = 0; index < packages.size(); index++) {
                packageObject(json, packages.get(index), projection);
                if (index + 1 < packages.size()) {
                    json.append(',');
                }
                json.append('\n');
            }
            indent(json, 1);
        }
        json.append("]");
    }

    private static void packageObject(
            StringBuilder json,
            LockPackage lockPackage,
            WorkspaceTreeProjection projection) {
        indent(json, 2).append("{\n");
        stringField(json, 3, "id", lockPackage.packageId().toString(), true);
        stringField(json, 3, "version", lockPackage.version(), true);
        stringField(json, 3, "coordinate", DependencyTreeLines.coordinate(lockPackage), true);
        nonDefaultVariant(lockPackage).ifPresent(variant ->
                stringField(json, 3, "variant", variant.key(), true));
        stringField(json, 3, "scope", lockPackage.scope().lockfileName(), true);
        booleanField(json, 3, "direct", lockPackage.direct(), true);
        stringArrayField(json, 3, "members", projection.members(lockPackage), true);
        stringArrayField(json, 3, "dependencies", projection.dependencies(lockPackage), false);
        indent(json, 2).append("}");
    }

    private static Optional<LockArtifactVariant> nonDefaultVariant(LockPackage lockPackage) {
        LockArtifactVariant variant = LockArtifactVariant.of(lockPackage);
        return variant.isDefault() ? Optional.empty() : Optional.of(variant);
    }
}
