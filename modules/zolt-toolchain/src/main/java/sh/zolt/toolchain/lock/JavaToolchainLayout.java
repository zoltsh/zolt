package sh.zolt.toolchain.lock;

public record JavaToolchainLayout(
        String javaHome,
        String java,
        String javac,
        String jar,
        String nativeImage) {
    public JavaToolchainLayout {
        javaHome = ToolchainLockPaths.layout(clean(javaHome, "."), "layout.javaHome", true);
        java = ToolchainLockPaths.layout(clean(java, "bin/java"), "layout.executables.java", false);
        javac = ToolchainLockPaths.layout(clean(javac, "bin/javac"), "layout.executables.javac", false);
        jar = ToolchainLockPaths.layout(clean(jar, "bin/jar"), "layout.executables.jar", false);
        nativeImage = clean(nativeImage, "");
        if (!nativeImage.isEmpty()) {
            nativeImage = ToolchainLockPaths.layout(nativeImage, "layout.executables.nativeImage", false);
        }
    }

    public static JavaToolchainLayout standard(boolean nativeImage) {
        return new JavaToolchainLayout(
                ".",
                "bin/java",
                "bin/javac",
                "bin/jar",
                nativeImage ? "bin/native-image" : "");
    }

    private static String clean(String value, String defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value.strip();
    }
}
