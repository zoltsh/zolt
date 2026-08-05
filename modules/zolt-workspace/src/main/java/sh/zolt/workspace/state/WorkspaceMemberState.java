package sh.zolt.workspace.state;

import java.util.ArrayList;
import java.util.List;

/**
 * One member's recorded build-input state.
 *
 * <p>New fields are appended, never inserted, so a state written by an older codec version decodes
 * by position and simply leaves the newer fields empty. That reads as "never observed" and costs the
 * members those fields describe one rebuild, rather than invalidating the whole workspace.
 */
public record WorkspaceMemberState(
        String configDigest,
        String toolchainDigest,
        String mainSourceTreeDigest,
        String resourceTreeDigest,
        String generatedInputDigest,
        String mainCompileKey,
        String mainOutputManifestDigest,
        String publicAbiDigest,
        String packagePrivateAbiDigest,
        String testCompileKey,
        String testResourceTreeDigest,
        String testOutputManifestDigest,
        String processorInputDigest,
        String generatedOutputDigest) {
    /** How many digest fields a row carries, in the order this record declares them. */
    static final int DIGESTS = 14;

    public WorkspaceMemberState {
        configDigest = value(configDigest);
        toolchainDigest = value(toolchainDigest);
        mainSourceTreeDigest = value(mainSourceTreeDigest);
        resourceTreeDigest = value(resourceTreeDigest);
        generatedInputDigest = value(generatedInputDigest);
        mainCompileKey = value(mainCompileKey);
        mainOutputManifestDigest = value(mainOutputManifestDigest);
        publicAbiDigest = value(publicAbiDigest);
        packagePrivateAbiDigest = value(packagePrivateAbiDigest);
        testCompileKey = value(testCompileKey);
        testResourceTreeDigest = value(testResourceTreeDigest);
        testOutputManifestDigest = value(testOutputManifestDigest);
        processorInputDigest = value(processorInputDigest);
        generatedOutputDigest = value(generatedOutputDigest);
    }

    public String compileAbiDigest() {
        return WorkspaceHash.text(publicAbiDigest + "|" + packagePrivateAbiDigest);
    }

    /** The digest fields in declaration order, which is the order a state row writes them. */
    List<String> digests() {
        return List.of(
                configDigest,
                toolchainDigest,
                mainSourceTreeDigest,
                resourceTreeDigest,
                generatedInputDigest,
                mainCompileKey,
                mainOutputManifestDigest,
                publicAbiDigest,
                packagePrivateAbiDigest,
                testCompileKey,
                testResourceTreeDigest,
                testOutputManifestDigest,
                processorInputDigest,
                generatedOutputDigest);
    }

    /** Rebuilds a member from a row's digests, padding any an older codec version did not write. */
    static WorkspaceMemberState of(List<String> digests) {
        List<String> padded = new ArrayList<>(digests);
        while (padded.size() < DIGESTS) {
            padded.add("");
        }
        return new WorkspaceMemberState(
                padded.get(0),
                padded.get(1),
                padded.get(2),
                padded.get(3),
                padded.get(4),
                padded.get(5),
                padded.get(6),
                padded.get(7),
                padded.get(8),
                padded.get(9),
                padded.get(10),
                padded.get(11),
                padded.get(12),
                padded.get(13));
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
