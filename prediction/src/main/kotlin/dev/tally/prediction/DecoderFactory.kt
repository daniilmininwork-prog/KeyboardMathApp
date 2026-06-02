package dev.tally.prediction

/**
 * Assembles the prediction stack from a loaded [EnglishDictionary].
 *
 * Keeps [WordPredictorImpl], [AutocorrectorImpl], and [GestureDecoderImpl] sharing a
 * single [BeamDecoder] and [FrequencyCache] instance so user-personalisation boosts
 * are applied consistently across all surfaces. The [PersonalizationStore] wraps the
 * same cache and is the sole entry point for policy-gated learning writes.
 *
 * Use [DecoderFactory.create] from the `prediction` module's initialisation path
 * (called once on the background executor after [DictionaryLoader.load] completes).
 */
object DecoderFactory {

    /**
     * Returns a ready-to-use [DecoderStack] for the given [dictionary].
     *
     * [store] defaults to a fresh [PersonalizationStore]; pass a store restored from
     * durable storage to warm the frequency table with prior session data.
     */
    fun create(
        dictionary: EnglishDictionary,
        store: PersonalizationStore = PersonalizationStore(),
    ): DecoderStack {
        val decoder = BeamDecoder(dictionary = dictionary, freqCache = store.cache)
        return DecoderStack(
            wordPredictor        = WordPredictorImpl(decoder = decoder, freqCache = store.cache),
            autocorrector        = AutocorrectorImpl(decoder = decoder, dictionary = dictionary),
            gestureDecoder       = GestureDecoderImpl(dictionary = dictionary),
            personalizationStore = store,
        )
    }
}

/**
 * The assembled prediction components produced by [DecoderFactory].
 *
 * [wordPredictor], [autocorrector], and [gestureDecoder] share the same underlying
 * [EnglishDictionary] and [FrequencyCache] (via [personalizationStore]). All learning
 * writes must go through [personalizationStore.record] so the [FieldPolicy] gate is
 * enforced.
 */
data class DecoderStack(
    val wordPredictor: WordPredictorImpl,
    val autocorrector: AutocorrectorImpl,
    val gestureDecoder: GestureDecoderImpl,
    val personalizationStore: PersonalizationStore,
)
