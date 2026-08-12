package sh.zolt.maven.repository;

import static sh.zolt.maven.repository.RepositoryHttpRequests.diagnosticUri;
import static sh.zolt.maven.repository.RepositoryHttpRequests.fetchRequest;
import static sh.zolt.maven.repository.RepositoryHttpRequests.uploadRequest;

import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.net.NetworkTransport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Optional;

public final class MavenRepositoryClient {
    private final HttpClient httpClient;
    private final MavenRepositoryPathBuilder pathBuilder;
    private final RepositoryHttpPolicy httpPolicy;

    public MavenRepositoryClient() {
        this(NetworkTransport.fromEnvironment());
    }

    public MavenRepositoryClient(NetworkTransport transport) {
        this(transport.newHttpClient(), new MavenRepositoryPathBuilder(), RepositoryHttpPolicy.defaults());
    }

    MavenRepositoryClient(HttpClient httpClient, MavenRepositoryPathBuilder pathBuilder) {
        this(httpClient, pathBuilder, RepositoryHttpPolicy.defaults());
    }

    MavenRepositoryClient(
            HttpClient httpClient,
            MavenRepositoryPathBuilder pathBuilder,
            RepositoryHttpPolicy httpPolicy) {
        this.httpClient = httpClient;
        this.pathBuilder = pathBuilder;
        this.httpPolicy = httpPolicy;
    }

    public RepositoryArtifact fetchPom(URI repositoryBaseUri, Coordinate coordinate) {
        return fetchPom(repositoryBaseUri, coordinate, RepositoryAuthentication.none());
    }

    public RepositoryArtifact fetchPom(
            URI repositoryBaseUri,
            Coordinate coordinate,
            Optional<RepositoryAuthentication> authentication) {
        return fetchPom(repositoryBaseUri, coordinate, authentication, RepositoryDownloadListener.NOOP);
    }

    public RepositoryArtifact fetchPom(
            URI repositoryBaseUri,
            Coordinate coordinate,
            Optional<RepositoryAuthentication> authentication,
            RepositoryDownloadListener downloadListener) {
        return fetch(
                repositoryBaseUri,
                new ArtifactDescriptor(coordinate, Optional.empty(), "pom"),
                pathBuilder.pomPath(coordinate),
                authentication,
                downloadListener);
    }

    public RepositoryArtifact fetchJar(URI repositoryBaseUri, Coordinate coordinate) {
        return fetchJar(repositoryBaseUri, coordinate, RepositoryAuthentication.none());
    }

    public RepositoryArtifact fetchJar(
            URI repositoryBaseUri,
            Coordinate coordinate,
            Optional<RepositoryAuthentication> authentication) {
        return fetchJar(repositoryBaseUri, coordinate, authentication, RepositoryDownloadListener.NOOP);
    }

    public RepositoryArtifact fetchJar(
            URI repositoryBaseUri,
            Coordinate coordinate,
            Optional<RepositoryAuthentication> authentication,
            RepositoryDownloadListener downloadListener) {
        return fetchArtifact(repositoryBaseUri, ArtifactDescriptor.jar(coordinate), authentication, downloadListener);
    }

    public RepositoryArtifact fetchArtifact(URI repositoryBaseUri, ArtifactDescriptor descriptor) {
        return fetchArtifact(repositoryBaseUri, descriptor, RepositoryAuthentication.none());
    }

    public RepositoryArtifact fetchArtifact(
            URI repositoryBaseUri,
            ArtifactDescriptor descriptor,
            Optional<RepositoryAuthentication> authentication) {
        return fetchArtifact(repositoryBaseUri, descriptor, authentication, RepositoryDownloadListener.NOOP);
    }

    public RepositoryArtifact fetchArtifact(
            URI repositoryBaseUri,
            ArtifactDescriptor descriptor,
            Optional<RepositoryAuthentication> authentication,
            RepositoryDownloadListener downloadListener) {
        return fetch(
                repositoryBaseUri,
                descriptor,
                pathBuilder.artifactPath(descriptor),
                authentication,
                downloadListener);
    }

    /**
     * Fetches a coordinate's {@code maven-metadata.xml} version listing. Advisory-only: used by
     * version discovery, never by resolution. Returns empty on 404 (the artifact is not hosted by
     * this repository); other transient failures throw per the existing retry policy.
     */
    public Optional<byte[]> fetchMetadata(
            URI repositoryBaseUri,
            String groupId,
            String artifactId,
            Optional<RepositoryAuthentication> authentication) {
        Coordinate coordinate = new Coordinate(groupId, artifactId, Optional.empty());
        ArtifactDescriptor descriptor = new ArtifactDescriptor(coordinate, Optional.empty(), "xml");
        try {
            RepositoryArtifact artifact = fetch(
                    repositoryBaseUri,
                    descriptor,
                    pathBuilder.metadataPath(groupId, artifactId),
                    authentication,
                    RepositoryDownloadListener.NOOP);
            return Optional.of(artifact.bytes());
        } catch (RepositoryMissingArtifactException exception) {
            return Optional.empty();
        }
    }

