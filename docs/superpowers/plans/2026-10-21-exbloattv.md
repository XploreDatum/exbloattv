# ExBloat TV Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A sideloadable Android phone app (`dev.mntl.exbloattv`) that finds an Android TV on the home Wi-Fi, connects over ADB, lists and categorises every installed app, lets the person choose what to keep, cleans up and speeds up the TV, swaps the launcher for FLauncher, and can undo everything.

**Architecture:** Single-module Android app. A pure-Kotlin core (parsers, catalog/categoriser, safety guard, cleanup planner, runner, TV state file, undo) sits behind an `AdbClient` interface so it is fully unit-testable with a fake; a dadb-backed implementation talks to the real TV. Jetpack Compose + Material 3 Expressive UI drives a resumable cleanup run hosted in a foreground service.

**Tech Stack:** Kotlin 2.x, Jetpack Compose (BOM), Material 3 (expressive APIs, opt-in), Navigation Compose, kotlinx.serialization, kotlinx.coroutines, dadb (`dev.mobile:dadb`), OkHttp (FLauncher download), JUnit4 + kotlinx-coroutines-test + Truth.

**Spec:** `docs/superpowers/specs/2026-10-21-exbloattv-design.md` (read it first; §12a overrides earlier sections where they differ). Mockup: `docs/superpowers/specs/mockups/material-v1.html`.

**Execution order:** 1 → 5 → 2 → 3 → 4 → 6 → 7 → 8 → 9 → 10 → 11 → 12 → 13 → 14 → 15 (Task 5 defines `AdbClient` + `FakeAdbClient`, which Task 2 uses).

## Global Constraints

- applicationId / namespace `dev.mntl.exbloattv`; minSdk **26**, targetSdk/compileSdk **36**; Java 17 toolchain.
- Android SDK at `/opt/homebrew/share/android-commandlinetools` (platforms 35, 36; build-tools 35.0.0, 36.0.0). Gradle is **not** installed globally — use the wrapper. Disk is ~22 GB free: no emulator system-image downloads without asking.
- No jargon in normal UI copy ("ADB", "package", "pm", "PSS" only inside Advanced/details).
- Never run a command that changes the real TV (192.168.10.3) without the owner's explicit go-ahead. Read-only commands are allowed.
- Never send anything off the phone except the FLauncher download. No analytics.
- FLauncher: `https://gitlab.com/api/v4/projects/26632151/packages/generic/flauncher/0.18.0/flauncher-0.18.0.apk`, SHA-256 `376fc528c880cdf7c7c04b2d3708c69f911144498218babb6300808b772af32e`.
- TV state file: `/data/local/tmp/exbloattv/state.json`; lock expiry 10 min; atomic write via temp file + `mv`.
- Disable = `pm disable-user --user 0 <pkg>`; fallback `pm uninstall -k --user 0 <pkg>`; restore = `pm enable <pkg>` / `cmd package install-existing <pkg>`; delete user app = `pm uninstall <pkg>` (only when the Review switch "Also delete switched-off apps I installed" is on).
- Never call `cmd package set-home-activity`.
- Every mutating step is verified by re-reading TV state; only verified changes are counted or logged as verified.

## Review Focus

