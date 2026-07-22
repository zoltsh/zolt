export const EXPECTED_ZOLT_COMMANDS = [
  "help", "init", "version", "config", "doctor", "self", "update",
  "add", "remove", "platform", "resolve", "tree", "why", "policy", "conflicts",
  "aliases", "tasks", "task", "build", "run", "exec", "test", "integration-test", "coverage",
  "package", "run-package", "clean",
  "check", "plan", "classpath", "ide", "toolchain", "shims", "explain", "quarkus",
  "native", "native-smoke", "release-archive", "release-verify", "publish",
  "self-check", "self-parity",
] as const;

export function parseListedCommands(output: string): string[] {
  const commands = output
    .split(/\r?\n/u)
    .filter((line) => /^ {4}[a-z]/u.test(line))
    .map((line) => line.trim().split(/\s+/u)[0]);
  if (commands.length === 0) {
    throw new Error("Could not parse any commands from `zolt --list` output.");
  }
  return commands;
}
