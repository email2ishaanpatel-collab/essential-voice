package com.ishaan.essentialvoice.ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * One question to Gemini, one answer back.
 *
 * `HttpURLConnection` and `org.json`, like the rest of this app's networking —
 * an app whose install already frightens Play Protect does not need another
 * dependency in it, and this is one request with one shape.
 *
 * **The key travels as the `x-goog-api-key` header, never in the URL.** Google's
 * own quickstart puts it in a `?key=` query parameter, and that is a bad habit
 * to copy: URLs are the part of a request that proxies, crash reports and
 * server logs record as a matter of course, so a key there is a key written
 * down in several places nobody audited.
 *
 * **This is the only outbound path in the app that carries anything the user
 * said.** Everything else — whisper, the notes, the island's notifications —
 * happens on the device, and that is a property worth keeping legible rather
 * than diluting quietly. So: nothing is sent unless the word "Gemini" started
 * the sentence, the request carries no identifier of any kind beyond the key
 * the user pasted in themselves, and nothing about the exchange is stored.
 */
object Gemini {

    private const val TAG = "EVGemini"

    /**
     * Flash rather than Pro. The answer is going onto a lozenge at the top of
     * the screen while somebody waits for it, so latency is the property that
     * matters and depth is not the one being bought.
     *
     * **This name expires.** The first build shipped `gemini-2.5-flash` and
     * got back a 404 reading "no longer available to new users" - Google
     * retires a model for *new* keys while it still answers for old ones, so
     * it fails only for people setting the app up fresh, which is the worst
     * possible distribution for a bug. If Gemini ever 404s, read the message:
     * it names the model to move to. That is why [readError] passes the API's
     * own text through for codes it does not recognise rather than replacing
     * it with something friendlier.
     */
    private const val MODEL = "gemini-3.6-flash"

    private const val HOST = "https://generativelanguage.googleapis.com"

    private const val V1BETA = "$HOST/v1beta/models/$MODEL:generateContent"
    private const val V1 = "$HOST/v1/models/$MODEL:generateContent"

    /**
     * The island is two lines wide, so the answer has to be too.
     *
     * This is a real constraint, not a preference: a model that answers in a
     * paragraph produces something that can only be read by pasting it
     * somewhere, which defeats the point of answering on screen at all.
     */
    private const val SYSTEM = "You are answering out loud on a phone's status " +
        "bar, in one or two short sentences at most. No preamble, no markdown, " +
        "no lists, no restating the question. If the honest answer is a single " +
        "word or number, give just that."

    /**
     * Long enough for a slow network *and* a slow model.
     *
     * 20s was not: the first build timed out repeatedly with `SocketException:
     * Socket closed` on a question as small as "how tall is Everest". The cause
     * was not the network — see [thinking] — but the read timeout is what
     * turned it into a failure, and 45s is the honest ceiling for something a
     * person is standing there waiting for.
     */
    private const val TIMEOUT_MS = 45_000

    /**
     * One request shape to try: where to send it, and what to send.
     *
     * A list rather than one body because Google answered a bad request with a
     * bare `INVALID_ARGUMENT` and **no field name** — no `details`, nothing
     * saying which argument it disliked. With no way to ask "which part?", the
     * only way to find out is to remove parts until it stops complaining, and
     * doing that by rebuilding and asking the user to speak again costs a round
     * trip per guess. The ladder does the whole bisect in one attempt and logs
     * which rung held, so the answer arrives *and* we learn why.
     *
     * Ordered richest first: every rung down gives up something real, so the
     * first one that works is the most capable shape this model accepts.
     */
    private data class Shape(val name: String, val endpoint: String, val body: (String) -> String)

    private fun shapes(): List<Shape> = listOf(
        // What we actually want: brief-answer instruction, reasoning off.
        Shape("full", V1BETA) { body(it, system = true, noThinking = true) },
        // Some models require thinking and reject a zero budget.
        Shape("no-thinking", V1BETA) { body(it, system = true, noThinking = false) },
        // Some reject systemInstruction; the instruction moves in-line instead.
        Shape("no-system", V1BETA) { body(it, system = false, noThinking = false) },
        // Nothing but the question.
        Shape("minimal", V1BETA) { minimal(it) },
        // Last resort: the stable endpoint rather than v1beta.
        Shape("minimal-v1", V1) { minimal(it) },
    )

