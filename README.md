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
- **Autofill in the strip** (Android 11+): the password manager's or autofill service's suggestions show as
  chips in the strip, in the keyboard's colours; tapping one fills the form.
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
(<https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2>) and Common Voice's English
sentence collection (the `server/data/en/*.txt` files of <https://github.com/common-voice/common-voice>, in
one folder). `--exclude` keeps the FUTO test and dev sentences out of the counts, so the real-swipe benchmark
stays fair:

```sh
tools/build_wordlist.py /path/to/scowl-2020.12.07 50
tools/build_ngrams.py /path/to/eng_sentences.tsv.bz2 --cv /path/to/cv-en --exclude /path/to/futo/test.jsonl /path/to/futo/dev.jsonl
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
   with the gesture's final speed statistics) and scored with the language model given the two words before
   them.
   The result is ready well within a millisecond on the JVM; the emulator logs 2 to 55 ms.
5. **Into the field, and redoing a word.** Glided words go straight into the field, with a space before them
   after a word and a space after them before one; a letter typed right after a glide starts a new word.
   To redo a word, tap inside it (or double-tap to select it, which also works for "a" and "I"): it is
   underlined and the strip shows it with its alternatives (its own runners-up if it was glided lately,
   suggestions otherwise). The next glide, or a tapped alternative, replaces it and keeps its capitals.
   To add a word instead, tap between words (a cursor at a word's edge targets nothing), or press space
   while a word is targeted, which moves past it. After the keyboard's own edits nothing is targeted, so
   gliding on never replaces anything, and nothing else in the field is ever rewritten. Each glide is
   decoded after the two words before it, with the language model mixed with the user's own word pairs, against a
   stroke measured on keys shifted by the user's learned offsets.
6. **Phrase gliding** (setting, off by default). Dipping below the middle of the space bar, or resting on it
   for 150 ms, ends a word without lifting; the space key lights up when the dip counts. The travel down to
   and up from the space bar belongs to no letter, so a word may finish early and coast into the space bar,
   and the next one may start anywhere on its approach, each at a small cost per point. Lifting inside the
   space bar after a dip adds a trailing space. The words of one stroke are decoded together, each scored
   with the two words before it, so a later word can change an earlier one before anything is written.
7. **Next-word suggestions** (setting, on by default). After a space the strip offers the three words most
   likely to come next, from the two words before the cursor (the three-word model mixed with the user's
   own word pairs), capitalised at a sentence start. Tapping one writes it with a space and offers the next;
   typing a letter replaces them with that word's suggestions. None in code mode, password fields or fields
   that ask for no suggestions.

The whole-word SHARK2 decoder (Kristensson & Zhai 2004) that shipped first is kept in the tests as the
baseline. `GestureSimulator` produces human-like strokes: noisy aim with a per-stroke offset, corners cut
toward the chord between neighbours, a smooth curve, speed following the two-thirds power law on curvature
with extra slowdowns at some intended letters, overshoot at the end, 120 Hz sampling that keeps only 2 dp
moves as the keyboard does.

Real fingers are sloppier than the simulator. The [FUTO swipe dataset](https://huggingface.co/datasets/futo-org/swipe.futo.org)
(MIT) has about a million real English swipes, each with its timing, the keyboard geometry it was made on
and the sentence it came from; the decoder's parameters are tuned on its dev split (coordinate descent,
`FutoSwipesTest.futoTune`) and measured on its test split, which the tuning never saw:

| Real swipes (FUTO test split, 10,000 in-dictionary words, words before known) | Before tuning | Tuned | Tuned, three-word model |
|---|---|---|---|
| Top-1 / top-3 | 80.9% / 85.7% | 89.0% / 95.6% | 91.0% / 96.1% |
| Top-1 without the words before | 79.0% (5,000 swipes) | 86.7% | 87.8% |
| Time per swipe after lift (JVM) | 0.50 ms | 1.28 ms | 2.4 ms (2.3 ms for the pair model in the same run) |

`FrictionTest` writes 400 of the dataset's sentences (4,205 words) through the text controller the way a
person would: each word glided with the swipe made for it, punctuation, digits and one-letter words tapped,
shift tapped for a capital the keyboard would not give, and a misread word fixed the cheapest way that
works (a strip alternative, else tapping inside it and gliding it again with someone else's swipe, else
selecting it and typing it). With the tuned decoder and the three-word model 92.4% of glided words are right
first time, 4.7% are fixed from the strip, 1.9% by gliding again and 1.0% only by typing (232 letters typed;
with pairs alone 90.5%, 5.9%, 2.1% and 1.4%, 302 letters); 99.0% of sentences end up exactly as meant, the
rest differing only in capitals (the dataset's own lowercase after "?" and "!", and "may" for
the month).

`GlideBenchmarkTest` prints the simulator's view, tuned values first, earlier values (tuned on the
simulator) second:

| Simulated strokes | Whole-word decoder | Streaming decoder, tuned / before |
|---|---|---|
| 1,000 most frequent words, no context, top-1 / top-3 | 76.1% / 88.1% | 90.8% / 99.5%, before 94.3% / 98.6% |
| 3,679 words of 600 held-out sentences, top-1 | 73.7% | 92.6% alone, 95.5% with context (99.3% top-3); before 93.9% and 96.3% |
| Original harness (tier-10 words, jittered ideal paths), top-1 / top-3 | 93.5% / 99.7% | 87.8% / 98.2%, before 94.5% / 99.5% |
| Phrase strokes of 2-4 words with the travel to and from the space bar | | 91.2% (95.4% one stroke per word), before 94.2% |
| Sentences glided word by word, each glide free to re-read up to four earlier words (a decoder capability the keyboard does not use: it never rewrites text on its own) | | 95.9% when glided, 97.2% at sentence end |
| A swiper who lands 0.15 key right and 0.3 row low, before and after ten days of adapting (`GlideAdaptationTest`) | | 84.0% before, 91.3% after |

The looser matching costs a little on the simulator's neat strokes and wins much more on real ones. Record
your own with **Settings > Record glides**, which prompts common words on the real keyboard and keeps each
glide with its timing and the key positions until you export it. Put exported `.jsonl` files in
`app/src/test/resources/glide/traces/` (or point `DEVBOARD_TRACES` at them). The dataset is not in the
repository; download `test.jsonl`, `dev.jsonl` and `swipe-5/layouts/qwerty.json` into one folder and run:

```sh
./gradlew testDebugUnitTest --tests '*GlideBenchmarkTest*' --tests '*RecordedGlidesTest*' -i | grep 'GLIDE BENCH'
FUTO_SWIPES=/data/test.jsonl FUTO_LIMIT=10000 ./gradlew testDebugUnitTest --tests '*FutoSwipesTest.futoSwipes*' --rerun -i | grep FUTO
FUTO_TUNE=/data/dev.jsonl FUTO_SWIPES=/data/test.jsonl ./gradlew testDebugUnitTest --tests '*FutoSwipesTest.futoTune*' --rerun -i | grep FUTO
FUTO_SWIPES=/data/test.jsonl FUTO_LIMIT=50000 ./gradlew testDebugUnitTest --tests '*FrictionTest*' --rerun -i | grep FRICTION
GLIDE_TUNE=1 ./gradlew testDebugUnitTest --tests '*GlideTuningTest*' -i | grep 'GLIDE TUNE'   # simulator sweeps
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
  was glided lately, to the word you meant and counts twice, when the stroke plausibly was that word. It
  learns slowly and ignores sloppy glides, and only so much counts each day, so one careless day cannot
  throw it off. Reset in Settings > Personal words.
