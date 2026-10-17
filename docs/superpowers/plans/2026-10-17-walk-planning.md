# Pre-Walk Planning Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let walkers optionally set a theme, time or distance preference, and planned stops before a walk, then use that plan for local stop prioritization and privacy-limited companion context.

**Architecture:** Add an immutable `WalkPlan` value shared by the planner UI, `WalkSession`, and `WalkRecord`; serialize it compatibly in history and persist only the active plan separately so a sticky service restart can recover it. Reuse the existing spot ranking and prompt flow, with planned-stop priority kept local and only the selected theme/time/distance summary added to AI context.

**Tech Stack:** Kotlin, Android Service, Jetpack Compose / Material 3, kotlinx.coroutines, existing `org.json` history codec, JUnit 4, Gradle wrapper.

**Spec:** [2026-10-17-walk-plan-design.md](../specs/2026-10-17-walk-plan-design.md)

## Global Constraints

- Preserve `minSdk = 26`, `compileSdk = 36`, `targetSdk = 36`, and Java/Kotlin target 17.
- Add no dependency, network endpoint, telemetry, or server requirement.
- The plan is optional; the user can start without entering one.
- Keep planned-stop coordinates, the complete planned-stop list, and free-text notes out of AI requests.
- Preserve the existing opt-in setting for sending the actual walked route and current location to AI.
- Keep plan and active-plan state in app-private, non-backed-up storage; clearing walk history removes the saved plan from each record.
- Do not change cue/safety priority or channel skip behavior when prioritizing planned stops.
- Keep old `walk_history.json` records readable when their JSON has no plan field.

---

## File Map

| File | Responsibility in this change |
|---|---|
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlan.kt` | Immutable plan and planned-stop values; redacted prompt summary and stop matching |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/walk/ActiveWalkPlanStore.kt` | Persist/restore/clear only the in-progress plan for sticky service restart |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkSession.kt` | Hold the active plan and include it in `WalkRecord` |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/history/HistoryStore.kt` | Add optional plan data to walk JSON |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/SanpoApp.kt` | Construct and expose the active-plan store |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/SpotChooser.kt` | Expose full ordering and rank planned stops ahead without changing existing filters |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/PlanSuggestions.kt` | Choose up to three already-loaded nearby stops for a selected category |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/TalkDecider.kt` | Carry the planned-stop flag into existing spot selection |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/walk/WalkService.kt` | Start with a plan, restore it on sticky restart, rank selected stops, and clear active state on finish |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkCompanion.kt` | Add only the plan's redacted summary to the existing situation variables |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainActivity.kt` | Route the start action to the planner and start the service with a confirmed plan |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainScreen.kt` | Preserve direct stop behavior and expose the planner/map/list selection entry points |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainViewModel.kt` | Own the editable plan draft and derive suggestions from already-loaded spots |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/WalkPlanScreen.kt` | Compose controls for theme, duration, distance, suggested/manual stops, note, skip, and start |
| `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/HistoryScreen.kt` | Show the recorded plan in the existing walk row |
| `prompts/src/main/resources/sanpoguide/prompts/companion/situation.md` | Document and render the optional redacted plan summary |
| `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlanTest.kt` | Verify prompt redaction and stop toggling |
| `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/walk/ActiveWalkPlanStoreTest.kt` | Verify persisted active-plan save, restart read, and clear |
| `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/SpotChooserTest.kt` | Verify ranking, planned-stop priority, and existing filtering |
| `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/PlanSuggestionsTest.kt` | Verify nearest/theme ordering, three-item cap, and empty input |
| `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/TalkDeciderTest.kt` | Verify planned-stop priority does not outrank safety or cue rules |
| `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/history/WalkJsonTest.kt` | Verify plan round-trip and legacy records |
| `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/prompt/PromptFilesTest.kt` | Render the situation prompt both with and without a plan |
| `README.md`, `docs/privacy-policy.md`, `docs/TODO.md` | Describe the released experience, data flow, and deferred road routing |

The repository has JVM unit tests but no Compose UI test source set or Compose test dependency. Keep behavioral logic in pure Kotlin units and validate the Compose flow with the documented emulator checklist; do not add UI-test tooling for this feature.

---

### Task 1: Add the Plan Model and Compatible History Codec

