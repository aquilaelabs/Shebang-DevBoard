# Shebang Voice (add-on)

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
binds the add-on's service with BIND_INCLUDE_CAPABILITIES (Android 10 and later), which lends it the keyboard's foreground status so
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
