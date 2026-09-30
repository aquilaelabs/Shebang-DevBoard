# Shebang DevBoard

An Android keyboard (IME) for developers, written in Kotlin.

- **Text mode**: QWERTY with suggestions, long-press alternates and auto-capitalisation, and glide typing
  that decodes while the finger moves: the strip shows the word before you lift; the word before is taken
  into account; tap inside a word (or double-tap it) and glide to redo it; the keyboard learns your words
  and how you swipe, on the phone; and (optionally) one stroke can write several words by dipping into the
  space bar between them.
- **Code mode** (`#!` key): every digit and printable ASCII symbol on one page, plus arrow keys. No shift,
  no long-press, no autocorrect.
- **Terminal bar**: a horizontally scrolling strip of terminal keys (Esc, Tab, Ctrl, Alt, ^C, arrows, F1-F12,
  snippets...) that sends real `KeyEvent`s, so Termux and other terminals receive them. Sticky modifiers let
  `Ctrl` then `c` on the main keyboard send Ctrl+C.
- **Field-aware**: terminals (`TYPE_NULL`) get raw characters and no composing; passwords get no
  suggestions or glide; number/phone/date fields get a numeric pad; email/URL fields get `@` and `/`.
- **On-device only**: the sole permission is `VIBRATE`. No network code, no analytics. What the keyboard
  learns (word counts, word pairs, swipe offsets) stays in the app's private files, can be reviewed and
  deleted in Settings > Personal words, and is never taken from password, number, email, URL, terminal or
  no-suggestion fields, or fields that ask for no learning.

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

Regenerate the word list from a SCOWL release, then the n-gram model from Tatoeba's English sentence export
(<https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2>):

```sh
tools/build_wordlist.py /path/to/scowl-2020.12.07 50
tools/build_ngrams.py /path/to/eng_sentences.tsv.bz2
```

## Layout of the code

