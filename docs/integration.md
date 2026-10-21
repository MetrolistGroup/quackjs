# Local QuickJS-free consumer integration

All changes and publications are local. The original `/mnt/work/projects/innertubex` and `/mnt/work/projects/Metrolist-KMP` were read, never edited or committed. Patches were made and tested in private tracked-file copies, not against a clean upstream checkout.

## Deliverables and exact baselines

| Patch | Original HEAD | Baseline |
| --- | --- | --- |
| [`innertubex.patch`](../integrations/innertubex.patch) | `b5f8b6f1bc2b73ee9e3f3ac95b7eaa255368864e` | [`innertubex.json`](../integrations/baselines/innertubex.json), 171 files including the user's dirty tracked contents |
| [`metrolist-kmp.patch`](../integrations/metrolist-kmp.patch) | `a7f682e4de7a4d485d097166153115fe941b4a52` | [`metrolist-kmp.json`](../integrations/baselines/metrolist-kmp.json), 2,727 files including initialized submodule contents |

The manifests contain SHA-256 of every baseline file, upstream commit, dirty status, and private snapshot Git commit/tree. They contain no cookies, credentials or source contents. Build directories, `.git`, untracked files and secret-file formats were excluded. Source files with names such as `TokenProvider.kt` are code, not credentials, and are retained.

The InnerTubeX patch is **only the migration delta against the dirty snapshot**. Unrelated user edits are already in the baseline, not replayed by the patch. Its public API baseline is unchanged; `apiCheck` passes. Deleted legacy implementation files necessarily appear with their exact dirty baseline contents in the deletion diff.

The original InnerTubeX worktree continued changing after capture: `ExtractionLatencyTest.kt` and `SabrAudioStreamTest.kt` no longer match the captured hashes. Do not apply this patch blindly to today's original or regenerate its baseline from HEAD. Retained private copies on this host are:

- `/tmp/quackjs-consumers-mvhec420/innertubex-baseline`: exact baseline files, verified against the manifest.
- `/tmp/quackjs-consumers-mvhec420/innertubex`: tested migration, standalone Git baseline commit `242f62785164351ff40decf7e2c46906e7e16711`.
- `/tmp/quackjs-consumers-mvhec420/metrolist-full-baseline`: exact Metrolist files including submodules.
- `/tmp/quackjs-consumers-mvhec420/Metrolist-KMP`: tested companion migration.

These temporary paths are private local working material, not durable distribution artifacts. Preserve the exact dirty baseline privately if needed elsewhere. File hashes deliberately reject a different user snapshot. The Metrolist submodules are materialized as ordinary tracked files in the standalone baseline, without their `.git` metadata.

## Reproduce application without touching originals

Set `Q` to this QuackJS checkout and `WORK` to a new private directory outside the originals:

```sh
export Q=/path/to/quackjs
export WORK=/tmp/quackjs-consumer-reproduction
umask 077
mkdir "$WORK"

# On this host, preserve the captured dirty baseline rather than recapturing newer user edits.
cp -a /tmp/quackjs-consumers-mvhec420/innertubex-baseline "$WORK/innertubex"
cp -a /tmp/quackjs-consumers-mvhec420/metrolist-full-baseline "$WORK/Metrolist-KMP"

python3 "$Q/integrations/snapshot-consumer.py" "$WORK/innertubex" \
  --manifest "$Q/integrations/baselines/innertubex.json" --verify
python3 "$Q/integrations/snapshot-consumer.py" "$WORK/Metrolist-KMP" \
  --manifest "$Q/integrations/baselines/metrolist-kmp.json" --verify

# Initialize only the copies. The index allows exact preimage checks, without committing anything.
git -C "$WORK/innertubex" init
git -C "$WORK/innertubex" add .
git -C "$WORK/innertubex" apply --check --index "$Q/integrations/innertubex.patch"
git -C "$WORK/innertubex" apply --index "$Q/integrations/innertubex.patch"
git -C "$WORK/Metrolist-KMP" init
git -C "$WORK/Metrolist-KMP" add .
git -C "$WORK/Metrolist-KMP" apply --check --index "$Q/integrations/metrolist-kmp.patch"
git -C "$WORK/Metrolist-KMP" apply --index "$Q/integrations/metrolist-kmp.patch"
```

