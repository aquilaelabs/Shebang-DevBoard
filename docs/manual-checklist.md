# Manual checklist

What to check by hand on a device or the emulator, and what has been checked. Automated tests cover the
rest (`./gradlew test`).

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
      Android's personal dictionary is glidable; Settings > Learning and privacy > Personal words lists learned words.
      *(verified on the emulator, except the Personal words screen)*
- [x] Settings > Learning and privacy > Personal words lists learned words; delete removes one; Undo recent learning lists the kept
      days and going back to the start of today restores the counts of that morning. *(verified on the
      emulator: "world" went from 5 uses back to 4 and "hello" from 3 to 2)*
- [ ] Reset glide adaptation zeroes the glide and correction counts.
- [x] Phrase gliding on: "hello", dip below the middle of the space bar, "world" in one stroke writes
      "hello world"; lifting inside the space bar adds a space. *(verified on the emulator)*
- [x] Settings > Typing and glide > Record glides: gliding the prompted word stores it, the count goes up, the next word
      appears; the exported file replays in `RecordedGlidesTest`. *(verified on the emulator with injected
      strokes; export through the file picker not exercised)*
- [ ] Record a few hundred glides on the Pixel and run `RecordedGlidesTest`.
- [ ] Password: no suggestions, no glide, no preview text left anywhere.
- [x] Autofill (with a test autofill service on the emulator): chips in the strip in the keyboard's colours;
  tapping one filled the username and password.
- [x] Email: `@` where the comma was, long-press for `_ - + , & # %`; the next plain field has the comma
      back. *(verified on the emulator in Chrome, `type="email"`)*
- [ ] URL: `/` and `@` on the bottom row; no auto-capitalisation in URL or email fields.
- [x] Email field: an address typed there and left is offered in an empty email field and fills it in.
      *(verified on the emulator in Contacts)*
- [ ] Number/phone: numeric pad; `#!` still reaches code mode.
- [ ] Diagnostics: with Include recent fields on, the exported file lists the last fields' apps and kinds (newest first)
  and nothing typed; with it off, no recentFields section.
- [ ] Browser terminal (tmux or a shell in Firefox or Chrome): "teh" stays "teh"; "git add ." keeps its space;
  backspacing into a word and typing on does not repeat it; backspace after a glide removes the whole glide;
  Ctrl+B, Esc and Tab on the bar reach the terminal.
- [ ] Joined words: "ataco" offers "a taco" on the strip; "thankyou" then space gives "thank you"; backspace
  right after puts back "thankyou".