**Files:**
- Create: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlan.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkSession.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/history/HistoryStore.kt`
- Create: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlanTest.kt`
- Modify: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/history/WalkJsonTest.kt`

**Interfaces:**
- Produces `WalkPlan(themeCategory: String? = null, durationMinutes: Int? = null, distanceKm: Int? = null, note: String? = null, stops: List<PlannedStop> = emptyList())`.
- Produces `PlannedStop(poiId: String, name: String, category: String, lat: Double, lon: Double)`.
- `WalkPlan.promptSummary(): String?` includes only non-empty theme, duration, and distance; it never includes the note, stop names, stop IDs, or coordinates.
- `WalkPlan.toggleStop(stop: PlannedStop): WalkPlan` adds a stop or removes an already-selected stop by `poiId`.
- `WalkPlanJson.toJson(plan: WalkPlan): JSONObject` and `WalkPlanJson.fromJson(json: JSONObject): WalkPlan` encode/decode the plan for history and the active-plan store.
- `WalkSession(startedAt: Long = ..., plan: WalkPlan = WalkPlan())` owns the active plan; `toRecord()` copies it into `WalkRecord`.
- `WalkRecord.plan` is a trailing field defaulting to `WalkPlan()` so existing Kotlin call sites remain source-compatible.

- [ ] **Step 1: Write failing plan-summary and history compatibility tests**

Put the summary and stop-toggle tests in `WalkPlanTest`; put the legacy JSON test in `WalkJsonTest`.

```kotlin
@Test
fun `prompt summary excludes note and planned locations`() {
    val plan = WalkPlan(
        themeCategory = "公園",
        durationMinutes = 60,
        distanceKm = 3,
        note = "この文はAIへ送らない",
        stops = listOf(PlannedStop("way/1", "中央公園", "公園", 35.0, 139.0)),
    )

    assertEquals("テーマ: 公園・時間: 60分・距離: 3km", plan.promptSummary())
    assertFalse(plan.promptSummary().orEmpty().contains("この文はAIへ送らない"))
    assertFalse(plan.promptSummary().orEmpty().contains("中央公園"))
    assertFalse(plan.promptSummary().orEmpty().contains("35.0"))
}

@Test
fun `toggling a selected stop removes it and toggling it again adds it`() {
    val stop = PlannedStop("way/1", "中央公園", "公園", 35.0, 139.0)
    val plan = WalkPlan().toggleStop(stop)

    assertEquals(listOf(stop), plan.stops)
    assertTrue(plan.toggleStop(stop).stops.isEmpty())
}

@Test
fun `a legacy walk without plan loads with an empty plan`() {
    val legacy = JSONObject(
        """{"id":1,"startedAt":1,"endedAt":2000000,"distanceM":0.0,"route":[],"visits":[],"stations":[]}"""
    )

    assertEquals(WalkPlan(), WalkJson.fromJson(legacy).plan)
}
```

- [ ] **Step 2: Run focused tests and confirm they fail**

Run from `C:\Dev\apps\yorisoi-sanpoguide`:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.duwenji.yorisoi.sanpoguide.companion.WalkPlanTest" --tests "io.github.duwenji.yorisoi.sanpoguide.history.WalkJsonTest"
```

Expected: compilation or assertions fail because `WalkPlan`, plan serialization, and plan-summary support do not exist.

- [ ] **Step 3: Implement the immutable model and legacy-safe JSON field**

Implement `promptSummary()` with a fixed, testable format and no reference to `note` or `stops`; implement `toggleStop()` by POI ID. Implement `WalkPlanJson` once and reuse it in `WalkJson` and the active-plan store. Add the plan as an optional `"plan"` object in `WalkJson.toJson`; in `WalkJson.fromJson`, use `WalkPlan()` only when the property is absent and let malformed present plan data follow the existing history parse-error path. Add plan to `WalkSession.toRecord()`.

- [ ] **Step 4: Add full plan round-trip and run focused tests**