1. **APK paths containing `=`** (e.g. `/data/app/me.efesser.flauncher-tblkRbh5KgNCj6fD9LrAxQ==/base.apk=me.efesser.flauncher`) must parse to package `me.efesser.flauncher` → test in Task 2.
2. **`pm` prints `Success`/`new state` but nothing changed, or prints an exception with exit code 0** → runner must decide from re-read state, not output → test in Task 8.
3. **Connection drops mid-run (TV sleeps, Wi-Fi blips)** → runner reconnects up to 3× with backoff, resumes from the first unverified step, never repeats a verified step → test in Task 8.
4. **Second phone opens a TV mid-run / after another phone cleaned it** → lock respected, already-off apps show as off, Undo covers the other phone's changes → tests in Tasks 6 and 10.
5. **Launcher swap leaves no working HOME (setupwraith or nothing wins)** → automatic rollback before reboot and after reboot → test in Task 9.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew(+wrapper)
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/assets/catalog.json                    # Task 4
app/src/main/java/dev/mntl/exbloattv/
  ExBloatApp.kt, MainActivity.kt                     # Task 1, 14
  adb/AdbClient.kt          # interface + errors      Task 5
  adb/DadbAdbClient.kt      # dadb impl + key mgmt     Task 5
  tv/Model.kt               # TvInfo, InstalledPackage, Inventory, stats   Task 2
  tv/Parsers.kt             # pure parsers                                   Task 2
  tv/TvInspector.kt         # runs read commands → Inventory                 Task 2
  safety/SafetyGuard.kt                                                     Task 3
  catalog/Catalog.kt, catalog/Categorizer.kt                                 Task 4
  state/TvState.kt, state/TvStateStore.kt                                    Task 6
  cleanup/Choices.kt, cleanup/Step.kt, cleanup/CleanupPlanner.kt             Task 7
  cleanup/CleanupRunner.kt, cleanup/RunProgress.kt                           Task 8
  cleanup/LauncherSwap.kt, cleanup/ApkDownloader.kt                          Task 9
  cleanup/Undo.kt                                                            Task 10
  discovery/SubnetScanner.kt, discovery/Discovery.kt                         Task 11
  apps/AppLabelResolver.kt                                                   Task 12
  ui/theme/*, ui/components/*                                                Task 13
  ui/screens/*, ui/nav/*, service/CleanupService.kt, data/TvRepository.kt    Task 14
app/src/test/java/dev/mntl/exbloattv/...            # mirrors main
app/src/test/resources/mibox4-android9/              # copy of /fixtures/mibox4-android9 (Task 2)
```

---

### Task 1: Project scaffold, theme seed, build

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle/wrapper/*`, `gradlew`, `gradlew.bat`, `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/dev/mntl/exbloattv/ExBloatApp.kt`, `MainActivity.kt`, `app/src/main/res/values/strings.xml` (`app_name` = "ExBloat TV"), launcher icon (adaptive, simple TV-with-sparkle vector)
- Test: `app/src/test/java/dev/mntl/exbloattv/SmokeTest.kt`

**Interfaces:**
- Produces: buildable app; `release` build type signed from `local.properties` keys `exbloat.storeFile`, `exbloat.storePassword`, `exbloat.keyAlias`, `exbloat.keyPassword` (keystore generated with `keytool` into `keystore/exbloattv.jks`, git-ignored).
- Manifest permissions: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `NEARBY_WIFI_DEVICES` (with `usesPermissionFlags="neverForLocation"`), `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `POST_NOTIFICATIONS`; `android:usesCleartextTraffic="false"`.

- [ ] **Step 1:** Bootstrap the Gradle wrapper without a global Gradle (download the Gradle 8.x/9.x bin zip into the scratch dir, run `gradle wrapper` from it, delete the zip). Verify: `./gradlew --version` prints the version.
- [ ] **Step 2:** Write `SmokeTest.smoke()` asserting `1 + 1 == 2`; add deps from the Tech Stack (pin exact versions in the catalog; Material 3 version must expose `MaterialExpressiveTheme`, `MaterialShapes`, `LinearWavyProgressIndicator`).
- [ ] **Step 3:** `MainActivity` shows a placeholder Compose screen in `MaterialExpressiveTheme` with dynamic colour on API 31+, else a scheme from seed `#386a20` (light + dark). Edge-to-edge.
- [ ] **Step 4:** Verify: `./gradlew test assembleDebug` → BUILD SUCCESSFUL; `app/build/outputs/apk/debug/app-debug.apk` exists.
- [ ] **Step 5:** Commit `chore: scaffold ExBloat TV app`.

---

### Task 2: TV model, parsers, inspector

**Files:**
- Create: `tv/Model.kt`, `tv/Parsers.kt`, `tv/TvInspector.kt`
- Copy: `fixtures/mibox4-android9/*` → `app/src/test/resources/mibox4-android9/`
- Test: `tv/ParsersTest.kt`, `tv/TvInspectorTest.kt`

**Interfaces:**
- Consumes: `AdbClient` (Task 5) — define the interface in Task 5 first if executing out of order; `TvInspector` only needs `shell`.
- Produces (all model classes `@Serializable`; Task 6 stores `AnimationScales` and Task 14 caches `Inventory`):
```kotlin
data class TvInfo(val serial: String, val model: String, val brand: String, val manufacturer: String, val androidRelease: String, val sdkInt: Int)
data class InstalledPackage(val name: String, val apkPath: String, val installer: String?, val uid: Int, val isSystem: Boolean, val enabled: Boolean)
data class MemoryStats(val totalKb: Long, val freeKb: Long, val usedPssKb: Long)
data class StorageStats(val totalKb: Long, val usedKb: Long, val availableKb: Long)
data class AnimationScales(val window: Float, val transition: Float, val animator: Float)
data class Inventory(val info: TvInfo, val packages: List<InstalledPackage>, val launchable: Set<String>,
                     val homePackages: List<String>, val resolvedHome: String?, val memory: MemoryStats,
                     val storage: StorageStats, val animations: AnimationScales, val backgroundOps: Map<String, String>)
object Parsers {
  fun pmListLines(text: String): List<Triple<String, String, Pair<String?, Int>>> // (pkg, apkPath, installer to uid)
  fun packageNames(text: String): Set<String>          // `package:x` lines
  fun queryActivitiesPackages(text: String): List<String>   // distinct `packageName=` under each `ActivityInfo:` in order
  fun resolveActivityBrief(text: String): String?       // "me.efesser.flauncher/.MainActivity" or null
  fun meminfo(text: String): MemoryStats
  fun df(text: String): StorageStats
  fun getprop(text: String): Map<String, String>
  fun resumedPackage(text: String): String?             // from mResumedActivity line
  fun appop(text: String, op: String): String?          // "ignore"/"allow"/null
  fun isPmError(text: String): Boolean                  // "Exception occurred" / "Error:" / "Failure"
}
class TvInspector(private val adb: AdbClient) { suspend fun read(): Inventory }
```

- [ ] **Step 1: Write failing tests** (fixtures from `mibox4-android9/`):
  - `pmListLines` on `pm_list_all.txt` → 96 entries; entry for `me.efesser.flauncher` has apkPath `/data/app/me.efesser.flauncher-tblkRbh5KgNCj6fD9LrAxQ==/base.apk`, installer `null`, uid `10091`. *(Review Focus 1)*
  - `packageNames(pm_list_disabled.txt)` has 27 entries incl. `com.google.android.tvlauncher`, `com.google.android.tungsten.setupwraith`.
  - `queryActivitiesPackages(query_home.txt)` starts with `me.efesser.flauncher` and contains `com.android.tv.settings`.
  - `resolveActivityBrief(resolve_home.txt)` == `me.efesser.flauncher/.MainActivity`.
  - `meminfo(dumpsys_meminfo.txt)` == `MemoryStats(2034840, 1287694, 475775)`; `meminfo(dumpsys_meminfo_before_cleanup.txt).usedPssKb` == `943414`.
  - `df(df_data.txt)` == `StorageStats(5292456, 3052996, 2190308)`.
  - `getprop(getprop.txt)["ro.product.model"]` == `MIBOX4`, `["ro.build.version.sdk"]` == `28`.
  - `resumedPackage(resumed_activity.txt)` == `me.efesser.flauncher`.
  - `appop(appops_netflix.txt, "RUN_ANY_IN_BACKGROUND")` == `ignore`; `appop(..., "CAMERA")` == null.
  - `isPmError(pm_uninstall_unknown.txt)` true; `isPmError(pm_disable_already_disabled.txt)` false.
  - `TvInspectorTest`: fake adb answering each command with the fixture → `Inventory.packages.size == 96`, `isSystem` true for `mitv.service` (in `-s` list), `enabled` false for `com.google.android.tvlauncher`, `info.model == "MIBOX4"`; serial falls back to `ro.boot.serialno` then `"unknown-" + model` when getprop lacks `ro.serialno` (fixture has it stripped).
- [ ] **Step 2:** Run `./gradlew test --tests '*Parsers*' --tests '*TvInspector*'` → FAIL (unresolved).
- [ ] **Step 3:** Implement. `pmListLines`: strip `package:`, split at the **last** `.apk=` (path = left + `.apk`), then tokens `installer=` and `uid:`. `TvInspector.read()` commands (exact strings, the fake keys on them): `getprop`, `pm list packages -f -i -U`, `pm list packages -s`, `pm list packages -d`, `cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER`, same with `android.intent.category.LAUNCHER`, same with `android.intent.category.HOME`, `cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME`, `dumpsys meminfo`, `df /data`, `settings get global window_animation_scale` / `transition_animation_scale` / `animator_duration_scale`, and `cmd appops get <pkg> RUN_ANY_IN_BACKGROUND` only for launchable non-system packages.
- [ ] **Step 4:** Run tests → PASS.
- [ ] **Step 5:** Commit `feat: TV inventory parsers and inspector`.

---

### Task 3: SafetyGuard

**Files:** Create `safety/SafetyGuard.kt`; Test `safety/SafetyGuardTest.kt`

**Interfaces:**
- Produces: `object SafetyGuard { val NEVER: List<String>; fun isProtected(pkg: String, activeLauncher: String? = null, replacementVerified: Boolean = false): Boolean; fun isRemoteInput(pkg: String): Boolean }`
- Globs: exactly the spec §7 list (`*` = any chars, may be leading/trailing/infix).

- [ ] **Step 1: Failing tests:** every literal in §7 is protected; globs: `com.android.providers.media.module`, `com.google.android.inputmethod.latin`, `com.droidlogic.ble`, `com.tcl.tvinput`, `com.android.settings.intelligence` protected; `isRemoteInput` true for `com.google.android.tv.remote.service`, `com.droidlogic.ble`, `*.inputmethod.*`, `*.autopair`, `*.globalkeyoverlay`. None of these is protected: every package in spec §12 categories Google extras, Manufacturer extras, Ads & tracking, Sideload tools, Streaming, Music, Features, and `com.google.android.tungsten.setupwraith`. Active launcher `com.google.android.tvlauncher` protected when `replacementVerified=false`, not protected when `true`.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Implement (glob → anchored regex, cached). **Step 4:** Run → PASS. **Step 5:** Commit `feat: never-disable safety guard`.

---

### Task 4: Catalog + Categorizer

**Files:** Create `app/src/main/assets/catalog.json`, `catalog/Catalog.kt`, `catalog/Categorizer.kt`; Test `catalog/CategorizerTest.kt` (loads the real asset file from `src/main/assets`).

**Interfaces:**
```kotlin
enum class Category { STREAMING, MUSIC, LIVE_TV, FEATURES, GOOGLE_EXTRAS, MANUFACTURER, ADS_TRACKING, SIDELOAD_TOOLS, OTHER, SYSTEM, LAUNCHER }
@Serializable data class CatalogEntry(val pattern: String, val category: Category, val name: String,
  val description: String = "", val defaultKeep: Boolean, val locked: Boolean = false, val musicApp: Boolean = false, val brand: String? = null)
class Catalog(entries: List<CatalogEntry>) { fun match(pkg: String): CatalogEntry?  // exact beats glob; longest glob wins
  companion object { fun parse(json: String): Catalog } }
data class CategorizedApp(val pkg: InstalledPackage, val category: Category, val name: String, val description: String,
  val defaultKeep: Boolean, val locked: Boolean, val musicApp: Boolean, val launchable: Boolean)
class Categorizer(private val catalog: Catalog) { fun categorize(inv: Inventory): List<CategorizedApp> }
```
Rules: catalog match wins (except anything `SafetyGuard.isProtected` → SYSTEM); unknown non-system → OTHER keep; unknown system → SYSTEM; the stock launchers + setupwraith → LAUNCHER (never shown as a switch; handled by the launcher swap). Default category for ADS_TRACKING: `defaultKeep=false, locked=true`. Friendly descriptions are one plain sentence (e.g. katniss: "Mic button on the remote stops working").

- [ ] **Step 1: Failing tests:** each spec §12 package lands in its listed category (table-driven, all of them); `com.netflix.ninja` name "Netflix"; `com.google.android.youtube.tvmusic` `musicApp=true`; `tv.alphonso.alphonso_eula` locked & defaultKeep=false; `com.google.android.videos` defaultKeep=false; `com.google.android.katniss` FEATURES defaultKeep=true; an unknown user package `com.example.foo` → OTHER keep; unknown system package `com.vendor.bar` → SYSTEM; `com.droidlogic.ble` → SYSTEM even if a catalog glob would match it; every entry in `catalog-seed/*.list` (except never-disable) is matched by the catalog; catalog JSON parses with no duplicate patterns.
- [ ] **Step 2:** Run → FAIL. **Step 3:** Write `catalog.json` (≈150 entries: all §12 + android-tv-tune lists + common Indian/global streaming apps the builder knows: Disney+, Hulu, HBO Max, JioTV, SunNXT, Aha, Voot, Eros, Crunchyroll, Twitch, Pluto, Tubi, Stremio, SmartTube…) and implement. **Step 4:** PASS. **Step 5:** Commit `feat: app catalog and categoriser`.

---

### Task 5: AdbClient + dadb implementation

**Files:** Create `adb/AdbClient.kt`, `adb/DadbAdbClient.kt`, `adb/AdbKeys.kt`; Test `adb/FakeAdbClient.kt` (test source set, shared by later tasks), `adb/AdbErrorsTest.kt`

**Interfaces:**
```kotlin
data class ShellResult(val output: String, val exitCode: Int)
interface AdbClient : java.io.Closeable {
  val host: String
  suspend fun shell(cmd: String): ShellResult
  suspend fun install(apk: java.io.File): ShellResult
  suspend fun push(local: java.io.File, remote: String)
  suspend fun pull(remote: String, local: java.io.File)
  suspend fun reboot()
}
sealed class AdbError(msg: String) : Exception(msg) { class Unauthorized : AdbError("waiting for approval on TV"); class Refused : AdbError("debugging off"); class Timeout : AdbError("timeout"); class Disconnected : AdbError("connection lost") }
interface AdbConnector { suspend fun connect(host: String, port: Int = 5555, authTimeoutMs: Long = 60_000): AdbClient }
class DadbConnector(context: Context) : AdbConnector   // keys via AdbKeys
object AdbKeys { fun loadOrCreate(dir: java.io.File, name: String): /* dadb AdbKeyPair */ Any }  // name "exbloattv@${Build.MODEL}"
```
`FakeAdbClient(responses: MutableMap<String, String>, val log: MutableList<String>)` — returns mapped output, records every command, supports `failNext(cmd, AdbError)` and scripted state changes via `onCommand: (String) -> Unit`.

- [ ] **Step 1: Failing test:** `AdbErrorsTest` maps exceptions to `AdbError`: `java.net.ConnectException` → Refused, `SocketTimeoutException` → Timeout, dadb auth-pending/IOException during handshake → Unauthorized, `EOFException`/broken pipe mid-session → Disconnected (`fun mapAdbException(t: Throwable): AdbError`).
- [ ] **Step 2:** FAIL. **Step 3:** Implement; dadb calls run on `Dispatchers.IO`; key files `adbkey`/`adbkey.pub` in `filesDir/adb`. **Step 4:** PASS.
- [ ] **Step 5: Live read-only check** (allowed): small instrumented or JVM `main` that connects to `192.168.10.3:5555` and runs `getprop ro.product.model` → `MIBOX4`. Note: the TV will show an approval dialog for the new key — tell the owner before running.
- [ ] **Step 6:** Commit `feat: ADB client over dadb`.

---

### Task 6: TV state file (restore point, change log, lock)

**Files:** Create `state/TvState.kt`, `state/TvStateStore.kt`; Test `state/TvStateStoreTest.kt`

**Interfaces:**
```kotlin
@Serializable data class RestorePoint(val takenAt: Long, val byPhone: String, val enabled: List<String>, val disabled: List<String>,
  val userInstalled: List<String>, val resolvedHome: String?, val animations: AnimationScales, val backgroundOps: Map<String, String>)
