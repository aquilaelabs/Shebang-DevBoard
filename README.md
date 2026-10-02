# Shebang DevBoard

An Android keyboard (IME) for developers, written in Kotlin.

- **Text mode**: QWERTY with suggestions, long-press alternates and auto-capitalisation, and glide typing
  that decodes while the finger moves: the strip shows the word before you lift; the word before is taken
  into account; tap inside a word (or double-tap it) and glide to redo it; the keyboard learns your words
  and how you swipe, on the phone; and (optionally) one stroke can write several words by dipping into the
  space bar between them.
- **Code mode** (`#!` key): every digit and printable ASCII symbol on one page, plus arrow keys. The two rows
  under the digits are the usual phone symbol page's (`@ # $ _ & - + ( ) /`, then `= * " ' : ; ! ? < >`), the
  code extras (`` ` ~ | \ [ ] { } ``) the row below, and backspace, comma, space, period and enter sit where
  they do in text mode; `%` and `^` are held on 5 and 6. No shift, no autocorrect.
- **Terminal bar**: a horizontally scrolling strip of terminal keys (Esc, Tab, Ctrl, Alt, ^C, arrows, F1-F12,
  snippets...) that sends real `KeyEvent`s, so Termux and other terminals receive them. Sticky modifiers let
  `Ctrl` then `c` on the main keyboard send Ctrl+C.