| Package (`dev.shebang.devboard.`) | What lives there |
|---|---|
| `ime` | `DevBoardService` (the `InputMethodService`), `FieldInfo` (EditorInfo -> what the field allows), `TextInputController` (composing, suggestions, smart spacing, redoing a tapped word, what is learned when), `GlideText` (context word and casing), `LanguageLoader` and `LanguageBuilder` (background load and rebuilds with learned words), `SystemUserDictionary` (Android's personal dictionary), `KeyboardSizing`, `Feedback` |
| `layout` | JSON models (`LayoutDef`, `KeyDef`, `BarItem`), `LayoutParser`, `KeyboardGeometry` (pixel positions computed at runtime), `KeyCodeNames` |
| `view` | `KeyboardView` (one Canvas-drawn view with its own multitouch), `KeyPopup` (preview and alternates), `TerminalBarView`, `SuggestionStripView`, `TopStripView`, `KeyboardTheme` |
| `input` | `ModifierState` (sticky modifier state machine), `KeyEventMapper`/`CharKeyCodes` (character -> keycode plans), `KeySender` (down/up KeyEvents with meta state) |
| `glide` | `LexiconTrie` (the dictionary as a tree of key sequences), `StreamingGlideDecoder` (beam search while the finger moves, exact re-alignment after lift, joint decoding across words), `GlideSession` (decoder thread fed by a lock-free ring), `GlideTrace` (recorded glides), `KeyLayoutModel` |
| `dict` | `Dictionary` (sorted word list with tiers), `NgramModel` (word and word-pair statistics), `Suggester` (prefix completion + edit-distance correction) |
| `settings` | `Settings`, `SettingsRepository` (DataStore), `SetupActivity`, `SettingsActivity` with the bar editor and the glide recorder (Compose + Material 3), `GlideRecorderView`, `GlideTraceStore` |

Layouts live in `app/src/main/assets/layouts/*.json`, the default terminal bar in `assets/bar/default.json`,
the word list in `assets/dict/en_words.txt` and the n-gram model in `assets/dict/en_ngrams.bin`.

## Glide typing

A touch that starts on a letter, travels more than half a key width and crosses onto another letter key is a
glide. From then on every touch point, with its time, goes to a decoder thread while the finger moves
(`GlideSession`, fed through a lock-free ring, so the main thread allocates nothing per point):

1. **Resampling.** Points are resampled every quarter key pitch. Each carries how long the finger took around
   it, relative to the gesture's median (slowness), and how sharply the path turns there.
2. **Beam search over a letter tree.** Every dictionary word is a path through the tree by its keys, with
   apostrophes dropped and double letters collapsed, so a glide over d-o-n-t can give "don't" and "to" and
   "too" share a path. A hypothesis is a partial word lined up with the path so far: the stretch between two
   letters is a chain of states a quarter key apart, and each point costs its distance from its state.
   Letter keys are cheaper where the finger slowed and the stretches between them where it cruised. Pruning
   uses the best word frequency below each node, and the work per point is bounded.
3. **Live preview.** Every 40 ms the strip shows the word (or words) the glide would write if the finger
   lifted now.
4. **After lift.** The best 48 candidates are re-aligned exactly (dynamic time warping over the same model,
   with the gesture's final speed statistics) and scored with the bigram model given the word before them.
   The result is ready well within a millisecond on the JVM; the emulator logs 2 to 55 ms.
5. **Into the field, and redoing a word.** Glided words go straight into the field, with a space before them
   after a word and a space after them before one; a letter typed right after a glide starts a new word.
   To redo a word, tap inside it (or double-tap to select it, which also works for "a" and "I"): it is
   underlined and the strip shows it with its alternatives (its own runners-up if it was glided lately,
   suggestions otherwise). The next glide, or a tapped alternative, replaces it and keeps its capitals.
   To add a word instead, tap between words (a cursor at a word's edge targets nothing), or press space
   while a word is targeted, which moves past it. After the keyboard's own edits nothing is targeted, so
   gliding on never replaces anything, and nothing else in the field is ever rewritten. Each glide is
   decoded after the word before it, with the bigram model mixed with the user's own word pairs, against a
   stroke measured on keys shifted by the user's learned offsets.
6. **Phrase gliding** (setting, off by default). Dipping below the middle of the space bar, or resting on it
   for 150 ms, ends a word without lifting; the space key lights up when the dip counts. The travel down to
   and up from the space bar belongs to no letter, so a word may finish early and coast into the space bar,
   and the next one may start anywhere on its approach, each at a small cost per point. Lifting inside the
   space bar after a dip adds a trailing space.

The whole-word SHARK2 decoder (Kristensson & Zhai 2004) that shipped first is kept in the tests as the
baseline. `GestureSimulator` produces human-like strokes: noisy aim with a per-stroke offset, corners cut
toward the chord between neighbours, a smooth curve, speed following the two-thirds power law on curvature
with extra slowdowns at some intended letters, overshoot at the end, 120 Hz sampling that keeps only 2 dp
moves as the keyboard does. `GlideBenchmarkTest` prints:

| Benchmark | Whole-word decoder | Streaming decoder |
|---|---|---|
| 1,000 most frequent words, no context, top-1 / top-3 | 73.0% / 87.2% | 94.3% / 98.6% |
| 3,679 words of 600 held-out sentences, top-1 | 73.7% | 93.9% alone, 96.3% with context (98.8% top-3) |
| Original harness (tier-10 words, jittered ideal paths), top-1 / top-3 | 93.5% / 99.7% | 94.5% / 99.5% |
| Phrase strokes of 2-4 words with the travel to and from the space bar | | 94.2% (96.3% one stroke per word) |
| Sentences glided word by word, each glide free to re-read up to four earlier words (a decoder capability the keyboard does not use: it never rewrites text on its own) | | 96.8% when glided, 97.5% at sentence end; 28 words fixed, 1 broken |
| A swiper who lands 0.15 key right and 0.3 row low, before and after adapting on 250 glides (`GlideAdaptationTest`) | | 67.5% before, 89.3% after |

These are synthetic strokes, and the decoder's timing assumptions are the simulator's too. On the simulator
the slowness cue adds under a point, and the turning angle lowered accuracy, so its weight is 0. Real
fingers decide: **Settings > Record glides** prompts common words on the real keyboard and keeps each glide
with its timing and the key positions on the phone until you export it. Put exported `.jsonl` files in
`app/src/test/resources/glide/traces/` (or point `DEVBOARD_TRACES` at them) and run:

```sh
./gradlew testDebugUnitTest --tests '*GlideBenchmarkTest*' --tests '*RecordedGlidesTest*' -i | grep 'GLIDE BENCH'
GLIDE_TUNE=1 ./gradlew testDebugUnitTest --tests '*GlideTuningTest*' -i | grep 'GLIDE TUNE'   # parameter sweeps
```

## Learning on the phone

Android gives a keyboard no access to other keyboards' learned words or swipe data; the one shared store is
Android's personal dictionary (`UserDictionary`), which only the current keyboard may read. DevBoard learns
its own, locally:

- **Words you use.** Each word committed in a field that allows learning is counted with the word before
  it. Words the dictionary lacks become glidable and suggestable on their second use. Counts are mixed into
  word frequencies (30% at most, reached after 2,000 words) and word pairs into the bigram model once a
  context has been seen 5 times. Settings > Personal words lists them with a delete button each.
- **How you swipe.** Where each kept glide passed each letter, relative to the key centre, moves that
  letter's key centre for decoding, shrunk toward the average lean of all your glides. A correction (a
  tapped word glided again, or swapped for an alternative) re-aligns the word's original stroke, when it
  was glided lately, to the word you meant and counts twice, when the stroke plausibly was that word. Reset in Settings > Personal words.
- **Android's personal dictionary.** Its words (English, or with no locale) join the vocabulary when the
  keyboard loads.

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
  ("ability's") are dropped, but the 's contractions of a closed set of pronouns and function words ("it's",
  "that's", "let's") are kept: SCOWL files them among the possessives. 61,846 words, 760 KB as text, written
  in the order the app searches it so loading skips the sort. Levels 60+ were left out as spell-checker noise.
- **Word frequencies and context**: unigram and bigram counts from Tatoeba's English sentences (2.0 million
  sentences, 15.7 million words; CC BY 2.0 FR), counted only for words in the word list. Bigrams seen once are
  dropped from the 1.8 MB asset; smoothing is Witten-Bell back to the unigram, which mixes the real count with
  a small pseudo-count by SCOWL tier so unseen words still rank. Tatoeba uses "Tom" as its default name (3%
  of all words); counts involving "tom" are scaled to the level of "john". Punctuation other than . ! ? is
  ignored for context; digits and words outside the list break it. Every sentence whose id ends in 7 modulo
  50 is held out of the counts; 3,000 of them are the benchmark's test sentences.
