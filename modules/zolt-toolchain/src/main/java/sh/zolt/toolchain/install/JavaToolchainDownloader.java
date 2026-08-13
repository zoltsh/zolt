package sh.zolt.toolchain.install;

import sh.zolt.error.ActionableException;
import sh.zolt.net.NetworkTransport;
import sh.zolt.toolchain.catalog.JavaToolchainArtifact;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.UnaryOperator;

public final class JavaToolchainDownloader {
    private static final long DEFAULT_MAXIMUM_ARCHIVE_BYTES = 1024L * 1024L * 1024L;

    private final HttpClient httpClient;
    private final UnaryOperator<URI> uriRewriter;
    private final long maximumArchiveBytes;

    public JavaToolchainDownloader() {
        this(NetworkTransport.fromEnvironment(), ToolchainDownloadMirror.fromEnvironment());
    }

    public JavaToolchainDownloader(NetworkTransport transport, ToolchainDownloadMirror mirror) {
        this(transport.httpClientBuilder().build(), mirror::rewrite);
    }

    JavaToolchainDownloader(HttpClient httpClient) {
        this(httpClient, UnaryOperator.identity(), DEFAULT_MAXIMUM_ARCHIVE_BYTES);
    }

    JavaToolchainDownloader(HttpClient httpClient, UnaryOperator<URI> uriRewriter) {
        this(httpClient, uriRewriter, DEFAULT_MAXIMUM_ARCHIVE_BYTES);
    }

    JavaToolchainDownloader(
            HttpClient httpClient,
            UnaryOperator<URI> uriRewriter,
            long maximumArchiveBytes) {
        this.httpClient = httpClient;
        this.uriRewriter = uriRewriter;
        if (maximumArchiveBytes < 1) {
            throw new IllegalArgumentException("Java toolchain archive limit must be positive.");
        }
        this.maximumArchiveBytes = maximumArchiveBytes;
    }

    public Path download(JavaToolchainArtifact artifact, Path destination) {
        URI uri = Objects.requireNonNull(
                uriRewriter.apply(artifact.uri()),
                "Java toolchain mirror must return a URI.");
        Path temporary = null;
        try {
            Path directory = destination.toAbsolutePath().normalize().getParent();
            if (directory == null) {
                throw new IOException("download destination has no parent directory");
            }
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, destination.getFileName().toString(), ".download");
            if ("file".equals(uri.getScheme())) {
                Path source = Path.of(uri);
                requireWithinLimit(Files.size(source), uri, "declared");
                try (InputStream input = Files.newInputStream(source)) {
                    stream(input, temporary, uri);
                }
                publish(temporary, destination);
                return destination;
            }
            if (!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) {
                throw new ActionableException(
                        "Unsupported Java toolchain artifact URI `" + uri + "`.",
                        "Use an HTTPS artifact URI, or configure an explicit HTTP/file toolchain mirror.");
            }
            HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
            HttpResponse<InputStream> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new ActionableException(
                        "Could not download Java toolchain artifact from " + uri + ".",
                        "The server returned HTTP " + response.statusCode() + "; check the catalog URL and try again.");
            }
            response.headers().firstValueAsLong("Content-Length")
                    .ifPresent(length -> requireWithinLimit(length, uri, "declared"));
            try (InputStream input = response.body()) {
                stream(input, temporary, uri);
            }
            publish(temporary, destination);
            return destination;
        } catch (IOException exception) {
            throw new ActionableException(
                    "Could not download Java toolchain artifact from " + uri + ".",
                    "Check your network connection; behind a firewall set HTTPS_PROXY, ZOLT_CA_BUNDLE, or an "
                            + "internal mirror (ZOLT_TOOLCHAIN_MIRROR or [network].toolchainMirror), then retry "
                            + "`zolt toolchain sync`.");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ActionableException(
                    "Java toolchain download was interrupted.",
                    "Retry `zolt toolchain sync`.");
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void stream(InputStream input, Path temporary, URI uri) throws IOException {
        long received = 0L;
        byte[] buffer = new byte[64 * 1024];
        try (var output = Files.newOutputStream(temporary, StandardOpenOption.TRUNCATE_EXISTING)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (received > maximumArchiveBytes - read) {
                    throw tooLarge(uri, "received more than");
                }
                output.write(buffer, 0, read);
                received += read;
            }
        }
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void requireWithinLimit(long size, URI uri, String measurement) {
        if (size > maximumArchiveBytes) {
            throw tooLarge(uri, measurement + " " + size + " bytes exceeds");
        }
    }

    private ActionableException tooLarge(URI uri, String detail) {
        return new ActionableException(
                "Java toolchain archive from " + uri + " is too large: " + detail + " the "
                        + maximumArchiveBytes + " byte limit.",
                "Use a trusted JDK archive within the managed toolchain size limit.");
    }

    private static void publish(Path temporary, Path destination) throws IOException {
        Files.move(
                temporary,
                destination,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }
}
