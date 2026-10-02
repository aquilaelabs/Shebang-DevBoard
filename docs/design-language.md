# The design language

What this project looks like, in one place. Written by BuilderBot's Design view from the notebook, so edit it there, or edit here and say so in the notebook. Every surface follows it; a change to the look is a change to this file first.

It governs the keyboard (IME views) and the Compose setup/settings app.

## Color

Color comes only from these tokens, named once in the stylesheet. The app starts on Night; a theme picker, where the app has one, offers the themes by name, and every theme names the same tokens:

### Night (dark, the default)

| token | value | for |
|---|---|---|
| `--background` | `#1b1f2a` | keyboard and strip background |
| `--key` | `#2e3440` | character keys |
| `--key-functional` | `#252b36` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#4a5366` | a key while touched |
| `--key-text` | `#eceff4` | key labels |
| `--key-text-secondary` | `#9aa3b5` | long-press hints, the space bar's cursor mark |
| `--accent` | `#7be0a6` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#0b2a1a` | text on the accent |
| `--popup` | `#3b4252` | key preview and alternates popup |
| `--popup-text` | `#eceff4` | text in the popup |
| `--strip-text` | `#e5e9f0` | suggestion strip words |
| `--modifier-active` | `#3f5a4b` | one-shot sticky modifier chip |
| `--modifier-locked` | `#5fbf8a` | locked sticky modifier chip |

### Day (light)

| token | value | for |
|---|---|---|
| `--background` | `#e6e9f0` | keyboard and strip background |
| `--key` | `#ffffff` | character keys |
| `--key-functional` | `#cfd5e1` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#b8c0d0` | a key while touched |
| `--key-text` | `#1b1f2a` | key labels |
| `--key-text-secondary` | `#5b6475` | long-press hints, the space bar's cursor mark |
| `--accent` | `#1e8e5a` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#ffffff` | text on the accent |
| `--popup` | `#ffffff` | key preview and alternates popup |
| `--popup-text` | `#1b1f2a` | text in the popup |
| `--strip-text` | `#2a2f3a` | suggestion strip words |
| `--modifier-active` | `#bfe8d2` | one-shot sticky modifier chip |
| `--modifier-locked` | `#57c48b` | locked sticky modifier chip |

### Phosphor (dark)

| token | value | for |
|---|---|---|
| `--background` | `#050a06` | keyboard and strip background |
| `--key` | `#0f1a11` | character keys |
| `--key-functional` | `#0a130c` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#1f3a24` | a key while touched |
| `--key-text` | `#8cffa0` | key labels |
| `--key-text-secondary` | `#3f8f4f` | long-press hints, the space bar's cursor mark |
| `--accent` | `#39ff6a` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#03140a` | text on the accent |
| `--popup` | `#14261a` | key preview and alternates popup |
| `--popup-text` | `#b8ffc4` | text in the popup |
| `--strip-text` | `#8cffa0` | suggestion strip words |
| `--modifier-active` | `#1e4a2a` | one-shot sticky modifier chip |
| `--modifier-locked` | `#2fd65a` | locked sticky modifier chip |

### Amber (dark)

| token | value | for |
|---|---|---|
| `--background` | `#0f0a03` | keyboard and strip background |
| `--key` | `#1e1507` | character keys |
| `--key-functional` | `#170f05` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#3a2a10` | a key while touched |
| `--key-text` | `#ffc266` | key labels |
| `--key-text-secondary` | `#a4733a` | long-press hints, the space bar's cursor mark |
| `--accent` | `#ff9f1c` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#1f1200` | text on the accent |
| `--popup` | `#2a1d0a` | key preview and alternates popup |
| `--popup-text` | `#ffd699` | text in the popup |
| `--strip-text` | `#ffc266` | suggestion strip words |
| `--modifier-active` | `#4a3310` | one-shot sticky modifier chip |
| `--modifier-locked` | `#e08a12` | locked sticky modifier chip |

### Deep Sea (dark)

| token | value | for |
|---|---|---|
| `--background` | `#0b1624` | keyboard and strip background |
| `--key` | `#132338` | character keys |
| `--key-functional` | `#0f1c2e` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#22395a` | a key while touched |
| `--key-text` | `#dde8f5` | key labels |
| `--key-text-secondary` | `#7d93ae` | long-press hints, the space bar's cursor mark |
| `--accent` | `#4fc3f7` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#04202e` | text on the accent |
| `--popup` | `#1a2e48` | key preview and alternates popup |
| `--popup-text` | `#dde8f5` | text in the popup |
| `--strip-text` | `#d0ddee` | suggestion strip words |
| `--modifier-active` | `#1d4260` | one-shot sticky modifier chip |
| `--modifier-locked` | `#3aa9da` | locked sticky modifier chip |

