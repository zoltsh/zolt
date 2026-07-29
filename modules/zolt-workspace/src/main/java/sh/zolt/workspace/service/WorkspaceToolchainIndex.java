package sh.zolt.workspace.service;

import sh.zolt.doctor.JdkChecker;
import sh.zolt.doctor.JdkStatus;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

final class WorkspaceToolchainIndex {
    private final Map<Object, Map<String, JdkStatus>> statuses =
            new HashMap<>();
    private int resolutions;
    private int hits;

    JdkChecker checker(
            WorkspaceJdkCheckerResolver resolver,
            Workspace workspace,
            WorkspaceMember member) {
        JdkChecker delegate = resolver.forMember(workspace, member);
        Object cacheKey = resolver.cacheKey(
                workspace,
                member,
                delegate);
        return requiredVersion -> detect(
                cacheKey,
                delegate,
                requiredVersion);
    }

    synchronized int resolutions() {
        return resolutions;
    }

    synchronized int hits() {
        return hits;
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
}
