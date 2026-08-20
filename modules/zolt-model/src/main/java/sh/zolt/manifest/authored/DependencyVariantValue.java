package sh.zolt.manifest.authored;

import sh.zolt.maven.CoordinateParseException;
import sh.zolt.maven.MavenRepositoryValue;

final class DependencyVariantValue {
    private DependencyVariantValue() {
    }

    static String classifier(String value) {
        try {
            return withoutKeyDelimiter(MavenRepositoryValue.classifier(value), "classifier");
        } catch (CoordinateParseException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    static String type(String value) {
        try {
            return withoutKeyDelimiter(MavenRepositoryValue.extension(value), "type");
        } catch (CoordinateParseException exception) {
            throw new IllegalArgumentException(exception.getMessage(), exception);
        }
    }

    private static String withoutKeyDelimiter(String value, String subject) {
        if (value.indexOf('|') >= 0) {
            throw new IllegalArgumentException("Dependency " + subject + " must not contain `|`.");
        }
        return value;
    }
}