- [ ] Multiline: Enter inserts a newline and shows the return arrow; a Search field shows the magnifier and
  performs the search (also a multi-line one, such as the Play Store's); Go, Send and Done fields show the
  straight arrow.
- [x] Type "wiht cat ", backspace back to the corrected "with": it stays, and the strip offers "wiht",
      "with", "whit" (no check mark: space leaves the word); tapping "wiht" puts it back. *(verified on the
      emulator)*
- [x] TalkBack on: exploring the keys speaks them, lifting types the key, a double tap types the focused
      key once; Delete, Space and Shift work. *(verified on the emulator)*
- [x] Hold backspace on a long sentence: after a second it deletes whole words and stops at a word's edge;
      with Hold backspace for whole words off it stops mid-word. *(verified on the emulator)*
- [x] Type "see you soon " so predictions show, then clear the field from a hardware keyboard: the
      predictions go and the bar comes back. *(verified on the emulator)*
- [x] Settings > About > Export diagnostics saves a file with no learned words or email addresses in it.
      *(verified on the emulator)*

Layout:
- [x] Rotate to landscape: keys re-flow to the IME window's width (narrower than the display with a cutout),
      glide still decodes (cache invalidated), popup positions right. *(verified on the emulator)*
- [ ] Change keyboard height in settings: the keyboard resizes immediately; glide still decodes.
- [ ] Number row on: a fifth row appears in text mode only.
- [ ] Strip: Auto swaps bar and suggestions; Always bar never shows suggestions; Two rows shows both.
- [ ] Holding #! (or ABC) opens the system keyboard picker; dragging along space moves the cursor.
- [x] Shift: tap once for one capital (caret over a bar); tap twice quickly, or hold it, for caps lock (two
      carets over a bar, accent-coloured key). *(hold verified on the emulator)*
- [x] Hold "e": E is first in the row and lifting types it; hold "a", slide into the row and back down onto
      the key: lifting types "a". *(verified on the emulator)*
- [x] Emoji and clipboard panels from the bar: Recent emoji, a pinned copy and a picture in the history.
      *(verified on the emulator)*
- [x] A copied picture pasted from the clipboard history into an app that takes pictures. *(verified on the
      owner's phone)*

Usability items (0.5.2):
- [x] Bar editing in an Android text field (Contacts): Undo empties typed text, Redo brings it back, All then
      Cut empties the field, Paste puts the clipboard at the cursor. *(verified on the API 36 emulator)*
- [x] The gear on the bar opens Settings. *(verified)*
- [x] One-handed mode, from Settings > Appearance: keys docked right with the side panel on the left; the
      chevron moves them left; the two-headed arrow goes back to full width; gliding "the" on the narrow
      keys writes it. *(verified)*
- [x] Personal words > Add a word: "k8s" is refused with the rule shown; "Zorbly" is added as used twice and
      the strip offers it after typing "zorb". *(verified)*
- [ ] Undo and Paste in a web page (Firefox or Chrome) and in Termux (Paste types the clipboard there).
- [x] Redesigned settings and setup, in Night and Day: home page status and summaries, Appearance, Typing
      and glide, Learning and privacy, Personal words, Terminal bar and the bar editor (drag still reorders).
      *(verified on the API 36 emulator, debug and the signed 0.5.2 release)*
- [x] Settings > Dictionaries: the regular words show "Always on" and the three packs have switches;
      importing a three-word text file from Downloads adds a list, and the strip offers its word after "zorbl";
      with Computer terms off "cpu" then space gives "cup", with it on "CPU". *(verified on the API 36
      emulator)*
- [ ] On a phone: import a large list (tens of thousands of words) and see how long the keyboard takes to
      be ready afterwards.
- [x] The bar's gear opens settings with the app's setup screen left open in the background, and from
      Contacts. *(verified on the signed 0.5.3 release, API 36 emulator; before the fix it only brought
      the setup screen back)*
- [x] On the Pixel 11 Pro XL (signed 0.5.3): installs over earlier builds, loads in about 0.4 s, types a
      44-key sentence correctly with 0.8% janky frames. *(measured 5 Oct)*
- [x] On the Pixel (signed 0.5.3): the keyboard renders at the owner's height setting and a scripted glide
      reaches it as one stroke (the strip offered hello / he'll / hell). *(5 Oct; BuilderBot's phone shot,
      ui and glide work since #56)* Glide accuracy on the phone is the owner's to judge: scripted strokes
      are not finger-like.
- [ ] With the rebuilt word model (technical documentation counted): glide "sudo", "git" and "kubectl" in
      a sentence, and check the strip predicts a word after "sudo apt". *(not done: the emulator was in
      another session and the phone not lent, 5 Oct; unit tests and benchmarks pass)*
- [x] Autocorrect with taps on the signed 0.5.3 (API 36 emulator): "thjs" then space gives "this ", and taps
      landing near the intended keys read as them ("you" straight off). Taps dead centre on the wrong keys
      ("yiy", "tbjs") are kept as typed, by design: a centred tap is likely meant. The two-slip cases are
      measured on TSI's real taps (typos fixed 75.9% -> 80.6%). *(5 Oct)*
- [x] Firefox's address bar (Firefox 157 on the API 36 emulator, example.com in history): glide "exam" (history
      completes "example.com/"), glide "world": "exam world". Glide "example" then "world": "example world".
      Glide "exam", type p (the completion stays "example.com/"), glide "world": "examp world".
      *(5 Oct; before the fix the second glide left only "world")*
