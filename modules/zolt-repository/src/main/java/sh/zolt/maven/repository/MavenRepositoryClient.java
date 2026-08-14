package sh.zolt.maven.repository;

import sh.zolt.maven.ArtifactDescriptor;
import sh.zolt.maven.Coordinate;
import sh.zolt.net.NetworkTransport;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class MavenRepositoryClient {
    private final MavenRepositoryPathBuilder pathBuilder;
    private final RepositoryDownloadLimits downloadLimits;
    private final RepositoryArtifactDownloader downloader;
    private final RepositoryArtifactUploader uploader;

    public MavenRepositoryClient() {
        this(NetworkTransport.fromEnvironment());
    }

    public MavenRepositoryClient(NetworkTransport transport) {
        this(
                transport.newHttpClient(),
                new MavenRepositoryPathBuilder(),
                RepositoryHttpPolicy.defaults(),
                RepositoryDownloadLimits.defaults());
    }

    MavenRepositoryClient(HttpClient httpClient, MavenRepositoryPathBuilder pathBuilder) {
        this(httpClient, pathBuilder, RepositoryHttpPolicy.defaults(), RepositoryDownloadLimits.defaults());
    }

    MavenRepositoryClient(
            HttpClient httpClient,
            MavenRepositoryPathBuilder pathBuilder,
            RepositoryHttpPolicy httpPolicy) {
        this(httpClient, pathBuilder, httpPolicy, RepositoryDownloadLimits.defaults());
    }

    MavenRepositoryClient(
            HttpClient httpClient,
            MavenRepositoryPathBuilder pathBuilder,
            RepositoryHttpPolicy httpPolicy,
            RepositoryDownloadLimits downloadLimits) {
        this.pathBuilder = pathBuilder;
        this.downloadLimits = downloadLimits;
        this.downloader = new RepositoryArtifactDownloader(httpClient, httpPolicy);
        this.uploader = new RepositoryArtifactUploader(httpClient, httpPolicy);
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
        return fetchPom(repositoryBaseUri, coordinate, authentication, downloadListener, defaultDownloadDirectory());
    }

    public RepositoryArtifact fetchPom(
            URI repositoryBaseUri,
            Coordinate coordinate,
            Optional<RepositoryAuthentication> authentication,
            RepositoryDownloadListener downloadListener,
            Path downloadDirectory) {
        return downloader.fetch(
                repositoryBaseUri,
                new ArtifactDescriptor(coordinate, Optional.empty(), "pom"),
                pathBuilder.pomPath(coordinate),
                authentication,
                downloadListener,
                downloadDirectory,
                downloadLimits.pomAndMetadataBytes(),
                "POM");
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

    public RepositoryArtifact fetchJar(
            URI repositoryBaseUri,
            Coordinate coordinate,
            Optional<RepositoryAuthentication> authentication,
            RepositoryDownloadListener downloadListener,
            Path downloadDirectory) {
        return fetchArtifact(
                repositoryBaseUri,
                ArtifactDescriptor.jar(coordinate),
                authentication,
                downloadListener,
                downloadDirectory);
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
        return fetchArtifact(
                repositoryBaseUri,
                descriptor,
                authentication,
                downloadListener,
                defaultDownloadDirectory());
    }

    public RepositoryArtifact fetchArtifact(
            URI repositoryBaseUri,
            ArtifactDescriptor descriptor,
            Optional<RepositoryAuthentication> authentication,
            RepositoryDownloadListener downloadListener,
            Path downloadDirectory) {
        return downloader.fetch(
                repositoryBaseUri,
                descriptor,
                pathBuilder.artifactPath(descriptor),
                authentication,
                downloadListener,
                downloadDirectory,
                downloadLimits.artifactBytes(),
                "artifact");
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
            try (RepositoryArtifact artifact = downloader.fetch(
                    repositoryBaseUri,
                    descriptor,
                    pathBuilder.metadataPath(groupId, artifactId),
                    authentication,
                    RepositoryDownloadListener.NOOP,
                    defaultDownloadDirectory(),
                    downloadLimits.pomAndMetadataBytes(),
                    "metadata")) {
                return Optional.of(Files.readAllBytes(artifact.temporaryPath()));
            } catch (IOException exception) {
                throw new RepositoryClientException("Could not read downloaded repository metadata.", exception);
            }
        } catch (RepositoryMissingArtifactException exception) {
            return Optional.empty();
        }
    }

    /**
     * Fetches a small repository document at an explicit relative path, or empty on 404. Publication
     * artifact comparisons must use {@link #compareFile} so large bodies are never retained in memory.
     */
    public Optional<byte[]> fetchFile(
            URI repositoryBaseUri,
            String repositoryPath,
            Optional<RepositoryAuthentication> authentication) {
        ArtifactDescriptor descriptor =
                new ArtifactDescriptor(new Coordinate("repository", "file", Optional.empty()), Optional.empty(), "bin");
        try {
            try (RepositoryArtifact artifact = downloader.fetch(
                    repositoryBaseUri,
                    descriptor,
                    repositoryPath,
                    authentication,
                    RepositoryDownloadListener.NOOP,
                    defaultDownloadDirectory(),
                    downloadLimits.repositoryFileBytes(),
                    "repository file")) {
                return Optional.of(Files.readAllBytes(artifact.temporaryPath()));
            } catch (IOException exception) {
                throw new RepositoryClientException("Could not read downloaded repository file.", exception);
            }
        } catch (RepositoryMissingArtifactException exception) {
            return Optional.empty();
        }
    }

    /**
     * Compares an immutable publication path by streaming its bytes into SHA-256. The response is
     * bounded by the larger of the expected local length and the small-response ceiling (so a normal
     * 404 body can still be classified), retained only in a temporary file, and removed before
     * returning. A different length or digest is {@link RemoteFileComparison#DIFFERENT}; a 404 is
     * {@link RemoteFileComparison#ABSENT}.
     */
    public RemoteFileComparison compareFile(
            URI repositoryBaseUri,
            String repositoryPath,
            Optional<RepositoryAuthentication> authentication,
            long expectedLength,
            String expectedSha256) {
        if (expectedLength < 0) {
            throw new IllegalArgumentException("Expected repository file length cannot be negative.");
        }
        if (expectedSha256 == null || !expectedSha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Expected repository file SHA-256 must be 64 lowercase hex characters.");
        }
        ArtifactDescriptor descriptor =
                new ArtifactDescriptor(new Coordinate("repository", "file", Optional.empty()), Optional.empty(), "bin");
        try {
            try (RepositoryArtifact artifact = downloader.fetch(
                    repositoryBaseUri,
                    descriptor,
                    repositoryPath,
                    authentication,
                    RepositoryDownloadListener.NOOP,
                    defaultDownloadDirectory(),
                    Math.max(downloadLimits.repositoryFileBytes(), expectedLength),
                    "publication file")) {
                return artifact.size() == expectedLength && artifact.sha256().equals(expectedSha256)
                        ? RemoteFileComparison.MATCHING
                        : RemoteFileComparison.DIFFERENT;
            }
        } catch (RepositoryMissingArtifactException exception) {
            return RemoteFileComparison.ABSENT;
        } catch (RepositoryClientException exception) {
            if (RepositoryArtifactDownloader.responseTooLarge(exception)) {
                return RemoteFileComparison.DIFFERENT;
            }
            throw exception;
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
        uploader.upload(
                repositoryBaseUri,
                coordinate.toString(),
                pathBuilder.pomPath(coordinate),
                source,
                authentication);
    }

    public void uploadArtifact(URI repositoryBaseUri, ArtifactDescriptor descriptor, Path source) {
        uploadArtifact(repositoryBaseUri, descriptor, source, RepositoryAuthentication.none());
    }

    public void uploadArtifact(
            URI repositoryBaseUri,
            ArtifactDescriptor descriptor,
            Path source,
            Optional<RepositoryAuthentication> authentication) {
        uploader.upload(
                repositoryBaseUri,
                descriptor.coordinate().toString(),
                pathBuilder.artifactPath(descriptor),
                source,
                authentication);
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
        uploader.upload(repositoryBaseUri, repositoryPath, repositoryPath, source, authentication);
    }

    private static Path defaultDownloadDirectory() {
        return Path.of(System.getProperty("java.io.tmpdir"), "zolt-repository-downloads");
    }

}
