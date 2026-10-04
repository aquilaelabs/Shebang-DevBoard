# Glide typing and learning

How glide typing decodes a stroke, how it is measured, and what the keyboard learns on the phone. The
reasons behind each choice are in [decisions.md](decisions.md).

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
   three-word model given the two words before them (see [decisions.md](decisions.md)). The emulator logs a median of 9 ms
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
   model and the user's own word pairs (see [decisions.md](decisions.md)), capitalised at a sentence start. Tapping one writes it with a space and offers the next;
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
your own with **Settings > Typing and glide > Record glides**, which prompts common words on the real keyboard and keeps each
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
  context has been seen 5 times. Settings > Learning and privacy > Personal words lists them with a delete button each.
- **How you swipe.** Where each kept glide passed each letter, relative to the key centre, moves that
  letter's key centre for decoding, shrunk toward the average lean of all your glides. A correction (a
  tapped word glided again, or swapped for an alternative) re-aligns the word's original stroke, when it
  was glided lately, to the word you meant and counts twice, when the stroke plausibly was that word. It
  learns slowly and ignores sloppy glides, and only so much counts each day, so one careless day cannot
  throw it off. Reset in Settings > Learning and privacy > Personal words.
- **Going back.** The learned words and the swipe adaptation as they stood at the start of each of the last
  14 days are kept; Settings > Learning and privacy > Personal words > Undo recent learning goes back to any of them. Deleting a
  word deletes it from those days too.
- **Android's personal dictionary.** Its words (English, or with no locale) join the vocabulary when the
  keyboard loads.
