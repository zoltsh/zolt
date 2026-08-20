package sh.zolt.toml.manifest;

import java.util.ArrayList;
import java.util.Objects;
import sh.zolt.toml.schema.ManifestPath;

/** Immutable display path for scalar and indexed manifest diagnostics. */
record ManifestDiagnosticPath(ManifestPath structure) {
    ManifestDiagnosticPath {
        Objects.requireNonNull(structure, "Manifest diagnostic path is required.");
    }

    static ManifestDiagnosticPath of(ManifestPath path) {
        return new ManifestDiagnosticPath(
                Objects.requireNonNull(path, "Manifest path is required."));
    }

    static ManifestDiagnosticPath indexed(ManifestPath path, int index) {
        return of(path).indexed(index);
    }

    ManifestDiagnosticPath child(String segment) {
        return new ManifestDiagnosticPath(structure.child(segment));
    }

    ManifestDiagnosticPath indexed(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("Manifest diagnostic indexes must not be negative.");
        }
        ArrayList<String> segments = new ArrayList<>(structure.segments());
        int last = segments.size() - 1;
        segments.set(last, segments.get(last) + "[" + index + "]");
        return new ManifestDiagnosticPath(new ManifestPath(segments));
    }

    @Override
    public String toString() {
        return structure.toString();
    }
}