- **"1,000 most common words" for the harness**: the original harness keeps its first definition (1,000
  tier-10 SCOWL words, fixed seed); the realistic benchmark uses the 1,000 most frequent glide-able words by
  Tatoeba count.
- **Frequency weights per tier**: 10 -> 1.0, 20 -> 0.45, 35 -> 0.18, 40 -> 0.08, 50 -> 0.03. Typed
  suggestions still rank by these tiers; glide uses the Tatoeba counts.
- **Glide decoder**: the streaming decoder replaced the whole-word SHARK2 decoder in the app, because it is
  far more accurate on realistic strokes (see Glide typing) and needs no wait after lift. The spec's
  ideal-path LRU cache has no counterpart any more: per-geometry work is one table of states per tree node,
  rebuilt when the key geometry version changes (rotation, height, layout). Parameters, in key pitches:
  resampling 0.25, location sigma 0.42 at letters and 0.45 between them, 0.40 at the first touch, first
  letters within 1.6, stay 0.8, skip 0.35, early lift 0.8 per state, slowness weight 1.0 with bias 0.5,
  turning weight 0, bigram weight 1.0, lookahead 0.5, beam 10 cost units and 3,000 hypotheses (three times
  wider when a word finds no candidate), 48 candidates re-aligned, 16 kept per word. Chosen by sweeps on the
  simulator (`GlideTuningTest`); to be revisited on recorded glides.
- **When nothing fits**: after the wider retry, the decoder falls back to the candidates the preview last
  showed rather than drop the gesture; one backspace removes the result.
