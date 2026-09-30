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
| `--key-text-secondary` | `#9aa3b5` | long-press hints in key corners |
| `--accent` | `#7be0a6` | enter key, caps lock, glide trail, popup selection, snippets |
| `--on-accent` | `#0b2a1a` | text on the accent |
| `--popup` | `#3b4252` | key preview and alternates popup |
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
| `--key-text-secondary` | `#5b6475` | long-press hints |
| `--accent` | `#1e8e5a` | enter key, caps lock, glide trail, popup selection |
| `--on-accent` | `#ffffff` | text on the accent |
| `--popup` | `#ffffff` | key preview and alternates popup |
| `--modifier-active` | `#bfe8d2` | one-shot sticky modifier chip |
| `--modifier-locked` | `#57c48b` | locked sticky modifier chip |

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

On Android 12+ with the Dynamic color setting on, the keyboard takes its palette from android.R.color.system_neutral1/neutral2/accent1/accent2 instead of these tokens: neutral1 for surfaces, accent1 for the accent. These hex values are the fallback palette and the pre-12 look.

### keys

Applies to: app/src/main/kotlin/dev/shebang/devboard/view; key, keyboard

Keys are flat rounded rectangles (radius 16% of the row height, clamped 4-12dp) separated by 5dp horizontal and 8dp vertical gaps, drawn on one Canvas. Character keys use `key`, function keys `key-functional`, enter and caps lock the accent. Letter keys show their first long-press alternate as a small hint in the top-right corner.

### icons

Applies to: icon

Backspace and return glyphs are Material Symbols paths (Apache-2.0); the shift arrow and the #! launcher icon are original. No brand marks anywhere.

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
  Rows are 52dp in portrait, 40dp in landscape, times the height setting. The bar and the suggestion strip are 44dp. Code mode swaps the four rows for five symbol rows at 86% row height with ABC / ? and arrows on the bottom row; the numeric pad is a 4-column grid. The keyboard pads for the navigation bar the IME window hosts on recent Android. With Preview glides on (the default), glided words wait in a preview row in the strip's place for 2 seconds after the last glide, or until anything else is typed, then go into the field; words in the field are never rewritten. Each staged word is a chip on the key colour; the selected one is filled with the accent; a glide in progress shows its words as italic chips outlined in the secondary text colour; the selected word's alternatives, or suggestions for letters typed to replace it, follow a thin divider as chips outlined in the accent. With Preview glides off, a glide in progress shows one bold line across the strip. With phrase gliding on, the space key lights up in the pressed colour once a dip counts as a word boundary.
This page's own rules:
  keyboard-text/strip: strip, suggestion, bar (`bb design show keyboard-text/strip` reads it)
  keyboard-text/glide-preview: glide, preview, strip (`bb design show keyboard-text/glide-preview` reads it)

#### Rules of the keyboard in text mode: terminal bar or suggestions above four QWERTY rows's own

##### keyboard-text/strip

Applies to: strip, suggestion, bar

In Auto mode the bar is replaced by suggestions while a word is being composed and returns once it is committed; code mode always shows the bar. Two rows stacks bar over suggestions.

##### keyboard-text/glide-preview

Applies to: glide, preview, strip

Glided words stage in a preview row that replaces the strip (code mode keeps the bar) and go into the field 2 s after the last glide, or at once when anything else is typed or the field is left. Tap a staged word to select it (accent fill; the timer pauses): the next glide replaces it, letters typed replace it (space or a second tap confirms), or an outlined alternative chip replaces it; then glides append at the end again. A later glide may re-read words still in the row when the phrase reads better; words already in the field are never changed. With the row off, a glide in progress shows a single full-width bold line and after lift the strip returns to three slots: runner-up, chosen word (bold, middle), third.

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
  2. List (rows of like things): Appearance: theme chips, dynamic colour, height slider, number row, key preview, across the middle, rows 2 to 6 of 28
  3. List (rows of like things): Feedback: haptics, strength chips, key sounds, across the middle, rows 7 to 9 of 28
  4. List (rows of like things): Typing: glide, trail, phrase gliding, preview glides, refine previewed words, Record glides, across the middle, rows 10 to 13 of 28
  5. List (rows of like things): Learning: learn words I type, adapt glide to my swiping, Personal words, across the middle, rows 14 to 16 of 28
  6. List (rows of like things): Corrections: autocorrect, auto-capitalize, double-space period, across the middle, rows 17 to 18 of 28
  7. List (rows of like things): Terminal bar: strip behaviour chips, Edit terminal bar, across the middle, rows 19 to 20 of 28
  8. List (rows of like things): Bar editor: items with up/down/remove, + add dialog, More: export/import/reset, across the middle, rows 21 to 24 of 28
  9. List (rows of like things): Personal words: note, reset adaptation, Android dictionary, delete all, words with count and delete, across the bottom, rows 25 to 28 of 28
Notes:
  Switch rows are ListItems with a trailing Switch; choices are FilterChip rows; the bar editor is a second screen inside the same activity. Personal words is a third screen in the same activity: a plain explanation first, then actions as ListItems, then each learned word with its use count and a trailing delete icon; clearing all and resetting adaptation confirm in an AlertDialog.

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
