package sh.zolt.build.compile;

import java.nio.file.Path;

record ConfiguredPath(String key, String configured, Path path, boolean allowsOutputRootSubtree) {
}
