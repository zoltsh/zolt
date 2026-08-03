package sh.zolt.build.fingerprint;

public record BuildFingerprintCheck(boolean current, String reason) {
    public BuildFingerprintCheck {
        reason = reason == null ? "" : reason;
    }

    static BuildFingerprintCheck hit() {
        return new BuildFingerprintCheck(true, "");
    }

    static BuildFingerprintCheck hit(String reason) {
        return new BuildFingerprintCheck(true, reason);
    }

    static BuildFingerprintCheck miss(String reason) {
        return new BuildFingerprintCheck(false, reason);
    }
}
