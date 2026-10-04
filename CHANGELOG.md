# Changelog

## Unreleased

### Added

- Settings > Dictionaries: turn packs of brands and names, development words and computer terms on or off, and import your own word lists. Glide and suggestions prefer regular words, then names and your lists, then development words, then computer terms (193d0be)

### Changed

- Address bars space words again, since they double as search boxes: picked suggestions, glides and predictions get spaces; autocorrect stays off and the slash keeps the comma's place (03a62b7)

### Fixed

- Punctuation after a word you ended with space, a picked suggestion or autocorrect goes against the word instead of after a space ('the .' is now 'the.'), and the space comes back before your next word (03a62b7)
- Hundreds of computer and developer terms (USB, HDMI, BIOS, VPN, git, sudo, grep, touchpad, hotspot) can be typed and glided; typed in lowercase they keep their capitals ('cpu' becomes CPU) instead of being corrected to everyday words (a38e598)

## 0.5.2: 2026-10-03

### Added

- Developer words like toolchain, monorepo, linter, websocket and Dockerfile are in the dictionary (78c89c1)
- Settings > Dictionaries > Built-in words: browse and search every word the keyboard ships with, delete ones you never want offered, and restore one or all from Removed (21762e3)
- Export diagnostics includes how your last 500 glides ended up (kept, fixed from the suggestions, glided again, edited or deleted), as counts by word length, to measure glide accuracy in real use (6764977)
- One-handed mode: the keys shrink to the left or right side, with buttons beside them to switch sides or go back to full width (Settings > Appearance, or a terminal bar item) (3103ff7)
- Undo, Redo, Select all, Cut, Copy and Paste on the terminal bar, done by the app itself so a terminal never gets a stray Ctrl+Z (3103ff7)
- A settings gear on the terminal bar (3103ff7)
- Settings > Learning and privacy > Personal words > Add a word: teach the keyboard a name or term by hand; it is offered and glided at once (3103ff7)

### Changed

- A new logo: the Shebang mark, # and ! interlocked, as the app icon and on the #! key (3872dfe)
- Settings redesigned: a home page that shows how each page stands and whether DevBoard is your current keyboard, then clear pages for appearance, typing, corrections, sound, the terminal bar, voice, and learning and privacy (dc18e4b)
- The setup screen shows your progress through the three steps, and finished steps fold away (dc18e4b)

### Fixed

- A tap clearly on a letter near the space bar no longer turns into a space just because the word could end there (typing 'toolchains' used to give 'tool chains'); the keyboard also stops making spaces from letter taps if you keep deleting them (78c89c1)
- Web addresses and email addresses are typed exactly: no autocorrect in address bars and email fields, and no spaces added for you in email fields (78c89c1)

## 0.5.0: 2026-10-03

### Changed

