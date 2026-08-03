package sh.zolt.build.incremental;

public record IncrementalCompileSummary(
        String publicAbiDigest,
        String packagePrivateAbiDigest,
        String outputManifestDigest) {
    public IncrementalCompileSummary {
        publicAbiDigest = requireDigest(publicAbiDigest, "Public ABI digest is required.");
        packagePrivateAbiDigest = requireDigest(
                packagePrivateAbiDigest,
                "Package-private ABI digest is required.");
        outputManifestDigest = requireDigest(
                outputManifestDigest,
                "Output manifest digest is required.");
    }

    public static IncrementalCompileSummary from(IncrementalCompileState state) {
        return new IncrementalCompileSummary(
                state.publicAbiDigest(),
                state.packagePrivateAbiDigest(),
                state.outputManifestDigest());
    }

    public String compileAbiDigest() {
        return IncrementalCompileInputHasher.hashText(
                publicAbiDigest + "|" + packagePrivateAbiDigest);
    }

    private static String requireDigest(String digest, String message) {
        if (digest == null || digest.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return digest;
    }
}
