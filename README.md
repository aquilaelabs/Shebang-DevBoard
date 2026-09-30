# Shebang DevBoard

An Android keyboard (IME) for developers, written in Kotlin.

- **Text mode**: QWERTY with glide typing, suggestions, long-press alternates, auto-capitalisation.
- **Code mode** (`#!` key): every digit and printable ASCII symbol on one page, plus arrow keys. No shift,
  no long-press, no autocorrect.
- **Terminal bar**: a horizontally scrolling strip of terminal keys (Esc, Tab, Ctrl, Alt, ^C, arrows, F1-F12,
  snippets...) that sends real `KeyEvent`s, so Termux and other terminals receive them. Sticky modifiers let
  `Ctrl` then `c` on the main keyboard send Ctrl+C.
- **Field-aware**: terminals (`TYPE_NULL`) get raw characters and no composing; passwords get no
  suggestions or glide; number/phone/date fields get a numeric pad; email/URL fields get `@` and `/`.
- **On-device only**: the sole permission is `VIBRATE`. No network code, no analytics, nothing typed is
  stored.

## Build and install

Requirements: JDK 17+ (21 used here), Android SDK with platform 36 and build-tools 36. Gradle is fetched by
the wrapper.

```sh
./gradlew assembleDebug test lint      # what CI runs (.github/workflows/android.yml)
./gradlew installDebug                 # install on the connected device or emulator
adb shell ime enable dev.shebang.devboard/.ime.DevBoardService
adb shell ime set dev.shebang.devboard/.ime.DevBoardService
```

Or open the **Shebang DevBoard** launcher entry and follow its three steps (enable, select, test).

Regenerate the word list from a SCOWL release with `tools/build_wordlist.py /path/to/scowl-2020.12.07 50`.

## Layout of the code

| Package (`dev.shebang.devboard.`) | What lives there |
|---|---|
| `ime` | `DevBoardService` (the `InputMethodService`), `FieldInfo` (EditorInfo -> what the field allows), `TextInputController` (composing, suggestions, smart spacing), `Feedback` |
| `layout` | JSON models (`LayoutDef`, `KeyDef`, `BarItem`), `LayoutParser`, `KeyboardGeometry` (pixel positions computed at runtime), `KeyCodeNames` |
| `view` | `KeyboardView` (one Canvas-drawn view with its own multitouch), `KeyPopup` (preview and alternates), `TerminalBarView`, `SuggestionStripView`, `TopStripView`, `KeyboardTheme` |
| `input` | `ModifierState` (sticky modifier state machine), `KeyEventMapper`/`CharKeyCodes` (character -> keycode plans), `KeySender` (down/up KeyEvents with meta state), `RepeatController` |
| `glide` | `PathResampler`, `IdealPath`/`IdealPathCache` (LRU, invalidated by geometry version), `GlideDecoder` (SHARK2 shape + location channels) |
| `dict` | `Dictionary` (sorted word list with tiers and a first/last-letter index), `Suggester` (prefix completion + edit-distance correction), `DictionaryLoader` (background load) |
| `settings` | `Settings`, `SettingsRepository` (DataStore), `SetupActivity`, `SettingsActivity` with the bar editor (Compose + Material 3) |

Layouts live in `app/src/main/assets/layouts/*.json`, the default terminal bar in `assets/bar/default.json`
and the word list in `assets/dict/en_words.txt`.

## Glide typing

A touch that starts on a letter, travels more than half a key width and crosses onto another letter key is a
glide. On release the raw path is copied and decoded on a background thread:

1. Candidates are pruned to words whose first and last letters have keys within 1.6 key widths of the
   gesture's start and end (the dictionary is indexed by first/last letter), and whose ideal path length is
   within a factor of the gesture's.
2. Each candidate's ideal path (key centres through its letters, repeats collapsed) and the gesture are
   resampled to 64 equidistant points.
3. A shape channel (centroid- and scale-normalised mean point distance) and a location channel (absolute
   mean point distance in key widths) each become a Gaussian likelihood; their product is multiplied by the
   word's frequency weight (SHARK2, Kristensson & Zhai 2004).
