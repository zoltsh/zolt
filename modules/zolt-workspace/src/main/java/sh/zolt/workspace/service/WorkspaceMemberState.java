package sh.zolt.workspace.service;

record WorkspaceMemberState(
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
        String testOutputManifestDigest) {
    WorkspaceMemberState {
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
    }

    String compileAbiDigest() {
        return WorkspaceHash.text(publicAbiDigest + "|" + packagePrivateAbiDigest);
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