- **Autocorrect that knows where you tapped**: a slip is weighed by where the finger came down, and the
  keyboard learns where your own taps land on each key.
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
tools/build_wordlist.py /path/to/scowl-2020.12.07
tools/build_ngrams.py /path/to/eng_sentences.tsv.bz2 --cv /path/to/cv-en --exclude /path/to/futo/test.jsonl /path/to/futo/dev.jsonl
```

The glide model is trained on the GPU with PyTorch (`python-ml` from the toolchain store's `pytorch`) on
FUTO's training split, then exported as the app's asset with test vectors for the Kotlin port
(`GlideModelTest`); `score.py` picks its weight on the dev split from a decoder dump
(`FUTO_DUMP=... ./gradlew testDebugUnitTest --tests '*FutoSwipesTest.futoDump'`):

```sh
python-ml tools/glide_model/prep.py /path/to/futo/train.jsonl train.npz
python-ml tools/glide_model/prep.py /path/to/futo/dev.jsonl dev.npz
bb gpu run -- python-ml tools/glide_model/train.py train.npz dev.npz glide.pt --hidden 64 --conv 64
python-ml tools/glide_model/score.py glide.pt cands-dev.jsonl cands-test.jsonl
python-ml tools/glide_model/export.py glide.pt app/src/main/assets/glide/glide_model.bin --vectors dev.npz app/src/test/resources/glide/glide_model_vectors.txt
```

The next-word model is trained the same way on the n-gram model's sentences plus English Wikinews (its
sentences go in the Common Voice folder), then exported with 8-bit word tables:

```sh
python3 tools/lm_model/wikinews.py enwikinews-latest-pages-articles.xml.bz2 /path/to/cv-en/zz_wikinews.txt
python-ml tools/lm_model/prep.py /path/to/eng_sentences.tsv /path/to/cv-en corpus.npz --exclude /path/to/futo/test.jsonl /path/to/futo/dev.jsonl
bb gpu run -- python-ml tools/lm_model/train.py corpus.npz lm.pt --futo /path/to/futo/test.jsonl --emb 128 --hidden 384 --epochs 8 --batch 128 --bptt 32
python-ml tools/lm_model/glide_context.py lm.pt cands-dev.jsonl cands-test.jsonl
python-ml tools/lm_model/export.py lm.pt app/src/main/assets/dict/en_next_word.bin --vectors app/src/test/resources/dict/next_word_vectors.txt
```

## Layout of the code

| Package (`dev.shebang.devboard.`) | What lives there |
|---|---|
| `ime` | `DevBoardService` (the `InputMethodService`), `FieldInfo` (EditorInfo -> what the field allows), `TextInputController` (composing, suggestions, smart spacing, redoing a tapped word, what is learned when), `GlideText` (context word and casing), `LanguageLoader` and `LanguageBuilder` (background load and rebuilds with learned words), `SystemUserDictionary` (Android's personal dictionary), `KeyboardSizing`, `Feedback` |
| `layout` | JSON models (`LayoutDef`, `KeyDef`, `BarItem`), `LayoutParser`, `KeyboardGeometry` (pixel positions computed at runtime), `KeyCodeNames` |
| `view` | `KeyboardView` (one Canvas-drawn view with its own multitouch), `KeyPopup` (preview and alternates), `TerminalBarView`, `SuggestionStripView`, `TopStripView`, `KeyboardTheme` |
| `input` | `ModifierState` (sticky modifier state machine), `KeyEventMapper`/`CharKeyCodes` (character -> keycode plans), `KeySender` (down/up KeyEvents with meta state) |
| `glide` | `LexiconTrie` (the dictionary as a tree of key sequences), `StreamingGlideDecoder` (beam search while the finger moves, exact re-alignment after lift, joint decoding across words), `GlideModel` (the learned reading of strokes), `GlideSession` (decoder thread fed by a lock-free ring), `GlideAdaptation` and `TapModel` (where this user's glides and taps land), `GlideTrace` (recorded glides), `KeyLayoutModel` |
| `dict` | `Dictionary` (sorted word list with tiers), `NgramModel` (word, word-pair and three-word statistics), `NextWordModel` (the neural next-word model), `WordPredictions` (the strip's next words from both), `Suggester` (prefix completion + edit-distance correction), `LetterPrior` |
| `settings` | `Settings`, `SettingsRepository` (DataStore), `SetupActivity`, `SettingsActivity` with the bar editor and the glide recorder (Compose + Material 3), `GlideRecorderView`, `GlideTraceStore` |

Layouts live in `app/src/main/assets/layouts/*.json`, the default terminal bar in `assets/bar/default.json`,
the word list in `assets/dict/en_words.txt`, the n-gram model in `assets/dict/en_ngrams.bin`, the next-word model
in `assets/dict/en_next_word.bin` and the glide model in `assets/glide/glide_model.bin`.

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
   with the gesture's final speed statistics); the best 16 are then read again by the glide model, a small
   network trained on real swipes, and weighed with the next-word model's reading of the sentence and the
   three-word model given the two words before them (see the Decisions). The emulator logs a median of 9 ms
   after lift.
5. **Into the field, and redoing a word.** Glided words go straight into the field, with a space before them
   after a word and a space after them before one; a letter typed right after a glide starts a new word.
   To redo a word, tap inside it (or double-tap to select it, which also works for "a" and "I"): it is
   underlined and the strip shows it with its alternatives (its own runners-up if it was glided lately,
   suggestions otherwise). The next glide, or a tapped alternative, replaces it and keeps its capitals.
   To add a word instead, tap between words (a cursor at a word's edge targets nothing), or hold space
   while a word is targeted, which moves past it; a tap on space with the cursor inside a word puts a space
   right there, splitting it ("twowords"). Holding #! (or ABC) opens the system's keyboard picker, which
   holding space used to. After the keyboard's own edits nothing is targeted, so
   gliding on never replaces a word; the one exception is the glided word right before it (setting, on by
   default), which the next glide may fix when the two together clearly read otherwise, and only while it
   stands as it went in and was not picked from the strip. Each glide is
   decoded after the two words before it, with the language model mixed with the user's own word pairs, against a
   stroke measured on keys shifted by the user's learned offsets.
6. **Phrase gliding** (setting, off by default). Dipping below the middle of the space bar, or resting on it
   for 150 ms, ends a word without lifting; the space key lights up when the dip counts. The travel down to
   and up from the space bar belongs to no letter, so a word may finish early and coast into the space bar,
   and the next one may start anywhere on its approach, each at a small cost per point. Lifting inside the
   space bar after a dip adds a trailing space. The words of one stroke are decoded together, each scored
   with the two words before it, so a later word can change an earlier one before anything is written.
7. **Next-word suggestions** (setting, on by default). After a space the strip offers the three words most
   likely to come next: the next-word model's reading of the whole sentence, mixed with the three-word
   model and the user's own word pairs (see the Decisions), capitalised at a sentence start. Tapping one writes it with a space and offers the next;
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
| Sentences glided word by word, each glide free to re-read up to four earlier words (the keyboard lets it fix one) | | 95.9% when glided, 97.2% at sentence end |
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

- **Clipboard chip** (the user's request): text copied in the last three minutes gets a chip in the strip's
  top row (the autofill row, ahead of any autofill chips) while no word is composed: "Paste" and the clip's
  first 28 characters, or dots when the copying app marked it sensitive (Android 13+, as password managers
  do) or the field is a password field. A tap pastes it; a sideways swipe puts it away with the autofill
  chips. A clip pasted or swiped away is not offered again. The clipboard is read only while the keyboard is
  up, when a field opens or the clip changes, and nothing of it is kept or learned. Freshness goes by when
  the keyboard saw the clip change, else by the clip's own timestamp (its clock differs between releases,
  so either is accepted). Terminals get no chip.
- **Enter key**: always the enter icon, whatever the field asks for (search, go, send, done); it still
  performs that action. Labels such as "Search" were drawn on the key until the user found them too big and
  preferred the icon not to change.
- **Autofill chips** (R11): the strip asks the autofill service for inline suggestions, styled with the
  bar-chip colour and strip text colours, and shows them in the bar's row (or the suggestions' in Auto mode)
  while no word is composed, so the keyboard's height never changes. They show in password fields too: they
  are the password manager's, drawn and filled by it, and the keyboard neither reads nor learns from them;
  "no suggestions in password fields" is about the keyboard's own words. Each chip is a surface the service
  draws: it keeps the size the platform gives it and goes into a row that is already visible, since a chip
  attached while hidden gives up its surface for good. The chips are centred (the row scrolls when there are
  more than fit), and a sideways swipe carries them with the finger, fading, then past a third of the strip
  (or flung) they slide off and the bar comes back for the field; let go sooner and they spring back (the
  user's request; a swipe down that made them vanish was tried first). A service's chip moves with the row
  but does not fade: its surface is the service's. When the row scrolls, only a swipe on past its end does. A swipe
  that starts on a chip reaches the keyboard too, because the platform hands a drag on a chip back to it. A known limit: the platform asks for the chip style
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
  frequency tiers, plus contractions, capitalised words, SCOWL's proper names (brands, products, people) and
  abbreviations written in capitals of three letters or more (and a few two-letter ones people type, such as
  "AI" and "TV"), all up to level 50, and `tools/extra_words.txt`, this project's own short list of words SCOWL
  lacks ("app", "offline", "config", "JSON", "Reddit", "WhatsApp", "McDonald's"). Possessive forms ("ability's")
  are dropped, but the 's contractions of a closed set of pronouns and function words ("it's", "that's",
  "let's") are kept: SCOWL files them among the possessives. 72,074 words, written in the order the app
  searches it so loading skips the sort. Levels 60+ were left out as spell-checker noise.
- **A word the dictionary lacks may be meant as typed** (the owner's request: autocorrect should judge
  whether an unknown word is a slip or intended, without the check mark). A correction of a word the
  dictionary lacks now has to be likely enough to beat keeping it: its score (how common the correction is
  after the words before, times how likely the real taps made that slip, so cleanly hit keys count as
  intent) must reach 2e-8. Measured on TSI's real taps, with each correctly typed phrase word decided as if
  the dictionary lacked it (standing in for names, terms and handles): words meant as typed kept 29.1% before
  and 65.7% now; typos fixed 77.7% to 75.9%, typos turned into another wrong word 8.4% to 4.3% (a wrong
  correction costs more than a typo left), and words in the dictionary still never changed. Thresholds of
  1e-9 to 1e-7 traded these off (43% to 76% kept, 77.4% to 72.8% fixed). An unknown word in capitals ("GPU")
  or with a capital mid-sentence where the keyboard gave none (a name) is always left as typed.
- **"its" or "it's" from the words before** (the owner's request): a word typed without its apostrophe that is
  a word either way ("its", "were", "well", "ill", "cant") becomes the contraction only when, after the two
  words before it, the contraction is at least 20 times likelier. On 923 uses in sentences the model never
  counted (held-out Tatoeba and the FUTO test split), 89.2% come out as meant, against 79.6% with the old rule
  (50 times likelier by frequency alone); 75.4% of the contractions are recovered, and 10 of 557 words meant
  without an apostrophe got one ("its", "were" and "well" never did). Only the words before count: the word
  after is not typed yet, and a word already passed is not changed.
- **Punctuation in a word is on purpose** (the owner's request): a word typed with an apostrophe keeps its
  letters, and only the apostrophe may move ("ca'nt" becomes "can't", "y'all" stays); a word joined to the one
  before by a hyphen, slash, dot, @ or the like with no space ("f-droid", "node.js", "and/or") is not
  autocorrected at all. The strip still offers alternatives. "i'm" still gets its capital (not a correction).
- **Names, brands and abbreviations** (the owner's request: "url, github, mcdonalds, google" were missing):
  the capitalised words to level 50, the proper names and the abbreviations were added after measuring each
  set. On the same 10,000 FUTO swipes glide top-1 went from 91.0% to 90.9%; FUTO swipes whose word the
  dictionary lacked halved (969 to 492 of 10,000 read); on whole FUTO sentences the friction test needed 1.96
  actions per word instead of 2.20 (names are glided instead of tapped out) with 98.8% of sentences exactly
  as meant instead of 99.0%; on TSI, autocorrect fixed 77.7% of typos instead of 78.4%. The brands and
  abbreviations alone cost nothing (78.4%, 2.14 actions per word) but leave out Paris and London, which SCOWL
  files with the first names. Three rules keep names from getting in the way: a word that is also a name
  ranks for autocorrect as the word does ("rich" against the name "Rich"), a name typed in lowercase is
  weighed against the words it is a slip away from ("thar" becomes "that") and otherwise gets its capitals
  ("github" becomes "GitHub"), and Tatoeba's second default name, "Mary", is scaled down like "Tom".
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
- **Glide parameters after the three-word model** (1 Oct): re-tuned on FUTO's dev split with the new
  model, the search reached 92.2% there (from 91.3%) but 90.7% on the test split against 91.0% for the
  values kept, so the earlier values stay.
- **A next-word model** (the owner's request to use the GPU for suggestions too): an LSTM over words (32,000
  words, 128-wide word table shared by input and output, 384 units, 5M weights; 6.3 MB with the word table
  in 8 bits, which cost nothing measurable) reads the whole sentence before the cursor, where the n-grams read
  two words. Trained on the n-grams' Tatoeba and Common Voice sentences plus English Wikinews (5.5 million
  words, CC BY 2.5): with Wikinews the same model was better on both held-out sets, while the n-grams gained
  nothing from it and were left as they were. The n-grams stay, mixed in, because they carry the user's own
  words and pairs. Suggestions after a space rank both models' best words by 0.5 n-gram cost + 0.5 next-word
  cost (chosen on FUTO's dev sentences): next word among the three 36.1% to 39.3% on Tatoeba's held-out
  sentences and 28.7% to 32.7% on FUTO's test sentences, first 21.9% to 24.2% and 16.3% to 19.4%. A glide's
  first word gets 0.5 times its next-word cost (chosen on the dev split): FUTO test top-1 93.7% to 94.4%
  (115 fixed, 51 broken in the candidates' sign test, p < 1e-6), words glided on in sentences 95.3% to
  95.6%, the friction test's right first time 95.2% to 95.5%. The model reads the sentence while the finger
  is still moving, so lifting waits for nothing: 8.6 ms median after lift on the emulator (21 glides). A
  larger version (10M weights, 20 MB) was no better at glide. The app grows from 16 to 22 MB; language
  loading takes 0.5 to 1.2 s on the emulator (2.4 s on the first start after installing). Plain Kotlin,
  checked against PyTorch (`NextWordModelTest`); a word the model does not know gets its unknown word's
  share less a little, never nothing.
- **A learned glide model** (the owner's request to use the GPU): a small network (a convolution and two
  bidirectional GRU layers, 138k weights, 276 KB as half floats) reads a glide point by point and says how
  likely each key is meant there; a word's model cost is how unlikely its keys are given the whole stroke
  (CTC). Trained on 909k swipes of FUTO's training split (12 passes, 15 minutes on the RTX 3070 Ti). It does
  not replace the decoder: once the finger lifts, the decoder's 16 best candidates get 0.75 times the model's
  cost added to their own (0.75 chosen on the dev split), so the model reorders what the decoder found, and
  the dictionary, the word context and the user's aim still count. On FUTO's test split (10,000 swipes,
  never seen in training or tuning) top-1 went from 91.0% to 93.7% through the app's own Kotlin code
  (sign test on the scored candidates: 341 fixed, 55 broken, p < 1e-50); words glided on in sentences
  92.7% to 95.3%; in the friction test glided words right first time 92.4% to 95.1% and those only fixable
  by typing halved. The simulator's synthetic strokes score lower with it (91.2% to 89.2% alone, 95.3% to
  94.8% with context), as expected of a model of real fingers reading drawn ones; real swipes decide. A
  larger version (492k weights) scored 94.1% but took more than twice as long: 28 ms median after lift on
  the emulator against 12 ms, so the small one ships. Written in plain Kotlin (no machine-learning library),
  checked against PyTorch's outputs; only whole-word strokes are read (not a phrase's words, whose strokes
  run into the space bar). Learning a user's aim still aligns strokes without it.
- **Looser along the stroke at turns** (from the owner's question about overshooting): at a letter where the
  stroke turns back, real fingers stop short along the way they came (FUTO dev: 0.16 key widths on average,
  spread 0.27 along against 0.20 across), so at a turn the decoder allows twice the spread along the
  direction of travel. Tuned on FUTO's dev split (2x to 3x all scored alike; expecting the finger short by a
  set amount did not help), then checked once on the test split: top-1 90.6% to 91.0% on 10,000 swipes,
  54 swipes fixed and 17 broken (sign test p < 0.0001); the friction test's glided words right first time
  92.0% to 92.4%. The simulator's strokes, whose noise is the same every way, dip 0.1 to 0.3 points; decoding
  costs 11% more time. Learning a user's aim still aligns strokes without it, so what is learned is where the
  finger went. Other habits measured (the arc of each person's strokes, starting late, lifting early) stay
  research on roadmap R20 until one is as clearly better.
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
- **Check mark before autocorrect** (the user's request, after Samsung's keyboard): while a word is typed
  that space would autocorrect, the strip shows the change ahead of time, in the middle in bold accent,
  with the word as typed on the left behind a check mark and another candidate on the right. The decision
  is worked out with the suggestions, under the same conditions space uses (autocorrect on, not code-like,
  not a word that stands elsewhere in the text, not one kept before). Tapping the check mark writes the
  word as typed with a space, and autocorrect leaves that word alone for the rest of the field. With
  autocorrect off the strip is as before.
- **Typed words ranked by the words before** (R13): the strip's suggestions and autocorrect weigh each
  candidate by the three-word model (mixed with the user's own word pairs) at 0.75 against how common the
  word is overall at 0.25, as glide does, so "haie" after "cut my" becomes "hair" rather than "have" and
  "vook" after "she can" becomes "cook" rather than "book". Weights of 0, 0.5, 0.75 and 1 fixed 88.5%,
  93.9%, 94.3% and 94.5% of synthetic slips, and 77.0%, 77.7%, 77.7% and 77.5% of TSI's real-tap typos;
  0.75 is the best on real taps. A word the dictionary knows is still never changed, and nothing changes
  an earlier typed word: correcting real words by context ("if" for "of") was considered and left out at
  the user's request.
- **Keys weigh the next letter's odds**: on top of where the finger landed, a tap on a letter key weighs how
  likely each letter is next: the dictionary words that start with what has been typed, each weighted by
  the three-word model with the words before (as autocorrect does), summed by their next letter, with 10%
  of the odds spread evenly so words the dictionary lacks can still be typed (floors of 1%, 2%, 5%, 10% and
  20% gave 97.4%, 97.4%, 97.3%, 97.2% and 97.0% of words right, and 90.4%, 91.6%, 94.0%, 95.2% and 95.2% of
  the words the dictionary lacks; 10% gives up a little on common words for names and code). Worked out in the background
  after each edit (0.4 ms per letter on the JVM) and cleared at once on every edit, so a tap never uses odds
  for another prefix. Only in plain text fields outside code mode: not in password, email, URL, number or
  terminal fields. On TSI's phrase words with each person's learned offsets: letters typed as meant 97.0%
  without the odds, 99.1% with word frequency alone, 99.3% with the words before; words typed right 87.9%,
  96.6% and 97.3% (TSI's own language model's letter odds give 95.4%). Of the 83 words the dictionary lacks,
  91.6% came out right without the odds and 94.0% with them. Weights of 0.5, 1, 1.5 and 2 against the tap
  gave 95.9%, 97.3%, 96.5% and 94.4% of words; 1 it is.
- **Thumbs that miss the space bar get a space** (the user's request): on TSI, 10.7% of taps meant for space
  land on the letters above it (thumbs land about 12 dp above the bar's middle), while letters almost never
  land on the bar (1 of 6,074). So a tap that comes down low on a letter just above the bar is weighed
  between that letter and the bar: the bar as where its taps fall (anywhere across it; down, a 13.8 dp spread
  around a point 12.3 dp above its middle) against the letter's tap model, each with its odds, where the
  bar's are the chance that what has been typed is the whole word (the same dictionary and model sums as
  the letter odds, 18% when nothing is known). On TSI's 9,854 phrase taps that landed on the bottom row or
  the bar, taps meant for space typed as space went from 90.7% to 99.8% and letters turned into spaces from
  1 to 15 (0.3%), 26 wrong in all against 513 (where taps land alone, without the word: 98.6%, 47, 123).
  Leans of 8 to 16 dp and spreads of 11 to 17 dp all gave 23 to 40 wrong. A tap above the letter's middle
  stays the letter however finished the word looks, a tap on the bar always stays a space, and it is
  decided when the tap ends, so a glide can still start on those letters. Plain text fields only, like the
  letter odds.
- **Keys follow where the user taps** (R14): a touch on a letter key types the letter whose usual landing
  spot is nearest: its centre nudged by the overall lean and this user's learned offsets (the same tap
  adaptation as R12, capped at 0.35 of a key). Other keys go by their drawn edges, and the key preview
  shows the letter that will be typed. Prompted by a friend's recording whose typos were almost all the
  key to the left of the one meant. On TSI's phrase words, replayed per person with the offsets learned as
  they type (from words that came out right, a day per task block): letters typed as meant 94.9% by drawn
  edges, 95.5% after the overall lean, 97.0% with each person's offsets; words typed right 81.2%, 82.8% and
  87.9%. This helps with autocorrect off too.
- **Autocorrect by where the taps landed** (R12): each typed letter keeps where its tap came down, and a
  wrong letter costs by how much less likely that tap was meant for the word's letter than for the key it
  hit, in place of a flat cost for neighbouring keys. Taps scatter around where a key is aimed at with a
  10.2 dp spread, 2.2 dp left of and 2.0 dp above its centre: fitted on the TSI tap dataset (CC BY 4.0;
  37,022 letter taps by 16 people; 94.2% land nearest the key meant). The overall lean is used rather than
  TSI's per-key one, which belongs to its phone's layout; each user's own lean per key is learned on the
  phone from words typed right and kept (not from corrected ones), with the glide adaptation's daily limit,
  clipped pulls and 14 days of snapshots, in its own file, under its own setting. Candidates rank by the
  same cost when the taps are known, and words of four letters or more may then be two slips away. On TSI's
  6,338 phrase words typed as the keys nearest their first taps (1,244 typos), autocorrect fixes 77.0%
  instead of 71.0%, makes another word of 9.2% instead of 11.3%, and changes none of the words typed right.
  Of the typos it leaves, 86 are themselves words (left alone by design) and 129 lack
  the word meant among the six candidates.
- **Fixing the word before** (the user's request, after the in-field design below): a glide may fix the
  glided word right before it, and only that one, when the decoder reads the pair otherwise by its margin
  (2.0 cost units); only while that word stands exactly as it went in, nothing has been typed since, and it
  was not picked from the strip. It is rewritten with its capitals, learned as it ends up without its
  stroke offsets, and tapping it offers the old word back. Gliding 400 FUTO sentences on without fixing
  anything (`GlideOnTest`), words right at the end go from 91.9% to 92.8%: 34 fixed, 1 broken. Margins of 3,
  4 and 6 broke none but fixed 14, 12 and 3. In `FrictionTest`, where every misread is fixed at once, the
  fixing can only change words already right: 2 of 400 sentences end with one wrong word (98.5% exact,
  99.0% without). Setting: Fix the last glided word. Only when the new pair has been seen (in the corpora
  or the user's own pairs): after a word as rare as "dirk" nothing is known about what follows, so any next
  word looks as likely there as anywhere, and on a friend's phone "to work" became "to dirk" when "pretty"
  came next. With that check, gliding on fixes 33 and breaks 1, and `FrictionTest` is back to 99.0%.
- **Redoing a word in the field** (changed at the user's request, after a preview row was tried): the
  keyboard does not rewrite text on its own beyond fixing the word before (above). An earlier design rewrote up to four glided words in place when
  a later glide made another reading likelier, which changed text the user had already seen go in. A
  preview row that held glided words for two seconds before they went in came next; it grew into a second
  text field, and since Android cuts a field's connection before the keyboard hears the user tapped
  another field, anything still in it was lost on a field switch unless it was mirrored into the field
  anyway. Now glides go straight in and the user points at the word to redo: a cursor strictly inside a
  word, or one selected word, placed by the user. Selection reports within 600 ms of the keyboard's own
  edit are taken as its own, so the keyboard's cursor never targets. A cursor at a word's edge targets
  nothing, because that is where a tap between words lands. The targeted word is underlined with a
  composing region; the replacement is checked against the text around the cursor first, and goes in
  with the old word's capitals. Glided words are not learned right away (see the learned user
  dictionary), so a glide redone is not learned as it was; the correction teaches the adaptation instead.
- **Learned user dictionary** (out of scope for v1, added at the user's request): on the phone only, in
  `personal_words.json` in the app's private files, written atomically when the keyboard hides. A word is
  learned when it is final (typed words on commit; a glided word once eight more glided words follow it
  or the field changes, so backspace, a strip swap, redoing it, or backing up to it a few words later and
  changing it are not learned as the wrong word: the owner usually notices a wrong glide only after the
  next word, and learning it then made the same mistake likelier each time, its word, its pairs and its
  stroke alike).
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
- **Code mode layout** (the owner's report: "?" was where muscle memory did not look): no study says where
  people expect symbols on a phone, so the rows follow the common phone symbol page, which is where thumbs
  look, and the extras code needs take the fifth row. Counted in prose (Tatoeba and Common Voice) the symbols
  are `.` 64%, `'` 17%, `,` 7%, `?` 6%; in code (this project's sources) `.` 16%, `(` `)` 12% each, `=` 9%,
  `,` 7%, `-` 6%, while `%` `^` `` ` `` `~` are almost never typed, so `%` and `^` were the two put on a hold.
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
  (`AutocorrectBenchmarkTest`; the slips are synthetic, of the kinds the costs describe), each with the
  two words before it: 94.6% fixed, 4.3% changed to another word, 1.1% left alone; no correctly typed word
  changed (slip weight 8; it was 6, which gave 94.3% and 4.7%, and on TSI's real taps 77.7% against 78.4%
  now; weights from 3 to 10 never changed a correct word). Ranked by how common words are overall, without the words before: 88.5%, 9.9% and 1.6%. Before: 58.4% fixed, because autocorrect looked only at the first suggestion
  and gave up whenever a typo was also the start of some rare word ("helo" starts "helot"). Setting:
  Autocorrect, on by default since a friend's recording showed every typo left in (it was off by default
  until then, with no reason recorded). A word that begins an identifier in the text around ("max" of
  "maxRetries") is never autocorrected, so the identifier keeps its place in the strip.
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
- **Bar editor**: reorder by dragging an item's handle (R3, replacing up/down buttons). The lifted row is
  drawn under the finger wherever the list has laid it out, so a swap never makes it jump; the others slide
  to their places; held within 72 dp of the top or bottom edge, the list scrolls, faster nearer the edge;
  the order is saved when the finger lifts. Items keep their ids across saves, so the list never takes one
  row for another. Move up and Move down are accessibility actions for TalkBack; remove, add key/modifier/snippet, import/export JSON through the system document
  picker. Import validates the JSON and rejects unknown keycodes or item types.
- **Build order**: the four phases were built in one pass because the service integrates the bar, dictionary
  and glide from the start; the git history groups the work by layer (core logic, IME and UI, docs) with each
  commit building.
- **Language load time**: the dictionary loads in 2.5 s and the n-gram model and letter tree in 2.7 s on the
  API 36 emulator right after install (not yet compiled ahead of time); the keyboard appears at once and
  glide starts working when both are in.

## Shebang Voice (add-on)

Speech typing comes as a separate app, `voice/`, so the keyboard keeps VIBRATE as its only permission and
has no network code; the add-on holds the microphone permission and has no network access either. It works
on the owner's Pixel (about 5 s from a pause to the text before flash attention and the shorter pause); speed
there is still being measured (R15). It
runs OpenAI's Whisper (base.en, 5-bit, 57 MB, MIT) through whisper.cpp (vendored, CPU only). Fetch the
model before building it: `tools/fetch_voice_model.sh`. `./gradlew :voice:connectedDebugAndroidTest`
transcribes a public-domain recording on a device: on the emulator (2 cores, AVX2) the 11 s Kennedy sample
comes out word for word in 15 s, against 28 s without AVX2. Speed on phones is still to be measured and
tuned (ARM instruction sets chosen at runtime).

How it works: the keyboard shows a mic at the right end of the strip once the add-on is installed (in text
mode, in fields that take typed words); otherwise Settings > Voice typing links to the GitHub releases. A tap
binds the add-on's service with BIND_INCLUDE_CAPABILITIES, which lends it the keyboard's foreground status so
Android does not silence its microphone. The add-on answers only apps signed with its own key, so the
keyboard needs no permission entry for it (a `<queries>` entry lets it see the add-on). It records,
cuts the audio at 500 ms pauses (or at 25 s), and transcribes each piece as it comes, so text arrives sentence by sentence;
it stops after 8 s without speech, when the mic is tapped again, or when a key is typed (what was said is
still written). Without the microphone permission the add-on's own screen asks for it, since a keyboard
cannot. On the emulator, with the Kennedy sample standing in for the microphone (debug builds of the add-on
only), the three pieces arrived in the field 7, 7 and 12 s after each was spoken.

Tidy dictation (setting, on by default; R16): each piece is tidied by rules before it is written
(`DictationCleanup`). Hesitations ("um", "uh", "er", "hmm") go with the commas around them; a word or
phrase of up to three words said twice in a row is written once ("we should, we should go"), except
doubles people mean ("had had", "that that"). A spoken correction (", no wait,", ", sorry,", ", I mean,",
", or rather,") replaces what came just before it: from the word it repeats ("on Tuesday, no wait, on
Wednesday"), or else the last word ("forty, sorry, fifty"); the words used plainly ("I mean it", "Sorry
I'm late", "I'd rather stay") are left alone. "Scratch that" drops the sentence before it, and said at the
start of a piece takes back the previous piece while it still stands before the cursor. Nothing is
reworded beyond that; a small language model was the other option, at 300 MB to 1 GB and seconds per
piece, and is left until the rules fall short.

## About and updates

Settings > About shows the version, the licence and the credits (`LICENSE` and `THIRD_PARTY_NOTICES.md`,
copied into the app's assets at build time by the `copyAboutDocs` task, so the app always carries the real
list), links to the source on GitHub, and Check for updates, which opens the GitHub releases page. The app
does not download or install anything itself: that would need network access and the install-packages
permission, which together are what Android's malware scanning looks for in a keyboard. Installing a newer
APK over the old one updates it in place, keeping settings and learned words, as long as both were signed
with the same key.

## Licence

MIT (see `LICENSE`). The data and libraries it builds on keep their own licences, all permissive or public
domain, listed with their attributions in `THIRD_PARTY_NOTICES.md`; the CC BY ones (Tatoeba, Wikinews, TSI)
ask for credit wherever the app or its data is redistributed.

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
