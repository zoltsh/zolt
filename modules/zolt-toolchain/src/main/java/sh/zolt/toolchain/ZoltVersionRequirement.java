package sh.zolt.toolchain;

import java.nio.file.Path;

/** One manifest's {@code [toolchain.zolt].version} pin and the file that declared it. */
public record ZoltVersionRequirement(Path manifestPath, String zoltVersion) {
}