For a fresh capture of an original, use `python3 "$Q/integrations/snapshot-consumer.py" /path/to/original "$WORK/copy" --manifest "$WORK/observed.json"`, then verify the copy against the delivered manifest **before applying**. A mismatch requires reconciling a new baseline in a copy, not forcing this patch or changing the original. The helper copies current tracked bytes, including staged additions and unstaged edits, recursively reads initialized submodules, refuses tracked symlinks and writes nothing to the source. Verification compares the complete nonexcluded file set as well as every hash: an added tracked source file is a mismatch, not an accepted newer baseline.

Both patches were applied to fresh baseline copies with `git apply --check --index` and `git apply --index`; their staged trees exactly matched the tested migrations. [`application-results.json`](../integrations/application-results.json) records patch digests and resulting tree IDs. Avoid `git archive` for the Metrolist submodule snapshot: libopus export attributes omit files and convert line endings. Use the verified filesystem baseline instead.

## Build and resolve actual local publications

Prerequisites: the checked-in Gradle wrappers, Android SDK (API 37), JDK 21 for QuackJS/InnerTubeX, and Temurin 25 plus the existing Android/native build prerequisites for Metrolist. Metrolist's submodule source must be present. Credentials and `local.properties` are not needed for these fixture/debug checks; `ANDROID_HOME` supplies the SDK location.

Run **in order**, not concurrently with republishing the same snapshot coordinates:

```sh
cd "$Q"
./gradlew --no-daemon jvmTest testAndroidHostTest assemble publishToMavenLocal --console=plain
python3 integrations/verify-quackjs-publication.py
./gradlew --no-daemon -I integrations/verify-runtime.init.gradle \
  :verifyCipherRuntime --no-configuration-cache --console=plain
python3 -m unittest discover -s integrations -p 'test_*.py'

python3 "$Q/integrations/verify-vendored-quackjs.py" "$WORK/innertubex"
cd "$WORK/innertubex"
./gradlew --no-daemon -PGROUP=com.github.MetrolistGroup.innertubex -PVERSION_NAME=v0.10.0-quackjs-SNAPSHOT \
  -I "$Q/integrations/verify-runtime.init.gradle" :verifyCipherRuntime \
  desktopTest testAndroidHostTest ktlintCheck apiCheck assemble publishToMavenLocal :harness:test \
  --no-configuration-cache --console=plain

# Independent end-to-end consumer: no includeBuild or project dependency substitution.
cd "$Q"
./gradlew --no-daemon -p integrations/published-consumer test --console=plain

cd "$WORK/Metrolist-KMP"
./gradlew --no-daemon -PuseMavenLocalInnerTubeX \
  -PinnerTubeXVersion=v0.10.0-quackjs-SNAPSHOT -Pmetrolist.sourceRevision= \
  -I "$Q/integrations/verify-runtime.init.gradle" :shared:verifyCipherRuntime \
  :shared:desktopTest :shared:testAndroidHostTest :androidApp:assembleDebug :desktopApp:assemble \
  --no-configuration-cache --console=plain
./gradlew --no-daemon -PuseMavenLocalInnerTubeX \
  -PinnerTubeXVersion=v0.10.0-quackjs-SNAPSHOT :desktopApp:ktlintKotlinScriptCheck --console=plain
```

The QuackJS root coordinate is **`meow.nyx:quackjs:0.1.0-SNAPSHOT`**. It publishes Android AAR, JVM JAR, Linux x64 and both existing iOS klibs with KMP module metadata. Android and JVM classes target Java 17 (major 61), while compilation uses JDK 21. AGP is `com.android.kotlin.multiplatform.library:9.4.1`, Kotlin is `2.4.20`, Android compile/min SDK are 37/23, matching InnerTubeX. Production variants depend only on Kotlin stdlib (and its usual transitive annotations). QuickJS exists only in QuackJS's JVM **oracle tests**, not in a publication.

