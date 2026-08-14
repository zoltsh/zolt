package sh.zolt.maven.repository;

/** Result of comparing one immutable publication path without retaining its remote body. */
public enum RemoteFileComparison {
    ABSENT,
    MATCHING,
    DIFFERENT
}
