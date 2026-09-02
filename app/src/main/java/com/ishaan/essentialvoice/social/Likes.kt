package com.ishaan.essentialvoice.social

import android.util.Log
import com.ishaan.essentialvoice.BuildConfig
import com.ishaan.essentialvoice.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The heart on each launcher card: how many people liked it, and whether you
 * are one of them.
 *
 * **This is the second outbound path in the app, and the first that is not the
 * user's own question.** Everything else — whisper, the notes, the island's
 * notifications — happens on the device, and the update check only ever reads a
 * static file. That property is worth stating plainly rather than letting it
 * erode: what leaves the phone here is a card's name, a random id this install
 * made up for itself, and nothing else. No account, no hardware id, no
 * advertising id, no text, and never anything anybody said. Clearing the app's
 * data throws the id away and makes a new one.
 *
 * `HttpURLConnection` and `org.json`, like [com.ishaan.essentialvoice.ai.Gemini]
 * and [com.ishaan.essentialvoice.Updater] — Supabase publishes a Kotlin SDK and
 * it brings Ktor with it, which is a large dependency and a second HTTP stack in
 * an APK whose install already frightens Play Protect. This is two requests with
 * one shape each.
 *
 * The server is `supabase/likes.sql`: a table the anon key cannot touch, and two
 * `security definer` functions that are the only way in.
 */
object Likes {

    private const val TAG = "EVLikes"

    /**
     * Short. Nobody is waiting on this — the hearts are decoration on a page
     * that works without them — so a slow network should give up quietly rather
     * than hold a connection open behind a screen the user has already left.
     */
    private const val TIMEOUT_MS = 10_000

    /** One card's tally. */
    data class Card(val likes: Int, val liked: Boolean)

    /**
     * Every card's tally, and whether there are any numbers yet.
     *
     * [known] is what the heart reads to decide whether to show a count at all.
     * A card with no likes and a card whose count has not arrived look identical
     * if you only carry the map, and drawing "0" under a card that in fact has
     * forty likes, for the two seconds before the request lands, is worse than
     * drawing nothing.
     */
    data class Board(val cards: Map<String, Card> = emptyMap(), val known: Boolean = false) {
        operator fun get(card: String): Card = cards[card] ?: Card(0, false)
    }

    private val _board = MutableStateFlow(Board())
    val board: StateFlow<Board> = _board

    /**
     * Whether this build has a backend at all.
     *
     * Empty when `supabase.properties` is missing, which is the normal state of
     * a fresh clone. The hearts then do not appear — an app that cannot count
     * likes should not draw a button that silently does nothing.
     */
    val configured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotEmpty() && BuildConfig.SUPABASE_ANON_KEY.isNotEmpty()

    /**
     * One write at a time.
     *
     * Two hearts pressed in quick succession are two writes with two counts
     * coming back, and applying those in whatever order they land lets the older
     * answer overwrite the newer one. The lock costs nothing here — these are
     * taps, not a stream.
     */
    private val gate = Mutex()

    /**
     * Read the board, painting the cached one on the way.
     *
     * The cache is there because the alternative is every card sitting blank for
     * as long as the request takes, on every single open of the app — including
     * the opens with no network at all. It is last night's numbers, and last
     * night's numbers are far closer to the truth than no numbers.
     */
    suspend fun refresh(prefs: Prefs) {
        if (!configured) return
        if (!_board.value.known) cached(prefs)?.let { _board.value = it }

        val body = JSONObject().put("p_device", prefs.installId).toString()
        val answer = post("like_counts", body) ?: return

        val cards = buildMap {
            val rows = runCatching { JSONArray(answer) }.getOrNull() ?: return@buildMap
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val card = row.optString("card").takeIf { it.isNotEmpty() } ?: continue
                put(card, Card(row.optInt("likes"), row.optBoolean("liked")))
            }
        }
        val board = Board(cards, known = true)
        _board.value = board
        prefs.likeCache = encode(board)
    }

    /**
     * Like or unlike, from the screen's own scope.
     *
     * The heart fills before the request goes out and the count moves with it,
     * because a like that waits for a server is a like that feels broken on a
     * train. If the write fails the change is put back exactly as it was — not
     * refetched, which would be a second request that can also fail, and not
     * left standing, which would be a wrong number on screen that survives until
     * the next time the app is opened.
     */
    fun toggle(scope: CoroutineScope, prefs: Prefs, card: String) {
        if (!configured) return
        val before = _board.value
        val was = before[card]
        val wanted = !was.liked

        _board.value = before.copy(
            cards = before.cards + (card to Card(
                // Never below zero. This count can be wrong downwards — a card
                // liked on another install reads as zero here until the refresh
                // lands — and unliking it must not go negative in the second
                // before that happens.
                likes = (was.likes + if (wanted) 1 else -1).coerceAtLeast(0),
                liked = wanted,
            )),
            known = true,
        )

        scope.launch {
            gate.withLock {
                val body = JSONObject()
                    .put("p_card", card)
                    .put("p_device", prefs.installId)
                    .put("p_liked", wanted)
                    .toString()
                val answer = post("set_like", body)

                // Only this card is put back or corrected. The rest of the board
                // may have been refreshed while the request was out.
                val settled = if (answer == null) {
                    was
                } else {
                    // The function returns the real total, so the optimistic
                    // guess is corrected whether or not it was wrong.
                    Card(answer.trim().toIntOrNull() ?: return@withLock, wanted)
                }
                _board.value = _board.value.let { it.copy(cards = it.cards + (card to settled)) }
                if (answer != null) prefs.likeCache = encode(_board.value)
            }
        }
    }

    // ---- the wire ----------------------------------------------------------

    /**
     * One RPC. Returns the body, or null for anything that was not a 2xx.
     *
     * Null rather than an exception or a message, because there is no screen for
     * this to fail on: a like that does not go through is a heart that goes back
     * to how it was, and a board that does not load is a page with no numbers on
     * it. Both are fine. The reason goes to the log for whoever is looking.
     */
    private suspend fun post(function: String, body: String): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val url = URL("${BuildConfig.SUPABASE_URL}/rest/v1/rpc/$function")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
                setRequestProperty("apikey", BuildConfig.SUPABASE_ANON_KEY)
                setRequestProperty("Authorization", "Bearer ${BuildConfig.SUPABASE_ANON_KEY}")
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
            }
            conn.outputStream.use { it.write(body.toByteArray()) }

            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "$function → $code ${conn.errorStream?.bufferedReader()?.readText()}")
                return@withContext null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            // Offline is the ordinary case here, not an error worth shouting.
            Log.d(TAG, "$function failed: ${e.javaClass.simpleName}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ---- the cache ---------------------------------------------------------

    private fun encode(board: Board): String {
        val o = JSONObject()
        board.cards.forEach { (card, c) ->
            o.put(card, JSONObject().put("n", c.likes).put("me", c.liked))
        }
        return o.toString()
    }

    /** The last board this install saw, or null if it has never seen one. */
    private fun cached(prefs: Prefs): Board? {
        val raw = prefs.likeCache.takeIf { it.isNotEmpty() } ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val cards = buildMap {
                o.keys().forEach { card ->
                    val c = o.getJSONObject(card)
                    put(card, Card(c.optInt("n"), c.optBoolean("me")))
                }
            }
            Board(cards, known = true)
        }.getOrNull()
    }
}
