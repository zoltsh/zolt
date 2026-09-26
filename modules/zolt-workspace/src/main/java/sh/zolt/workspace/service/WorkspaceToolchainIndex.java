package sh.zolt.workspace.service;

import sh.zolt.build.BuildException;
import sh.zolt.build.compile.EffectiveCompilerIdentity;
import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

final class WorkspaceToolchainIndex {
    private final Map<Object, Map<String, JdkStatus>> statuses =
            new HashMap<>();
    private final Map<Object, Toolchain> toolchainsByKey =
            new HashMap<>();
    private int resolutions;
    private int hits;
    private int identityCalculations;
    private int identityHits;
    private WorkspaceJdkCheckerResolver observedResolver;
    private Path resolvedCompiler;

    JdkChecker checker(
            WorkspaceJdkCheckerResolver resolver,
            Workspace workspace,
            WorkspaceMember member) {
        Toolchain toolchain = toolchain(resolver, workspace, member);
        return requiredVersion -> detect(
                toolchain.cacheKey(),
                toolchain.checker(),
                requiredVersion);
    }

    String compileIdentity(
            WorkspaceJdkCheckerResolver resolver,
            Workspace workspace,
            WorkspaceMember member) {
        Toolchain toolchain = toolchain(resolver, workspace, member);
        JdkStatus status = detect(
                toolchain.cacheKey(),
                toolchain.checker(),
                member.config().project().java());
        requireUsable(member, status);
        rememberCompiler(status);
        return toolchain.configuredIdentity()
                + "|effectiveCompiler="
                + EffectiveCompilerIdentity.of(status)
                + "|required="
                + status.requiredVersion();
    }

    /**
     * A compiler some member has already resolved, which is enough to warm workers for. Reading it
     * costs nothing and resolves nothing: a member that never needed a compiler — a BOM, say — must
     * not acquire one just because the build would like warm workers.
     */
    synchronized Optional<Path> resolvedCompiler() {
        return Optional.ofNullable(resolvedCompiler);
    }

    private synchronized void rememberCompiler(JdkStatus status) {
        if (resolvedCompiler == null) {
            resolvedCompiler = status.javac().orElse(null);
        }
    }

    synchronized int resolutions() {
        return resolutions;
    }

    synchronized int hits() {
        return hits;
    }

    synchronized int lockfileParses() {
        return observedResolver == null
                ? 0
                : observedResolver.lockfileParseCount();
    }

    synchronized int identityCalculations() {
        return identityCalculations;
    }

    synchronized int identityHits() {
        return identityHits;
    }

    private synchronized JdkStatus detect(
            Object cacheKey,
            JdkChecker checker,
            String requiredVersion) {
        Map<String, JdkStatus> byVersion =
                statuses.computeIfAbsent(cacheKey, ignored -> new LinkedHashMap<>());
        JdkStatus cached = byVersion.get(requiredVersion);
        if (cached != null) {
            hits++;
            return cached;
        }
        resolutions++;
        JdkStatus status = checker.detect(requiredVersion);
        byVersion.put(requiredVersion, status);
        return status;
    }

    private synchronized Toolchain toolchain(
            WorkspaceJdkCheckerResolver resolver,
            Workspace workspace,
            WorkspaceMember member) {
        observedResolver = resolver;
        JdkChecker checker = resolver.forMember(workspace, member);
        Object cacheKey = resolver.cacheKey(
                workspace,
                member,
                checker);
        Toolchain cached = toolchainsByKey.get(cacheKey);
        if (cached != null) {
            identityHits++;
            return cached;
        }
        identityCalculations++;
        Toolchain resolved = new Toolchain(
                checker,
                cacheKey,
                resolver.compileIdentity(
                        workspace,
                        member,
                        checker,
                        cacheKey));
        toolchainsByKey.put(cacheKey, resolved);
        return resolved;
    }

    /**
     * Stage 0 can declare a member clean and never enter the build pipeline, so the JDK check the
     * pipeline — and the clean-member finalization before it — performed has to happen here or not at
     * all for that member.
     *
     * <p>The effective compiler identity describes compiler selection, not whether all required tools
     * are currently usable. The status is already resolved and cached by the time we get here, so
     * keeping the explicit readiness check costs one comparison and prevents a clean member from
     * bypassing toolchain validation.
     */
    private static void requireUsable(WorkspaceMember member, JdkStatus status) {
        if (status.ok()) {
            return;
        }
        throw BuildException.actionable(
                "JDK check failed.",
                "Workspace member `"
                        + member.path()
                        + "`: "
                        + String.join(" ", status.problems()));
    }

    private record Toolchain(
            JdkChecker checker,
            Object cacheKey,
            String configuredIdentity) {
    }
}
