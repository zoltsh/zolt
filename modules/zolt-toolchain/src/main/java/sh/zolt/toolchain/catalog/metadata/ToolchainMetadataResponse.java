package sh.zolt.toolchain.catalog.metadata;

public record ToolchainMetadataResponse(
        int statusCode,
        String body) {
    public ToolchainMetadataResponse {
        body = body == null ? "" : body;
    }

    public boolean successful() {
        return statusCode >= 200 && statusCode < 300;
    }
}