@Serializable enum class ChangeKind { DISABLE, ENABLE, REMOVE_FOR_USER, INSTALL_EXISTING, UNINSTALL, ANIMATIONS, APPOPS, INSTALL_APK, LAUNCHER_SWAP }
@Serializable data class Change(val id: String, val kind: ChangeKind, val pkg: String?, val from: String, val to: String,
  val byPhone: String, val at: Long, val verified: Boolean)
@Serializable data class Lock(val byPhone: String, val at: Long)
@Serializable data class TvState(val version: Int = 1, val serial: String, val original: RestorePoint? = null,
  val latest: RestorePoint? = null, val changes: List<Change> = emptyList(), val lock: Lock? = null)
class TvStateStore(private val adb: AdbClient, private val now: () -> Long = System::currentTimeMillis) {
  suspend fun load(serial: String): TvState                      // missing file → TvState(serial)
  suspend fun save(state: TvState)                              // mkdir -p; write .tmp via base64 shell; mv
  suspend fun acquireLock(serial: String, phone: String): Result<TvState>   // fails if another phone holds a lock < 10 min old
  suspend fun releaseLock(serial: String, phone: String)
  suspend fun appendChange(serial: String, change: Change)
  suspend fun setOriginalIfAbsent(serial: String, rp: RestorePoint)
}
```
- [ ] **Step 1: Failing tests** (FakeAdbClient emulating a tiny file system for `cat`, `mkdir -p`, write, `mv`): round-trip save/load; `setOriginalIfAbsent` never overwrites; lock held by "Pixel 8" 3 min ago blocks "Galaxy S24"; lock 11 min old is taken over; `appendChange` keeps order; corrupted JSON → load returns `TvState(serial)` and the bad file is kept as `state.json.bad-<ts>` *(Review Focus 4)*.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. Writing: `echo <base64> | base64 -d > state.json.tmp && mv state.json.tmp state.json` (Android 9 toybox has `base64`; chunk at 32 KB). **Step 4:** PASS. **Step 5:** Commit `feat: shared TV state file with lock and change log`.

---

### Task 7: Choices + CleanupPlanner

**Files:** Create `cleanup/Choices.kt`, `cleanup/Step.kt`, `cleanup/CleanupPlanner.kt`; Test `cleanup/CleanupPlannerTest.kt`

**Interfaces:**
```kotlin
data class Choices(val keep: Map<String, Boolean>, val fasterAnimations: Boolean = true, val stopBackground: Boolean = true,
  val clearCaches: Boolean = true, val newHomeScreen: Boolean = true, val deleteUserApps: Boolean = false)
