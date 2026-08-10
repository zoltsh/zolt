package sh.zolt.toolchain.catalog.metadata;

import java.net.URI;
import java.util.Map;

public interface ToolchainMetadataTransport {
    ToolchainMetadataResponse get(URI uri, Map<String, String> headers);
}
