package sh.zolt.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable launch policy for one process supervised by Zolt. */
public record SupervisedProcessSpec(
        List<String> command,
        Path directory,
        Map<String, String> environment,
        boolean clearEnvironment,
        boolean mergeErrorStream,
        ProcessInputPolicy inputPolicy,
        Duration timeout,
        int diagnosticTailCharacters,
        Consumer<String> stdoutConsumer,
        Consumer<String> stderrConsumer) {
    public SupervisedProcessSpec {
        command = List.copyOf(Objects.requireNonNull(command, "Process command is required."));
        if (command.isEmpty() || command.getFirst().isBlank()) {
            throw new IllegalArgumentException("Process command must include an executable.");
        }
        environment = Map.copyOf(Objects.requireNonNull(environment, "Process environment is required."));
        inputPolicy = Objects.requireNonNull(inputPolicy, "Process input policy is required.");
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("Process timeout must be positive.");
        }
        if (diagnosticTailCharacters < 1) {
            throw new IllegalArgumentException("Process diagnostic tail must retain at least one character.");
        }
        stdoutConsumer = Objects.requireNonNull(stdoutConsumer, "Process stdout consumer is required.");
        stderrConsumer = Objects.requireNonNull(stderrConsumer, "Process stderr consumer is required.");
    }

    public static Builder builder(List<String> command) {
        return new Builder(command);
    }

    public static final class Builder {
        private final List<String> command;
        private Path directory;
        private Map<String, String> environment = Map.of();
        private boolean clearEnvironment;
        private boolean mergeErrorStream = true;
        private ProcessInputPolicy inputPolicy = ProcessInputPolicy.CLOSED;
        private Duration timeout;
        private int diagnosticTailCharacters = ProcessSupervisor.DEFAULT_DIAGNOSTIC_TAIL_CHARACTERS;
        private Consumer<String> stdoutConsumer = ignored -> {};
        private Consumer<String> stderrConsumer = ignored -> {};

        private Builder(List<String> command) {
            this.command = command;
        }

        public Builder directory(Path directory) {
            this.directory = directory;
            return this;
        }

        public Builder environment(Map<String, String> environment) {
            this.environment = environment;
            return this;
        }

        public Builder clearEnvironment(boolean clearEnvironment) {
            this.clearEnvironment = clearEnvironment;
            return this;
        }

        public Builder mergeErrorStream(boolean mergeErrorStream) {
            this.mergeErrorStream = mergeErrorStream;
            return this;
        }

        public Builder inputPolicy(ProcessInputPolicy inputPolicy) {
            this.inputPolicy = inputPolicy;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder diagnosticTailCharacters(int diagnosticTailCharacters) {
            this.diagnosticTailCharacters = diagnosticTailCharacters;
            return this;
        }

        public Builder stdoutConsumer(Consumer<String> stdoutConsumer) {
            this.stdoutConsumer = stdoutConsumer;
            return this;
        }

        public Builder stderrConsumer(Consumer<String> stderrConsumer) {
            this.stderrConsumer = stderrConsumer;
            return this;
        }

        public SupervisedProcessSpec build() {
            return new SupervisedProcessSpec(
                    command,
                    directory,
                    environment,
                    clearEnvironment,
                    mergeErrorStream,
                    inputPolicy,
                    timeout,
                    diagnosticTailCharacters,
                    stdoutConsumer,
                    stderrConsumer);
        }
    }
}
