# Changelog

## Unreleased

### Added

- Text mode: QWERTY with glide typing, suggestions, long-press alternates, auto-capitalisation, double-space period (4447b83)
- Code mode (#! key): every digit and printable ASCII symbol on one page plus arrow keys, no shift or long-press (4447b83)
- Terminal bar with sticky Ctrl/Alt/Shift/Meta, real KeyEvents for terminals, hold-to-repeat arrows and Del, snippets, and a JSON editor with import/export (4447b83)
- Field-aware behaviour: raw input for terminals, no suggestions or glide in password fields, numeric pad for number/phone/date, @ and / for email/URL (4447b83)
- Setup screen with the three enable/select/test steps and a Material 3 settings app (theme, height, haptics, sounds, glide, autocorrect, strip mode) (4447b83)
- Glided words go straight into the field and the keyboard never rewrites text on its own; a letter typed right after a glide starts a new word (6383035, 15d883d, 52ca7a2)
- Phrase gliding: dip into the space bar mid-stroke to glide several words without lifting (Settings, off by default) (6383035)
- Settings > Record glides keeps glides of prompted words on the phone to measure accuracy on real fingers (6383035)
- Tap inside a word (or double-tap it) and glide to redo it, or pick one of its alternatives from the strip; tap between words to add one instead (15d883d, 52ca7a2)
- The keyboard learns your words and word pairs on the phone; new words become glidable after two uses; review or delete them in Settings > Personal words (Settings: Learn words I type) (15d883d)
- Glide adapts to where your swipes land on each key, learning most from the words you correct (Settings: Adapt glide to my swiping) (15d883d)
- Words in Android's personal dictionary can be glided and are suggested (15d883d)
- Settings > Personal words > Undo recent learning goes back to how learned words and swipe adaptation stood at the start of any of the last 14 days (3cf59d0)

### Changed

- Key preview and alternates draw inside the keyboard window; autocorrect and auto-capitalisation no longer do dictionary or editor work per keystroke on the main thread (8d54743)
- Glide typing decodes while the finger moves and shows the word in the strip before you lift; it is far more accurate and uses the word before the cursor (6383035)
- Glide reads real, imprecise swipes far better: tuned on a public set of a million real swipes, it picks the right word 89% of the time there, up from 81% (3cf59d0)
- Swipe adaptation learns slowly, ignores sloppy glides and only takes so much each day, so one careless day cannot throw it off (3cf59d0)

### Fixed

- Glide can now produce apostrophe words (don't, it's), and it's, that's, let's and other 's contractions are in the word list (6383035)
- Tapping inside a word and gliding no longer sometimes splits the word in two (dd5bef3)
- Redoing a word no longer copies the capital of words like I or I'd onto the new word (3cf59d0)
