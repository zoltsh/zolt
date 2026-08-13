package sh.zolt.maven.repository;

/** Independent response ceilings for repository metadata and binary artifacts. */
record RepositoryDownloadLimits(
        long pomAndMetadataBytes,
        long artifactBytes,
        long repositoryFileBytes) {
    private static final long MIB = 1024L * 1024L;

    RepositoryDownloadLimits {
        if (pomAndMetadataBytes < 1 || artifactBytes < 1 || repositoryFileBytes < 1) {
            throw new IllegalArgumentException("Repository download limits must be positive.");
        }
    }

    static RepositoryDownloadLimits defaults() {
        return new RepositoryDownloadLimits(8L * MIB, 512L * MIB, 8L * MIB);
    }
}
