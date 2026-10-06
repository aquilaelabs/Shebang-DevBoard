package dev.shebang.devboard

import android.content.Intent
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.shebang.devboard.fieldtest.FieldTestActivity
import dev.shebang.devboard.ime.DevBoardService
import dev.shebang.devboard.ime.EnterKind
import dev.shebang.devboard.layout.Key
import dev.shebang.devboard.layout.KeyAction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard in real fields (R33): EditText, Compose and WebView fields and a terminal-like view, in
 * FieldTestActivity (debug builds only), which runs in a process of its own as any app does. Its field opens
 * focused; each test waits for the keyboard, types by tapping its keys through the same entry the keyboard view uses, and checks what the app received:
 * its text, the editor actions it ran, the keys a terminal got, and the Enter key's glyph. This is the layer the
 * unit tests' fake text field cannot see (B14: Enter in a multi-line Compose search box).
 *
 * Run on an emulator: ./gradlew :app:connectedDebugAndroidTest. It selects Shebang DevBoard as the keyboard and
 * leaves it selected. The EditText fields ask for no learning; Compose and WebView fields cannot, so on a phone
 * the few test words could be learned.
 */
@RunWith(AndroidJUnit4::class)
class FieldCompatibilityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var opened = false

    companion object {
        private const val IME = "dev.shebang.devboard/.ime.DevBoardService"

        @BeforeClass
        @JvmStatic
        fun selectTheKeyboard() {
            for (cmd in listOf(
                "settings put secure show_ime_with_hard_keyboard 1",
                "ime enable $IME",
                "ime set $IME",
            )) shell(cmd)
        }

        private fun shell(cmd: String): String {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
            return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }
        }
    }

    @After
    fun close() {
        if (opened) {
            context.startActivity(fieldIntent().putExtra(FieldTestActivity.EXTRA_FINISH, true))
            opened = false
        }
        // The keyboard hides with the activity; the next test waits for it to come up for its own field.
        waitFor(5_000) { DevBoardService.running?.inputShownForTest != true }
    }

    // ---- Enter ------------------------------------------------------------------------------------

    @Test
    fun enterAddsANewLineInAMultiLineEditText() {
        open(FieldTestActivity.MULTILINE)
        assertEquals(EnterKind.RETURN, service().enterKindForTest)
        type("hi\nyo")
        assertText("hi\nyo")
        assertEquals(emptyList<String>(), actions())
    }

    @Test
    fun enterRunsTheSearchInASearchField() {
        open(FieldTestActivity.SEARCH)
        assertEquals(EnterKind.SEARCH, service().enterKindForTest)
        type("cats\n")
        assertActions("search")
        assertText("cats")
    }

    @Test
    fun enterRunsTheSearchInAMultiLineComposeSearchBox() {
        // B14: the Play Store's search box is a Compose field, multi-line, asking for Search.
        open(FieldTestActivity.COMPOSE_MULTILINE_SEARCH)
        assertEquals(EnterKind.SEARCH, service().enterKindForTest)
        type("cats\n")
        assertActions("search")
        assertText("cats")
    }

    @Test
    fun enterAddsANewLineInAPlainComposeField() {
        open(FieldTestActivity.COMPOSE_PLAIN)
        assertEquals(EnterKind.RETURN, service().enterKindForTest)
        type("hi\nyo")
        assertText("hi\nyo")
    }

    @Test
    fun enterSubmitsAWebSearchFormAndAddsALineInAWebTextarea() {
        // The page turns autocapitalize off; Chrome otherwise asks for sentence capitals, and gets "Cats".
        open(FieldTestActivity.WEB_SEARCH)
        assertNotEquals(EnterKind.RETURN, service().enterKindForTest)
        type("cats\n")
        assertActions("submit")
        assertText("cats")
        close()

        open(FieldTestActivity.WEB_TEXTAREA)
        type("hi\nyo")
        assertText("hi\nyo")
    }

    // ---- Typing -----------------------------------------------------------------------------------

    @Test
    fun autocorrectFixesATypoAndPunctuationJoinsTheWord() {
        open(FieldTestActivity.MULTILINE, needsLanguage = true)
        type("teh ")
        assertText("the ")
        // B11: punctuation after the space goes against the word.
        type(".")
        assertText("the.")
    }

    // ---- A browser terminal: xterm.js (B17) -----------------------------------------------------------

    /** What a shell's line editor holds after [received]: each DEL takes a character off; lines end at Enter. */
    private fun shellLines(received: String): List<String> {
        val lines = ArrayList<String>()
        val line = StringBuilder()
        for (c in received) when (c) {
            '\u007f' -> if (line.isNotEmpty()) line.setLength(line.length - 1)
            '\r' -> { lines += line.toString(); line.setLength(0) }
            else -> line.append(c)
        }
        return lines
    }

    private fun assertShellGot(vararg lines: String) {
        waitFor(4_000) { shellLines(currentText()) == lines.toList() }
        assertEquals("the terminal received ${currentText().map { if (it.code < 32 || it.code == 127) "\\x%02x".format(it.code) else it.toString() }.joinToString("")}",
            lines.toList(), shellLines(currentText()))
    }

    @Test
    fun aBrowserTerminalGetsExactlyWhatIsTyped() {
        open(FieldTestActivity.WEB_TERMINAL, needsLanguage = true)
        val f = service().fieldForTest
        assertTrue("xterm.js's input is typed exactly (or raw): $f", f.exact || !f.allowsComposing)
        // No autocorrect, and punctuation stays after its space ("git add ." is not "git add.").
        type("teh .\n")
        // One-letter words arrive once.
        type("a b c\n")
        assertShellGot("teh .", "a b c")
    }

    @Test
    fun quickTypingInABrowserTerminalLosesNothing() {
        // The terminal picks finished words up on a timer, so edits that land close together can race: the same
        // lines several times over, as fast as the test types.
        open(FieldTestActivity.WEB_TERMINAL, needsLanguage = true)
        val lines = listOf("teh .", "a b c", "git add .", "ls x y", "cd ..")
        repeat(3) { for (l in lines) type(l + "\n", settle = false) }
        assertShellGot(*(lines + lines + lines).toTypedArray())
    }

    @Test
    fun backspacingIntoAWordInABrowserTerminalDoesNotSendItAgain() {
        open(FieldTestActivity.WEB_TERMINAL, needsLanguage = true)
        // cat, space, two backspaces, r: the shell must hold "car", not "cacar" (B17) or "catr" (B18: a deletion
        // xterm.js only noticed on a timer, missed on GitHub's slower emulator). At full test speed: backspace
        // goes as a key press, which the terminal acts on at once.
        type("cat \b\br\n")
        assertShellGot("car")
    }

    @Test
    fun backspaceAfterAGlideInABrowserTerminalTakesTheWholeGlideOffTheShell() {
        open(FieldTestActivity.WEB_TERMINAL, needsLanguage = true)
        var glides = false
        waitFor(30_000) { instrumentation.runOnMainSync { glides = service().isGlideAllowed() }; glides }
        assertTrue("glide is ready", glides)
        // Glide "hello": whatever word it reads, backspace must take all of it off the shell's line, one
        // backspace for each character.
        stroke(*"hello".map { key(it)!!.let { k -> k.centerX to k.centerY } }.toTypedArray())
        waitFor(4_000) { currentText().isNotEmpty() }
        assertTrue("the glided word reached the terminal", currentText().isNotEmpty())
        type("\bok\n")
        assertShellGot("ok")
    }

    // ---- Touches near the bottom row (B16) ----------------------------------------------------------

    @Test
    fun aGlideThatStartsOnBackspaceDeletesNothing() {
        open(FieldTestActivity.MULTILINE, needsLanguage = true)
        type("one two three four five six ")
        assertText("one two three four five six ")
        // A glide for "my" whose first touch lands on the left edge of backspace, beside the m, then heads up-left.
        val back = keyFor(KeyAction.BACKSPACE)
        val y = key('y')!!
        stroke(back.left + back.width * 0.15f to back.centerY, y.centerX to y.centerY)
        assertText("one two three four five six ")
        assertEquals("nothing selected", state().optInt("selStart"), state().optInt("selEnd"))
    }

    @Test
    fun aLevelSwipeLeftFromBackspaceStillDeletesAWord() {
        open(FieldTestActivity.MULTILINE, needsLanguage = true)
        type("one two three ")
        val back = keyFor(KeyAction.BACKSPACE)
        val kw = key('q')!!.width
        stroke(back.centerX to back.centerY, back.centerX - kw * 1.2f to back.centerY)
        waitFor(3_000) { currentText().length < "one two three ".length }
        assertTrue("a word went: '${currentText()}'", currentText().trimEnd() == "one two")
    }

    @Test
    fun slidingOnSpaceAtASentenceStartMovesTheCursorWithoutSelecting() {
        // A field that asks for sentence capitals, as message boxes do: after ". " the keyboard turns shift on by
        // itself for the next word. That is not the user's shift, so the slide must not select.
        open(FieldTestActivity.SENTENCES, needsLanguage = true)
        type("one two. ")
        assertText("One two. ")
        var shift = dev.shebang.devboard.view.ShiftState.OFF
        instrumentation.runOnMainSync { shift = service().keyboardForTest!!.shiftState }
        assertEquals("shift is on by itself at the sentence start", dev.shebang.devboard.view.ShiftState.ON, shift)
        val space = keyFor(KeyAction.SPACE)
        val kw = key('q')!!.width
        // A short slide, one step left: a longer one hides the fault, since the keyboard drops its own shift once
        // the cursor leaves the sentence start and the next plain step collapses what the first selected.
        stroke(space.centerX to space.centerY, space.centerX - kw * 1.3f to space.centerY)
        waitFor(2_000) { state().optInt("selEnd") < "One two. ".length }
        assertEquals("One two. ", currentText())
        assertEquals("nothing selected", state().optInt("selStart"), state().optInt("selEnd"))
        assertTrue("the cursor moved left", state().optInt("selEnd") < "One two. ".length)
    }

    @Test
    fun twoWordsRunTogetherAreSplitOnSpace() {
        open(FieldTestActivity.MULTILINE, needsLanguage = true)
        type("so thankyou ")
        assertText("so thank you ")
    }

    @Test
    fun aWebAddressFieldTypesExactlyAndGoes() {
        open(FieldTestActivity.URL, needsLanguage = true)
        assertEquals(EnterKind.SUBMIT, service().enterKindForTest)
        type("teh github.io")
        assertText("teh github.io")
        type("\n")
        assertActions("go")
    }

    @Test
    fun anEmailFieldTypesExactly() {
        open(FieldTestActivity.EMAIL, needsLanguage = true)
        assertEquals(EnterKind.SUBMIT, service().enterKindForTest)
        type("jon@exmaple.com")
        assertText("jon@exmaple.com")
        type("\n")
        assertActions("done")
    }

    @Test
    fun aPasswordFieldGetsLettersWithNothingComposing() {
        open(FieldTestActivity.PASSWORD)
        type("abcd")
        assertText("abcd")
        assertEquals("composing span in a password field", -1, state().optInt("composingStart", -2))
    }

    @Test
    fun aNumberFieldGetsTheNumberPad() {
        open(FieldTestActivity.NUMBER)
        assertTrue("no 'q' key on a number pad", key('q') == null)
        type("42")
        assertText("42")
    }

    @Test
    fun aTerminalGetsKeyEvents() {
        open(FieldTestActivity.TERMINAL)
        assertEquals(EnterKind.RETURN, service().enterKindForTest)
        type("ls\n")
        waitFor(3_000) { state().optString("keys") == "ls\n" }
        assertEquals("ls\n", state().optString("keys"))
    }

    // ---- Helpers ----------------------------------------------------------------------------------

    private fun fieldIntent() = Intent(context, FieldTestActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    private fun open(kind: String, needsLanguage: Boolean = false) {
        FieldTestActivity.stateFile(context).delete()
        context.startActivity(fieldIntent().addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK).putExtra(FieldTestActivity.EXTRA_FIELD, kind))
        opened = true
        val up = waitFor(15_000) {
            DevBoardService.running?.let { s -> s.inputShownForTest && s.keysForTest.isNotEmpty() } == true
        }
        assertTrue("the keyboard did not come up in the $kind field", up)
        // The keyboard remembers code mode per app, and these fields are all one app's: a run that follows someone
        // using code mode there would find no letters. The tests type in text mode.
        if (service().keysForTest.any { it.action == KeyAction.MODE_TEXT }) {
            val abc = keyFor(KeyAction.MODE_TEXT)
            instrumentation.runOnMainSync { service().tapForTest(abc) }
            assertTrue("the keyboard did not go back to letters", waitFor(5_000) { key('a') != null })
        }
        if (needsLanguage) assertTrue("the dictionary did not load", waitFor(30_000) { service().languageReadyForTest })
        instrumentation.waitForIdleSync()
    }

    /** What the field's app wrote about its field (FieldTestActivity runs in another process). */
    private fun state(): org.json.JSONObject {
        val f = FieldTestActivity.stateFile(context)
        return runCatching { org.json.JSONObject(f.readText()) }.getOrElse { org.json.JSONObject() }
    }

    private fun actions(): List<String> {
        val a = state().optJSONArray("actions") ?: return emptyList()
        return (0 until a.length()).map { a.getString(it) }
    }

    private fun keyFor(action: KeyAction): Key = service().keysForTest.first { it.action == action }

    /** A finger on the keyboard view: down at the first point, moving through the rest about 60 times a second, up at the last. */
    private fun stroke(vararg points: Pair<Float, Float>) {
        val view = service().keyboardForTest ?: error("no keyboard view")
        val down = SystemClock.uptimeMillis()
        fun send(action: Int, x: Float, y: Float) {
            val e = android.view.MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.runOnMainSync { view.dispatchTouchEvent(e) }
            e.recycle()
        }
        send(android.view.MotionEvent.ACTION_DOWN, points[0].first, points[0].second)
        for (i in 1 until points.size) {
            val (x0, y0) = points[i - 1]
            val (x1, y1) = points[i]
            for (k in 1..12) {
                SystemClock.sleep(16)
                send(android.view.MotionEvent.ACTION_MOVE, x0 + (x1 - x0) * k / 12f, y0 + (y1 - y0) * k / 12f)
            }
        }
        SystemClock.sleep(16)
        send(android.view.MotionEvent.ACTION_UP, points.last().first, points.last().second)
        instrumentation.waitForIdleSync()
        SystemClock.sleep(400)
    }

    private fun service(): DevBoardService = DevBoardService.running ?: error("the keyboard is not running")

    private fun key(c: Char): Key? = service().keysForTest.firstOrNull {
        when (c) {
            ' ' -> it.action == KeyAction.SPACE
            '\n' -> it.action == KeyAction.ENTER
            '\b' -> it.action == KeyAction.BACKSPACE
            else -> it.def.text == c.toString()
        }
    }

    /** Taps the keys for [s], one at a time, letting each tap's work settle as a person's pace would. */
    private fun type(s: String, settle: Boolean = true, gap: Long = 80) {
        for (c in s) {
            val k = key(c) ?: throw AssertionError("no key for '$c' in ${service().keysForTest.map { it.def.text ?: it.action }}")
            instrumentation.runOnMainSync { service().tapForTest(k) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(gap)
        }
        if (settle) SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
    }

    private fun currentText(): String = state().optString("text")

    private fun assertText(expected: String) {
        waitFor(3_000) { currentText() == expected }
        assertEquals(expected, currentText())
    }

    private fun assertActions(vararg expected: String) {
        waitFor(3_000) { actions() == expected.toList() }
        assertEquals(expected.toList(), actions())
    }

    /** Polls [condition] until it holds or [timeoutMs] passes; whether it held. */
    private fun waitFor(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val end = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return true
            SystemClock.sleep(50)
        }
        return condition()
    }
}