sealed interface Step { val label: String
  data class Disable(val pkg: String) : Step; data class Enable(val pkg: String) : Step
  data class InstallExisting(val pkg: String) : Step; data class Uninstall(val pkg: String) : Step
  data class SetAnimations(val scale: Float) : Step; data class RestrictBackground(val pkg: String) : Step
  data object TrimCaches : Step; data object SwapLauncher : Step; data object RebootAndVerify : Step }
object CleanupPlanner { fun plan(apps: List<CategorizedApp>, inv: Inventory, choices: Choices, removedForUser: Set<String>): List<Step> }
fun defaultChoices(apps: List<CategorizedApp>): Choices   // keep = currently-enabled ? defaultKeep : false … see test
```
Order: Disable/Uninstall batch → Enable/InstallExisting batch → RestrictBackground → SetAnimations → TrimCaches → SwapLauncher → RebootAndVerify. `RebootAndVerify` only if SwapLauncher present or ≥1 disable. Skip no-ops. Filter through `SafetyGuard.isProtected` (protected → dropped, never planned).

- [ ] **Step 1: Failing tests:** using Mi Box *pre-cleanup* inventory (`pm_list_f_before_cleanup.txt` + enabled list) and default choices → plan disables exactly the off-by-default set (assert it contains `com.miui.tv.analytics`, `tv.alphonso.alphonso_eula`, `com.google.android.videos`, `com.mitv.tvhome.atv`; does **not** contain `com.google.android.katniss`, `com.netflix.ninja`, any `SafetyGuard`-protected pkg); user app switched off → `Disable` when `deleteUserApps=false`, `Uninstall` when true; a currently-disabled app switched on → `Enable`; in `removedForUser` and switched on → `InstallExisting`; `RestrictBackground` only for kept STREAMING/LIVE_TV non-music apps; `defaultChoices` on the *current cleaned* inventory marks currently-disabled apps as off; order matches the rule above; empty diff + no speed options → empty plan.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat: cleanup planner`.

