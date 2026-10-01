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
- Twelve original themes (Night, Day, Phosphor, Amber, Deep Sea, Ember, Orchid, Forest, Paper, Glacier, Sand, High Contrast) plus Auto and Wallpaper, picked from swatches in Settings > Appearance; the setup and settings screens follow the theme (925a20f)
- Backspacing back to a word reopens it: the strip offers what else it could be, and a pick or a glide replaces it (c3b3a8a)
- Code mode pairs brackets and quotes, steps over a closing one, and backspace between an empty pair takes both (Settings: Pair brackets and quotes) (04cb7b0)
- Swipe left from backspace to delete whole words, drag the space bar with shift on to select, and Undo and Redo keys on the default terminal bar (04cb7b0)
- Flick up on a key to type the character in its corner (Settings: Flick up for symbols) (04cb7b0)
- The keyboard remembers text or code mode per app, and each app can have its own terminal bar (04cb7b0)
- Code-aware words: identifiers already in the text are suggested as you type and offered after a glide, and words glided in a row can be joined as camelCase or snake_case (c5888d9)
- Next-word suggestions: after a space the strip offers the three words most likely to come next; tap one to write it and get the next three. Settings > Next-word suggestions turns them off (8e1d2ae)
- Autofill in the strip (Android 11+): your password manager's suggestions show as chips above the keys, in the keyboard's colours; tap one to fill the form (61eb9d5)
- A glide can fix the glided word just before it when the two together clearly read otherwise (only that word, only while untouched, never one picked from the strip; tap it to change it back). Settings > Fix the last glided word (d3bf76c)
- Autocorrect weighs each typo by where your finger actually landed on the keys, and learns where your taps land: on real taps it fixes 77% of typos instead of 71%, and still never changes a word typed right. Settings > Adapt autocorrect to my taps; Undo recent learning covers it (544fca1)
- When space is about to autocorrect a word, the strip shows the correction in the accent colour and your word with a check mark: tap the check mark to keep what you typed (afecda7)
- Keys follow where you tap: the keyboard learns your lean (say, a little left of each key) and types the key you meant, even with autocorrect off. On real taps, words typed right go from 81% to 88% (bddf074)

### Changed

- Key preview and alternates draw inside the keyboard window; autocorrect and auto-capitalisation no longer do dictionary or editor work per keystroke on the main thread (8d54743)
- Glide typing decodes while the finger moves and shows the word in the strip before you lift; it is far more accurate and uses the word before the cursor (6383035)
- Glide reads real, imprecise swipes far better: tuned on a public set of a million real swipes, it picks the right word 89% of the time there, up from 81% (3cf59d0)
- Swipe adaptation learns slowly, ignores sloppy glides and only takes so much each day, so one careless day cannot throw it off (3cf59d0)
- A look of its own: keys drawn as raised keycaps, original backspace, return and shift glyphs, and a cursor mark on the space bar (925a20f)
- Suggestions while typing rank by how common words really are, so the likelier word comes first (ff7863d)
- With the number row on, the top-row letters no longer show or hold the digits (ef17318)
- Autocorrect leaves code alone: words like getUser, max_retries or user2, and any word already used in the text around the cursor (04cb7b0)
- Glide reads the two words before it, not one, from a larger language model (Tatoeba plus Common Voice's public-domain sentences): real swipes right first time 88.9% -> 91.0%, phrase glides 90.9% -> 91.2%. The app is 5.6 MB larger (8e1d2ae)
- Typed suggestions and autocorrect take the words before into account, as glide does: 'cut my haie' becomes 'hair', 'she can vook' becomes 'cook'. Words you typed right are still never changed (d3eb58e)
- Holding backspace (or any repeating key) buzzes once when pressed instead of on every repeat; the key sound still repeats (1a78b83)
- Autocorrect is on by default (it was off unless switched on in Settings); a word that begins a name in the code around, like 'max' of 'maxRetries', is left alone (07c5515)

### Fixed

- Glide can now produce apostrophe words (don't, it's), and it's, that's, let's and other 's contractions are in the word list (6383035)
- Tapping inside a word and gliding no longer sometimes splits the word in two (dd5bef3)
- Redoing a word no longer copies the capital of words like I or I'd onto the new word (3cf59d0)
- Autocorrect fixes far more typos (89% of one-slip typos in testing, from 58%): it weighs all suggestions by how common they are and how likely the slip is, corrects before punctuation, restores missing apostrophes (dont, cant, youre), and backspace right after undoes it (ff7863d)
- A lone i (and i'm, i'd, i'll, i've) is capitalised when the word ends (ff7863d)
- The setup screen has one settings button instead of two (ef17318)
- Tapping a word in the suggestion strip gives the same feedback (vibration, click) as a key (c3b3a8a)
- Typing on at the end of a word (after backspacing into it, or when the app dropped the word being typed) takes in the whole word, so the underline and a suggestion cover all of it rather than only the new letters (f981e15)
- Fixing the last glided word no longer swaps a right word for a rare one ('to work pretty' could become 'to dirk pretty') (b4e6ef5)
