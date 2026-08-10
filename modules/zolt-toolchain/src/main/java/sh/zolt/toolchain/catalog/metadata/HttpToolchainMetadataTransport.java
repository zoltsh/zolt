package sh.zolt.toolchain.catalog.metadata;

import sh.zolt.error.ActionableException;
import sh.zolt.net.NetworkTransport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

public final class HttpToolchainMetadataTransport implements ToolchainMetadataTransport {
    private static final int MAX_RESPONSE_CHARACTERS = 8 * 1024 * 1024;
    private final HttpClient httpClient;

    public HttpToolchainMetadataTransport(NetworkTransport transport) {
        this((transport == null ? NetworkTransport.fromEnvironment() : transport).newHttpClient());
    }

    HttpToolchainMetadataTransport(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public ToolchainMetadataResponse get(URI uri, Map<String, String> headers) {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new ActionableException(
                    "Java toolchain metadata URI must use HTTPS.",
                    "Use an official HTTPS metadata endpoint.");
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(30))
                    .GET();
            headers.forEach(request::header);
            HttpResponse<String> response = httpClient.send(
                    request.build(),
                    HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
            if (response.body().length() > MAX_RESPONSE_CHARACTERS) {
                throw new ActionableException(
                        "Java toolchain metadata response from " + uri.getHost() + " was too large.",
                        "Retry later or check the configured network proxy.");
            }
            return new ToolchainMetadataResponse(response.statusCode(), response.body());
        } catch (IOException exception) {
            throw new ActionableException(
                    "Could not query Java toolchain metadata from " + uri.getHost() + ".",
                    "Check the network connection, HTTPS_PROXY, and ZOLT_CA_BUNDLE, then retry `zolt toolchain sync`.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActionableException(
                    "Java toolchain metadata request was interrupted.",
                    "Retry `zolt toolchain sync`.");
        }
    }
}