---

### Task 8: CleanupRunner (verify, retry, resume)

**Files:** Create `cleanup/CleanupRunner.kt`, `cleanup/RunProgress.kt`; Test `cleanup/CleanupRunnerTest.kt`

**Interfaces:**
```kotlin
enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }
data class StepState(val step: Step, val status: StepStatus, val message: String? = null)
data class RunProgress(val steps: List<StepState>, val fraction: Float, val waitingForTv: Boolean = false)
class CleanupRunner(private val connect: suspend () -> AdbClient, private val store: TvStateStore,
  private val launcher: LauncherSwap, private val phone: String, private val serial: String) {
  fun run(plan: List<Step>, before: Inventory): kotlinx.coroutines.flow.Flow<RunProgress>
  // first action after taking the lock: build RestorePoint from `before`, store.setOriginalIfAbsent(...), save as `latest`
  suspend fun retry(index: Int); suspend fun skip(index: Int)
}
```
Verification per step: Disable → pkg in `pm list packages -d` **or** absent from `pm list packages` (removed for user, after fallback); Enable → in `-e`; InstallExisting → present; Uninstall → absent from `pm list packages`; SetAnimations → three `settings get` equal; RestrictBackground → `cmd appops get <pkg> RUN_ANY_IN_BACKGROUND` == ignore; TrimCaches → command exit 0. Disable fallback: if not verified after `disable-user`, run `pm uninstall -k --user 0` and log `REMOVE_FOR_USER`. Each verified step appends a `Change` with its `from` state. Lock acquired at start, released at end/failure.

