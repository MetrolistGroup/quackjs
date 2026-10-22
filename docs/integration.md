# InnerTubeX migration

Replace InnerTubeX's player cipher backend with the external
[QuackJS](https://github.com/MetrolistGroup/quackjs) KMP dependency. This is migration
guidance, not a claim that InnerTubeX's current default dependency has migrated.
Work in a dedicated consumer checkout; no snapshot-specific patches or vendored
QuackJS sources are required.

## Dependency

Add JitPack to `dependencyResolutionManagement.repositories` in
`settings.gradle.kts`, retaining `google()` and `mavenCentral()`. InnerTubeX uses
`FAIL_ON_PROJECT_REPOS`, so do not add repositories to `build.gradle.kts`.

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

Add QuackJS to `commonMain` in `build.gradle.kts`:

```kotlin
kotlin {
    sourceSets.commonMain.dependencies {
        implementation("com.github.MetrolistGroup.quackjs:quackjs:0.1.2")
    }
}
```

Use this KMP module coordinate, not JitPack's `com.github.MetrolistGroup:quackjs`
aggregate POM, which pulls every platform at once. Android requires min SDK 23;
Android/JVM artifacts target Java 17. Do not rely on Maven Local, `includeBuild`,
dependency substitution or a copied `:quackjs` module for the hosted migration.

## Backend changes

1. Compile a `PlayerProgram` from the exact downloaded player variant and its
   validated Faraday signature expression, `nClass` and timestamp. Retain `nClass`
   as data, not a generated JavaScript expression. The
   [usage example](../README.md#use) shows the API. With missing or unusable hints,
   attempt `PlayerProgram.compile(playerJs)` for landmark discovery; unsupported
   or ambiguous players must remain failures, with no JS-engine fallback.
2. Cache compiled programs by exact player variant and hints. Reuse programs
   across preloads, batches and single calls; each transform owns its mutable
   state. Preserve bounded download/program/failure caches, download single-flight,
   cooldowns, config invalidation and disposal generations. A failed preferred
   variant may fall back to a distinct validated supplied variant with hint-free
   discovery. Rethrow cancellation; do not turn it into a fallback attempt.
3. Keep existing approved-HTTPS-host validation, download size/time limits,
   redirect policy and bounded retries. QuackJS performs no networking. Run its
   synchronous CPU work off the UI thread and check cancellation around calls and
   between formats; an individual bounded transform cannot poll coroutine
   cancellation internally.
4. Pass decoded signature/n challenges directly to the API, then query-encode
   outputs exactly once. Preserve URL fragments and unrelated parameters. Memoize
   each distinct challenge per batch, including failures using key presence rather
   than nullable `getOrPut`. Keep the service's failure contract: null for failed
   single helpers, no URL for affected batch formats, unaffected direct formats
   still usable. Never substitute the original encrypted challenge on failure.
5. Remove the retired QuickJS/EJS engines, bundled solver scripts, native runtime
   loaders, dependency aliases and platform-specific embedding/JNI workarounds only
   after all production callers use QuackJS. Remove any old copied QuackJS module
   and its settings/dependency entries too. Keep meaningful service/security tests;
   replace engine-specific tests with backend contract coverage, not skipped tests.

If retaining binary-compatible `prewarmEjs()` and
`setPreprocessedPlayerCache(read, write)` APIs, document them as no-ops and never
invoke the storage callbacks. `preloadPlayerCode(playerUrl)` should download and
compile the Kotlin program. Ignore old EJS storage rather than deleting user data.

## Validate before publishing InnerTubeX

Using InnerTubeX's wrapper and required SDK/toolchain, run its existing checks:

```sh
./gradlew desktopTest testAndroidHostTest :harness:test ktlintCheck apiCheck assemble
```

Cover all five [pinned players](../fixtures/manifest.json), both hinted and
hint-free compilation, repeated batches and one-download reuse. Include failed
preferred downloads/unsupported scripts, supplied-variant fallback, cancellation,
disposal, malformed challenges, blank/unchanged/thrown results and output encoding.
Do not regenerate API baselines merely to silence a failed `apiCheck`.

Resolve Android and desktop **production** dependency graphs and inspect packaged
artifacts: QuackJS must select the matching Android/JVM variants, with no
QuickJS/EJS/native cipher payload. A test-only QuickJS oracle is separate from the
production graph. Test a fresh consumer against the hosted dependency without
Maven Local or project substitution before calling the release usable.

Publish and verify the migrated InnerTubeX version before changing Metrolist's pin.
Keep legacy shrinker/native packaging rules while its selected default InnerTubeX
still uses QuickJS; remove them only with the verified dependency migration.
Live playback, device tests, Apple linking and release-size claims require separate
verification. See [core verification and limits](verification.md) and
[license obligations](../THIRD_PARTY_NOTICES.md).
