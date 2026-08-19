package sh.zolt.manifest;

import java.util.List;

/** One authored built-in-command expansion from {@code [aliases]}, excluding its map-owned ID. */
public record AuthoredAlias(List<String> argv) {
    public AuthoredAlias {
        argv = ManifestModelValues.immutableList(argv, "Alias arguments");
        if (argv.isEmpty()) {
            throw new IllegalArgumentException("Alias arguments must not be empty.");
        }
        for (String argument : argv) {
            if (argument.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("Alias arguments must not contain NUL.");
            }
        }
        ManifestModelValues.requireNonBlank(argv.getFirst(), "Alias target");
        new LocalId(argv.getFirst());
    }

    public LocalId target() {
        return new LocalId(argv.getFirst());
    }
}
