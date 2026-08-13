package sh.zolt.maven.repository;

import sh.zolt.maven.ArtifactDescriptor;
import java.io.IOException;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Streams one response into a bounded, fsynced temporary file while computing SHA-256. */
final class BoundedFileBodyHandler implements HttpResponse.BodyHandler<DownloadedRepositoryBody> {
    private static final long UNKNOWN_LENGTH = -1L;

    private final ArtifactDescriptor descriptor;
    private final RepositoryDownloadListener listener;
    private final Path downloadDirectory;
    private final long maximumBytes;
    private final String responseKind;

    BoundedFileBodyHandler(
            ArtifactDescriptor descriptor,
            RepositoryDownloadListener listener,
            Path downloadDirectory,
            long maximumBytes,
            String responseKind) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.listener = listener == null ? RepositoryDownloadListener.NOOP : listener;
        this.downloadDirectory = Objects.requireNonNull(downloadDirectory, "downloadDirectory");
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("Repository response limit must be positive.");
        }
        this.maximumBytes = maximumBytes;
        this.responseKind = Objects.requireNonNull(responseKind, "responseKind");
    }

    @Override
    public HttpResponse.BodySubscriber<DownloadedRepositoryBody> apply(
            HttpResponse.ResponseInfo responseInfo) {
        long declaredLength = contentLength(responseInfo.headers());
        if (declaredLength > maximumBytes) {
            return new RejectedBodySubscriber(oversize(declaredLength, "declared"));
        }
        Path temporary = null;
        try {
            Files.createDirectories(downloadDirectory);
            temporary = Files.createTempFile(downloadDirectory, "repository-", ".download");
            long total = successful(responseInfo.statusCode()) ? declaredLength : UNKNOWN_LENGTH;
            return new BoundedFileBodySubscriber(
                    descriptor,
                    listener,
                    total,
                    temporary,
                    maximumBytes,
                    responseKind);
        } catch (IOException | RuntimeException exception) {
            deleteIfPresent(temporary);
            return new RejectedBodySubscriber(exception);
        }
    }

    private IOException oversize(long bytes, String measurement) {
        return new RepositoryResponseTooLargeException(
                "Repository " + responseKind + " response is too large: " + measurement + " "
                        + bytes + " bytes exceeds the " + maximumBytes + " byte limit.");
    }

    private static boolean successful(int statusCode) {
        return statusCode >= 200 && statusCode < 300;
    }

    private static long contentLength(HttpHeaders headers) {
        return headers.firstValue("Content-Length")
                .flatMap(BoundedFileBodyHandler::parseNonNegativeLong)
                .orElse(UNKNOWN_LENGTH);
    }

    private static Optional<Long> parseNonNegativeLong(String value) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private static void deleteIfPresent(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static final class RejectedBodySubscriber
            implements HttpResponse.BodySubscriber<DownloadedRepositoryBody> {
        private final CompletableFuture<DownloadedRepositoryBody> result = new CompletableFuture<>();

        private RejectedBodySubscriber(Throwable failure) {
            result.completeExceptionally(failure);
        }

        @Override
        public CompletionStage<DownloadedRepositoryBody> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            subscription.cancel();
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
        }

        @Override
        public void onError(Throwable throwable) {
        }

        @Override
        public void onComplete() {
        }
    }

    private static final class BoundedFileBodySubscriber
            implements HttpResponse.BodySubscriber<DownloadedRepositoryBody> {
        private final ArtifactDescriptor descriptor;
        private final RepositoryDownloadListener listener;
        private final long total;
        private final Path temporary;
        private final long maximumBytes;
        private final String responseKind;
        private final CompletableFuture<DownloadedRepositoryBody> result = new CompletableFuture<>();
        private final MessageDigest digest;
        private final FileChannel output;
        private Flow.Subscription subscription;
        private long received;
        private boolean finished;

        private BoundedFileBodySubscriber(
                ArtifactDescriptor descriptor,
                RepositoryDownloadListener listener,
                long total,
                Path temporary,
                long maximumBytes,
                String responseKind) throws IOException {
            this.descriptor = descriptor;
            this.listener = listener;
            this.total = total;
            this.temporary = temporary;
            this.maximumBytes = maximumBytes;
            this.responseKind = responseKind;
            this.digest = sha256();
            this.output = FileChannel.open(temporary, StandardOpenOption.WRITE);
        }

        @Override
        public CompletionStage<DownloadedRepositoryBody> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription value) {
            this.subscription = value;
            value.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (finished) {
                return;
            }
            try {
                for (ByteBuffer item : items) {
                    int length = item.remaining();
                    if (received > maximumBytes - length) {
                        fail(new RepositoryResponseTooLargeException(
                                "Repository " + responseKind + " response is too large: received more than "
                                        + maximumBytes + " bytes."));
                        subscription.cancel();
                        return;
                    }
                    ByteBuffer digestBytes = item.asReadOnlyBuffer();
                    digest.update(digestBytes);
                    while (item.hasRemaining()) {
                        output.write(item);
                    }
                    received += length;
                    if (total != UNKNOWN_LENGTH) {
                        listener.onBytes(descriptor, received, total);
                    }
                }
                subscription.request(1);
            } catch (IOException | RuntimeException exception) {
                subscription.cancel();
                fail(exception);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            fail(throwable);
        }

        @Override
        public void onComplete() {
            if (finished) {
                return;
            }
            finished = true;
            try {
                output.force(true);
                output.close();
                result.complete(new DownloadedRepositoryBody(
                        temporary,
                        received,
                        HexFormat.of().formatHex(digest.digest())));
            } catch (IOException exception) {
                cleanup();
                result.completeExceptionally(exception);
            }
        }

        private void fail(Throwable throwable) {
            if (finished) {
                return;
            }
            finished = true;
            cleanup();
            result.completeExceptionally(throwable);
        }

        private void cleanup() {
            try {
                output.close();
            } catch (IOException ignored) {
            }
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
            }
        }

        private static MessageDigest sha256() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException exception) {
                throw new IllegalStateException("SHA-256 is unavailable.", exception);
            }
        }
    }
}
