export const BASE_QUALITY_CHECK_ARGUMENTS = [
  "--check", "lockfile",
  "--check", "project-model",
  "--check", "package-metadata",
  "--check", "manifest-metadata",
  "--check", "generated-sources",
] as const;
