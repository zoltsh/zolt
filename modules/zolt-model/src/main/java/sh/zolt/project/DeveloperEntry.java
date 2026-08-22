package sh.zolt.project;

/**
 * A structured POM developer entry sourced from {@code [project.developers.<id>]}. The
 * table key supplies {@link #id()}; the remaining fields are optional. Maven Central requires at
 * least one developer with a name and email, which this record can carry (the simpler
 * {@code developers} name array cannot).
 */
public record DeveloperEntry(String id, String name, String email, String organization, String url) {
    public DeveloperEntry {
        id = normalize(id);
        name = normalize(name);
        email = normalize(email);
        organization = normalize(organization);
        url = normalize(url);
    }

    public boolean isEmpty() {
        return id.isBlank() && name.isBlank() && email.isBlank() && organization.isBlank() && url.isBlank();
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? "" : value.trim();
    }
}
