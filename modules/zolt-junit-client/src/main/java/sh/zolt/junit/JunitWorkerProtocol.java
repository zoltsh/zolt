package sh.zolt.junit;

import sh.zolt.test.TestSelection;
import sh.zolt.test.TestSelectionCodec;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The line-oriented wire protocol spoken between {@link JunitWorkerClient} (parent) and the forked
 * JUnit worker JVM.
 *
 * <p>Each request and result is a single line of UTF-8 text. A line is a tab-delimited list of
 * fields. The first field is always a bare verb ({@code RUN}, {@code QUIT}, or the result prefix);
 * every field after it is a {@code name=value} pair — a self-describing, tagged field:
 *
 * <pre>
 *   RUN  &lt;TAB&gt; v=1 &lt;TAB&gt; id=junit-1 &lt;TAB&gt; out=target/test-classes &lt;TAB&gt; classes=com.example.MainTest
 *   QUIT &lt;TAB&gt; v=1 &lt;TAB&gt; id=junit-2
 *   ZOLT_WORKER_RESULT &lt;TAB&gt; id=junit-1 &lt;TAB&gt; exit=0
 * </pre>
 *
 * <p>The first tagged field of every request is {@code v=<schemaVersion>}, so both ends agree on the
 * field vocabulary before reading anything else. Decoding parses the tagged fields into a name-keyed
 * map and reads each value <em>by name</em> — it never counts fields or derives offsets from the
 * field count. Absent optional fields are simply missing keys, so adding a field is backward-tolerant
 * (older workers ignore unknown keys; a breaking change bumps {@link #SCHEMA_VERSION}). This keeps the
 * format honest as it grows: a new field is one new key, never a new part-count special case.
 *
 * <p>Field values are percent-escaped on the wire (see {@link Frame}) so they can never contain a
 * tab, newline, carriage return, the {@code =} that separates name from value, or the {@code %}
 * escape marker itself. List-valued fields (selectors, patterns, tags, events) are first comma-joined
 * by {@link sh.zolt.test.TestSelectionCodec} (via {@link TestSelectionField}) and then escaped as a
 * single value.
 */
public final class JunitWorkerProtocol {
    public static final String RESULT_PREFIX = "ZOLT_WORKER_RESULT";

    /** The wire schema version. Bump only on a breaking change to the field vocabulary. */
    public static final int SCHEMA_VERSION = 1;

    private static final String RUN = "RUN";
    private static final String QUIT = "QUIT";

    private static final String FIELD_VERSION = "v";
    private static final String FIELD_REQUEST_ID = "id";
    private static final String FIELD_PROJECT_DIRECTORY = "cwd";
    private static final String FIELD_TEST_OUTPUT = "out";
    private static final String FIELD_TEST_RUNTIME_CLASSPATH = "classpath";
    private static final String FIELD_REPORTS = "reports";
    private static final String FIELD_PROFILE = "profile";
    private static final String FIELD_EVENTS = "events";
    private static final String FIELD_EXIT_CODE = "exit";

    private JunitWorkerProtocol() {
    }

    public static String runRequest(String requestId, Path testOutputDirectory) {
        return runRequest(requestId, testOutputDirectory, TestSelection.empty());
    }

    public static String runRequest(String requestId, Path testOutputDirectory, TestSelection testSelection) {
        return runRequest(requestId, testOutputDirectory, testSelection, Optional.empty(), List.of());
    }

    public static String runRequest(
            String requestId,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events) {
        return runRequest(requestId, testOutputDirectory, testSelection, reportsDirectory, events, Optional.empty());
    }

    public static String runRequest(
            String requestId,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        return runRequest(
                requestId,
                List.of(),
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                profileDirectory);
    }

    public static String runRequest(
            String requestId,
            List<Path> testRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        return runRequest(
                requestId,
                null,
                testRuntimeClasspath,
                testOutputDirectory,
                testSelection,
                reportsDirectory,
                events,
                profileDirectory);
    }

    public static String runRequest(
            String requestId,
            Path projectDirectory,
            List<Path> testRuntimeClasspath,
            Path testOutputDirectory,
            TestSelection testSelection,
            Optional<Path> reportsDirectory,
            List<String> events,
            Optional<Path> profileDirectory) {
        if (testOutputDirectory == null) {
            throw new IllegalArgumentException("JUnit worker test output directory is required.");
        }
        Frame frame = Frame.command(RUN);
        frame.put(FIELD_VERSION, Integer.toString(SCHEMA_VERSION));
        frame.put(FIELD_REQUEST_ID, validateRequestId(requestId));
        if (projectDirectory != null) {
            frame.put(FIELD_PROJECT_DIRECTORY, projectDirectory.toString());
        }
        frame.put(FIELD_TEST_OUTPUT, requireField("JUnit worker test output directory", testOutputDirectory.toString()));
        frame.put(FIELD_TEST_RUNTIME_CLASSPATH, encodedPaths(testRuntimeClasspath));
        optionalPath(reportsDirectory).ifPresent(path -> frame.put(FIELD_REPORTS, path));
        optionalPath(profileDirectory).ifPresent(path -> frame.put(FIELD_PROFILE, path));
        TestSelectionField.encodeStrings(frame, FIELD_EVENTS, events);
        TestSelectionField.encode(frame, testSelection == null ? TestSelection.empty() : testSelection);
        return frame.render();
    }

    public static String quitRequest(String requestId) {
        Frame frame = Frame.command(QUIT);
        frame.put(FIELD_VERSION, Integer.toString(SCHEMA_VERSION));
        frame.put(FIELD_REQUEST_ID, validateRequestId(requestId));
        return frame.render();
    }

    public static WorkerRequest parseRequest(String line) {
        Frame frame = Frame.parse(line);
        requireSchemaVersion(frame);
        String requestId = validateRequestId(frame.require(FIELD_REQUEST_ID, "JUnit worker request id"));
        if (QUIT.equals(frame.command())) {
            frame.rejectUnexpected("JUnit worker quit request", List.of(FIELD_VERSION, FIELD_REQUEST_ID));
            return WorkerRequest.quit(requestId);
        }
        if (!RUN.equals(frame.command())) {
            throw new IllegalArgumentException("Unknown JUnit worker request command `" + frame.command() + "`.");
        }
        return new WorkerRequest(
                WorkerCommand.RUN,
                requestId,
                frame.optional(FIELD_PROJECT_DIRECTORY),
                frame.require(FIELD_TEST_OUTPUT, "JUnit worker test output directory"),
                decodedPaths(frame),
                frame.optional(FIELD_REPORTS),
                frame.optional(FIELD_PROFILE),
                TestSelectionField.events(frame, FIELD_EVENTS),
                TestSelectionField.decode(frame));
    }

    public static String result(String requestId, int exitCode) {
        Frame frame = Frame.command(RESULT_PREFIX);
        frame.put(FIELD_REQUEST_ID, validateRequestId(requestId));
        frame.put(FIELD_EXIT_CODE, Integer.toString(exitCode));
        return frame.render();
    }

    public static WorkerResult parseResult(String line) {
        Frame frame = Frame.parse(line);
        if (!RESULT_PREFIX.equals(frame.command())) {
            throw new IllegalArgumentException(
                    "Malformed JUnit worker result. Expected " + RESULT_PREFIX + "<TAB>id=<requestId><TAB>exit=<exitCode>.");
        }
        String requestId = validateRequestId(frame.require(FIELD_REQUEST_ID, "JUnit worker request id"));
        String exitCode = frame.require(FIELD_EXIT_CODE, "JUnit worker result exit code");
        try {
            return new WorkerResult(requestId, Integer.parseInt(exitCode));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Malformed JUnit worker result exit code `" + exitCode + "`.",
                    exception);
        }
    }

    private static void requireSchemaVersion(Frame frame) {
        String version = frame.require(FIELD_VERSION, "JUnit worker schema version");
        int parsed;
        try {
            parsed = Integer.parseInt(version);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Malformed JUnit worker schema version `" + version + "`.",
                    exception);
        }
        if (parsed != SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "Unsupported JUnit worker schema version " + parsed + "; this build speaks version " + SCHEMA_VERSION + ".");
        }
    }

    private static String validateRequestId(String requestId) {
        String value = requireField("JUnit worker request id", requestId);
        // The request id is a control token used to correlate a result with its request, never user
        // data, so it must be a clean single-line token even though the frame would otherwise escape it.
        if (value.indexOf('\t') >= 0 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("JUnit worker request id must not contain tabs or newlines.");
        }
        return value;
    }

    private static String requireField(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required.");
        }
        return value;
    }

    private static Optional<String> optionalPath(Optional<Path> value) {
        return value == null ? Optional.empty() : value.map(Path::toString).filter(path -> !path.isBlank());
    }

    private static String encodedPaths(List<Path> paths) {
        if (paths == null || paths.isEmpty()) {
            return null;
        }
        return TestSelectionCodec.encodeStrings(paths.stream()
                .map(Path::toString)
                .toList());
    }

    private static List<String> decodedPaths(Frame frame) {
        return TestSelectionCodec.decodeStrings(
                "JUnit worker test runtime classpath",
                frame.optional(FIELD_TEST_RUNTIME_CLASSPATH).orElse(""));
    }

    public enum WorkerCommand {
        RUN,
        QUIT
    }

    public record WorkerRequest(
            WorkerCommand command,
            String requestId,
            Optional<String> projectDirectory,
            String testOutputDirectory,
            List<String> testRuntimeClasspath,
            Optional<String> reportsDirectory,
            Optional<String> profileDirectory,
            List<String> events,
            TestSelection testSelection) {
        public WorkerRequest {
            projectDirectory = projectDirectory == null
                    ? Optional.empty()
                    : projectDirectory;
            testRuntimeClasspath = testRuntimeClasspath == null
                    ? List.of()
                    : List.copyOf(testRuntimeClasspath);
            reportsDirectory = reportsDirectory == null ? Optional.empty() : reportsDirectory;
            profileDirectory = profileDirectory == null ? Optional.empty() : profileDirectory;
            events = events == null ? List.of() : List.copyOf(events);
        }

        public WorkerRequest(
                WorkerCommand command,
                String requestId,
                String testOutputDirectory,
                Optional<String> reportsDirectory,
                Optional<String> profileDirectory,
                List<String> events,
                TestSelection testSelection) {
            this(
                    command,
                    requestId,
                    Optional.empty(),
                    testOutputDirectory,
                    List.of(),
                    reportsDirectory,
                    profileDirectory,
                    events,
                    testSelection);
        }

        static WorkerRequest quit(String requestId) {
            return new WorkerRequest(
                    WorkerCommand.QUIT,
                    requestId,
                    Optional.empty(),
                    "",
                    List.of(),
                    Optional.empty(),
                    Optional.empty(),
                    List.of(),
                    TestSelection.empty());
        }
    }

    public record WorkerResult(String requestId, int exitCode) {
    }
}
