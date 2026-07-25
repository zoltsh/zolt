package sh.zolt.workspace.service;

public record WorkspaceProjectEdge(
        String from,
        String to,
        String scope,
        String coordinate,
        boolean exported,
        boolean optional) {
    public WorkspaceProjectEdge(
            String from,
            String to,
            String scope,
            String coordinate,
            boolean exported) {
        this(from, to, scope, coordinate, exported, false);
    }

    public WorkspaceProjectEdge(
            String from,
            String to,
            String scope,
            String coordinate) {
        this(from, to, scope, coordinate, false, false);
    }
}