The locally verified InnerTubeX coordinate is **`com.github.MetrolistGroup.innertubex:innertubex:v0.10.0-quackjs-SNAPSHOT`**. The explicit `GROUP` override matches the real KMP group consumed by Metrolist; InnerTubeX's default local group differs from JitPack's exposed KMP group. These are actual Maven Local artifacts, not claimed remote releases. The companion patch neither bumps the app version nor changes its remote dependency pin. Existing `-PuseMavenLocalInnerTubeX` resolves InnerTubeX and its same-group/version QuackJS module transitively; do not combine it with `-PuseLocalInnerTubeX`.

**Default build and CI/JitPack:** there is no remotely published `meow.nyx:quackjs:0.1.0-SNAPSHOT` to assume. The InnerTubeX patch therefore includes the exact seven QuackJS common source files as a `:quackjs` KMP module and uses `implementation(project(":quackjs"))`. It builds without Maven Local, a separate checkout, an opt-in property or prepublication. The existing CI and JitPack commands build and publish the module automatically. The exact CI command `./gradlew --no-daemon clean allTests ktlintCheck apiCheck assemble publishToMavenLocal --no-build-cache --no-configuration-cache --console=plain` passed with no QuackJS property or prepublication. Its group/version inherit InnerTubeX's publication properties, so published consumers depend on `com.github.MetrolistGroup.innertubex:quackjs:v0.10.0-quackjs-SNAPSHOT` and its Android/desktop/iOS variants, not an unreachable standalone snapshot. The standalone QuackJS publication above remains independently verified but is not a dependency of this self-contained migration.

`quackjs/UPSTREAM.json` records source hashes and the source commit. This source copy is required only until there is an actual usable remote QuackJS release. The final seven core files at `25e10da`, including the selected 32-lane performance patches, have been synchronized into the private InnerTubeX copy, its provenance and patch regenerated, and the combined library/consumer/app verification rerun successfully. Future core changes require resynchronizing `src/commonMain/kotlin/meow/nyx/quackjs/*.kt`, updating provenance, regenerating the patch against the same captured baseline, and rerunning these commands. Do not ship a different core from the one verified here. `python3 integrations/verify-vendored-quackjs.py /path/to/private/innertubex` fails on stale or extra core files or stale provenance hashes. The module's generated `quackjs/api/desktop/quackjs.api` is checked by the existing `apiCheck` workflow too. For an intentional core API change, run `./gradlew :quackjs:desktopApiDump`, review its diff, then run `apiCheck` separately; do not disable validation or regenerate it automatically during checks.

The init script resolves Android/JVM **production** configurations and fails on QuickJS components. It confirms Android selects `quackjs-android`/AAR and desktop selects the standalone `quackjs-jvm` or self-contained InnerTubeX `quackjs-desktop`/JAR. It intentionally uses `--no-configuration-cache` and an explicitly qualified task path such as `:verifyCipherRuntime`, not the unqualified selector that also invokes unrelated subproject tasks.

## Migration behavior

InnerTubeX now compiles immutable `PlayerProgram`s using validated Faraday `signatureExpression`, `nClass` and timestamp hints. If hints are missing or unsupported, it attempts Kotlin landmark discovery. It never executes EJS, QuickJS, a full player initializer, or a JS-engine fallback. The internal config retains `nClass` directly instead of generating a JavaScript expression. The supplied player URL is validated before canonical/Faraday selection and is retained unchanged for fallback: if the preferred variant fails to download or compile (or is cooling down), a distinct supplied variant is tried with hint-free Kotlin discovery. Cancellation is rethrown without trying another variant, and both candidates respect disposal generations and the same bounded caches.

The patch deletes legacy engines, parser/solver fallbacks, native thread implementations, resource loaders, bundled EJS scripts, native embedding generation, dependency aliases and the Android-host JNI substitution. Retired implementation-specific tests were replaced with service/backend tests, not skipped. Remote configuration refresh/security tests remain intact; legacy remote timestamp parsing does not execute downloaded code.

