package sh.zolt.maven.repository;

import static sh.zolt.maven.repository.RepositoryHttpRequests.diagnosticUri;
import static sh.zolt.maven.repository.RepositoryHttpRequests.uploadRequest;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Optional;

/** Repository upload transport and retry policy. */
final class RepositoryArtifactUploader {
    private final HttpClient httpClient;
    private final RepositoryHttpPolicy httpPolicy;

    RepositoryArtifactUploader(HttpClient httpClient, RepositoryHttpPolicy httpPolicy) {
        this.httpClient = httpClient;
        this.httpPolicy = httpPolicy;
    }

    void upload(
            URI repositoryBaseUri,
            String subject,
            String path,
            Path source,
            Optional<RepositoryAuthentication> authentication) {
        URI artifactUri = RepositoryArtifactUri.resolve(repositoryBaseUri, path);
        HttpRequest.BodyPublisher bodyPublisher;
        try {
            bodyPublisher = HttpRequest.BodyPublishers.ofFile(source);
        } catch (IOException exception) {
            throw new RepositoryClientException(
                    "Could not read upload source for "
                            + subject
                            + " at "
                            + source
                            + ". Check that the file exists and is readable.",
                    exception);
        }
        HttpRequest request = uploadRequest(artifactUri, bodyPublisher, authentication, httpPolicy);

        IOException lastIoException = null;
        for (int attempt = 1; attempt <= httpPolicy.maxAttempts(); attempt++) {
            HttpResponse<Void> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (IOException exception) {
                lastIoException = exception;
                if (!hasAttemptsRemaining(attempt)) {
                    throw RepositoryTransferErrors.upload(subject, artifactUri, attempt, exception);
                }
                sleepBeforeRetry(subject, artifactUri, attempt);
                continue;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RepositoryClientException(
                        "Upload interrupted while publishing "
                                + subject
                                + " to "
                                + diagnosticUri(artifactUri)
                                + ". Try again.",
                        exception);
            }

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return;
            }
            if (!transientStatus(response.statusCode()) || !hasAttemptsRemaining(attempt)) {
                throw RepositoryTransferErrors.status("uploading", subject, artifactUri, response.statusCode(), attempt);
            }
            sleepBeforeRetry(subject, artifactUri, attempt);
        }

        throw RepositoryTransferErrors.upload(subject, artifactUri, httpPolicy.maxAttempts(), lastIoException);
    }

    private boolean hasAttemptsRemaining(int attempt) {
        return attempt < httpPolicy.maxAttempts();
    }

    private void sleepBeforeRetry(String subject, URI artifactUri, int attempt) {
        if (httpPolicy.retryBackoff().isZero()) {
            return;
        }
        try {
            Thread.sleep(httpPolicy.retryBackoff().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RepositoryClientException(
                    "Repository request interrupted while retrying uploading "
                            + subject
                            + " from "
                            + diagnosticUri(artifactUri)
                            + " after attempt "
                            + attempt
                            + ". Try again.",
                    exception);
        }
    }

    private static boolean transientStatus(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode <= 599);
    }
}
