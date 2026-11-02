package sh.zolt.workspace.service;

import sh.zolt.build.SourceCompileException;

/** A non-Java source compiler failure attributed to the workspace member that was executing. */
public final class WorkspaceMemberCompileException extends SourceCompileException {
    public WorkspaceMemberCompileException(String message, Throwable cause) {
        super(message, cause);
    }
}
