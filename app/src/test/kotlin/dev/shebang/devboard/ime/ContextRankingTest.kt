package dev.shebang.devboard.ime

import android.os.Handler
import android.text.InputType
import dev.shebang.devboard.dict.NgramModelTest
import dev.shebang.devboard.dict.Suggester
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.Executor

/** Typed suggestions and autocorrect rank by the words before the word being typed (R13). */
class ContextRankingTest {
    private val dictionary get() = NgramModelTest.dictionary
    private val lm get() = NgramModelTest.lm
    private val ic = FakeInputConnection()
    private var strip: List<String> = emptyList()

    private val controller = TextInputController(
        { ic },
        object : TextInputController.Ui {
            override fun showCandidates(words: List<String>) { strip = words }
            override fun setComposing(composing: Boolean) = Unit
        },
        Executor { it.run() },
        Handler(),
        postToMain = { it.run() },
    ).also {
        it.startInput(FieldInfo.from(InputType.TYPE_CLASS_TEXT, 0))
        it.suggester = Suggester(dictionary, null, FloatArray(dictionary.size) { i -> kotlin.math.exp(-lm.unigramCost(i).toDouble()).toFloat() }, lm)
        it.predictionModel = dictionary to lm
        it.settings = it.settings.copy(autocorrect = true, nextWord = false)
    }

    private fun type(s: String) {
        for (c in s) if (c == ' ') controller.space() else controller.typeText(c.toString())
    }

    @Test
    fun aSlipIsCorrectedToTheWordThatFitsTheWordsBefore() {
        // Alone, "haie" would become "have".
        type("cut my haie ")
        assertEquals("cut my hair ", ic.toString())
    }

    @Test
    fun anotherSlipFitsItsSentence() {
        // Alone, "vook" would become "book".
        type("she can vook ")
        assertEquals("she can cook ", ic.toString())
    }

    @Test
    fun theStripLeadsWithTheCompletionThatFits() {
        type("can you hel")
        assertEquals("help", strip[1].lowercase())
    }
}