Preserved behavior includes 8 MiB bounded/nonredirecting player downloads, approved HTTPS endpoints and media authorities, three bounded transient retries per source URL (at most the preferred and supplied variants), download single-flight and cancellation recovery, raw-player cache size one, compiled-program/failure caches bounded to four, cooldowns and config-change retry, and disposal generations preventing speculative preloads from recreating caches. CPU work is offloaded from the caller and checks cancellation around bounded compilation/transforms and between formats. The synchronous stdlib-only core cannot poll coroutine cancellation inside an individual bounded transform.

Malformed signatures/n challenges and unsupported players keep the existing caller failure contract: single helpers return null, affected batch formats lose their URL, and unaffected direct formats remain usable. Outputs are query-encoded once; fragments and unrelated query fields remain intact. Encoded n inputs are decoded before transformation. Batch memoization uses key presence rather than nullable `getOrPut`, so successful, thrown, blank and unchanged results are each computed only once per distinct challenge per batch. No failed challenge is substituted into a supposedly deciphered URL. Validation and length limits apply before returning URLs.

`prewarmEjs()` and `setPreprocessedPlayerCache(read, write)` remain documented binary-compatible **no-ops**, including never invoking the storage callbacks. `preloadPlayerCode(playerUrl)` downloads and compiles the Kotlin program. Existing app EJS storage is ignored, not deleted or mutated. No background native runtime or idle-release thread is needed.

Metrolist's companion documents the local publication workflow. Its default `innerTubeXVersion=b5f8b6f1bc` still uses QuickJS, so the original ProGuard keep rule, native stripping map and native allowlist are retained byte-for-byte. Removing those before the default pin migrates breaks the default desktop release. They do not introduce QuickJS into the selected QuackJS-free dependency graph. Remove the legacy rules only together with a future verified default dependency migration. The historical APK size table remains identified as historical; no fresh release-size claim is made. A full verification run also exposed an existing backend-test leak: the no-counterpart stream test injected only the authoritative lookup, leaving fallback search and song lookup on the global repository. The companion injects empty/null fallback results too, preserving every assertion and preventing real-network-dependent results. No application UI or production behavior is changed.

## Verification and limits

See [`verification-results.json`](../integrations/verification-results.json) for the integration lane's recorded commands, outcomes and test counts, and [final combined verification](verification.md#final-combined-verification) for the rerun against the optimized core. Lane logs are retained under `/tmp/quackjs-integration-logs/`; the latest final logs are `/tmp/quackjs-perf32/combined-*.log`. The initial implementation's logs remain under `/tmp/quackjs-combined-*.log`. They are not shipped as sanitized user-facing telemetry.

Targeted regressions reproduced both subsequent review findings before the fix: a valid supplied variant returned null after a preferred 404, and 50 repeated failing signature/n pairs executed 100 transforms instead of two. The fixed tests cover preferred HTTP/unsupported-script failures, IAS/non-IAS supplied variants, hints and hint-free discovery, reuse across preload/batch/single APIs, cancellation without fallback, and thrown/blank/unchanged batch results with actual transform-function resolution counts in isolated runtimes.

The independent published-consumer test uses all four original fixtures (`4f38b487`, `10733253`, `eb27e5fd`, `5008be0a`), Node-derived goldens and the public InnerTubeX service. It exercises hinted and hint-free compilation, repeated batches, single helpers, callback no-ops and one-download reuse. It does not link QuackJS or InnerTubeX source projects.

QuackJS is deliberately a bounded dispatcher/table-player subset, **not general JavaScript**. These checks do not establish compatibility with every archived player or future rotations. Unsupported syntax must remain an explicit core failure and a fail-closed consumer result. No credentials, live YouTube playback, device/emulator tests, iOS execution/linking, desktop release shrinker/package, hosted publication, APK-size benchmark or performance benchmark were exercised in this integration lane. Linux-host assembly compiled the available iOS sources/metadata but cannot run Apple simulator tests. No UI tests were added.