    /**
     * Fetches the bytes stored at an explicit repository-relative {@code repositoryPath}, or empty when
     * the repository returns 404. Symmetric with {@link #uploadFile}: it makes a re-PUT idempotent — an
     * already-landed artifact, checksum, or signature is confirmed present (and its bytes compared)
     * before re-uploading, so a resumed publish never re-PUTs a path an immutable release repository
     * would reject. Transient failures throw per the existing retry policy.
     */
    public Optional<byte[]> fetchFile(
            URI repositoryBaseUri,
            String repositoryPath,
            Optional<RepositoryAuthentication> authentication) {
        ArtifactDescriptor descriptor =
                new ArtifactDescriptor(new Coordinate("repository", "file", Optional.empty()), Optional.empty(), "bin");
        try {
            RepositoryArtifact artifact = fetch(
                    repositoryBaseUri, descriptor, repositoryPath, authentication, RepositoryDownloadListener.NOOP);
            return Optional.of(artifact.bytes());
        } catch (RepositoryMissingArtifactException exception) {
            return Optional.empty();
        }
    }

    public void uploadPom(URI repositoryBaseUri, Coordinate coordinate, Path source) {
        uploadPom(repositoryBaseUri, coordinate, source, RepositoryAuthentication.none());
    }

    public void uploadPom(
            URI repositoryBaseUri,
            Coordinate coordinate,
            Path source,
            Optional<RepositoryAuthentication> authentication) {
        upload(repositoryBaseUri, coordinate, pathBuilder.pomPath(coordinate), source, authentication);
    }

    public void uploadArtifact(URI repositoryBaseUri, ArtifactDescriptor descriptor, Path source) {
        uploadArtifact(repositoryBaseUri, descriptor, source, RepositoryAuthentication.none());
    }

    public void uploadArtifact(
            URI repositoryBaseUri,
            ArtifactDescriptor descriptor,
            Path source,
            Optional<RepositoryAuthentication> authentication) {
        upload(repositoryBaseUri, descriptor.coordinate(), pathBuilder.artifactPath(descriptor), source, authentication);
    }

    /**
     * Uploads a file to an explicit repository-relative path. Used for auxiliary files such as
     * checksum sidecars ({@code .sha1}/{@code .md5}/{@code .sha256}) and detached signatures
     * ({@code .asc}) whose paths are derived by suffixing an already-computed artifact path.
     */
    public void uploadFile(
            URI repositoryBaseUri,
            String repositoryPath,
            Path source,
            Optional<RepositoryAuthentication> authentication) {
        send(repositoryBaseUri, repositoryPath, repositoryPath, source, authentication);
    }

    private RepositoryArtifact fetch(
            URI repositoryBaseUri,
            ArtifactDescriptor descriptor,
            String path,
            Optional<RepositoryAuthentication> authentication,
            RepositoryDownloadListener downloadListener) {
        Coordinate coordinate = descriptor.coordinate();
        URI artifactUri = RepositoryArtifactUri.resolve(repositoryBaseUri, path);
        HttpRequest request = fetchRequest(artifactUri, authentication, httpPolicy);

        IOException lastIoException = null;
        for (int attempt = 1; attempt <= httpPolicy.maxAttempts(); attempt++) {
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request, new CountingByteArrayBodyHandler(descriptor, downloadListener));
            } catch (IOException exception) {
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

            if (response.statusCode() == 404) {
                throw new RepositoryMissingArtifactException(
                        "Could not find " + coordinate + " at " + diagnosticUri(artifactUri) + ".");
            }
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new RepositoryArtifact(coordinate, path, artifactUri, response.body());
            }
            if (!transientStatus(response.statusCode()) || !hasAttemptsRemaining(attempt)) {
                throw RepositoryTransferErrors.status(coordinate.toString(), artifactUri, response.statusCode(), attempt);
            }
            sleepBeforeRetry(coordinate.toString(), artifactUri, attempt);
        }

        throw RepositoryTransferErrors.download(coordinate.toString(), artifactUri, httpPolicy.maxAttempts(), lastIoException);
    }

    private void upload(
            URI repositoryBaseUri,
            Coordinate coordinate,
            String path,
            Path source,
            Optional<RepositoryAuthentication> authentication) {
        send(repositoryBaseUri, coordinate.toString(), path, source, authentication);
    }

    private void send(
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
            HttpResponse<byte[]> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (IOException exception) {
                lastIoException = exception;
                if (!hasAttemptsRemaining(attempt)) {
                    throw RepositoryTransferErrors.upload(subject, artifactUri, attempt, exception);
                }
                sleepBeforeRetry("uploading", subject, artifactUri, attempt);
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
            sleepBeforeRetry("uploading", subject, artifactUri, attempt);
        }

        throw RepositoryTransferErrors.upload(subject, artifactUri, httpPolicy.maxAttempts(), lastIoException);
    }

    private boolean hasAttemptsRemaining(int attempt) {
        return attempt < httpPolicy.maxAttempts();
    }

    private static boolean transientStatus(int statusCode) {
        return statusCode == 429 || (statusCode >= 500 && statusCode <= 599);
    }

    private void sleepBeforeRetry(String subject, URI artifactUri, int attempt) {
        sleepBeforeRetry("fetching", subject, artifactUri, attempt);
    }

    private void sleepBeforeRetry(String operation, String subject, URI artifactUri, int attempt) {
        if (httpPolicy.retryBackoff().isZero()) {
            return;
        }
        try {
            Thread.sleep(httpPolicy.retryBackoff().toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RepositoryClientException(
                    "Repository request interrupted while retrying "
                            + operation
                            + " "
                            + subject
                            + " from "
                            + diagnosticUri(artifactUri)
                            + " after attempt "
                            + attempt
                            + ". Try again.",
                    exception);
        }
    }

}
