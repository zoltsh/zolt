package sh.zolt.manifest;

/** A project artifact identity using the final portable Maven-safe character set. */
public record ProjectName(String value) {
    public ProjectName {
        value = ProjectIdentityValue.validate(value, "project name");
    }

    @Override
    public String toString() {
        return value;
    }
}