- [ ] **Step 1: Failing tests (FakeAdbClient scripted):** happy path all DONE + changes appended in order; first run sets `original` and `latest`, second run updates only `latest`; `pm disable-user` returns "new state: disabled-user" but `-d` doesn't list it → fallback runs → verified as REMOVE_FOR_USER *(Review Focus 2)*; both fail → FAILED with message, run continues with next step; already-disabled pkg → DONE without a `disable-user` call and no Change; `AdbError.Disconnected` on step 3 → reconnect (connect called again) and step 3 retried, steps 1–2 not re-run; 4th consecutive disconnect → `waitingForTv=true` and run pauses *(Review Focus 3)*; lock held by another phone → run emits FAILED on a synthetic first step "Another phone is cleaning this TV" and makes no changes *(Review Focus 4)*; `retry(i)` re-runs only that step.
- [ ] **Step 2:** FAIL. **Step 3:** Implement (backoff 2 s, 5 s, 10 s). **Step 4:** PASS. **Step 5:** Commit `feat: verified, resumable cleanup runner`.

---

### Task 9: LauncherSwap + ApkDownloader

**Files:** Create `cleanup/LauncherSwap.kt`, `cleanup/ApkDownloader.kt`; Test `cleanup/LauncherSwapTest.kt`, `cleanup/ApkDownloaderTest.kt`

**Interfaces:**
```kotlin
class ApkDownloader(private val http: okhttp3.OkHttpClient, private val cacheDir: java.io.File) {
  suspend fun fetch(url: String, sha256: String): java.io.File   // cached by sha; throws ChecksumMismatch
}
sealed interface SwapResult { data object Swapped : SwapResult; data object AlreadyDone : SwapResult
  data class RolledBack(val reason: String) : SwapResult }
class LauncherSwap(private val downloader: ApkDownloader, private val store: TvStateStore, private val phone: String) {
  suspend fun swap(adb: AdbClient, serial: String): SwapResult
  suspend fun verifyAfterReboot(adb: AdbClient, serial: String): SwapResult
  suspend fun rebootAndReconnect(adb: AdbClient, reconnect: suspend () -> AdbClient, timeoutMs: Long = 180_000): AdbClient
}
const val FLAUNCHER_PKG = "me.efesser.flauncher"
```
Sequence (spec §6.10): if FLauncher installed & resolved HOME already FLauncher → AlreadyDone; else download+verify → `install` → `monkey -p me.efesser.flauncher -c android.intent.category.LAUNCHER 1` → poll `dumpsys activity activities` up to 15 s for resumed FLauncher → HOME packages from query-activities minus FLauncher, minus `com.android.tv.settings` → disable each + `com.google.android.tungsten.setupwraith` → `input keyevent KEYCODE_HOME` → poll resumed == FLauncher (15 s) → else re-enable all disabled in this swap, `RolledBack`. Log Changes (INSTALL_APK, DISABLE×n, LAUNCHER_SWAP). Skip entirely (return RolledBack("Fire TV not supported yet")) when `ro.product.manufacturer` == Amazon.

- [ ] **Step 1: Failing tests:** downloader rejects wrong checksum (MockWebServer serving bytes ≠ hash) and accepts right one; swap happy path issues commands in the order above and returns Swapped; HOME resolves to setupwraith after disabling (fake keeps resumed = setupwraith) → RolledBack and both stock launcher and setupwraith re-enabled *(Review Focus 5)*; after reboot resumed ≠ FLauncher → `verifyAfterReboot` rolls back; Amazon manufacturer → RolledBack without any command beyond getprop; already swapped → AlreadyDone.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat: safe launcher swap with rollback`.

---

### Task 10: Undo

**Files:** Create `cleanup/Undo.kt`; Test `cleanup/UndoTest.kt`

**Interfaces:**
```kotlin
data class UndoReport(val restored: Int, val failed: List<String>, val reinstallFromStore: List<String>)
class Undo(private val store: TvStateStore, private val phone: String) { suspend fun undoAll(adb: AdbClient, serial: String): UndoReport
  suspend fun openInPlayStore(adb: AdbClient, pkg: String) }