- Key preview and alternates draw inside the keyboard window; autocorrect and auto-capitalisation no longer do dictionary or editor work per keystroke on the main thread (9b385ff)
- Glide typing decodes while the finger moves and shows the word in the strip before you lift; it is far more accurate and uses the word before the cursor (33a992d)
- Glide reads real, imprecise swipes far better: tuned on a public set of a million real swipes, it picks the right word 89% of the time there, up from 81% (14a9011)
- Swipe adaptation learns slowly, ignores sloppy glides and only takes so much each day, so one careless day cannot throw it off (14a9011)
- A look of its own: keys drawn as raised keycaps, original backspace, return and shift glyphs, and a cursor mark on the space bar (60444dd)
- Suggestions while typing rank by how common words really are, so the likelier word comes first (2b0b9cf)
- With the number row on, the top-row letters no longer show or hold the digits (e9d5e53)
- Autocorrect leaves code alone: words like getUser, max_retries or user2, and any word already used in the text around the cursor (6ed70d9)
- Glide reads the two words before it, not one, from a larger language model (Tatoeba plus Common Voice's public-domain sentences): real swipes right first time 88.9% -> 91.0%, phrase glides 90.9% -> 91.2%. The app is 5.6 MB larger (7a090a2)
- Typed suggestions and autocorrect take the words before into account, as glide does: 'cut my haie' becomes 'hair', 'she can vook' becomes 'cook'. Words you typed right are still never changed (1a78b83)
- Holding backspace (or any repeating key) buzzes once when pressed instead of on every repeat; the key sound still repeats (afecda7)
- Autocorrect is on by default (it was off unless switched on in Settings); a word that begins a name in the code around, like 'max' of 'maxRetries', is left alone (3d46dff)
- Autocorrect fixes a few more typos and picks the wrong word less often (real taps: 78.4% of typos fixed instead of 77.7%); taps leave unusual words such as names alone a little more (0171d36)
- Autofill chips sit centred in the strip, and a sideways swipe puts them away so the terminal bar is back (bbf878b)
- The enter key always shows the enter icon; it no longer turns into an oversized 'Search', 'Go' or 'Send' label (it still does what the field asks) (fc07c2f)
- Reorder the terminal bar by dragging an item's handle in the bar editor, instead of up and down buttons (c7bba27)
- Dragging bar items in the editor is smooth (the other rows slide out of the way) and the list scrolls when you hold an item near the top or bottom (9c9b1ac)
- Swiping the chips away is a real swipe: they move with your finger and slide off, or spring back if you let go early (0a85acd)
- A tap that lands low on a letter just above the space bar types a space when the word looks finished, so thumbs that fall short of the bar still get their space (369c718)
- Autocorrect keeps the letters of a word you typed an apostrophe into (only the apostrophe may move), and leaves words joined by punctuation alone (f-droid, node.js) (5d8d5f1)
- Tapping space with the cursor inside a word splits it there; holding space moves past the word; the keyboard picker moved to holding #! (3d7052c)
- A word typed without its apostrophe gets one from the words before it: "I think its" becomes "I think it's", while "the dog wagged its" stays (1e952c8)
- Code mode's symbols are laid out like the usual phone symbol page (? and ! at the right of the third row), with backspace, comma, period and enter where they are in text mode; % and ^ are held on 5 and 6 (694ee3a)
- Glide forgives stopping short at turns, the way thumbs actually move: 91.0% of real swipes right instead of 90.6% (1484317)
- Glide reads strokes with a model trained on 900,000 real swipes: 93.7% of words right first time instead of 91.0% (a36a242)
- Suggestions after a space read the whole sentence with a model trained on the GPU: the next word is among the three 39% of the time instead of 36%, and glide gets the word right a little more often (4afa886)
- Autocorrect leaves a word alone when it looks meant as typed: cleanly tapped words the dictionary lacks, names capitalised mid-sentence and words in capitals; wrong corrections are halved (d777355)
- Switching to code mode or the number pad no longer changes the keyboard's height (3402fd4)
- Caps lock has its own shift glyph (two carets over a bar), so it no longer looks like one capital; backspace is drawn at the shift caret's thin weight (cb1fddd)
- Shebang Voice has its own icon (the mic) instead of Android's default (bd73d5d)
- Email fields have @ where the comma is (long-press it for the comma and _ - +), and fields whose hint asks for an email count as email fields too (5f7d634)

### Added

- Text mode: QWERTY with glide typing, suggestions, long-press alternates, auto-capitalisation, double-space period (925fd25)
- Code mode (#! key): every digit and printable ASCII symbol on one page plus arrow keys, no shift or long-press (925fd25)
- Terminal bar with sticky Ctrl/Alt/Shift/Meta, real KeyEvents for terminals, hold-to-repeat arrows and Del, snippets, and a JSON editor with import/export (925fd25)
- Field-aware behaviour: raw input for terminals, no suggestions or glide in password fields, numeric pad for number/phone/date, @ and / for email/URL (925fd25)
- Setup screen with the three enable/select/test steps and a Material 3 settings app (theme, height, haptics, sounds, glide, autocorrect, strip mode) (925fd25)
- Glided words go straight into the field and the keyboard never rewrites text on its own; a letter typed right after a glide starts a new word (9e09ad3)
- Phrase gliding: dip into the space bar mid-stroke to glide several words without lifting (Settings, off by default) (33a992d)
- Settings > Record glides keeps glides of prompted words on the phone to measure accuracy on real fingers (33a992d)
- Tap inside a word (or double-tap it) and glide to redo it, or pick one of its alternatives from the strip; tap between words to add one instead (9e09ad3)
- The keyboard learns your words and word pairs on the phone; new words become glidable after two uses; review or delete them in Settings > Personal words (Settings: Learn words I type) (3778f89)
- Glide adapts to where your swipes land on each key, learning most from the words you correct (Settings: Adapt glide to my swiping) (3778f89)
- Words in Android's personal dictionary can be glided and are suggested (3778f89)
- Settings > Personal words > Undo recent learning goes back to how learned words and swipe adaptation stood at the start of any of the last 14 days (14a9011)
- Twelve original themes (Night, Day, Phosphor, Amber, Deep Sea, Ember, Orchid, Forest, Paper, Glacier, Sand, High Contrast) plus Auto and Wallpaper, picked from swatches in Settings > Appearance; the setup and settings screens follow the theme (60444dd)
- Backspacing back to a word reopens it: the strip offers what else it could be, and a pick or a glide replaces it (5f70018)
- Code mode pairs brackets and quotes, steps over a closing one, and backspace between an empty pair takes both (Settings: Pair brackets and quotes) (6ed70d9)
- Swipe left from backspace to delete whole words, drag the space bar with shift on to select, and Undo and Redo keys on the default terminal bar (6ed70d9)
- The keyboard remembers text or code mode per app, and each app can have its own terminal bar (6ed70d9)
- Code-aware words: identifiers already in the text are suggested as you type and offered after a glide, and words glided in a row can be joined as camelCase or snake_case (8e1d2ae)
- Next-word suggestions: after a space the strip offers the three words most likely to come next; tap one to write it and get the next three. Settings > Next-word suggestions turns them off (7a090a2)
- Autofill in the strip (Android 11+): your password manager's suggestions show as chips above the keys, in the keyboard's colours; tap one to fill the form (d3bf76c)
- A glide can fix the glided word just before it when the two together clearly read otherwise (only that word, only while untouched, never one picked from the strip; tap it to change it back). Settings > Fix the last glided word (544fca1)
- Autocorrect weighs each typo by where your finger actually landed on the keys, and learns where your taps land: on real taps it fixes 77% of typos instead of 71%, and still never changes a word typed right. Settings > Adapt autocorrect to my taps; Undo recent learning covers it (d3eb58e)
- When space is about to autocorrect a word, the strip shows the correction in the accent colour and your word with a check mark: tap the check mark to keep what you typed (b4e6ef5)
- Keys follow where you tap: the keyboard learns your lean (say, a little left of each key) and types the key you meant, even with autocorrect off. On real taps, words typed right go from 81% to 88% (0da0ba7)
- Taps between keys go to the letter that makes a word: after 'th', a tap on the edge of w types e. Words come out right as tapped 97% of the time on real taps, up from 88%, before autocorrect does anything. Not in password, email, URL or code fields (9d68563)
- A chip offers to paste what you copied in the last few minutes (dots for passwords); tap to paste, swipe sideways to put it away (335456f)
- Voice typing through the new Shebang Voice add-on (a separate download): a mic at the end of the strip; speech is turned into text on the phone, sentence by sentence, and the keyboard itself never uses the microphone (d025d9f)
- Settings > About: version, licence, credits for everything the keyboard is built on, the source on GitHub, and Check for updates (opens the GitHub releases) (47c8012)
- Tidy dictation: um and uh, stutters and repeats are dropped, and spoken corrections work ('Tuesday, no wait, Wednesday'; 'scratch that'). On by default; Settings > Tidy dictation (0e07792)
- The dictionary knows names, places, brands and abbreviations (Paris, GitHub, YouTube, iPhone, URL, McDonald's), and a name typed in lowercase gets its capitals (6d500e2)
- Personal words has a search field: type part of a word to find it among the words the keyboard learned (2c526ea)
- Emoji and clipboard history panels, opened from the terminal bar (first two items by default; move or remove them in the bar editor); the clipboard history keeps pictures too, for apps that accept them (e6c7521)
- Hold shift for caps lock, as well as tapping it twice (cb1fddd)
- Backspacing back to a word autocorrect changed, even after typing on, offers what you typed, the correction and the other suggestion for what you typed (12a13dc)
- Email addresses you type into email fields are remembered and offered on the strip as you type them again; turn it off in Settings > Learning, forget them in Personal words (83479de)
- Export diagnostics (Settings > About): a file to send the developer with your settings and how your taps and glides lean, leaving out learned words, email addresses, the clipboard, your terminal bar and anything typed (ee022cf)
- Holding a lowercase letter offers its capital first in the row; sliding back down onto the key cancels the row and types the key itself (eb90a44)
- A privacy policy, in Settings > About and in the repository: nothing leaves the phone, what is kept and how to delete it (591f200)
- Holding backspace deletes whole words after a second (setting: Hold backspace for whole words) (1de6725)
- Works with TalkBack: every key is spoken as you explore, and lifting on a key (or a double tap) types it (df9c56a)
- Crash reports stay on the phone (where the code failed, no messages or dates) and are included in Export diagnostics, so a crash can be sent by choice (1aaea6a)

### Removed

- Check for updates is gone from Settings > About (the app will also be on Google Play, which allows updates only through Play); Source code still links to GitHub (a733a23)

### Fixed

- Glide can now produce apostrophe words (don't, it's), and it's, that's, let's and other 's contractions are in the word list (33a992d)
- Tapping inside a word and gliding no longer sometimes splits the word in two (4200999)
- Redoing a word no longer copies the capital of words like I or I'd onto the new word (14a9011)
- Autocorrect fixes far more typos (89% of one-slip typos in testing, from 58%): it weighs all suggestions by how common they are and how likely the slip is, corrects before punctuation, restores missing apostrophes (dont, cant, youre), and backspace right after undoes it (2b0b9cf)
- A lone i (and i'm, i'd, i'll, i've) is capitalised when the word ends (2b0b9cf)
- The setup screen has one settings button instead of two (e9d5e53)
- Tapping a word in the suggestion strip gives the same feedback (vibration, click) as a key (5f70018)
- Typing on at the end of a word (after backspacing into it, or when the app dropped the word being typed) takes in the whole word, so the underline and a suggestion cover all of it rather than only the new letters (61eb9d5)
- Fixing the last glided word no longer swaps a right word for a rare one ('to work pretty' could become 'to dirk pretty') (07c5515)
- The setup screen's title no longer slides under the status bar when the keyboard opens (75fd1a3)
- The keyboard height setting shows 110% (not 109%) and the other steps exactly (c5808bd)
- The terminal bar fades out before the mic instead of being cut off by it (3029383)
- A glide deleted by swiping left from backspace is no longer learned, so a wrong glide removed that way does not make the same mistake likelier next time (813a34f)
- A glided word you back up to and fix a few words later is no longer learned as the wrong word (012e58f)
- On a taller keyboard the letters no longer grow into the number and accent hints; the bar's fade by the mic is shorter (d2da3d2)
- A correction that arrives after you have started the next word is no longer lost: it goes in ahead of the word you are typing (2d0f189)
- Next-word suggestions no longer linger after the app changes the text (a message sent and the field cleared, a hardware keyboard) (018e3b3)
- Learned words, adaptation, email addresses and clipboard history can no longer be wiped by a damaged save file: it is set aside instead of overwritten, and saves cannot be cut off half-written; a damaged settings file falls back to the defaults instead of stopping the keyboard (101122c)