### Ember (dark)

| token | value | for |
|---|---|---|
| `--background` | `#1a1110` | keyboard and strip background |
| `--key` | `#2b1c19` | character keys |
| `--key-functional` | `#221614` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#4a2e28` | a key while touched |
| `--key-text` | `#f7e4dd` | key labels |
| `--key-text-secondary` | `#b08a80` | long-press hints, the space bar's cursor mark |
| `--accent` | `#ff6b4a` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#2a0b04` | text on the accent |
| `--popup` | `#36231f` | key preview and alternates popup |
| `--popup-text` | `#f7e4dd` | text in the popup |
| `--strip-text` | `#f0dad2` | suggestion strip words |
| `--modifier-active` | `#5a2a1f` | one-shot sticky modifier chip |
| `--modifier-locked` | `#e85a3a` | locked sticky modifier chip |

### Orchid (dark)

| token | value | for |
|---|---|---|
| `--background` | `#1a1422` | keyboard and strip background |
| `--key` | `#2a2035` | character keys |
| `--key-functional` | `#221a2c` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#43345a` | a key while touched |
| `--key-text` | `#f0e7fa` | key labels |
| `--key-text-secondary` | `#a493ba` | long-press hints, the space bar's cursor mark |
| `--accent` | `#d08bf2` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#2a0e38` | text on the accent |
| `--popup` | `#332745` | key preview and alternates popup |
| `--popup-text` | `#f0e7fa` | text in the popup |
| `--strip-text` | `#e8ddf5` | suggestion strip words |
| `--modifier-active` | `#4a335e` | one-shot sticky modifier chip |
| `--modifier-locked` | `#b774db` | locked sticky modifier chip |

### Forest (dark)

| token | value | for |
|---|---|---|
| `--background` | `#121a15` | keyboard and strip background |
| `--key` | `#1d2a22` | character keys |
| `--key-functional` | `#17221b` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#314638` | a key while touched |
| `--key-text` | `#e4efe6` | key labels |
| `--key-text-secondary` | `#8fa696` | long-press hints, the space bar's cursor mark |
| `--accent` | `#a3d977` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#16240b` | text on the accent |
| `--popup` | `#26372c` | key preview and alternates popup |
| `--popup-text` | `#e4efe6` | text in the popup |
| `--strip-text` | `#dce8de` | suggestion strip words |
| `--modifier-active` | `#36502a` | one-shot sticky modifier chip |
| `--modifier-locked` | `#8bc25e` | locked sticky modifier chip |

### Paper (light)

| token | value | for |
|---|---|---|
| `--background` | `#ece6da` | keyboard and strip background |
| `--key` | `#fbf8f1` | character keys |
| `--key-functional` | `#ddd5c6` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#cfc5b3` | a key while touched |
| `--key-text` | `#2b2622` | key labels |
| `--key-text-secondary` | `#7a6f63` | long-press hints, the space bar's cursor mark |
| `--accent` | `#b8430e` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#fff7f0` | text on the accent |
| `--popup` | `#fffdf8` | key preview and alternates popup |
| `--popup-text` | `#2b2622` | text in the popup |
| `--strip-text` | `#3a332c` | suggestion strip words |
| `--modifier-active` | `#f1d2c2` | one-shot sticky modifier chip |
| `--modifier-locked` | `#d9683a` | locked sticky modifier chip |

### Glacier (light)

| token | value | for |
|---|---|---|
| `--background` | `#dce8ee` | keyboard and strip background |
| `--key` | `#ffffff` | character keys |
| `--key-functional` | `#c3d6df` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#afc7d3` | a key while touched |
| `--key-text` | `#12303d` | key labels |
| `--key-text-secondary` | `#587383` | long-press hints, the space bar's cursor mark |
| `--accent` | `#0077a8` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#ffffff` | text on the accent |
| `--popup` | `#ffffff` | key preview and alternates popup |
| `--popup-text` | `#12303d` | text in the popup |
| `--strip-text` | `#1c3c4a` | suggestion strip words |
| `--modifier-active` | `#bfe3f2` | one-shot sticky modifier chip |
| `--modifier-locked` | `#2a9ccc` | locked sticky modifier chip |

### Sand (light)

