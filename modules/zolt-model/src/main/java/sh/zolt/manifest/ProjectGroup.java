package sh.zolt.manifest;

/** A project group identity using the final portable Maven-safe character set. */
public record ProjectGroup(String value) {
    public ProjectGroup {
        value = ProjectIdentityValue.validate(value, "project group");
    }

    @Override
    public String toString() {
        return value;
    }
}
