package sh.zolt.update;

import sh.zolt.maven.Coordinate;
import sh.zolt.maven.CoordinateParseException;
import java.util.Optional;

/** The {@code group:artifact} a surface's version listing is discovered under. */
record DiscoveryCoordinate(String groupId, String artifactId) {
    static Optional<DiscoveryCoordinate> of(String coordinate) {
        if (coordinate == null) {
            return Optional.empty();
        }
        int colon = coordinate.indexOf(':');
        if (colon <= 0 || colon >= coordinate.length() - 1) {
            return Optional.empty();
        }
        String groupId = coordinate.substring(0, colon);
        String remainder = coordinate.substring(colon + 1);
        int next = remainder.indexOf(':');
        String artifactId = next < 0 ? remainder : remainder.substring(0, next);
        if (groupId.isBlank() || artifactId.isBlank()) {
            return Optional.empty();
        }
        try {
            Coordinate validated = new Coordinate(groupId, artifactId, Optional.empty());
            return Optional.of(new DiscoveryCoordinate(validated.groupId(), validated.artifactId()));
        } catch (CoordinateParseException exception) {
            throw new CoordinateParseException(
                    "Dependency coordinate `" + coordinate + "` is not safe for repository metadata discovery: "
                            + exception.getMessage());
        }
    }

    String coordinate() {
        return groupId + ":" + artifactId;
    }
}