- **Going back.** The learned words and the swipe adaptation as they stood at the start of each of the last
  14 days are kept; Settings > Personal words > Undo recent learning goes back to any of them. Deleting a
  word deletes it from those days too.
- **Android's personal dictionary.** Its words (English, or with no locale) join the vocabulary when the
  keyboard loads.

## Decisions

- **Autofill chips** (R11): the strip asks the autofill service for inline suggestions, styled with the
  bar-chip colour and strip text colours, and shows them in the bar's row (or the suggestions' in Auto mode)
  while no word is composed, so the keyboard's height never changes. They show in password fields too: they
  are the password manager's, drawn and filled by it, and the keyboard neither reads nor learns from them;
  "no suggestions in password fields" is about the keyboard's own words. Each chip is a surface the service
  draws: it keeps the size the platform gives it and goes into a row that is already visible, since a chip
  attached while hidden gives up its surface for good. A known limit: the platform asks for the chip style
  once per app screen, and if that happens before the keyboard's window has ever been shown in a fresh
  keyboard process, it cannot draw the chips on that screen; the next screen has them.

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
- **Word frequencies and context**: word, word-pair and three-word counts from Tatoeba's English sentences
  (2.0 million sentences; CC BY 2.0 FR), counted twice, and Common Voice's English sentence collection (1.6
  million sentences; CC0), counted once: 43.5 million weighted words, counted only for words in the word list.
  Pairs are kept from a weighted count of 2 (so a Tatoeba pair seen once stays), with the 400 most frequent
  followers of a word; three-word counts only after pairs seen 20 times, followers from a count of 3, the 24
  most frequent. 7.4 MB, up from 1.8 MB for Tatoeba's pairs alone. Smoothing is Witten-Bell: the three-word
  count backs off to the pair, the pair to the unigram, which mixes the real count with a small pseudo-count
  by SCOWL tier so unseen words still rank; what pruning dropped from a context also goes to the lower order
  (without that the three-word model scored worse than pairs alone: perplexity 182 against 178 on held-out
  sentences; with it 104 against 155). The weights were picked on the FUTO test split and the held-out
  sentences, against Tatoeba alone and both corpora at equal weight: Common Voice lifts real swipes from 88.9%
  to 91.0% top-1 (its sentences are closer to what people write than Tatoeba's short lessons), and counting
  Tatoeba twice keeps phrase glides at 91.2% (90.1% at equal weight) at 2 MB more. The FUTO test and dev
  sentences are excluded from the counts. The cost: autocorrect, which ranks by the unigram alone, fixes
  88.5% of slips instead of 89.1%, and the simulator's held-out Tatoeba sentences score a little lower with
  context (95.5% against 96.1%). Tatoeba uses "Tom" as its default name (3%
  of all words); counts involving "tom" are scaled to the level of "john". Punctuation other than . ! ? is
  ignored for context; digits and words outside the list break it. Every sentence whose id ends in 7 modulo
  50 is held out of the counts; 3,000 of them are the benchmark's test sentences.
- **"1,000 most common words" for the harness**: the original harness keeps its first definition (1,000
  tier-10 SCOWL words, fixed seed); the realistic benchmark uses the 1,000 most frequent glide-able words by
  Tatoeba count.
- **Frequency weights per tier**: 10 -> 1.0, 20 -> 0.45, 35 -> 0.18, 40 -> 0.08, 50 -> 0.03, used as pseudo
  counts where a word has no Tatoeba count. Glide and typed suggestions both rank by the language model's
  word probability (Tatoeba counts, these tiers where counts are missing, and the user's own words), so "the"
  outranks "tea" although both are tier 10.
- **Glide decoder**: the streaming decoder replaced the whole-word SHARK2 decoder in the app, because it is
  far more accurate on realistic strokes (see Glide typing) and needs no wait after lift. The spec's
  ideal-path LRU cache has no counterpart any more: per-geometry work is one table of states per tree node,
  rebuilt when the key geometry version changes (rotation, height, layout). Parameters, in key pitches:
  resampling 0.25, location sigma 0.84 at letters and 1.125 between them, 0.40 at the first touch, first
  letters within 1.6, stay 0.4, skip 0.2625, early lift 1.8 per state, slowness weight 1.0 with bias 1.0,
  turning weight 0.5, bigram weight 0.75, lookahead 0.5, beam 10 cost units and 3,000 hypotheses (three
  times wider when a word finds no candidate), 48 candidates re-aligned, 16 kept per word. Tuned on real
  swipes (the FUTO dataset's dev split, see Glide typing); a second pass from these values found nothing
  better. The earlier values (sigma 0.42 and 0.45, stay 0.8, skip 0.35, early lift 0.8, bias 0.5, turning 0,
  bigram 1.0) were tuned on the simulator and scored 80.9% on real swipes against 89.0% after tuning (91.0%
  with the three-word model, whose context weight kept the tuned 0.75). The cost: with
  no context a perfectly drawn "hello" now reads "help" first ("hello" second), because matching is loose
  enough for real fingers; words that share a path ("of" and "off", "to" and "too") were always settled by
  frequency and context.
- **When nothing fits**: after the wider retry, the decoder falls back to the candidates the preview last
  showed rather than drop the gesture; one backspace removes the result.
- **Whose glides tune the decoder** (the user's decision): the shipped parameters are tuned on the FUTO
  dataset only. Glides recorded with **Settings > Record glides** (by the author or sent in by users as
  exported `.jsonl`) are replayed by `RecordedGlidesTest` to measure, never to tune: a trial search on
  1,058 real swipes treated as one person's recordings gained 2.6 points on that person's held-back words
  and lost 1.1 on FUTO's test split. A person's own habits are learned on their phone by the glide
  adaptation, with its daily limits and snapshots.
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
  letter, capped at 200 observations per letter and 800 overall so it keeps following the user slowly,
  shrunk toward the overall lean with a weight of 5 observations, at most 0.35 key from the centre; single
  points more than 0.8 key off are ignored. Corrections count twice, and only when the re-aligned stroke
  passed within 0.4 key of the meant word's letters on average: typing a different word over a glide is a
  change of mind, not a mis-glide. In `glide_adaptation.json`, with the glide and correction counts.
  Setting: Adapt glide to my swiping (on).
- **A bad day does no lasting harm** (asked for by the user: a night of drunk gliding must not ruin the
  tuning): a glide that strays more than 0.45 key from its letters on average teaches nothing; each
  observation's pull on an estimate is clipped to 0.25 key before the 1/n step; only 400 letter
  observations (about 80 words) count per day. In `GlideAdaptationTest` a night of 1,000 sloppy,
  uncorrected glides after ten sober days moves no key more than 0.042 of a key and leaves the next
  morning's accuracy unchanged (91.3% both). The state at the start of each of the last 14 days is kept, in
  `glide_adaptation.json` and, for learned words, as `personal_words.day-N.json` beside the vocabulary;
  Settings > Personal words > Undo recent learning restores both to the start of a chosen day. Deleting a
  word rewrites the kept days without it, and deleting everything deletes them, so going back never
  brings a deleted word back.
- **Android's personal dictionary**: read through `UserDictionary.Words` when the language loads, words of
  English or no locale, frequency 1 to 255 mapped to a use count; the keyboard never writes to it.
- **Phrase gliding**: off by default. A dip counts below the middle of the space bar or after 150 ms there,
  so grazing it on the way to c, v, b or n does not. Travel to and from the space bar is free (swept 0 to
  0.3 per point with the tuned location spread; 0 is best, 89.4%, and the next word may still only start
  within its first 40 points); treating it as letters got 18.4% of words right.
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
- **Autocorrect** (on space, and on sentence punctuation; on enter and a following glide when the suggestions
  are for the word) leaves a dictionary word alone, except one typed without the apostrophe of a contraction
  at least 50 times more common ("cant", "wont", "dont"; "its", "were", "well" and "ill" stay). Otherwise the
  most likely of the six suggestions wins, among common words (tier 35 or better, or used by the user) within
  one slip (two from six letters): frequency times exp(-6 x slip cost) times 0.35 for a wrong first letter.
  Slip costs follow how fingers miss on QWERTY: a skipped apostrophe 0.2, one of a double letter dropped 0.4,
  a neighbouring key, two letters swapped or a letter doubled 0.5, another letter dropped 0.8, anything else
  1.0. Two-letter words are only corrected by a letter they dropped, so "js" and "ui" stay. Backspace right
  after an autocorrect puts back what was typed, and that word is not corrected again in the field. When
  the suggestions are not for the word yet (a quick space), the correction is worked out in the background
  and applied if the word and space still stand as typed. On 7,000 one-slip typos of held-out words
  (`AutocorrectBenchmarkTest`; the slips are synthetic, of the kinds the costs describe): 88.5% fixed, 9.9%
  changed to another word (mostly real ambiguities such as "tht" for "that" or "the"), 1.6% left alone; no
  correctly typed word changed. Before: 58.4% fixed, because autocorrect looked only at the first suggestion
  and gave up whenever a typo was also the start of some rare word ("helo" starts "helot"). Setting:
  Autocorrect (off by default).
- **Walking back with backspace**: when backspace removes the space or punctuation after a word, that word
  becomes the composing word again (underlined) and the strip offers what else it could be: its runners-up
  if it was glided lately, suggestions for it otherwise. A strip pick or a glide replaces it (a correction,
  with the old stroke re-aligned when it was glided), typing goes on with it, and backspace deletes its
  letters and then reopens the word before. Space leaves it as it was, without autocorrect or learning it
  again. A word glued to digits or symbols before it ("x86") is not reopened. Tapping the strip gives the
  same feedback as a key.
- **The pronoun I**: "i", "i'm", "i'd", "i'll" and "i've" typed on their own get a capital as the word ends,
  with auto-capitalisation on, whether or not autocorrect is.
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
  still being composed. Autocorrect reuses the candidates the background thread already produced for the
  word, and never scans the dictionary on the main thread.
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
- **Theme colours**: the Wallpaper theme takes Material 3 dynamic colour from `android.R.color.system_*` on
  Android 12+; every other theme is an original fixed palette (see Themes).
- **A look of its own** (asked for by the user: it should not look like a copy of Gboard): the keys stay
  where they were, but each is a keycap, a face raised above an edge in a deeper shade that a press sinks
  onto; the glyphs are original and terminal-flavoured (backspace a chevron erasing toward a block cursor,
  return a bent arrow with an open chevron head, shift a caret that gains an underline while on); and the
  space bar carries a small cursor mark. The default is no longer the system's wallpaper colours (the look
  the stock keyboard wears) but Auto, which follows the system between the original Night and Day.
- **Themes**: Auto, Wallpaper (the system palette on Android 12+, Auto before) and twelve original palettes
  with original names: Night, Day, Phosphor, Amber, Deep Sea, Ember, Orchid, Forest, Paper, Glacier, Sand,
  High Contrast. Each is the same set of tokens (`Palettes`); keycap edges are derived from the key colours.
  Settings > Appearance picks one from a row of swatches drawn in its own colours, and the setup and
  settings screens wear it too. This replaces the System/Light/Dark choice and the Dynamic color switch.
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
- [x] Settings > Personal words lists learned words; delete removes one; Undo recent learning lists the kept
      days and going back to the start of today restores the counts of that morning. *(verified on the
      emulator: "world" went from 5 uses back to 4 and "hello" from 3 to 2)*
- [ ] Reset glide adaptation zeroes the glide and correction counts.
- [x] Phrase gliding on: "hello", dip below the middle of the space bar, "world" in one stroke writes
      "hello world"; lifting inside the space bar adds a space. *(verified on the emulator)*
- [x] Settings > Record glides: gliding the prompted word stores it, the count goes up, the next word
      appears; the exported file replays in `RecordedGlidesTest`. *(verified on the emulator with injected
      strokes; export through the file picker not exercised)*
- [ ] Record a few hundred glides on the Pixel and run `RecordedGlidesTest`.
- [ ] Password: no suggestions, no glide, no preview text left anywhere.
- [x] Autofill (with a test autofill service on the emulator): chips in the strip in the keyboard's colours;
  tapping one filled the username and password.
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
