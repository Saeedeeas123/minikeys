# Activity keys — the shortcut page is now per-activity (v0.9.0)

Press the **⌃** key and the shortcut page shows the actions **of the app you are in**: "Send",
"Switch model", "Terminal" … Press one and the real button is pressed for you — no reaching for it,
no coordinates, no typing.

## How it works (one pipeline)

```
accessibility service (:perception)          keyboard (:keyboard) / edge overlay (:edgeoverlay)
  active window → node tree → functions  ──▶  ActivityShortcuts  ──▶  ⌃ page = activity keys
                                          ◀──  performAction(click/long/focus/scroll)  ◀── key press
```

1. **`:perception`** (new module) reads the **active application window** (not "the last event"), so
   with several apps open it still knows which one the user is looking at.
2. `FunctionExtractor` turns the tree into functions — **including the elements scrolled out of the
   viewport**, which are marked off-screen rather than silently dropped.
3. `ActivityShortcuts` publishes them **per activity** (`package/Activity`), with a cache so returning
   to an app restores its keys instantly.
4. The keyboard's ⌃ page = **activity keys on top, the fixed Windows combinations below**. No screen
   reader enabled → the page is exactly what it always was.
5. A key press calls `performAction` on the **real node**: path + identity check (never a blind tap),
   and if the element is off-screen the app **scrolls it into view first**.
6. When the activity changes, both the IME and the edge strips **rebuild their keys** immediately.

## Enabling it

1. **Settings › Accessibility › MiniKeys screen reader** → ON.
2. Use the keyboard normally and press **⌃** — the activity's keys are the first block.
3. Without step 1 the ⌃ page still works exactly as before (Windows shortcuts only), and the log says
   `screen reader is not enabled` if an activity key is pressed.

The switch lives in SharedPreferences (`minikeys_activity_keys` / `enabled`), so the feature can be
turned off without losing the rest of the keyboard.

## Why an accessibility service and not the IME

An IME can only **write text** into the focused field (`InputConnection`); it cannot press a button in
another app. That is the single hard constraint behind this design — the keyboard offers the keys, the
accessibility service presses them. They live in one app process, so the hand-off is a plain in-memory
object: no binder, no IPC.

## Files

| Question | File |
|---|---|
| Which app/activity is in front, and what does it offer? | `perception/…/ScanService.kt` |
| Which elements count as functions, and how are they labelled? | `perception/…/uimap/FunctionExtractor.kt` (JVM-tested, 21 tests) |
| The per-activity key set handed to the keyboard | `perception/…/ActivityShortcuts.kt` |
| Running a function on its real element | `perception/…/ScreenActions.kt` |
| The dynamic ⌃ page | `keyboard/…/KeyboardLayouts.kt` (`shortcutKeys()`) |
| Pressing an activity key | `keyboard/…/KeyDispatcher.kt` → `ActivityShortcuts.run(index)` |

## Known limits

- **Virtualized lists**: items a `RecyclerView`/`LazyColumn` has not materialized are not in the tree,
  so they cannot appear as keys until they have been scrolled to once. (FocusMap's Crawl action covers
  that case in the standalone app.)
- **Actions that apps do not expose** (custom gestures) fail honestly: the log records
  `activity key #N '…' -> FAILED: … refused the click action`.
- Keys are ranked by the order the screen exposes them; a scoring/ranking pass is the next refinement
  (a rich screen can offer more functions than a page can show).
- There is **no settings-screen card yet** for enabling the screen reader — use Android's Accessibility
  settings (the service appears as *MiniKeys screen reader*).
