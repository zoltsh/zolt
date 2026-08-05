package sh.zolt.build.packageplan;

import java.nio.file.Path;

/**
 * One archive-bound file of a compiled output directory, identified by a single read.
 *
 * @param name archive entry name, relative to the snapshot root, already `/`-separated
 * @param path absolute source path the entry was read from
 * @param size byte length observed while the snapshot was taken
 */
public record PackageInputEntry(String name, Path path, long size) {
}
