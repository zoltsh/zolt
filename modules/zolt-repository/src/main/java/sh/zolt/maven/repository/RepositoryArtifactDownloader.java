package sh.zolt.maven.repository;

import static sh.zolt.maven.repository.RepositoryHttpRequests.diagnosticUri;
import static sh.zolt.maven.repository.RepositoryHttpRequests.fetchRequest;

import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Optional;

/** Bounded streaming transport and retry policy for one repository response. */
final class RepositoryArtifactDownloader {
    private final HttpClient httpClient;
    private final RepositoryHttpPolicy httpPolicy;

    RepositoryArtifactDownloader(HttpClient httpClient, RepositoryHttpPolicy httpPolicy) {
        this.httpClient = httpClient;
        this.httpPolicy = httpPolicy;
    }

    RepositoryArtifact fetch(
            URI repositoryBaseUri,
            ArtifactDescriptor descriptor,
            String path,
            Optional<RepositoryAuthentication> authentication,
            RepositoryDownloadListener downloadListener,
            Path downloadDirectory,
            long maximumBytes,
            String responseKind) {
        Coordinate coordinate = descriptor.coordinate();
        URI artifactUri = RepositoryArtifactUri.resolve(repositoryBaseUri, path);
        HttpRequest request = fetchRequest(artifactUri, authentication, httpPolicy);

        IOException lastIoException = null;
        for (int attempt = 1; attempt <= httpPolicy.maxAttempts(); attempt++) {
            HttpResponse<DownloadedRepositoryBody> response;
            try {
                response = httpClient.send(
                        request,
                        new BoundedFileBodyHandler(
                                descriptor,
                                downloadListener,
                                downloadDirectory,
                                maximumBytes,
                                responseKind));
            } catch (IOException exception) {
                if (responseTooLarge(exception)) {
                    throw new RepositoryClientException(
                            "Could not download " + coordinate + " from " + diagnosticUri(artifactUri)
                                    + ": " + rootMessage(exception),
                            exception);
                }
                lastIoException = exception;
                if (!hasAttemptsRemaining(attempt)) {
                    throw RepositoryTransferErrors.download(coordinate.toString(), artifactUri, attempt, exception);
                }
                sleepBeforeRetry(coordinate.toString(), artifactUri, attempt);
                continue;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RepositoryClientException(
                        "Download interrupted while fetching "
                                + coordinate
                                + " from "
                                + diagnosticUri(artifactUri)
                                + ". Try again.",
                        exception);
            }

            DownloadedRepositoryBody body = response.body();
            if (response.statusCode() == 404) {
                body.close();
                throw new RepositoryMissingArtifactException(
                        "Could not find " + coordinate + " at " + diagnosticUri(artifactUri) + ".");
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new RepositoryArtifact(
                        coordinate,
                        path,
                        artifactUri,
                        "",
                        body.path(),
                        body.size(),
                        body.sha256());
            }
            body.close();
            if (!transientStatus(response.statusCode()) || !hasAttemptsRemaining(attempt)) {
                throw RepositoryTransferErrors.status(coordinate.toString(), artifactUri, response.statusCode(), attempt);
            }
            sleepBeforeRetry(coordinate.toString(), artifactUri, attempt);
        }

        throw RepositoryTransferErrors.download(coordinate.toString(), artifactUri, httpPolicy.maxAttempts(), lastIoException);
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
                    "Repository request interrupted while retrying fetching "
                            + subject
                            + " from "
                            + diagnosticUri(artifactUri)
                            + " after attempt "
                            + attempt
                            + ". Try again.",
                    exception);
        }
    }

    static boolean responseTooLarge(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof RepositoryResponseTooLargeException) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private static boolean transientStatus(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode <= 599);
    }
}
