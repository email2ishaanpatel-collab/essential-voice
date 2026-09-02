package com.ishaan.essentialvoice.whisper

import android.content.Context
import com.ishaan.essentialvoice.Prefs
import java.io.File

/**
 * One downloadable file: the pair of a name and the exact size it must end up.
 *
 * The size is not decoration. [ModelDownloader] resumes with a Range request
 * and treats "the file is this many bytes" as the only proof a download
 * finished, so a wrong number here is a model that can never install.
 */
data class ModelVariant(val fileName: String, val bytes: Long) {

    val sizeMb: Int get() = ((bytes + 500_000) / 1_000_000).toInt()

    val url: String get() = "${ModelCatalog.BASE_URL}$fileName"

    fun file(context: Context): File = File(ModelCatalog.dir(context), fileName)

    fun isInstalled(context: Context): Boolean {
        val f = file(context)
        return f.isFile && f.length() == bytes
    }
}

/**
 * The quality toggle, expressed as four settings that were measured on this
 * phone rather than guessed.
 *
 * [millisPer10s] is the wall time whisper.cpp took on a CMF Phone 2 Pro for an
 * eleven-second clip of clear speech at four threads — the number the tier card
 * shows, so a tier's cost in waiting is as visible as its cost in bytes.
 *
 * Anything larger than `small` was tried and rejected on this hardware:
 * `medium.en-q5_0` spends 19.6s in the encoder alone and `large-v3-turbo-q5_0`
 * spends 32.6s, both fixed costs that no thread count or beam setting moves.
 * Neither is a dictation tool. Quantised `small.en-q5_1` was also slower than
 * fp16 here (7.1s against 5.8s): the Dimensity does fp16 natively, so
 * dequantising costs time and buys nothing but disk.
 *
 * ------------------------------------------------------ English only, on purpose
 *
 * Every tier names one file, and it is the `.en` build. There used to be a
 * multilingual variant beside each of them and it was removed rather than
 * defended: OpenAI trained these twice, once on English alone and once on
 * ninety-nine more languages in the *same* parameter budget, and at these sizes
 * the second training does not survive the split. `base` multilingual is around
 * 15-20% WER on Spanish and past 80% on Hindi — not "a little worse", unusable
 * — and `small`, the only tier that is arguable, still sits in the high
 * thirties on Hindi while costing 487MB and 5.8s per ten seconds of speech.
 *
 * There is no larger model to escape into: `medium.en-q5_0` spends 19.6s in the
 * encoder alone on this SoC and `large-v3-turbo-q5_0` 32.6s, both fixed costs
 * no thread count moves. And the phone already carries a recogniser that does
 * a hundred languages offline and instantly. So whisper listens in English, and
 * every other language is Google's — see [com.ishaan.essentialvoice.Prefs.setLanguage],
 * which keeps that pairing so no combination on screen can be a broken one.
 */
data class QualityTier(
    val id: String,
    val label: String,
    val sub: String,
    /** The `.en` model. whisper only ever listens in English; see above. */
    val model: ModelVariant,
    /** >1 selects beam search; 1 means greedy sampling. */
    val beamSize: Int,
    /** Candidates the sampler keeps. */
    val bestOf: Int,
    val millisPer10s: Int,
) {
    /** Human reading of [millisPer10s]: "1.5s", "6s". */
    val waitLabel: String
        get() {
            val s = millisPer10s / 1000f
            return if (s < 3f) "%.1fs".format(s) else "${s.toInt()}s"
        }

    fun file(context: Context): File = model.file(context)
    fun isInstalled(context: Context): Boolean = model.isInstalled(context)
    val sizeMb: Int get() = model.sizeMb
}

object ModelCatalog {

    const val BASE_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/"
    const val DEFAULT_TIER_ID = "balanced"

    val tiers = listOf(
        QualityTier(
            id = "fast",
            label = "Fast",
            sub = "Short commands and notes to self. Will miss names and jargon.",
            model = ModelVariant("ggml-tiny.en.bin", 77_704_715L),
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 1_500,
        ),
        QualityTier(
            id = "balanced",
            label = "Balanced",
            sub = "The everyday setting. Clean punctuation on ordinary speech.",
            model = ModelVariant("ggml-base.en.bin", 147_964_211L),
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 2_200,
        ),
        QualityTier(
            id = "accurate",
            label = "Accurate",
            sub = "Holds up to accents, background noise and technical words.",
            model = ModelVariant("ggml-small.en.bin", 487_614_201L),
            beamSize = 1,
            bestOf = 2,
            millisPer10s = 5_800,
        ),
        QualityTier(
            id = "maximum",
            label = "Maximum",
            sub = "The Accurate model, searched harder. Nothing extra to download — " +
                "it just thinks for longer before committing to a word.",
            model = ModelVariant("ggml-small.en.bin", 487_614_201L),
            beamSize = 5,
            bestOf = 5,
            millisPer10s = 7_800,
        ),
    )

    fun byId(id: String): QualityTier = tiers.firstOrNull { it.id == id } ?: tiers[1]

    fun dir(context: Context): File =
        File(context.filesDir, "models").apply { if (!exists()) mkdirs() }

    /**
     * Everything on disk. Deduplicated by file, because two tiers share one
     * model — Maximum is Accurate searched harder, not a second download.
     */
    fun installedBytes(context: Context): Long =
        tiers.map { it.model }
            .distinctBy { it.fileName }
            .filter { it.isInstalled(context) }
            .sumOf { it.bytes }
}