| token | value | for |
|---|---|---|
| `--background` | `#e9dfcb` | keyboard and strip background |
| `--key` | `#f8f2e5` | character keys |
| `--key-functional` | `#d8cbb2` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#c9ba9d` | a key while touched |
| `--key-text` | `#3a2e1f` | key labels |
| `--key-text-secondary` | `#857255` | long-press hints, the space bar's cursor mark |
| `--accent` | `#8a5a00` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#fff8ea` | text on the accent |
| `--popup` | `#fffbf2` | key preview and alternates popup |
| `--popup-text` | `#3a2e1f` | text in the popup |
| `--strip-text` | `#45382a` | suggestion strip words |
| `--modifier-active` | `#ebd6a8` | one-shot sticky modifier chip |
| `--modifier-locked` | `#b98524` | locked sticky modifier chip |

### High Contrast (dark)

| token | value | for |
|---|---|---|
| `--background` | `#000000` | keyboard and strip background |
| `--key` | `#1a1a1a` | character keys |
| `--key-functional` | `#0d0d0d` | shift, backspace, mode, bar chips |
| `--key-pressed` | `#404040` | a key while touched |
| `--key-text` | `#ffffff` | key labels |
| `--key-text-secondary` | `#ffd600` | long-press hints, the space bar's cursor mark |
| `--accent` | `#ffd600` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#000000` | text on the accent |
| `--popup` | `#1a1a1a` | key preview and alternates popup |
| `--popup-text` | `#ffffff` | text in the popup |
| `--strip-text` | `#ffffff` | suggestion strip words |
| `--modifier-active` | `#5c4d00` | one-shot sticky modifier chip |
| `--modifier-locked` | `#ffd600` | locked sticky modifier chip |

## Type

The platform's own sans-serif: system-ui on Web, Roboto on Android, SF Pro on iOS, the desktop's own on Linux, SF Pro on macOS, Segoe UI Variable on Windows, set as System sans for keys and the app; monospace for terminal-bar chips.

## Shape

Small radii: 6px on controls, 8px on boxes, 4px on tags.

## Space

An ordinary rhythm: 11px by 14px bands, 10px between fields.

## Motion

Short eases on state changes, nothing decorative, and none under prefers-reduced-motion.

## Rules

Each is one rule of the design, by name; a session is handed one on the turn it edits a file or is asked about a word the rule names, and `bb design show NAME` reads one.

### dynamic-color

Applies to: theme, color, palette

Themes: Auto (the default) follows the system between Night and Day; Wallpaper takes the palette from android.R.color.system_neutral1/neutral2/accent1/accent2 on Android 12+ (neutral1 for surfaces, accent1 for the accent) and behaves like Auto before that; every other theme is one of these fixed palettes. The setup and settings screens wear the chosen theme too: its background, text, key colour for cards and accent, with status and navigation bar icons to match. Themes are picked in Settings > Appearance from a row of swatches, each a tiny keyboard in its colours.

### keys

Applies to: app/src/main/kotlin/dev/shebang/devboard/view; key, keyboard

Keys are keycaps: a rounded face (radius 16% of the row height, clamped 4-12dp) raised above an edge in a deeper shade of the face (45% toward black on dark themes, 22% on light), about 4.5% of the row height tall (1.5-3dp); a pressed key sinks onto its edge. Gaps: 5dp horizontal, 8dp vertical, one Canvas. Character keys use `key`, function keys `key-functional`, enter and caps lock the accent. Letter keys, and code mode's keys that hold a character (5 holds %, 6 holds ^), show their first long-press alternate as a small hint in the top-right corner; labels and hints are sized from the row height but never wider than the key allows (60% and 32% of its width). The space bar carries a short cursor mark (an underscore) in `key-text-secondary`.

### icons

Applies to: icon

Key glyphs are original, in a terminal idiom: backspace is a left chevron erasing toward a block cursor, return a bent arrow ending in an open chevron, shift a caret that gains an underline while shift is on (caps lock also fills the key with the accent). The #! launcher icon is original. The settings app uses Compose Material icons (Apache-2.0) for its own controls. No brand marks anywhere. The mic is a capsule on a cradle and stand with a block cursor for a foot.

## Pages

Each page is a layout of labeled blocks on a frame, named for what it is for, on the platform it is built for.

### the keyboard in text mode: terminal bar or suggestions above four QWERTY rows (Android app)