4. The top 5 are returned; the best is committed with a smart leading space and the rest shown in the strip.

The synthetic-swipe harness (`GlideDecoderTest.harnessMeetsAccuracyTargets`) jitters the ideal paths of
1,000 tier-10 words by a Gaussian of 0.22 key widths per vertex plus per-sample wobble, dropped samples and
corner overshoot. Latest run on the JVM: **top-1 93.5%, top-3 99.7%, mean 3-6 ms per decode, max 60 ms**
with a cold ideal-path cache (targets: 85% / 95% / under 100 ms). Run it with
`./gradlew test --tests '*GlideDecoderTest*' -i | grep 'GLIDE HARNESS'`.

## Decisions

Ambiguities were resolved with the simplest sensible option; each is recorded here.

- **applicationId / namespace**: `dev.shebang.devboard`.
- **compileSdk / targetSdk 36** (Android 16). This is the newest platform installed on the build node; SDK 37
  is not present, so the Compose BOM is pinned to 2026.06.01, core-ktx to 1.18.0, lifecycle to 2.10.0 and
  activity-compose to 1.12.4, the newest releases that compile against 36. Bump all five together when
  platform 37 is installed.
- **Toolchain**: Gradle 9.7.1, AGP 9.4.1 with its built-in Kotlin support (no `kotlin-android` plugin),
  Kotlin 2.4.20 compiler plugins for Compose and kotlinx.serialization, JDK 21, single `app` module.
- **Word list**: SCOWL 2020.12.07, `english` + `american` lists (American spelling), size levels 10 to 50 as
  frequency tiers, plus contractions and the common capitalised words up to level 35. Possessive forms
  ("ability's") are dropped. 61,825 words, 760 KB as text. Levels 60+ were left out as spell-checker noise.
- **"1,000 most common words" for the harness**: SCOWL has no per-word frequency, so the harness takes 1,000
  tier-10 words (the most frequent list) sampled with a fixed seed.
- **Frequency weights per tier**: 10 -> 1.0, 20 -> 0.45, 35 -> 0.18, 40 -> 0.08, 50 -> 0.03.
- **Glide sigmas**: shape 0.16 (bounding box = 1), location 0.9 key widths, endpoint radius 1.6 key widths,
  length ratio 0.45-2.2. Tuned against the harness; a wider location sigma lets shape and frequency decide.
- **Text-mode bottom row**: `#!` 1.25, `,` 1, space (flex), `.` 1, enter 1.25 units. Email and URL fields
  replace `,` with `/` and `@`; the space key absorbs the width difference so rows always sum to 10 units.
- **Numeric pad**: a 4-column pad (digits, backspace, `-`, `.`, `,`/`+` for phone, `#!`, enter) with
  long-press alternates for `+ * / # ( ) : ;`. Number, phone and date fields all use it.
- **Code mode height**: five rows at 86% of the text row height so the keyboard grows only a little.
- **Mode persistence**: the text/code choice persists across fields; numeric fields force the numeric pad
  while in text mode.
- **Suggestion strip order**: the typed word on the left (when it is not itself a suggestion), the best
  candidate in the middle, the runner-up on the right. The best *prefix completion* always leads; corrections
  never displace it.
- **Autocorrect-on-space** only fires for a tier <= 35 word one edit away from a word not in the dictionary.
- **Glide commit** adds a leading space unless at the field start or after whitespace or `( [ { <`; no
  trailing space. Backspace right after a glide deletes the word only (the leading space stays).
- **Key preview and alternates** are drawn by one overlay view inside the IME window rather than a
  `PopupWindow`: creating a window per tap stalled the main thread for hundreds of milliseconds on the
  emulator. The popup sits over the upper third of its key so the top row's popup fits under the strip.
- **Caps-mode queries** (`getCursorCapsMode`, an IPC) run only at word boundaries, never after a letter that is
  still being composed. Autocorrect-on-space reuses the candidates the background thread already produced.
- **Backspace repeat**: 380 ms initial delay, then 80 ms shrinking by 15% per tick to 25 ms.
  Arrows and Del repeat the same way on the bar and in code mode.
