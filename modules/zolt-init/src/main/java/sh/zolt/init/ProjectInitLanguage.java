package sh.zolt.init;

/** Source language emitted by {@code zolt init}. */
public enum ProjectInitLanguage {
    JAVA("java", "java"),
    KOTLIN("kotlin", "kt");

    private final String id;
    private final String sourceExtension;

    ProjectInitLanguage(String id, String sourceExtension) {
        this.id = id;
        this.sourceExtension = sourceExtension;
    }

    public String id() {
        return id;
    }

    public String sourceExtension() {
        return sourceExtension;
    }

    @Override
    public String toString() {
        return id;
    }
}