Platform: an Android app: Material's shapes and elevation, 48dp touch targets, the app's sections as a bottom bar, and the system's back gesture respected.
Layout of the keyboard in text mode: terminal bar or suggestions above four QWERTY rows on a phone frame (6 columns by 12 rows), from the Blank template, numbered in drawing order (a later block draws over an earlier one where they overlap):
  1. Content (the main reading or working area): host app content, across the top, rows 1 to 7 of 12
  2. Tabs (a strip of tabs switching one area): terminal bar (Esc Tab Ctrl Alt Shift ^C ...) or 3 suggestions while composing, across the middle, row 8 of 12
  3. Content (the main reading or working area): q w e r t y u i o p, across the middle, row 9 of 12
  4. Content (the main reading or working area): a s d f g h j k l, across the middle, row 10 of 12
  5. Content (the main reading or working area): shift z x c v b n m backspace, across the middle, row 11 of 12
  6. Content (the main reading or working area): #! , space . enter, across the bottom, row 12 of 12
Notes:
  Rows are 52dp in portrait, 40dp in landscape, times the height setting. The bar and the suggestion strip are 44dp. Code mode swaps the four rows for five symbol rows at 86% row height with ABC / ? and arrows on the bottom row; the numeric pad is a 4-column grid. The keyboard pads for the navigation bar the IME window hosts on recent Android. While a glide is in progress the strip shows one bold line across its width: the words the glide would write if the finger lifted now. A word the user taps inside (or selects) is underlined in the field, and the strip shows it in the middle slot with its alternatives either side; the next glide or a tapped alternative replaces it. With phrase gliding on, the space key lights up in the pressed colour once a dip counts as a word boundary. The terminal bar's Emoji and Clipboard items (first by default, movable and removable like any item) draw original single-colour glyphs in strip-text, like the mic: a ring face with block-cursor eyes, and a board with a solid clip. Each opens a panel in place of the keys, at the keys' height, on background: the emoji panel has category tabs (the selected one on key-functional), an eight-column grid and Recent first; the clipboard panel a 'Clipboard' header in key-text-secondary with Clear, then copies as key-coloured rows (two lines, key-text) with Pin (Pinned on the accent) and remove chips. Both end in a row of ABC and backspace on key-functional around a space key with its cursor mark.
This page's own rules:
  keyboard-text/strip: strip, suggestion, bar (`bb design show keyboard-text/strip` reads it)
  keyboard-text/glide-preview: glide, preview, strip (`bb design show keyboard-text/glide-preview` reads it)

#### Rules of the keyboard in text mode: terminal bar or suggestions above four QWERTY rows's own

##### keyboard-text/strip

Applies to: strip, suggestion, bar

In Auto mode the bar is replaced by suggestions while a word is being composed and returns once it is committed; code mode always shows the bar. Two rows stacks bar over suggestions. When space would autocorrect the word being typed, the strip shows that word on the left with a check mark (tapping it keeps the word as typed, and autocorrect leaves it alone from then on), the correction space will write in the middle in bold accent, and another candidate on the right. Autofill chips from the password manager take the bar's row (in Auto mode, the suggestions' row) while no word is composed, centred, styled with key-functional and strip-text; a sideways swipe carries the chips with the finger, fading, and past a third of the strip or flung they slide off and the bar comes back for that field; let go sooner and they spring back (when they scroll, only a swipe on past the end of the row). Text copied in the last three minutes gets a chip in the same row, ahead of any autofill chips: 'Paste' in key-text-secondary and the clip's first 28 characters in strip-text (dots when the clip is marked sensitive or the field is a password field) on key-functional; a tap pastes it, and a sideways swipe puts it away with the autofill chips. A clip pasted or swiped away is not offered again; terminals get no chip. With the Shebang Voice add-on installed, a mic button sits at the right end of the top row (one row tall and wide), in text mode and fields that take typed words: the mic glyph in strip-text when idle; on an accent disc, the glyph in on-accent, while listening (the disc swells with the voice level) and while writing (steady). The strip then shows 'Listening — tap the mic to stop' or 'Writing…' in bold. The bar and the chips row fade out over 16 dp where there is more to scroll, so a chip is never cut off by the mic or the edge; the bar runs 8 dp under the mic's empty left edge, so its fade ends close to the glyph.

##### keyboard-text/glide-preview

Applies to: glide, preview, strip

