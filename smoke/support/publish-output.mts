export interface PublishDryRun {
  readonly artifactChecksum: string;
  readonly artifactPath: string;
  readonly artifactUploadPath: string;
  readonly pomChecksum: string;
  readonly pomPath: string;
  readonly pomUploadPath: string;
}

export function parsePublishDryRun(output: string): PublishDryRun {
  return {
    artifactPath: readField(output, "Artifact path"),
    artifactChecksum: readField(output, "Artifact checksum"),
    artifactUploadPath: readField(output, "Artifact upload path"),
    pomPath: readField(output, "Generated POM"),
    pomChecksum: readField(output, "POM checksum"),
    pomUploadPath: readField(output, "POM upload path"),
  };
}

function readField(output: string, label: string): string {
  const prefix = `${label}: `;
  const line = output.split(/\r?\n/u).find((candidate) => candidate.startsWith(prefix));
  if (line === undefined) {
    throw new Error(`Publish dry-run output did not include ${JSON.stringify(label)}.`);
  }
  return line.slice(prefix.length).trim();
}
