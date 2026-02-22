package net.alexsobolev.tts.infra.g2p

import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSTagFormat
import opennlp.tools.postag.POSTaggerME
import java.io.File

/**
 * Assigns part-of-speech tags to a list of word tokens.
 *
 * Used by [EnglishPhonemeGenerator] to select POS-appropriate pronunciations
 * from multi-variant dictionary entries (e.g., "live" as adjective vs verb).
 */
internal interface PosTagger {
    /**
     * Tags each token with a POS label.
     *
     * @param tokens whitespace-free word tokens (lowercase, punctuation stripped)
     * @return POS tags in the same order, one per token
     */
    fun tag(tokens: List<String>): List<String>
}

/**
 * [PosTagger] backed by Apache OpenNLP's maximum-entropy POS model.
 *
 * Uses [POSTagFormat.PENN] to produce fine-grained Penn Treebank tags (VBD, VBN, VBP, NN, JJ, etc.)
 * instead of coarse Universal Dependencies tags (VERB, NOUN, ADJ). This is required because the
 * misaki dictionaries key POS variants on Penn Treebank tags (e.g., "read" has VBD→ɹˈɛd vs DEFAULT→ɹˈid).
 *
 * The model is loaded lazily on first use to avoid slowing down application startup.
 *
 * @param modelPath filesystem path to the OpenNLP binary model (e.g., `en-pos-maxent.bin`)
 */
internal class OpenNlpPosTagger(modelPath: String) : PosTagger {
    // POSTagFormat.PENN overrides OpenNLP 2.x default (UD) to emit fine-grained tags
    private val tagger: POSTaggerME by lazy {
        POSTaggerME(POSModel(File(modelPath).inputStream()), POSTagFormat.PENN)
    }

    override fun tag(tokens: List<String>): List<String> = tagger.tag(tokens.toTypedArray()).toList()
}
