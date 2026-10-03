package dev.shebang.devboard.ime

import android.os.Handler
import android.os.Looper
import android.text.InputType
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import dev.shebang.devboard.glide.GlideResult
import dev.shebang.devboard.glide.GlideWord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/** Identifiers from the text around the cursor: suggested while typing, offered after a glide, and joined from glided words. */
class CodeWordsTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var strip: List<String> = emptyList()
    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) {
                strip = words
            }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(Looper.getMainLooper()),
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { i -> kotlin.math.exp(-lm.unigramCost(i).toDouble()).toFloat() })
    }

    private fun idx(w: String) = dictionary.indexOfLower(w)

    private fun glide(w: String, stroke: FloatArray? = null) {
        controller.glideContext(dictionary, lm)
        val r = GlideResult(intArrayOf(idx(w)), listOf(stroke), listOf(null), listOf(GlideWord(idx(w), intArrayOf(idx(w)), floatArrayOf(0f))), intArrayOf(idx(w)), IntArray(0), -1, 5)
        controller.commitGlide(r, dictionary, false, false)
    }

    @Test
    fun anIdentifierInTheTextIsSuggestedWhileTyping() {
        ic.commitText("val maxRetries = 3\nif (", 1)
        for (c in "max") controller.typeText(c.toString())
        assertEquals("maxRetries", strip[1])
    }

    @Test
    fun glidedWordsJoinIntoOneName() {
        ic.commitText("fun getUserName() = 1\n", 1)
        glide("get")
        glide("user")
        glide("name")
        assertEquals(listOf("getUserName", "name", "get_user_name"), strip)
        controller.pickCandidate("get_user_name")
        assertEquals("fun getUserName() = 1\nget_user_name", ic.toString())
    }

    @Test
    fun noJoinsInPlainProse() {
        glide("get")
        glide("user")
        assertTrue("getUser" !in strip)
    }

    @Test
    fun anIdentifierThatFitsTheStrokeIsOffered() {
        ic.commitText("run kubectl_apply first; ", 1)
        val stroke = floatArrayOf(0f, 0f, 1f, 1f)
        controller.identifierScorer = { _, letters -> if (letters == "kubectlapply") 0.30f else if (letters == "keyboard") 0.25f else 1f }
        glide("keyboard", stroke)
        assertEquals("kubectl_apply", strip[0])
        controller.pickCandidate("kubectl_apply")
        assertEquals("run kubectl_apply first; kubectl_apply", ic.toString())
    }
}
