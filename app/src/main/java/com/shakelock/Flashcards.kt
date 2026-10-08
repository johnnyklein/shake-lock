package com.shakelock

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import kotlinx.coroutines.delay
import kotlin.random.Random

/** One vocabulary card. [id] is the Spanish side. */
data class Card(val spanish: String, val english: String) {
    val id get() = spanish

    /** Distractors are drawn from the same kind, so verbs compete with verbs etc. */
    val kind
        get() = when {
            english.startsWith("to ") -> "verb"
            spanish.startsWith("¿") -> "question"
            else -> "other"
        }
}

object SpanishDeck {
    private var cards: List<Card>? = null

    fun load(context: Context): List<Card> = cards ?: context.assets.open("spanish_en.tsv").bufferedReader().useLines { lines ->
        lines.mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size == 2) Card(parts[0].trim(), parts[1].trim()) else null
        }.toList()
    }.also { cards = it }
}

/**
 * Leitner-style spaced repetition: a right answer moves a card up a box (longer until it's asked again),
 * a wrong one sends it back to box 1 (asked again within a minute).
 */
class Srs(context: Context) {
    private val prefs = context.getSharedPreferences("srs", Context.MODE_PRIVATE)

    private fun state(card: Card): Pair<Int, Long>? = prefs.getString(card.id, null)?.split('|')?.let {
        it[0].toInt() to it[1].toLong()
    }

    fun box(card: Card) = state(card)?.first ?: 0

    fun record(card: Card, correct: Boolean) {
        val box = if (correct) (box(card) + 1).coerceAtMost(INTERVALS_MS.lastIndex) else 1
        prefs.edit { putString(card.id, "$box|${System.currentTimeMillis() + INTERVALS_MS[box]}") }
    }

    /** Words that survived at least a day. */
    fun knownCount() = prefs.all.values.count { (it as? String)?.substringBefore('|')?.toIntOrNull()?.let { box -> box >= KNOWN_BOX } == true }

    /** Due cards first, then new ones in deck order, then whatever is due soonest. */
    fun next(deck: List<Card>, skip: Card?): Card {
        val now = System.currentTimeMillis()
        val candidates = deck.filter { it != skip }
        val seen = candidates.mapNotNull { card -> state(card)?.let { card to it.second } }
        seen.filter { it.second <= now }.minByOrNull { it.second }?.let { return it.first }
        candidates.firstOrNull { state(it) == null }?.let { return it }
        return seen.minByOrNull { it.second }?.first ?: candidates.random()
    }

    private companion object {
        val INTERVALS_MS = longArrayOf(
            0, 60_000, 10 * 60_000, 24 * 3_600_000L, 3 * 24 * 3_600_000L, 7 * 24 * 3_600_000L, 21 * 24 * 3_600_000L,
        )
        const val KNOWN_BOX = 4
    }
}

private data class Question(val card: Card, val prompt: String, val answer: String, val options: List<String>, val reverse: Boolean)

private fun makeQuestion(card: Card, deck: List<Card>, srs: Srs): Question {
    // English → Spanish only once a word is a bit familiar.
    val reverse = srs.box(card) >= 2 && Random.nextFloat() < 0.35f
    fun side(c: Card) = if (reverse) c.spanish else c.english
    val answer = side(card)
    val distractors = deck.asSequence()
        .filter { it != card && it.kind == card.kind }
        .map(::side)
        .filter { it != answer }
        .distinct()
        .shuffled()
        .take(3)
        .toList()
    return Question(card, if (reverse) card.english else card.spanish, answer, (distractors + answer).shuffled(), reverse)
}

private val Right = Color(0xFF4CC38A)
private val Wrong = Color(0xFFFF6B6B)

/**
 * Multiple-choice Spanish quiz in the lock screen's style.
 * With [goal], [onGoal] fires after that many right answers; without, it just keeps going.
 */
@Composable
fun FlashCardQuiz(goal: Int?, onGoal: () -> Unit = {}) {
    val context = LocalContext.current
    val deck = remember { SpanishDeck.load(context) }
    val srs = remember { Srs(context) }
    val stats = remember { StatsLog(context) }
    var question by remember { mutableStateOf(makeQuestion(srs.next(deck, null), deck, srs)) }
    var picked by remember { mutableStateOf<String?>(null) }
    var right by remember { mutableIntStateOf(0) }
    var answered by remember { mutableIntStateOf(0) }

    LaunchedEffect(picked) {
        val choice = picked ?: return@LaunchedEffect
        val correct = choice == question.answer
        delay(if (correct) 650 else 1600)
        if (goal != null && right >= goal) {
            onGoal()
        } else {
            question = makeQuestion(srs.next(deck, question.card), deck, srs)
            picked = null
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (goal != null) {
            Text("$right / $goal right", color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress = { right.toFloat() / goal },
                color = Coral,
                trackColor = Color.White.copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small),
            )
        } else {
            Text(
                if (answered == 0) "Tap the right translation" else "$answered answered · $right right",
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp,
            )
        }
        Spacer(Modifier.height(16.dp))

        Box(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .background(Color.White.copy(alpha = 0.08f))
                .padding(vertical = 28.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    if (question.reverse) "ENGLISH → SPANISH" else "SPANISH → ENGLISH",
                    color = Coral,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(question.prompt, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.height(14.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            question.options.forEach { option ->
                val revealed = picked != null
                val color = when {
                    revealed && option == question.answer -> Right
                    revealed && option == picked -> Wrong
                    else -> Color.White.copy(alpha = 0.35f)
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.small)
                        .background(if (revealed && (option == question.answer || option == picked)) color.copy(alpha = 0.25f) else Color.Transparent)
                        .border(1.5.dp, color, MaterialTheme.shapes.small)
                        .clickable(enabled = !revealed) {
                            val correct = option == question.answer
                            srs.record(question.card, correct)
                            stats.add(CardEvent(System.currentTimeMillis(), question.card.id, correct))
                            answered++
                            if (correct) right++
                            picked = option
                        }
                        .padding(vertical = 14.dp, horizontal = 16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(option, color = Color.White, fontSize = 17.sp, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
