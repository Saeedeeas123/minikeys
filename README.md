# MiniKeys — keyboard module (v0.1.0)

An intentionally small Android keyboard (IME) that is built as a **module**, not as a monolith: the
input method, the persisted settings model and the settings UI live in separate Gradle modules with a
one-way dependency flow, so the next module (layouts, voice input, suggestions…) can be added without
touching the existing ones.

```
:app            settings, AI provider config, live preview (Compose) — the only module with UI framework deps
   │
   ▼
:keyboard       the IME: InputMethodService + MiniKeyboardView   (framework APIs only)
   │
   ▼
:core:settings  KeyboardSettings / KeyboardTheme / persistence codec  (no UI, no third-party deps)
:core:llm       OpenAI-compatible LLM client + Keystore key vault     (no third-party deps)
```

Nothing depends upwards. `:core:settings` is pure Kotlin + SharedPreferences, which is why its
persistence rules are covered by JVM unit tests.

## What it does

Typing (v0.1 scope, deliberately minimal):

- QWERTY letters, second page with digits and symbols (`?123` / `ABC`)
- shift with double-tap caps lock, backspace with press-and-hold repeat
- enter key label follows the editor action (Done / Go / Search / Send / Next / Prev, or a newline in
  multi-line fields), space/comma/period, haptic feedback on key press

Settings, all applied to the keyboard **immediately** (no IME restart):

| Setting | Range | Default |
|---|---|---|
| Theme | Dark, Light, Ocean, Sunset | Dark |
| Keyboard height | 160–360 dp | 240 dp |
| Key label size | 12–24 sp | 16 sp |

The settings screen shows three kinds of proof that a change was saved:

1. a **live keyboard preview** rendered by the same `MiniKeyboardView` code as the real IME, whose
   height follows the saved height,
2. a **test field** that the preview types into (so the change is visible without leaving the app),
3. a **"Persisted values (read back from disk)"** panel: theme, height, label size, the timestamp of
   the last file write, the preferences file path and its raw entries — all read back from
   SharedPreferences, not from in-memory state.

## Enabling the keyboard

1. Install `minikeys-debug.apk`.
2. Open **MiniKeys** → **Enable** → tick *MiniKeys* in the system keyboard list.
3. Back in the app → **Choose keyboard** → pick *MiniKeys* (or use the keyboard switcher while typing).
4. The status card turns both rows to `YES` when the app comes back to the front.

The system gear icon shown next to the keyboard in the switcher opens this same settings screen
(`android:settingsActivity` in `res/xml/method.xml`).

## Storage

Single SharedPreferences file, written with `commit()` so a read-back is always truthful:

```
/data/data/com.saeed.minikeys/shared_prefs/minikeys_settings.xml
    theme_id            = "dark" | "light" | "ocean" | "sunset"
    keyboard_height_dp  = Int   (clamped to 160..360 on read)
    key_text_size_sp    = Int   (clamped to 12..24 on read)
```

`KeyboardPrefsCodec` never throws: missing, foreign or out-of-range values fall back to (or are
clamped into) valid settings, so a corrupted or older preferences file can not break the keyboard.

## Build

On this device (proot Ubuntu, ARM64) — frozen toolchain, see the `android-build-ship` skill:

```bash
cd /root/projects/minikeys
./gradlew :core:settings:testDebugUnitTest :app:assembleDebug --no-daemon
cp -f app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/minikeys-debug.apk
```

## Roadmap

### Module policy (2026-10-10)

MiniKeys is a **consumer** of the standalone modules kept in `/sdcard/Documents/Operit_Modules/`
(see `README.md` and `CATALOG.md` there). A module is written once, stored separately, proven by an
APK, and documented with a step-by-step recipe — so consuming it in an app never means re-debugging.

| Module | What it gives this app | Status |
|---|---|---|
| `minikeys-keyboard` | the IME itself + settings model (this repo) | accepted |
| `core-llm` | online LLM: OpenAI-compatible client + Keystore key vault | accepted |
| `core-litertlm` | offline LLM: `.litertlm` model loader (LiteRT-LM) | accepted |

### LLM status in this app

- **Offline LLM engines were removed permanently on 2026-10-10** (commit `da77b2f`, app v0.5.0):
  `:core:mediapipe` and `:core:llama` never worked reliably, so their modules, settings cards, state
  holders and imported model data were deleted. Any leftover references were cleaned on 2026-10-10.
- The earlier standing order *"do not add an LLM to MiniKeys"* is **revoked** — an LLM may be added
  here again. When that happens it comes from the modules area: `core-llm` (online, the proven one) or
  `core-litertlm` (offline), never a re-derived one-off.
- The **cloud AI card** (`core-llm`: OpenAI-compatible client + Keystore vault, with LAN-server
  presets for llama.cpp server / Ollama / LM Studio) is kept and is the only LLM surface in the app.

### Extension points (next modules)

- `KeyboardLayouts.rowsFor(page)` is pure data: a Persian layout, an emoji page or a numeric pad is a
  new page + row list, no drawing/input code changes.
- `MiniKeyboardView.Listener` is the single seam between the keyboard surface and whatever consumes
  the input (currently the IME service; a preview or a test harness can implement it too).
- `KeyboardTheme` is a data class of ARGB ints, so themes can later come from a file/asset without
  touching either the IME or the settings model.
- Not implemented in v0.1 on purpose: suggestions/dictionary, gesture typing, clipboard, per-app
  themes, multi-language subtypes beyond the en_US subtype declared in `method.xml`.

## Keyboard modes (v0.6.0)
Two geometries, switchable in Settings → **Keyboard mode** and persisted with the rest of the settings:
- **Normal** — the classic bottom strip (`MiniKeyboardView`).
- **Vertical** — `EdgeKeyboardView`: two independent, scrollable key columns hugging the left and
  right screen edges, middle of the screen left to the app. The IME window goes full height and its
  touchable region is limited to the two columns, so the middle stays interactive. Keys split
  left/right (first half of the page left, second half right), with shift/space/page-toggle on the
  left column and backspace/comma/period/enter on the right. Arrow hints show when a column can
  still scroll. First key clears the notification bar, last key clears the home bar (real insets).
A dedicated **Type here (any keyboard)** card in the settings screen gives a plain text box for
end-to-end typing checks with MiniKeys (either mode) or any other installed keyboard.
