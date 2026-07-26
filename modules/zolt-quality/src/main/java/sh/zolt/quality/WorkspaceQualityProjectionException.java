package sh.zolt.quality;

final class WorkspaceQualityProjectionException extends RuntimeException {
    private final String nextStep;

    WorkspaceQualityProjectionException(String message, String nextStep) {
        super(message);
        this.nextStep = nextStep;
    }

    String nextStep() {
        return nextStep;
    }
}