```
Reverse `changes` newest-first (all phones' changes): DISABLE→`pm enable`, REMOVE_FOR_USER→`cmd package install-existing`, ANIMATIONS→restore `from`, APPOPS→restore `from`, INSTALL_APK of FLauncher→uninstall **after** stock launcher re-enabled, LAUNCHER_SWAP→`input keyevent KEYCODE_HOME` then verify stock HOME resumed, UNINSTALL→added to `reinstallFromStore`. Each reversal verified; reversed changes are removed from the log (log entry kind `UNDO` not needed — log shrinks). Lock held during undo.

- [ ] **Step 1: Failing tests:** after a simulated run (changes from two phones), `undoAll` issues reversals newest-first, FLauncher uninstalled last-but-one before HOME check, `reinstallFromStore` lists uninstalled user apps, state file ends with empty `changes` and unchanged `original`; a reversal that fails verification stays in the log and in `failed`. `openInPlayStore` sends `am start -a android.intent.action.VIEW -d market://details?id=<pkg> -p com.android.vending`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS. **Step 5:** Commit `feat: undo everything from the shared change log`.

---

### Task 11: Discovery

**Files:** Create `discovery/SubnetScanner.kt`, `discovery/Discovery.kt`; Test `discovery/SubnetScannerTest.kt`

**Interfaces:**
```kotlin
enum class TvReadiness { READY, NEEDS_SETUP }
data class FoundTv(val host: String, val readiness: TvReadiness, val name: String? = null)
fun interface PortProbe { suspend fun isOpen(host: String, port: Int, timeoutMs: Int): Boolean }
class SubnetScanner(private val probe: PortProbe, private val parallelism: Int = 64) {
  fun hostsFor(ipv4: String, prefix: Int): List<String>   // /24 max; larger prefixes clamp to the /24 containing the phone
  fun scan(hosts: List<String>): kotlinx.coroutines.flow.Flow<FoundTv> }
class Discovery(context: Context) { fun phoneWifiIpv4(): Pair<String, Int>?   // via ConnectivityManager LinkProperties of the active Wi-Fi network
  fun scan(): Flow<FoundTv>   // subnet scan + NsdManager for _adb._tcp and _androidtvremote2._tcp merged, de-duplicated by host
}
```
Classification: 5555 open → READY; else any of 6466, 6467, 8008, 8009 open → NEEDS_SETUP; else not emitted. Excludes the phone's own IP.

- [ ] **Step 1: Failing tests:** `hostsFor("192.168.10.42", 24)` → 253 hosts `.1…254` minus `.42`; `/16` clamps to the /24; fake probe with `.3:5555` and `.17:8009` → emits READY `.3` and NEEDS_SETUP `.17` only; results stream as they arrive (first emission before all probes complete, via a probe that delays `.200`).
- [ ] **Step 2:** FAIL. **Step 3:** Implement (`Socket().connect(InetSocketAddress, timeoutMs=800)`). **Step 4:** PASS. **Step 5:** Commit `feat: find TVs on the home Wi-Fi`.

---

### Task 12: App names and icons

**Files:** Create `apps/AppLabelResolver.kt`; Test `apps/AppLabelResolverTest.kt`

**Interfaces:**
```kotlin
data class AppLabel(val name: String, val iconPng: java.io.File?)
class AppLabelResolver(private val cacheDir: java.io.File, private val parseApk: (java.io.File) -> AppLabel?) {
  suspend fun resolve(adb: AdbClient, app: CategorizedApp, apkSizeBytes: Long?): AppLabel
  companion object { fun prettify(pkg: String): String } }
```
Catalog name wins for the label; icon: pull APK when `≤ 40 MB` (size via `stat -c %s <apkPath>`), parse with `PackageManager.getPackageArchiveInfo` (set `applicationInfo.sourceDir/publicSourceDir` before `loadLabel/loadIcon`), save icon PNG at `cacheDir/icons/<pkg>-<versionCode>.png`, delete the APK. `prettify("com.mxtech.videoplayer.pro")` → "Videoplayer Pro"; `prettify("org.videolan.vlc")` → "Vlc".

- [ ] **Step 1: Failing tests:** prettify cases above; cached icon short-circuits (no pull on second call); APK over 40 MB → no pull, `iconPng == null`; parse failure → prettified name.
- [ ] **Step 2:** FAIL. **Step 3:** Implement (`parseApk` real impl lives in UI layer wiring with `PackageManager`). **Step 4:** PASS. **Step 5:** Commit `feat: app names and icons from the TV`.

---

### Task 13: Material 3 Expressive theme + components

**Files:** Create `ui/theme/Theme.kt`, `ui/theme/Type.kt`, `ui/components/SegmentedGroup.kt`, `ui/components/AppRow.kt`, `ui/components/CookieBadge.kt`, `ui/components/StatusChip.kt`, `ui/components/BigButtons.kt`, `ui/components/Previews.kt`

