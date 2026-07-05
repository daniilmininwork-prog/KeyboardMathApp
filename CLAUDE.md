# KeyboardMathApp (Tally) — Claude working notes

Offline Android math IME. Build needs `JAVA_HOME` = Android Studio's bundled JBR.

## Driving the emulator (so Claude can SEE & verify the app)

The emulator is **not broken** — Claude just needs a bridge. `adb`/`emulator` may be
off PATH; after a shell restart they resolve from `~/Library/Android/sdk`, but you can
always use the absolute path `~/Library/Android/sdk/platform-tools/adb`.

- **Device:** AVD `Tally_Test_API35`, serial `emulator-5554`, API 35. Start headless
  out-of-band: `emulator -avd Tally_Test_API35 -no-window -no-audio` (single instance
  per AVD unless `-read-only`).
- **SEE (prefer the tree over pixels):**
  - Element tree: `adb -s emulator-5554 exec-out uiautomator dump /dev/tty`
    → tap the center of a node's `bounds`.
  - Screenshot (only when the tree is opaque — ~1.3 MB each):
    `adb -s emulator-5554 exec-out screencap -p > /tmp/s.png` then Read it.
- **ACT:** `adb -s emulator-5554 shell input tap X Y | input swipe ... |
  input text 'ascii' | input keyevent KEYCODE_*`

## IME verification — read this before testing the keyboard

- **IME ids:** release `dev.tally/.ime.TallyInputMethodService`; debug
  `dev.tally.debug/helium314.keyboard.latin.LatinIME` (debug is the device default —
  restore it when done). Switch: `adb shell ime enable <id>` then `ime set <id>`;
  confirm with `adb shell settings get secure default_input_method`.
- **CRITICAL:** `input text`/`keyevent` (and any MCP "type" tool) inject events
  *below* the IME via InputManager — they do **not** exercise Tally's keys. To truly
  verify Tally, screenshot/dump the on-screen keyboard and **tap its rendered keys** by
  coordinate/element.
- **`input text` is ASCII-only** (non-ASCII → `NullPointerException`, rc=255). For math
  symbols / Unicode, tap the keys, or `adb shell cmd clipboard set '...'` +
  `input keyevent KEYCODE_PASTE`.
- To focus a field: launch an Activity with an EditText (e.g. the debug "Tally Debug
  Typing" harness), dump the tree, tap the EditText center to raise the keyboard.
- **Avoid uiautomator2/Appium-based MCP servers** here — they install their own
  FastInput IME to type, which swaps the active keyboard and defeats an IME test.
