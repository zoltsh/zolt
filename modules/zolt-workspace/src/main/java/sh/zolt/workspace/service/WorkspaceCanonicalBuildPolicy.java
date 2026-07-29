package sh.zolt.workspace.service;

import sh.zolt.classpath.ClasspathSet;

final class WorkspaceCanonicalBuildPolicy {
    private WorkspaceCanonicalBuildPolicy() {
    }

    static boolean hasGeneratedInputs(
            WorkspaceMember member,
            ClasspathSet classpaths) {
        return !classpaths.processor().entries().isEmpty()
                || !member.config().build().generatedMainSources().isEmpty();
    }

    static boolean hasFrameworkOutputs(WorkspaceMember member) {
        return member.config().frameworkSettings().springBoot().nativeEnabled();
    }
}