**Interfaces:**
- `@Composable fun ExBloatTheme(content: @Composable () -> Unit)` — `MaterialExpressiveTheme`, dynamic colour on 31+, seed `#386a20` fallback, headline style ≈ 34 sp, rounded (Roboto Flex / system Google Sans where available).
- `@Composable fun SegmentedGroup(items: List<@Composable () -> Unit>)` — 24 dp outer / 6 dp inner corners, 3 dp gaps, `surfaceContainer` background.
- `@Composable fun AppRow(name: String, description: String?, icon: Painter?, checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit)` — M3 `Switch` with check icon thumb; semantics: "Netflix, keep, switch on".
- `@Composable fun CookieBadge(shape: RoundedPolygon = MaterialShapes.Cookie9Sided, icon: ImageVector, size: Dp)`
- `@Composable fun StatusChip(text: String, kind: ChipKind /* READY, SETUP */)`, `@Composable fun PrimaryButton(text, onClick)`, `@Composable fun QuietButton(text, onClick)` (56 dp pill / text button).

- [ ] **Step 1:** Implement with `@Preview` (light + dark, fontScale 2f) for each component.
- [ ] **Step 2:** Verify: `./gradlew assembleDebug` succeeds; render previews to PNG with Paparazzi **or** compare against the mockup by eye using the emulator in Task 15. Record which in the task report.
- [ ] **Step 3:** Commit `feat: Material 3 Expressive theme and components`.

---

### Task 14: Screens, navigation, repository, foreground service

**Files:** Create `data/TvRepository.kt` (remembered TVs in DataStore: serial, name, lastHost, lastCleanedAt), `ui/nav/NavGraph.kt`, `ui/screens/{HomeScreen,FindScreen,DebugStepsScreen,ApproveScreen,KeepScreen,ReviewSheet,CleaningScreen,DoneScreen,TvDetailsScreen}.kt` + one ViewModel each where state is non-trivial, `ui/screens/BrandSteps.kt` (steps per brand: Android TV, Google TV, Xiaomi, TCL, Sony, Hisense, Philips, Fire TV — copy from spec §6.2), `service/CleanupService.kt` (foreground service, `dataSync` type, notification "Cleaning up <TV name>…" with progress, owns the `CleanupRunner`/`Undo` coroutine so locking the phone doesn't stop it).
- Test: `ui/screens/KeepViewModelTest.kt`, `ui/screens/FindViewModelTest.kt`

**Interfaces:**
- Consumes everything above. Nav routes: `home`, `find`, `debug/{host}`, `approve/{host}`, `keep/{serial}`, `cleaning/{serial}`, `done/{serial}`, `tv/{serial}`.
- Screen copy and layout follow spec §6 and the mockup exactly (titles: "Find your TV", "Turn on debugging", "Keep what you watch", "Cleaning up", "All clean"). Keep screen groups by `Category` in this order: Streaming video, Music & audio, Live TV & sports, Features, Google extras, Manufacturer extras, Store & sideload tools, Other; Ads & tracking collapsed row "Always switched off (N)"; "Show system apps" at the bottom (read-only list). Speed switches below the apps. Review is a modal bottom sheet with the delete-user-apps switch (default off) and the counts.

- [ ] **Step 1: Failing tests:** `KeepViewModel` builds groups in the order above from the Mi Box fixture inventory, locked rows not toggleable, toggling updates `Choices.keep`, counts "N switched off, M removed" match `CleanupPlanner` output; `FindViewModel` merges remembered TVs (by serial) with scan results and marks a remembered TV whose IP changed with the new IP.
- [ ] **Step 2:** FAIL. **Step 3:** Implement screens + ViewModels + service. Before-numbers taken in `keep` load; after-numbers after `RebootAndVerify` + 60 s. **Step 4:** PASS + `./gradlew assembleDebug`.
- [ ] **Step 5:** Commit `feat: screens, navigation and cleanup service`.

---

### Task 15: Release build + end-to-end check (read-only)

- [ ] **Step 1:** Generate keystore (`keytool -genkeypair -v -keystore keystore/exbloattv.jks -alias exbloattv -keyalg RSA -keysize 4096 -validity 36500`, random passwords into `local.properties`). `./gradlew test lintRelease assembleRelease` → all green; R8 keeps dadb + kotlinx.serialization classes.
- [ ] **Step 2:** Copy APK to `dist/ExBloatTV-1.0.0.apk`; print SHA-256.
- [ ] **Step 3:** E2E **read-only** on an available device (the owner's phone via `adb install` if connected, else an existing AVD if one is already present — do **not** download system images): app finds `192.168.10.3` as Ready, connects (owner approves on TV), Keep screen lists every installed package in the right groups, already-disabled apps show off. Screenshot each screen in light and dark into `docs/screenshots/`. Do **not** press "Clean up TV" against the Mi Box.
- [ ] **Step 4:** Commit `chore: release build and screenshots`.
