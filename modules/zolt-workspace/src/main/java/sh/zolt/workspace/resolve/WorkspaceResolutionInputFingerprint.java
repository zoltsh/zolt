package sh.zolt.workspace.resolve;

import sh.zolt.lockfile.ZoltLockfile;
import sh.zolt.lockfile.toml.LockfileSidecars;
import sh.zolt.project.ProjectConfig;
import sh.zolt.resolve.ResolveException;
import sh.zolt.resolve.fingerprint.ProjectResolutionFingerprint;
import sh.zolt.workspace.service.Workspace;
import sh.zolt.workspace.service.WorkspaceMember;
import sh.zolt.workspace.service.WorkspaceProjectEdge;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Digest of everything a locked workspace resolve would read, so a command can decide the root lock
 * is current without redoing that resolve.
 *
 * <p>The digest covers, in this fixed order and each sorted within its group:
 *
 * <ol>
 *   <li>{@code schema} — this fingerprint's own encoding version.</li>
 *   <li>{@code lockVersion} — the lockfile schema the resolver writes, so a resolver whose output
 *       shape changed cannot match a lock written by the previous one.</li>
 *   <li>{@code input} — the byte digest of every authoritative config path workspace discovery
 *       captured, keyed by its location relative to the workspace root, including paths that were
 *       absent when captured. This is deliberately coarse: a comment-only edit invalidates.</li>
 *   <li>{@code member} — the sorted declared member paths.</li>
 *   <li>{@code memberCoordinate} — each member's group, artifact, and version.</li>
 *   <li>{@code memberResolution} — each member's {@link ProjectResolutionFingerprint} computed over
 *       its <em>effective</em> config, that is after workspace repository, platform, and credential
 *       policy merging. This is the resolution-relevant configuration, repository definitions, and
 *       resolution-affecting framework inputs.</li>
 *   <li>{@code edge} — the workspace project edges with their scope, coordinate, exported flag, and
 *       optional flag.</li>
 *   <li>{@code lockContent} — the digest of the lock's own canonical dependency content, so the
 *       fingerprint certifies a specific lock rather than only the inputs that derived one. Without
 *       it a hand-edited {@code [[package]]} block that stayed self-consistent would pass the gate
 *       the byte comparison used to catch.</li>
 * </ol>
 *
 * <p>The lock content enters through {@link LockfileSidecars#canonicalDependencyLockfile}, which
 * already drops the recorded fingerprint line, so recording the value cannot change the digest that
 * produced it.
 *
 * <p>Deliberately excluded, because they do not change what a resolve produces: {@code --offline}
 * (it restricts where artifacts may come from, not which are selected), the retry command in error
 * text, artifact progress listeners, and coverage tooling inclusion (which is derived from the very
 * lock being checked). Repository overlays are excluded because workspace resolve rejects them.
 */
public final class WorkspaceResolutionInputFingerprint {
    private static final String SCHEMA = "v2";

    private WorkspaceResolutionInputFingerprint() {
    }

    /**
     * Fingerprint of {@code workspace} against {@code lockfileContent}, merging each member's
     * workspace policy to reach its effective config. Empty when discovery captured no config bytes,
     * since a digest over missing evidence must never be recorded or matched.
     */
    public static Optional<String> fingerprint(Workspace workspace, String lockfileContent) {
        return fingerprint(workspace, effectiveConfigs(workspace), lockfileContent);
    }

    /** Fingerprint reusing effective configs a caller already merged, keyed by member path. */
    public static Optional<String> fingerprint(
            Workspace workspace,
            Map<String, ProjectConfig> effectiveConfigs,
            String lockfileContent) {
        if (workspace.inputs().isEmpty()) {
            return Optional.empty();
        }
        List<String> inputs = inputs(workspace, effectiveConfigs, lockfileContent);
        return Optional.of("sha256:" + sha256(
                (String.join("\n", inputs) + "\n").getBytes(StandardCharsets.UTF_8)));
    }

    static Map<String, ProjectConfig> effectiveConfigs(Workspace workspace) {
        WorkspacePolicyMerger merger = new WorkspacePolicyMerger();
        Map<String, ProjectConfig> configs = new TreeMap<>();
        for (WorkspaceMember member : workspace.members()) {
            configs.put(member.path(), merger.merge(workspace, member));
        }
        return configs;
    }

    static List<String> inputs(
            Workspace workspace,
            Map<String, ProjectConfig> effectiveConfigs,
            String lockfileContent) {
        List<String> inputs = new ArrayList<>();
        line(inputs, "schema", SCHEMA);
        line(inputs, "lockVersion", Integer.toString(ZoltLockfile.CURRENT_VERSION));
        workspace.inputs()
                .digestsRelativeTo(workspace.root())
                .forEach((path, digest) -> line(inputs, "input", path, digest));
        List<WorkspaceMember> members = workspace.members().stream()
                .sorted(Comparator.comparing(WorkspaceMember::path))
                .toList();
        for (WorkspaceMember member : members) {
            line(inputs, "member", member.path());
        }
        for (WorkspaceMember member : members) {
            line(
                    inputs,
                    "memberCoordinate",
                    member.path(),
                    member.config().project().group(),
                    member.config().project().name(),
                    member.config().project().version());
        }
        for (WorkspaceMember member : members) {
            ProjectConfig effectiveConfig = effectiveConfigs.get(member.path());
            if (effectiveConfig == null) {
                throw new ResolveException(
                        "Missing effective configuration for workspace member `"
                                + member.path()
                                + "` while fingerprinting workspace resolution inputs.");
            }
            line(
                    inputs,
                    "memberResolution",
                    member.path(),
                    ProjectResolutionFingerprint.fingerprint(effectiveConfig));
        }
        workspace.edges().stream()
                .sorted(Comparator
                        .comparing(WorkspaceProjectEdge::from)
                        .thenComparing(WorkspaceProjectEdge::to)
                        .thenComparing(WorkspaceProjectEdge::scope)
                        .thenComparing(WorkspaceProjectEdge::coordinate))
                .forEach(edge -> line(
                        inputs,
                        "edge",
                        edge.from(),
                        edge.to(),
                        edge.scope(),
                        edge.coordinate(),
                        Boolean.toString(edge.exported()),
                        Boolean.toString(edge.optional())));
        line(inputs, "lockContent", lockContentDigest(lockfileContent));
        return List.copyOf(inputs);
    }

    /**
     * Digest of the lock content the fingerprint certifies. Canonicalising first drops the recorded
     * fingerprint line and the Java toolchain blocks, so the value a resolve computes before writing
     * equals the value the gate computes after reading the file back.
     */
    private static String lockContentDigest(String lockfileContent) {
        return sha256(LockfileSidecars
                .canonicalDependencyLockfile(lockfileContent == null ? "" : lockfileContent)
                .getBytes(StandardCharsets.UTF_8));
    }

    private static void line(List<String> inputs, String category, String... values) {
        inputs.add(category + "\t" + String.join("\t", values));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new ResolveException(
                    "Could not fingerprint workspace resolution inputs because SHA-256 is unavailable.",
                    exception);
        }
    }
}
