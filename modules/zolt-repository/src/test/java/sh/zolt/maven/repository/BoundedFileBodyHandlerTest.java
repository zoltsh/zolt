package sh.zolt.maven.repository;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BoundedFileBodyHandlerTest {
    private static final Coordinate APP = new Coordinate("com.example", "app", Optional.of("1.0.0"));
    private static final ArtifactDescriptor DESCRIPTOR = ArtifactDescriptor.jar(APP);

    @TempDir
    private Path tempDir;

    @Test
    void knownContentLengthStreamsExactBytesWithDigestAndProgress() throws Exception {
        List<ByteEvent> events = new ArrayList<>();
        HttpResponse.BodySubscriber<DownloadedRepositoryBody> subscriber = handler(
                5,
                (descriptor, received, total) -> events.add(new ByteEvent(descriptor, received, total)))
                .apply(responseInfo(200, Map.of("Content-Length", List.of("5"))));
        RecordingSubscription subscription = new RecordingSubscription();

        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {4, 5})));
        subscriber.onComplete();

        DownloadedRepositoryBody body = subscriber.getBody().toCompletableFuture().join();
        assertEquals(3L, subscription.requested());
        assertArrayEquals(new byte[] {1, 2, 3, 4, 5}, Files.readAllBytes(body.path()));
        assertEquals(5L, body.size());
        assertEquals("74f81fe167d99b4cb41d6d0ccda82278caee9f3e2f25d5e5a3936ff3dcec60d0", body.sha256());
        assertEquals(
                List.of(
                        new ByteEvent(DESCRIPTOR, 2L, 5L),
                        new ByteEvent(DESCRIPTOR, 3L, 5L),
                        new ByteEvent(DESCRIPTOR, 5L, 5L)),
                events);
        body.close();
    }

    @Test
    void unknownContentLengthIsCountedAndBoundedWhileStreaming() throws Exception {
        List<ByteEvent> events = new ArrayList<>();
        HttpResponse.BodySubscriber<DownloadedRepositoryBody> subscriber = handler(
                4,
                (descriptor, received, total) -> events.add(new ByteEvent(descriptor, received, total)))
                .apply(responseInfo(200, Map.of()));

        subscriber.onSubscribe(new RecordingSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {9, 8}), ByteBuffer.wrap(new byte[] {7, 6})));
        subscriber.onComplete();

        DownloadedRepositoryBody body = subscriber.getBody().toCompletableFuture().join();
        assertArrayEquals(new byte[] {9, 8, 7, 6}, Files.readAllBytes(body.path()));
        assertEquals(List.of(), events);
        body.close();
    }

    @Test
    void nonSuccessResponseDoesNotEmitProgress() {
        List<ByteEvent> events = new ArrayList<>();
        HttpResponse.BodySubscriber<DownloadedRepositoryBody> subscriber = handler(
                16,
                (descriptor, received, total) -> events.add(new ByteEvent(descriptor, received, total)))
                .apply(responseInfo(404, Map.of("Content-Length", List.of("7"))));

        subscriber.onSubscribe(new RecordingSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3, 4, 5, 6, 7})));
        subscriber.onComplete();

        DownloadedRepositoryBody body = subscriber.getBody().toCompletableFuture().join();
        assertEquals(List.of(), events);
        body.close();
    }

    @Test
    void declaredOversizeCancelsBeforeCreatingTemporaryFile() throws Exception {
        HttpResponse.BodySubscriber<DownloadedRepositoryBody> subscriber = handler(4, null)
                .apply(responseInfo(200, Map.of("Content-Length", List.of("5"))));
        RecordingSubscription subscription = new RecordingSubscription();

        subscriber.onSubscribe(subscription);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        assertTrue(exception.getCause().getMessage().contains("declared 5 bytes exceeds the 4 byte limit"));
        assertTrue(subscription.cancelled());
        assertDirectoryEmpty();
    }

    @Test
    void observedOversizeCancelsAndDeletesPartialFile() throws Exception {
        HttpResponse.BodySubscriber<DownloadedRepositoryBody> subscriber = handler(4, null)
                .apply(responseInfo(200, Map.of()));
        RecordingSubscription subscription = new RecordingSubscription();
        subscriber.onSubscribe(subscription);

        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {4, 5})));

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        assertTrue(exception.getCause().getMessage().contains("received more than 4 bytes"));
        assertTrue(subscription.cancelled());
        assertDirectoryEmpty();
    }

    @Test
    void transportFailureDeletesPartialFile() throws Exception {
        HttpResponse.BodySubscriber<DownloadedRepositoryBody> subscriber = handler(10, null)
                .apply(responseInfo(200, Map.of()));
        RuntimeException failure = new RuntimeException("socket closed");
        subscriber.onSubscribe(new RecordingSubscription());
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2, 3})));

        subscriber.onError(failure);

        CompletionException exception = assertThrows(
                CompletionException.class,
                () -> subscriber.getBody().toCompletableFuture().join());
        assertEquals(failure, exception.getCause());
        assertDirectoryEmpty();
    }

    private BoundedFileBodyHandler handler(long maximumBytes, RepositoryDownloadListener listener) {
        return new BoundedFileBodyHandler(
                DESCRIPTOR,
                listener,
                tempDir.resolve("downloads"),
                maximumBytes,
                "artifact");
    }

    private void assertDirectoryEmpty() throws Exception {
        Path downloads = tempDir.resolve("downloads");
        if (!Files.exists(downloads)) {
            return;
        }
        try (var paths = Files.list(downloads)) {
            assertEquals(List.of(), paths.toList());
        }
    }

    private static HttpResponse.ResponseInfo responseInfo(
            int statusCode,
            Map<String, List<String>> headers) {
        return new TestResponseInfo(statusCode, HttpHeaders.of(headers, (name, value) -> true));
    }

    private record TestResponseInfo(int statusCode, HttpHeaders headers)
            implements HttpResponse.ResponseInfo {
        @Override
        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class RecordingSubscription implements Flow.Subscription {
        private long requested;
        private boolean cancelled;

        @Override
        public void request(long n) {
            requested += n;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        private long requested() {
            return requested;
        }

        private boolean cancelled() {
            return cancelled;
        }
    }

    private record ByteEvent(ArtifactDescriptor descriptor, long received, long total) {
    }
}
