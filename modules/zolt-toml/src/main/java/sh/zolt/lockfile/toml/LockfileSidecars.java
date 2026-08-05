package sh.zolt.lockfile.toml;

public final class LockfileSidecars {
    /**
     * Optional root-lock annotation recording the workspace resolution inputs that produced the
     * lock. It is derived from inputs the rest of the lock already pins, so it stays out of the
     * canonical dependency lockfile: a lock written before the annotation existed still verifies
     * under {@code --locked}, and an input edit that changes only the annotation (a comment-only
     * config edit) does not newly fail a locked verification.
     */
    public static final String WORKSPACE_RESOLUTION_INPUT_FINGERPRINT =
            "workspaceResolutionInputFingerprint";

    private LockfileSidecars() {
    }

    public static String withJavaToolchainBlocksFromExisting(
            String dependencyLockfile,
            String existingLockfile) {
        String javaToolchains = javaToolchainBlocks(existingLockfile);
        if (javaToolchains.isBlank()) {
            return dependencyLockfile;
        }
        return dependencyLockfile.stripTrailing() + "\n\n" + javaToolchains.strip() + "\n";
    }

    public static String canonicalDependencyLockfile(String content) {
        return withoutWorkspaceResolutionInputFingerprint(
                withoutJavaToolchainBlocks(content)).stripTrailing() + "\n";
    }

    /**
     * Records {@code fingerprint} on {@code content} at the position {@link
     * sh.zolt.lockfile.toml.ZoltLockfileWriter} writes it — last in the header, before the blank
     * line that opens the package blocks — so a lock upgraded in place is byte-identical to the one
     * an ordinary resolve would have written.
     */
    public static String withWorkspaceResolutionInputFingerprint(
            String content,
            String fingerprint) {
        String assignment =
                WORKSPACE_RESOLUTION_INPUT_FINGERPRINT + " = \"" + fingerprint + "\"";
        StringBuilder output = new StringBuilder();
        boolean recorded = false;
        for (String line : safeLines(withoutWorkspaceResolutionInputFingerprint(content))) {
            if (!recorded && (line.isBlank() || line.startsWith("["))) {
                output.append(assignment).append('\n');
                recorded = true;
            }
            output.append(line).append('\n');
        }
        if (!recorded) {
            output.append(assignment).append('\n');
        }
        return output.toString();
    }

    private static String withoutWorkspaceResolutionInputFingerprint(String content) {
        String prefix = WORKSPACE_RESOLUTION_INPUT_FINGERPRINT + " = ";
        if (content == null || !content.contains(prefix)) {
            return content;
        }
        StringBuilder output = new StringBuilder();
        for (String line : safeLines(content)) {
            if (line.startsWith(prefix)) {
                continue;
            }
            output.append(line).append('\n');
        }
        return output.toString();
    }

    private static String withoutJavaToolchainBlocks(String content) {
        StringBuilder output = new StringBuilder();
        boolean skipping = false;
        for (String line : safeLines(content)) {
            String trimmed = line.strip();
            if ("[[toolchain.java]]".equals(trimmed)) {
                skipping = true;
                continue;
            }
            if (skipping && trimmed.startsWith("[")) {
                skipping = false;
            }
            if (!skipping) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private static String javaToolchainBlocks(String content) {
        StringBuilder output = new StringBuilder();
        boolean copying = false;
        for (String line : safeLines(content)) {
            String trimmed = line.strip();
            if ("[[toolchain.java]]".equals(trimmed)) {
                copying = true;
            } else if (copying && trimmed.startsWith("[")) {
                copying = false;
            }
            if (copying) {
                output.append(line).append('\n');
            }
        }
        return output.toString();
    }

    private static java.util.List<String> safeLines(String content) {
        return content == null ? java.util.List.of() : content.lines().toList();
    }
}
