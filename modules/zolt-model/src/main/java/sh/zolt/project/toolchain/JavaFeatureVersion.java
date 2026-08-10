package sh.zolt.project.toolchain;

public final class JavaFeatureVersion {
    private JavaFeatureVersion() {
    }

    public static boolean isConcrete(String value) {
        if (value == null || value.isEmpty() || value.charAt(0) == '0') {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
