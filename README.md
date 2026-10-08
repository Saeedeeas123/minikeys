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

## Extension points (next modules)

- `KeyboardLayouts.rowsFor(page)` is pure data: a Persian layout, an emoji page or a numeric pad is a
  new page + row list, no drawing/input code changes.
- `MiniKeyboardView.Listener` is the single seam between the keyboard surface and whatever consumes
  the input (currently the IME service; a preview or a test harness can implement it too).
- `KeyboardTheme` is a data class of ARGB ints, so themes can later come from a file/asset without
  touching either the IME or the settings model.
- Not implemented in v0.1 on purpose: suggestions/dictionary, gesture typing, clipboard, per-app
  themes, multi-language subtypes beyond the en_US subtype declared in `method.xml`.
