package sh.zolt.workspace.service;

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
        return toolchain.configuredIdentity()
                + "|javaHome="
                + path(status.javaHome())
                + "|java="
                + path(status.java())
                + "|javac="
                + path(status.javac())
                + "|jar="
                + path(status.jar())
                + "|runtime="
                + status.version().orElse("missing")
                + "|required="
                + status.requiredVersion();
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

    private static String path(Optional<Path> path) {
        return path.map(value -> value.toAbsolutePath().normalize().toString())
                .orElse("missing");
    }

    private record Toolchain(
            JdkChecker checker,
            Object cacheKey,
            String configuredIdentity) {
    }
}