During a glide the suggestion strip shows a single full-width bold line (the words that lift would write). After lift it returns to three slots: runner-up, chosen word (bold, middle), third. Glided words go straight into the field; the keyboard never rewrites text on its own. To redo a word the user taps inside it or selects it: it is underlined (the field's composing underline) and the strip shows it in the middle slot with its alternatives either side; the next glide or a tapped alternative replaces it, keeping its capitals. A cursor at a word's edge targets nothing, so a glide there adds a word; space with a word targeted moves past it.

### the launcher setup screen with three steps and a test field (Android app)

Platform: an Android app: Material's shapes and elevation, 48dp touch targets, the app's sections as a bottom bar, and the system's back gesture respected.
Layout of the launcher setup screen with three steps and a test field on a phone frame (6 columns by 12 rows), from the Mobile app template, numbered in drawing order (a later block draws over an earlier one where they overlap):
  1. Header (the band at the top): Shebang DevBoard, across the top, row 1 of 12
  2. Text (a block of prose): one-line pitch, across the middle, row 2 of 12
  3. Card (a box holding one thing): 1 Enable the keyboard (Done / button), across the middle, rows 3 to 4 of 12
  4. Card (a box holding one thing): 2 Select it (Done / Choose keyboard), across the middle, rows 5 to 6 of 12
  5. Card (a box holding one thing): 3 Try it: multiline test field, across the middle, rows 7 to 9 of 12
  6. Button (an action to press): Open DevBoard settings, across the middle, row 11 of 12
Notes:
  Material 3, dynamic colour when available. Step state is re-checked on resume and every second while visible because the system sends no broadcast.

### the settings screen, the terminal-bar editor and personal words (Android app)

Platform: an Android app: Material's shapes and elevation, 48dp touch targets, the app's sections as a bottom bar, and the system's back gesture respected.
Layout of the settings screen, the terminal-bar editor and personal words on a phone frame (6 columns by 28 rows: the frame shows 12 of them, and the page scrolls), from the Settings template, numbered in drawing order (a later block draws over an earlier one where they overlap):
  1. Header (the band at the top): DevBoard settings, across the top, row 1 of 28
  2. List (rows of like things): Appearance: theme swatches (Auto, Wallpaper, 12 themes), height slider, number row, key preview, across the middle, rows 2 to 6 of 28
  3. List (rows of like things): Feedback: haptics, strength chips, key sounds, across the middle, rows 7 to 9 of 28
  4. List (rows of like things): Typing: glide, trail, phrase gliding, Record glides, across the middle, rows 10 to 13 of 28
  5. List (rows of like things): Learning: learn words I type, adapt glide to my swiping, Personal words, across the middle, rows 14 to 16 of 28
  6. List (rows of like things): Corrections: autocorrect, auto-capitalize, double-space period, across the middle, rows 17 to 18 of 28
  7. List (rows of like things): Terminal bar: strip behaviour chips, Edit terminal bar, across the middle, rows 19 to 20 of 28
  8. List (rows of like things): Bar editor: items with up/down/remove, + add dialog, More: export/import/reset, across the middle, rows 21 to 24 of 28
  9. List (rows of like things): Personal words: search under the title (only matches show), note, reset, undo learning, Android dictionary, words, across the bottom, rows 25 to 28 of 28
Notes:
  Switch rows are ListItems with a trailing Switch; choices are FilterChip rows; the bar editor is a second screen inside the same activity. Personal words is a third screen in the same activity: a plain explanation first, then actions as ListItems, then each learned word with its use count and a trailing delete icon; clearing all, resetting adaptation and undoing recent learning confirm in an AlertDialog; undo first lists the kept days (Today, Yesterday, then weekday and date).

### the glide recorder: prompts common words and records glides on a copy of the keyboard (Android app)

Platform: an Android app: Material's shapes and elevation, 48dp touch targets, the app's sections as a bottom bar, and the system's back gesture respected.
Layout of the glide recorder: prompts common words and records glides on a copy of the keyboard on a phone frame (6 columns by 12 rows), from the Mobile app template, numbered in drawing order (a later block draws over an earlier one where they overlap):
  1. Header (the band at the top): Record glides, Export, Delete all, across the top, row 1 of 12
  2. Text (a block of prose): What is recorded and where it stays, across the middle, row 2 of 12
  3. Text (a block of prose): The prompted word, large, across the middle, rows 3 to 4 of 12
  4. Text (a block of prose): N recorded; Skip, Undo last, across the middle, row 5 of 12
  5. Content (the main reading or working area): The text keyboard, sized like the real one; taps do nothing, across the bottom, rows 9 to 12 of 12
Notes:
  Reached from Settings > Typing > Record glides. Each finished glide is saved with the prompt, its touch points and times, and the letter key centres, then the next word appears. Delete all asks first.
This page's own rules:
  glide-recorder/privacy: record, trace, export (`bb design show glide-recorder/privacy` reads it)

#### Rules of the glide recorder: prompts common words and records glides on a copy of the keyboard's own

##### glide-recorder/privacy

Applies to: record, trace, export

Only prompted words are recorded, in the app's private files; nothing leaves the phone except through Export and the system file picker.