Extend `WalkJsonTest` with a non-empty plan (including a stop's ID, display name, category, and coordinates) and assert equality after JSON round-trip. Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.duwenji.yorisoi.sanpoguide.companion.WalkPlanTest" --tests "io.github.duwenji.yorisoi.sanpoguide.history.WalkJsonTest"
```

Expected: both plan redaction and old/new record serialization tests pass.

- [ ] **Step 5: Commit the model and history codec**

```powershell
git add app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlan.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkSession.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/history/HistoryStore.kt app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlanTest.kt app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/history/WalkJsonTest.kt
git commit -m "feat: add walk plan model and history" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 2: Reuse Spot Ranking for Suggestions and Planned Stops

**Files:**
- Create: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/PlanSuggestions.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/SpotChooser.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/TalkDecider.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/walk/WalkService.kt`
- Modify: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/SpotChooserTest.kt`
- Create: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/PlanSuggestionsTest.kt`
- Modify: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/TalkDeciderTest.kt`

**Interfaces:**
- `SpotCandidate` gains `planned: Boolean = false`; existing positional construction remains valid.
- `SpotChooser.rank(candidates: List<SpotCandidate>, prefer: Set<String>, skip: Set<String>, newSpots: Boolean, revisits: Boolean): List<SpotCandidate>` returns the same filtered candidates in the exact order used by `pick()`.
- `SpotChooser.pick()` remains the single-result API and returns `rank(...).firstOrNull()`.
- `PlanSuggestions.recommend(candidates: List<SpotCandidate>, themeCategory: String?): List<SpotCandidate>` returns at most three candidates; without a theme it sorts by distance, and with a theme it uses the existing preferred-category score and distance tie-break.
- `NearbySpot` carries `planned: Boolean = false`; `WalkService.situation()` sets it by matching each current POI against the active plan.

- [ ] **Step 1: Add failing tests for planned priority and suggestion cap**

Add the first test to `SpotChooserTest` alongside its existing `spot()` helper:

```kotlin
@Test
fun `a planned spot is selected before a nearer unplanned spot`() {
    val result = SpotChooser.pick(
        candidates = listOf(
            SpotCandidate(spot("近い公園", "公園"), 10f, false),
            SpotCandidate(spot("予定した神社", "神社"), 80f, false, planned = true),
        ),
        prefer = emptySet(),
        skip = emptySet(),
        newSpots = true,
        revisits = true,
    )

    assertEquals("予定した神社", result?.poi?.name)
}
```

Create `PlanSuggestionsTest` with `Poi`, `SpotCandidate`, `PlanSuggestions`, `assertEquals`, `assertTrue`, and `Test` imported. Put these fixtures and test inside the test class:

```kotlin
private fun candidate(name: String, category: String, distanceM: Float) =
    SpotCandidate(Poi(name, name, 0.0, 0.0, category, emptyMap()), distanceM, false)

@Test
fun `no theme uses nearest order and theme uses category preference`() {
    val candidates = listOf(
        candidate("遠い公園", "公園", 100f),
        candidate("近い神社", "神社", 10f),
        candidate("近い公園", "公園", 20f),
        candidate("史跡", "史跡", 30f),
    )

    assertEquals(
        listOf("近い神社", "近い公園", "史跡"),
        PlanSuggestions.recommend(candidates, null).map { it.poi.name },
    )
    assertEquals("近い公園", PlanSuggestions.recommend(candidates, "公園").first().poi.name)
    assertEquals(3, PlanSuggestions.recommend(candidates, null).size)
    assertTrue(PlanSuggestions.recommend(emptyList(), null).isEmpty())
}
```

- [ ] **Step 2: Run chooser tests and confirm the new assertions fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.duwenji.yorisoi.sanpoguide.companion.SpotChooserTest" --tests "io.github.duwenji.yorisoi.sanpoguide.companion.PlanSuggestionsTest" --tests "io.github.duwenji.yorisoi.sanpoguide.companion.TalkDeciderTest"
```

Expected: the new named tests fail because ranking does not yet expose all candidates or recognize planned candidates.

- [ ] **Step 3: Implement ranking as a shared comparator**

Move the filtering and score ordering into `SpotChooser.rank()`. Keep channel skip/new/revisit filtering unchanged; rank planned candidates ahead of other eligible candidates, then preserve the existing notability/preferred-category score and distance tie-break. Have `pick()` delegate to the first ranked item. Implement `PlanSuggestions.recommend()` with no channel skip set and take three. Without a theme, sort by distance only; with a theme, use `SpotChooser.rank()` with both new and revisited spots enabled and the theme category as preferred.

- [ ] **Step 4: Wire the plan flag into walk decisions**

When `WalkService.situation()` builds `NearbySpot`, mark matches from `WalkSession.plan.stops`. `TalkDecider` must still evaluate safety rules and channel cues before the ordinary spot rule, and must still apply channel category skip rules to planned stops.

- [ ] **Step 5: Verify existing and new ranking behavior**

Add assertions that an unplanned walk has identical ordering, channel-skipped categories remain skipped, and a planned spot never outranks weather or an explicit spot cue. Run the focused tests from Step 2; expected: all pass.

- [ ] **Step 6: Commit ranking and decision behavior**

```powershell
git add app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/PlanSuggestions.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/SpotChooser.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/TalkDecider.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/walk/WalkService.kt app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/SpotChooserTest.kt app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/PlanSuggestionsTest.kt app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/TalkDeciderTest.kt
git commit -m "feat: prioritize planned walk stops" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 3: Start and Restore the Active Plan in WalkService

**Files:**
- Create: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/walk/ActiveWalkPlanStore.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/SanpoApp.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/walk/WalkService.kt`
- Create: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/walk/ActiveWalkPlanStoreTest.kt`

**Interfaces:**
- `ActiveWalkPlanStore.save(plan: WalkPlan)`, `load(): WalkPlan?`, and `clear()` persist only the current plan under `filesDir`; do not store route tracking or conversation state there.
- `SanpoApp.activeWalkPlan` exposes one store instance.
- `WalkService.start(context: Context, plan: WalkPlan)` saves the plan before starting the foreground service and includes its JSON in the start intent.
- `WalkService.onStartCommand()` uses the intent plan when present; for a null sticky-restart intent it reads the stored active plan before constructing `WalkSession`.

- [ ] **Step 1: Add failing tests for active-plan persistence**

Add these tests to `ActiveWalkPlanStoreTest`, using JUnit 4 `TemporaryFolder` and the same production `WalkPlanJson` codec as history. Include `@Rule @JvmField val temp = TemporaryFolder()` in the test class.

```kotlin
@Test
fun `saved plan is available after store recreation and disappears after clear`() {
    val file = File(temp.root, "active_walk_plan.json")
    val plan = WalkPlan(themeCategory = "公園", durationMinutes = 60)

    ActiveWalkPlanStore(file).save(plan)
    assertEquals(plan, ActiveWalkPlanStore(file).load())

    ActiveWalkPlanStore(file).clear()
    assertNull(ActiveWalkPlanStore(file).load())
}
```

- [ ] **Step 2: Run the focused test before implementing the store**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.duwenji.yorisoi.sanpoguide.walk.ActiveWalkPlanStoreTest"
```

Expected: the new persistence assertion fails until `ActiveWalkPlanStore` exists.

- [ ] **Step 3: Add the app-private active-plan store**

Use an app-private `active_walk_plan.json` file. Write to a sibling temporary file and atomically replace the active file so a process interruption cannot leave a partial JSON document. Surface file read/write errors through the store API; do not silently replace malformed persisted data with a success-shaped empty plan.

- [ ] **Step 4: Pass, restore, and clear the plan at service boundaries**

In `WalkService.start`, persist before `startForegroundService`; if persistence fails, surface the error and do not claim the plan started successfully. In `onStartCommand`, prefer the supplied plan JSON and use the stored plan only when Android restarts the service without an intent. In `finishWalk`, clear active state on every normal end path, including walks too short to be added to history; do not clear it merely because Android is recreating the service.

- [ ] **Step 5: Run model tests and verify lifecycle behavior on an emulator**

Run the focused `WalkPlanTest` and `WalkJsonTest`. Then start a plan-backed walk, force-stop only the service process through Android Studio's test workflow (not the app's user-facing stop action), and confirm the sticky restart retains the theme/stop plan while leaving route-history restoration unchanged.

- [ ] **Step 6: Commit the active-plan service path**

```powershell
git add app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/SanpoApp.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/walk/ActiveWalkPlanStore.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/walk/WalkService.kt app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/walk/ActiveWalkPlanStoreTest.kt
git commit -m "feat: preserve active plan across walk service restart" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 4: Add the Redacted Plan Summary to Companion Prompts

**Files:**
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkCompanion.kt`
- Modify: `prompts/src/main/resources/sanpoguide/prompts/companion/situation.md`
- Modify: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/prompt/PromptFilesTest.kt`
- Modify: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlanTest.kt`

**Interfaces:**
- `WalkCompanion.situationVars()` adds optional `"plan"` using only `session?.plan?.promptSummary()`.
- The prompt resource documents and renders `plan` only when non-null; it does not receive `note`, `stops`, IDs, or coordinates.

- [ ] **Step 1: Add a prompt-rendering test case**

Add one sample with `"plan" to "テーマ: 公園・時間: 60分"` and one with `"plan" to null` to the `Prompts.Talk.SITUATION` cases in `PromptFilesTest`.

- [ ] **Step 2: Run the prompt test and confirm the resource contract fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "io.github.duwenji.yorisoi.sanpoguide.prompt.PromptFilesTest"
```

Expected: the new prompt sample fails the current template-variable contract because `plan` is not rendered yet.

- [ ] **Step 3: Add only the summary to `WalkCompanion`**

Set `"plan"` to `session?.plan?.promptSummary()` in the map built by `situationVars()`. Do not add any other plan fields to that map. Add the documented optional block to `companion/situation.md` and tell the companion to use it as a preference, not as a route or factual statement.

- [ ] **Step 4: Verify redaction and both prompt shapes**

Run `WalkPlanTest` and `PromptFilesTest`. Assert in `WalkPlanTest` that the summary contains the chosen theme/time/distance and contains none of the note, stop name, stop ID, or coordinate values. Expected: both empty and populated prompt samples render and all redaction assertions pass.

- [ ] **Step 5: Commit the prompt integration**

```powershell
git add app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkCompanion.kt prompts/src/main/resources/sanpoguide/prompts/companion/situation.md app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/prompt/PromptFilesTest.kt app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlanTest.kt
git commit -m "feat: tailor companion context to walk plan" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 5: Build the Optional Pre-Walk Planner UI

**Files:**
- Create: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/WalkPlanScreen.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainActivity.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainScreen.kt`
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainViewModel.kt`

**Interfaces:**
- `MainViewModel.planDraft: StateFlow<WalkPlan>` holds edits across Compose screen changes and Activity recreation.
- `MainViewModel.updatePlan(transform: (WalkPlan) -> WalkPlan)` updates one draft; `MainViewModel.suggestions: StateFlow<List<SpotCandidate>>` is derived from the already-loaded `spots` and the current theme.
- `WalkPlanScreen` receives the draft, suggestions, nearby spots, and callbacks for theme/time/distance/note/stop selection, cancel, and start.
- `WalkPlanScreen` receives `startError: String?`; `MainActivity` keeps it visible until retry or cancel.
- Start action uses the confirmed draft; “プランなしですぐ開始” uses `WalkPlan()`; the active-walk button still calls `WalkService.stop`.

- [ ] **Step 1: Verify the recommendation tests from Task 2**

Run `PlanSuggestionsTest` and confirm the no-theme nearest ordering, theme-category preference, three-item cap, and empty-input cases still pass before wiring them into `MainViewModel`.

- [ ] **Step 2: Implement suggestion derivation from loaded spots only**

Convert `MainViewModel.spots` to the existing `SpotCandidate` shape, call `PlanSuggestions.recommend`, and expose the result as a state flow. Do not trigger a new network request when opening the planner.

- [ ] **Step 3: Add persistent draft state**

Add a `MutableStateFlow(WalkPlan())` to `MainViewModel`, expose it read-only, and implement `updatePlan`. The draft must survive returning from map selection and Activity configuration changes, and reset after the walk starts or the planner is cancelled.

- [ ] **Step 4: Implement the plan controls and spot picker**

Create `WalkPlanScreen` with:
  - theme: “指定なし” plus existing spot categories;
  - time: none, 30, 60, or 90 minutes;
  - distance: none, 1, 3, or 5 km;
  - up to three suggested stops and selectable nearby spots from both the map and list;
  - an optional local-only note;
  - distinct actions for immediate start without a plan, start with this plan, and cancel.

Reuse the existing `SpotMap` and nearby `SpotItem` data rather than adding a new map SDK or POI request. A map/list selection must toggle the stop in the draft, not open AI narration as a side effect.

- [ ] **Step 5: Route the start/stop action and service call**

Change the main-screen callback so a non-walking start opens the planner and an active walk stops immediately. Add a saveable planner screen state to `MainActivity`, handle back/cancel without losing a valid draft, and call `WalkService.start(this, plan)` only from the planner's start actions.
Catch the active-plan store's `IOException` at the start callback, keep the planner open, and show it through `startError` instead of claiming the walk began. If a sticky restart finds a corrupt active-plan file, log the parse error, post a notification that the walk could not be resumed, and stop without silently starting with an empty plan.

- [ ] **Step 6: Verify planner transitions manually**

On an emulator, with location permission granted:
  1. open and cancel the planner; verify no walk begins;
  2. select a theme, duration, and two stops, leave the screen and return; verify the draft remains;
  3. start with the plan; verify foreground notification and location tracking start;
  4. start without a plan; verify the existing walk starts with empty plan context;
  5. while walking, verify the button still ends the walk without opening the planner;
  6. with spot loading failed or no spots, verify the reason is visible and start remains available.

- [ ] **Step 7: Commit the planner UI**

```powershell
git add app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/WalkPlanScreen.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainActivity.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainScreen.kt app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/MainViewModel.kt
git commit -m "feat: add optional pre-walk planner" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 6: Show the Saved Plan and Update User-Facing Documentation

**Files:**
- Modify: `app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/HistoryScreen.kt`
- Modify: `README.md`
- Modify: `docs/privacy-policy.md`
- Modify: `docs/TODO.md`

- [ ] **Step 1: Show recorded plan information in each existing history row**

Extend `WalkRow` to display theme, planned duration/distance, and selected stop names. Display the note only in the same row on the device. Omit empty fields so walks without plans retain their current compact appearance. Keep the existing “delete all records” behavior as the deletion path for saved plans.

- [ ] **Step 2: Update README and privacy policy**

Describe the optional planner and candidate-only (not road-route) output in `README.md`. In `docs/privacy-policy.md`, list the selected theme/time/distance summary sent to the configured AI service, explicitly exclude the free-text note/planned-stop list/planned coordinates, preserve the existing opt-in actual-route disclosure, and add the plan and note to on-device history.

- [ ] **Step 3: Update the TODO entry**

Change the route-suggestion item in `docs/TODO.md` to mark optional plan input and stop suggestions as implemented after they are shipped, while leaving road-route optimization and time/distance guarantees as deferred work.

- [ ] **Step 4: Review documentation against generated requests and history**

Compare the policy wording with `WalkCompanion.situationVars()` and `WalkPlan.promptSummary()`. Confirm the new plan data is not described as sent if it is not included in the AI request, and confirm the actual-route opt-in text is unchanged.

- [ ] **Step 5: Commit history display and documentation**

```powershell
git add app/src/main/java/io/github/duwenji/yorisoi/sanpoguide/ui/HistoryScreen.kt README.md docs/privacy-policy.md docs/TODO.md
git commit -m "docs: describe walk planning and local plan history" -m "Co-authored-by: Copilot <223556219+Copilot@users.noreply.github.com>"
```

### Task 7: Run Complete Verification

**Files:**
- Test: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/WalkPlanTest.kt`
- Test: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/SpotChooserTest.kt`
- Test: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/PlanSuggestionsTest.kt`
- Test: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/companion/TalkDeciderTest.kt`
- Test: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/walk/ActiveWalkPlanStoreTest.kt`
- Test: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/history/WalkJsonTest.kt`
- Test: `app/src/test/java/io/github/duwenji/yorisoi/sanpoguide/prompt/PromptFilesTest.kt`

- [x] **Step 1: Run the Android app unit-test suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
```

Expected: all existing and new JVM unit tests pass.

- [x] **Step 2: Build the debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug
```

Expected: successful debug APK build with no new dependency or manifest permission.

- [ ] **Step 3: Run the planner/history/privacy emulator checklist**

Confirm the six planner flows in Task 5, plus:
  - planned stops speak before nearer ordinary eligible spots, but after weather/safety messages and explicit channel cues;
  - channels still skip their configured categories;
  - old history records load with no plan and new records show plan fields;
  - deleting all walk history removes saved plans and notes;
  - planner summary appears in AI companion prompts, while free-text notes, planned stop names/IDs/coordinates do not;
  - enabling the pre-existing walked-route sharing setting still sends only the already-documented current/actual location context.

The emulator check covered selecting a duration, navigating back and reopening the planner with the draft retained, and cancelling without starting a walk. The rest of the emulator checklist remains unverified.

- [x] **Step 4: Review the final diff and working tree**

Run:

```powershell
git diff --check
git status --short
```

Expected: no whitespace errors and only the implementation files listed in this plan are changed.