    /**
     * Ask, on the IO dispatcher.
     *
     * Returns a [Result] rather than throwing, because every caller is on the
     * path that would otherwise drop a dictation on the floor, and the failure
     * has to become something the user can read.
     *
     * Only a **400** moves down the ladder. A rejected key, a quota error or a
     * server fault are all facts about the request being fine and something
     * else being wrong, and retrying those four more times would turn one
     * honest failure into five and a long wait.
     */
    suspend fun ask(key: String, question: String): Result<String> =
        withContext(Dispatchers.IO) {
            if (key.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("No Gemini key \u2014 add one in the app's settings"),
                )
            }
            var last: Reply? = null
            for (shape in shapes()) {
                val reply = post(key, shape.endpoint, shape.body(question))
                if (reply is Reply.Ok) {
                    if (shape.name != "full") {
                        // The thing worth knowing. Once this names a rung
                        // consistently, the ladder above it can be deleted.
                        Log.i(TAG, "shape '${shape.name}' accepted; earlier shapes were refused")
                    }
                    return@withContext reply.toResult()
                }
                last = reply
                val failed = reply as? Reply.Failed
                if (failed == null || failed.code != 400) break
                Log.i(TAG, "shape '${shape.name}' refused with 400; trying the next")
            }
            (last ?: Reply.Broke(Exception("Gemini did not answer"))).toResult()
        }

    /** What one HTTP round trip produced. */
    private sealed interface Reply {
        data class Ok(val text: String) : Reply
        data class Failed(val code: Int, val raw: String, val message: String) : Reply
        data class Broke(val error: Throwable) : Reply
    }

    private fun Reply.toResult(): Result<String> = when (this) {
        is Reply.Ok -> Result.success(text)
        is Reply.Failed -> Result.failure(Exception(message))
        is Reply.Broke -> Result.failure(error)
    }

    private fun post(key: String, endpoint: String, payload: String): Reply {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", key)
            }
            conn.outputStream.use { it.write(payload.toByteArray()) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val raw = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()

            if (code !in 200..299) {
                // The body, not just the code. Masking this behind a friendly
                // string is how a 400 became "Gemini refused the question" and
                // cost a whole round trip with the user to find out nothing.
                // Whatever is shown on screen, the log gets the truth.
                Log.w(TAG, "HTTP $code: ${raw.take(400)}")
                return Reply.Failed(code, raw, readError(code, raw))
            }
            readAnswer(raw)?.let { Reply.Ok(it) }
                ?: Reply.Failed(code, raw, "Gemini sent no answer").also {
                    Log.w(TAG, "empty answer: ${raw.take(400)}")
                }
        } catch (t: java.net.SocketTimeoutException) {
            Log.w(TAG, "ask timed out", t)
            Reply.Broke(Exception("Gemini took too long"))
        } catch (t: Throwable) {
            Log.w(TAG, "ask failed", t)
            Reply.Broke(t)
        } finally {
            // HttpURLConnection is not Closeable; it is disconnected.
            runCatching { conn?.disconnect() }
        }
    }

    private fun body(question: String, system: Boolean, noThinking: Boolean): String =
        JSONObject().apply {
            if (system) {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().put(JSONObject().put("text", SYSTEM)))
                })
            }
            put("contents", JSONArray().put(JSONObject().apply {
                put("role", "user")
                // With no systemInstruction the brevity rule still has to reach
                // the model, or the answer is a paragraph on a lozenge.
                val text = if (system) question else "$SYSTEM\n\n$question"
                put("parts", JSONArray().put(JSONObject().put("text", text)))
            }))
            put("generationConfig", JSONObject().apply {
                // Room for two sentences plus slack. Not a tight cap: on a
                // thinking model the budget is shared with reasoning tokens, so
                // a small number does not buy brevity — it buys an empty answer
                // that hit MAX_TOKENS before writing anything.
                put("maxOutputTokens", 800)
                put("temperature", 0.4)
                if (noThinking) put("thinkingConfig", thinking())
            })
        }.toString()

    /** The question and nothing else — the shape every version has accepted. */
    private fun minimal(question: String): String = JSONObject().apply {
        put("contents", JSONArray().put(JSONObject().apply {
            put("parts", JSONArray().put(JSONObject().put("text", "$SYSTEM\n\n$question")))
        }))
    }.toString()

    /**
     * Thinking off.
     *
     * Gemini's flash models from 2.5 onward reason before answering **by
     * default**, and that is the whole reason this timed out: "how tall is
     * Everest" was spending tens of seconds thinking about a fact it already
     * knows, then closing the socket before the answer arrived.
     *
     * Nothing here is worth thinking about — the system instruction asks for
     * one or two sentences off the top of the model's head, which is precisely
     * the case reasoning does not improve. `thinkingBudget = 0` is the
     * documented way to switch it off, and it is what makes an answer on the
     * island arrive while the person is still looking at it.
     */
    private fun thinking(): JSONObject = JSONObject().put("thinkingBudget", 0)

    /** The answer text, or null if the response had none. */
    private fun readAnswer(raw: String): String? = runCatching {
        val parts = JSONObject(raw)
            .getJSONArray("candidates")
            .getJSONObject(0)
            .getJSONObject("content")
            .getJSONArray("parts")
        buildString {
            for (i in 0 until parts.length()) {
                parts.getJSONObject(i).optString("text").takeIf { it.isNotBlank() }?.let {
                    append(it)
                }
            }
        }.trim().ifBlank { null }
    }.getOrNull()

    /**
     * Something short enough to read on a lozenge.
     *
     * The two that actually happen get named: a bad key is the mistake everyone
     * makes once, and a quota error is the one that looks like a bug when it is
     * a bill.
     */
    private fun readError(code: Int, raw: String): String {
        val detail = runCatching {
            JSONObject(raw).getJSONObject("error").optString("message")
        }.getOrNull().orEmpty()
        return when (code) {
            // The API's own words. A generic string here hides the one piece
            // of information that would explain the failure; see [post].
            400 -> if (detail.contains("API key", true)) {
                "That Gemini key was rejected"
            } else {
                detail.ifBlank { "Gemini refused the question" }
            }
            401, 403 -> "That Gemini key was rejected"
            429 -> "Gemini is rate-limiting — out of quota"
            // Names the replacement when Google retires a model; see MODEL.
            404 -> detail.ifBlank { "Gemini does not know that model" }
            in 500..599 -> "Gemini is having trouble; try again"
            else -> detail.ifBlank { "Gemini returned $code" }
        }
    }
}
