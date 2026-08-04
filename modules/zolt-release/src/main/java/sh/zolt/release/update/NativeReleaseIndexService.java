package sh.zolt.release.update;

import sh.zolt.release.channel.ReleaseIndexManifest;
import sh.zolt.release.channel.ReleaseIndexManifestValidator;
import sh.zolt.release.signing.ReleaseSignatureException;
import sh.zolt.release.signing.ReleaseSignatureVerifier;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class NativeReleaseIndexService {
    private static final int MAX_RELEASE_INDEX_BYTES = 2_097_152;
    private static final int MAX_RELEASE_INDEX_SIGNATURE_BYTES = 8_192;

    private final ReleaseIndexManifestValidator indexValidator;
    private final ReleaseSignatureVerifier signatureVerifier;
    private final NativeUpdateTransport transport;

    public NativeReleaseIndexService() {
        this(
                new ReleaseIndexManifestValidator(),
                ReleaseSignatureVerifier.bundled(),
                NativeUpdateTransport.standard());
    }

    NativeReleaseIndexService(
            ReleaseIndexManifestValidator indexValidator,
            ReleaseSignatureVerifier signatureVerifier,
            NativeUpdateTransport transport) {
        this.indexValidator = indexValidator;
        this.signatureVerifier = signatureVerifier;
        this.transport = transport;
    }

    public NativeReleaseListResult list(NativeReleaseListRequest request) {
        ReleaseIndexManifest index = read(request);
        return new NativeReleaseListResult(
                index.channel(),
                index.versions().stream()
                        .map(version -> new NativeReleaseVersion(
                                version.version(),
                                version.commit(),
                                version.createdAt(),
                                version.artifacts().stream()
                                        .map(artifact -> artifact.target())
                                        .sorted()
                                        .toList()))
                        .toList());
    }

    public ReleaseIndexManifest read(NativeReleaseListRequest request) {
        try {
            ReleaseChannelUriPolicy.validate(request.releaseIndexUri(), true);
            byte[] indexBytes = transport.downloadBytes(
                    request.releaseIndexUri(),
                    MAX_RELEASE_INDEX_BYTES,
                    "release index manifest");
            verifyIndexSignature(request, indexBytes);
            ReleaseIndexManifest index =
                    validateIndex(request, new String(indexBytes, StandardCharsets.UTF_8));
            ReleaseChannelUriPolicy.requireChannelDocument(
                    request.releaseIndexUri(), "releases", index.channel());
            return index;
        } catch (IOException exception) {
            throw new NativeUpdateException("Could not read native Zolt release index: " + exception.getMessage(), exception);
        }
    }

    private ReleaseIndexManifest validateIndex(NativeReleaseListRequest request, String json) {
        if (ReleaseChannelUriPolicy.isLocalFile(request.releaseIndexUri())) {
            return indexValidator.validateLocalManifest(json);
        }
        return indexValidator.validate(json);
    }

    private void verifyIndexSignature(NativeReleaseListRequest request, byte[] indexBytes) throws IOException {
        if (ReleaseChannelUriPolicy.isLocalFile(request.releaseIndexUri())) {
            return;
        }
        var signatureUri = ReleaseChannelSignatureUris.sidecar(request.releaseIndexUri());
        String sidecarText;
        try {
            sidecarText = new String(
                    transport.downloadBytes(
                            signatureUri,
                            MAX_RELEASE_INDEX_SIGNATURE_BYTES,
                            "release index signature"),
                    StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new NativeUpdateException(
                    "Release index signature is required but could not be downloaded from " + signatureUri + ".",
                    exception);
        }
        try {
            signatureVerifier.verify(indexBytes, sidecarText);
        } catch (ReleaseSignatureException exception) {
            throw new NativeUpdateException(
                    "Release index signature verification failed: " + exception.getMessage(),
                    exception);
        }
    }
}
