package sh.zolt.cli.build.kotlin;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.jar.JarOutputStream;
import sh.zolt.cli.CliTestRepository;

/** Publishes the resolver identity used by the in-process typed Protobuf generator. */
public final class ProtobufKotlinCliFixture {
    public static final String COORDINATE = "com.example:protoc-stub";
    public static final String VERSION = "1.0.0";

    private ProtobufKotlinCliFixture() {
    }

    public static void publish(CliTestRepository repository) throws IOException {
        repository.addArtifact(
                "com.example",
                "protoc-stub",
                VERSION,
                """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example</groupId>
                  <artifactId>protoc-stub</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(VERSION),
                emptyJar());
    }

    private static byte[] emptyJar() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream ignored = new JarOutputStream(bytes)) {
            // A valid empty archive is sufficient because the typed generator is in-process.
        }
        return bytes.toByteArray();
    }
}
