package sh.zolt.build.compile.kotlin;

import java.nio.file.Path;
import java.util.Objects;
import sh.zolt.build.KotlinCompileException;

/** Invocation-local paths that are deliberately excluded from immutable compiler policy. */
public record KotlinCompilerInvocationContext(Path friendPath) {
    public KotlinCompilerInvocationContext {
        friendPath = friendPath == null ? null : friendPath.normalize();
        if (friendPath != null && friendPath.toString().contains(",")) {
            throw new KotlinCompileException(
                    "Kotlin test compilation cannot use a friend output path containing a comma because "
                            + "kotlinc treats commas as friend-path separators. Move the project to a path "
                            + "without commas and try again.");
        }
    }

    public static KotlinCompilerInvocationContext none() {
        return new KotlinCompilerInvocationContext(null);
    }

    public KotlinCompilerInvocationContext withFriendPath(Path path) {
        return new KotlinCompilerInvocationContext(
                Objects.requireNonNull(path, "Kotlin friend path is required."));
    }
}
