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
import org.junit.Assert.fail
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

    private fun service(): DevBoardService = DevBoardService.running ?: error("the keyboard is not running")

    private fun key(c: Char): Key? = service().keysForTest.firstOrNull {
        when (c) {
            ' ' -> it.action == KeyAction.SPACE
            '\n' -> it.action == KeyAction.ENTER
            else -> it.def.text == c.toString()
        }
    }

    /** Taps the keys for [s], one at a time, letting each tap's work settle as a person's pace would. */
    private fun type(s: String) {
        for (c in s) {
            val k = key(c) ?: fail("no key for '$c' in ${service().keysForTest.map { it.def.text ?: it.action }}") as Nothing
            instrumentation.runOnMainSync { service().tapForTest(k) }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(80)
        }
        SystemClock.sleep(300)
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