- **Redoing a word in the field** (changed at the user's request, after a preview row was tried): the
  keyboard never rewrites text on its own. An earlier design rewrote up to four glided words in place when
  a later glide made another reading likelier, which changed text the user had already seen go in. A
  preview row that held glided words for two seconds before they went in came next; it grew into a second
  text field, and since Android cuts a field's connection before the keyboard hears the user tapped
  another field, anything still in it was lost on a field switch unless it was mirrored into the field
  anyway. Now glides go straight in and the user points at the word to redo: a cursor strictly inside a
  word, or one selected word, placed by the user. Selection reports within 600 ms of the keyboard's own
  edit are taken as its own, so the keyboard's cursor never targets. A cursor at a word's edge targets
  nothing, because that is where a tap between words lands. The targeted word is underlined with a
  composing region; the replacement is checked against the text around the cursor first, and goes in
  with the old word's capitals. The last glide stays unlearned until the next edit, so a glide redone
  right away is not learned as it was; the correction teaches the adaptation instead.
- **Learned user dictionary** (out of scope for v1, added at the user's request): on the phone only, in
  `personal_words.json` in the app's private files, written atomically when the keyboard hides. A word is
  learned when it is final (typed words on commit; a glide when the next edit happens, so backspace, a
  strip swap or redoing it right away are not learned as the wrong word).
  Learnable: 2 to 32 letters with apostrophes or inner hyphens, nothing with digits. Never learned from
  password, number, email, URL, terminal or no-suggestion fields, or fields with
  `IME_FLAG_NO_PERSONALIZED_LEARNING`. New words join after 2 uses. At most 5,000 words and 20,000 pairs;
  the least used, weighted by a 60-day half-life, are evicted first. Capitals: "GitHub" and "NASA" keep
  theirs; "Tokyo" keeps its capital only when used mid-sentence. The vocabulary is rebuilt in the
  background when the keyboard hides after a new word became known (or 50 uses since the last build), and
  the rebuilt model is swapped in only when no glide is in flight. Setting: Learn words
  I type (on).
- **Glide adaptation**: per-letter offsets in key pitches, a running mean of where kept glides passed each
  letter, capped at 50 observations per letter and 200 overall so it keeps following the user, shrunk
  toward the overall lean with a weight of 5 observations, at most 0.35 key from the centre; single points
  more than 0.8 key off are ignored. Corrections count twice, and only when the re-aligned stroke passed
  within 0.4 key of the meant word's letters on average: typing a different word over a glide is a change
  of mind, not a mis-glide. In `glide_adaptation.json`, with the glide and correction counts. Setting: Adapt
  glide to my swiping (on).
- **Android's personal dictionary**: read through `UserDictionary.Words` when the language loads, words of
  English or no locale, frequency 1 to 255 mapped to a use count; the keyboard never writes to it.
- **Phrase gliding**: off by default. A dip counts below the middle of the space bar or after 150 ms there,
  so grazing it on the way to c, v, b or n does not. Travel to and from the space bar costs 0.3 per point
  (swept 0.1 to 0.5); treating it as letters got 14.1% of words right.
- **Glide threading**: one decoder thread per keyboard process, previews at most every 40 ms and only when
  the words change; results carry the glide's id so a late result for an abandoned glide is ignored. A
  glide owns the keyboard until it ends: other fingers are ignored.
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
  trailing space, except after a phrase stroke that lifts inside the space bar. Backspace right after a
  glide deletes everything that glide wrote (all words of a phrase stroke; the leading space stays). The strip
  offers alternatives for the glide's last word.
- **Glide recorder**: prompts the 1,000 most frequent glide-able words in random order on a copy of the text
  keyboard sized like the real one. Only prompted words are recorded, in the app's private files; nothing
  leaves the phone unless exported through the system file picker. The trace keeps the letter key centres
  so a benchmark can replay it on the geometry it was made on.
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
- **Permissions**: the merged manifest declares `VIBRATE` and nothing else. AndroidX Core's automatic
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` is removed with `tools:node="remove"`; no code here or in the
  Compose/DataStore dependencies registers a runtime receiver through `ContextCompat`. If a future dependency
  does, it will fail loudly on Android 12 and older and this removal must go.
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
- **Language load time**: the dictionary loads in 2.5 s and the n-gram model and letter tree in 2.7 s on the
  API 36 emulator right after install (not yet compiled ahead of time); the keyboard appears at once and
  glide starts working when both are in.

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
- [x] Glide three words in a row ("always keyboard terminal"); the strip shows the word before lift.
      *(verified on the emulator)*
- [x] Tap inside a glided word: it is underlined and the strip shows it with its runners-up; glide another
      word and it is replaced, and the next glide goes at the end. Tap between two words and glide: a word
      is added with spaces around it. *(verified on the emulator: "hello world", tap in "world", glide
      "would", glide "again", tap between "hello" and "would", glide "big" gives "hello big would again")*
- [x] Press space with a word targeted, then glide: a word is added after it. *(verified on the emulator:
      tap in "big" of "hello big world", space, glide "bad" gives "hello big bad world")*
- [ ] Double-tap a word to select it, then glide: it is replaced. *(unit-tested; injected taps arrive too far
      apart to make a double-tap on the emulator)*
- [x] A word typed twice ("kubectl") is glidable after the keyboard hides and reopens; a word added to
      Android's personal dictionary is glidable; Settings > Personal words lists learned words.
      *(verified on the emulator, except the Personal words screen)*
- [ ] Settings > Personal words: delete a word, then the keyboard no longer glides it after reopening;
      reset glide adaptation zeroes the counts.
- [x] Phrase gliding on: "hello", dip below the middle of the space bar, "world" in one stroke writes
      "hello world"; lifting inside the space bar adds a space. *(verified on the emulator)*
- [x] Settings > Record glides: gliding the prompted word stores it, the count goes up, the next word
      appears; the exported file replays in `RecordedGlidesTest`. *(verified on the emulator with injected
      strokes; export through the file picker not exercised)*
- [ ] Record a few hundred glides on the Pixel and run `RecordedGlidesTest`.
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
