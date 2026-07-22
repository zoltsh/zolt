export interface LibraryCanaryContract {
  readonly className: string;
  readonly fixture: string;
  readonly packageName: string;
  readonly testClass: string;
  readonly testProperty: string;
}

export const LIBRARY_CANARY_CONTRACTS: readonly LibraryCanaryContract[] = [
  {
    className: "org/apache/commons/cli/DefaultParser",
    fixture: "commons-cli-canary",
    packageName: "org.apache.commons.cli",
    testClass: "org.apache.commons.cli.DefaultParserTest",
    testProperty: "-Dcommons.cli.canary=true",
  },
  {
    className: "com/zaxxer/hikari/HikariDataSource",
    fixture: "hikaricp-canary",
    packageName: "com.zaxxer.hikari",
    testClass: "com.zaxxer.hikari.HikariDataSourceTest",
    testProperty: "-Dhikari.canary.leakDetectionMs=250",
  },
];