- **Sticky modifiers**: tap = one-shot, second tap within 350 ms = locked, tap while locked = off.
  `KeySender` brackets each key with the modifier keys' own down/up events so apps tracking modifiers see a
  consistent stream. Bar keys with their own modifiers (`^C`) add the sticky ones on top.
- **Shift + letter on the main keyboard** types the capital as text; Ctrl/Alt/Meta + a main key becomes a
  `KeyEvent` using the character's US keycode (adding Shift meta for shifted symbols such as `_`).
- **TYPE_NULL fields**: letters and symbols are committed as text, Backspace and Enter as `KeyEvent`s.
- **Enter**: newline when the field is multiline or sets `IME_FLAG_NO_ENTER_ACTION`; otherwise
  `performEditorAction` for Go/Search/Send/Next/Done/Previous; a plain Enter `KeyEvent` when the action is
  none/unspecified or the field is a terminal.
- **Theme colours**: Material 3 dynamic colour from `android.R.color.system_*` on Android 12+, an original
  fallback palette otherwise. Light/dark follows the setting or the system.
- **Icons**: backspace and return are Material Symbols paths (Apache-2.0); the shift arrow and the `#!`
  launcher icon are original vector art.
- **Sounds**: the system's own key-click effects via `AudioManager.playSoundEffect`; no bundled audio.
- **Haptics**: `VibrationEffect.createOneShot` at 8/12/18 ms and amplitude 60/140/255 for light/medium/strong.
- **Bar editor**: reorder with up/down buttons, remove, add key/modifier/snippet, import/export JSON through
  the system document picker. Import validates the JSON and rejects unknown keycodes or item types.
- **Build order**: the four phases were built in one pass because the service integrates the bar, dictionary
  and glide from the start; the git history groups the work by layer (core logic, IME and UI, docs) with each
  commit building.

## Manual checklist

Emulator notes: an AVD reports a hardware keyboard, so run
`adb shell settings put secure show_ime_with_hard_keyboard 1` or no soft keyboard appears; and
`adb shell am force-stop dev.shebang.devboard` makes the system fall back to another keyboard, so select
DevBoard again afterwards.

Termux (install from F-Droid; its terminal uses `inputType` `TYPE_NULL`):
- [x] `sleep 100`, then tap `^C` on the bar: the sleep stops with `^C` shown. *(verified on the API 36 emulator)*
- [x] Tap `Ctrl` on the bar, then `c` on the main keyboard: same result. *(verified)*
- [x] Type `slee`, tap `Tab`: completes to `sleep `. *(verified; note that Termux cannot complete paths under
      `/` such as `/da`, whatever keyboard sends the Tab)*
- [x] Tap `↑` on the bar: previous command recalled. *(verified)* Hold it: repeats.
- [ ] In code mode: arrows move the cursor; `⌫` repeats when held; `|`, `~`, `>` type directly.
- [ ] `Alt` then `.` on the bar/keyboard: last argument inserted (bash).

Fields (the setup screen has a multiline test field; a browser form has the rest):
- [x] Plain text: suggestions appear while typing, tapping one replaces the word; glide writes a word;
      backspace right after a glide removes it; double space gives ". ". *(verified on the emulator)*
- [ ] Password: no suggestions, no glide, no preview text left anywhere.
- [ ] URL/email: `@` and `/` on the bottom row; no auto-capitalisation.
- [ ] Number/phone: numeric pad; `#!` still reaches code mode.
- [ ] Multiline: Enter inserts a newline; a Search field shows "Search" and performs it.

Layout:
- [x] Rotate to landscape: keys re-flow to the IME window's width (narrower than the display with a cutout),
      glide still decodes (cache invalidated), popup positions right. *(verified on the emulator)*
- [ ] Change keyboard height in settings: the keyboard resizes immediately; glide still decodes.
- [ ] Number row on: a fifth row appears in text mode only.
- [ ] Strip: Auto swaps bar and suggestions; Always bar never shows suggestions; Two rows shows both.
- [ ] Long-press space opens the system keyboard picker; dragging along space moves the cursor.
- [ ] Shift: tap once for one capital, twice quickly for caps lock (accent-coloured key).
