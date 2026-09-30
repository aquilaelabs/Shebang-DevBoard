# Changelog

## Unreleased

### Added

- Text mode: QWERTY with glide typing, suggestions, long-press alternates, auto-capitalisation, double-space period (4447b83)
- Code mode (#! key): every digit and printable ASCII symbol on one page plus arrow keys, no shift or long-press (4447b83)
- Terminal bar with sticky Ctrl/Alt/Shift/Meta, real KeyEvents for terminals, hold-to-repeat arrows and Del, snippets, and a JSON editor with import/export (4447b83)
- Field-aware behaviour: raw input for terminals, no suggestions or glide in password fields, numeric pad for number/phone/date, @ and / for email/URL (4447b83)
- Setup screen with the three enable/select/test steps and a Material 3 settings app (theme, height, haptics, sounds, glide, autocorrect, strip mode) (4447b83)
- A later glide can fix up to four earlier glided words when the pair makes their meaning clear (Settings: Fix earlier glided words) (6383035)
- Phrase gliding: dip into the space bar mid-stroke to glide several words without lifting (Settings, off by default) (6383035)
- Settings > Record glides keeps glides of prompted words on the phone to measure accuracy on real fingers (6383035)

### Changed

- Key preview and alternates draw inside the keyboard window; autocorrect and auto-capitalisation no longer do dictionary or editor work per keystroke on the main thread (8d54743)
- Glide typing decodes while the finger moves and shows the word in the strip before you lift; it is far more accurate and uses the word before the cursor (6383035)

### Fixed

- Glide can now produce apostrophe words (don't, it's), and it's, that's, let's and other 's contractions are in the word list (6383035)
