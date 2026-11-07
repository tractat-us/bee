// kuilt's equivalents (build-logic GenerateKarmaTimeouts.kt) are private to its
// kmp-library convention plugin, so bee carries its own. A cold Chrome on a shared CI
// runner, and interpreted wasm blocking the page's one thread, outlast Karma's defaults.
config.set({
  pingTimeout: 120000,
  captureTimeout: 120000,
  browserNoActivityTimeout: 120000,
  browserDisconnectTimeout: 120000,
  browserDisconnectTolerance: 3,
  // Pipe the page's console to the Gradle log, so wasm-side panics are visible.
  browserConsoleLogOptions: { level: 'log', format: '%b %T: %m', terminal: true },
});
// Mutate config.client in place: the Kotlin test runner has already set client.args.
config.client = config.client || {};
config.client.mocha = config.client.mocha || {};
config.client.mocha.timeout = 60000;
