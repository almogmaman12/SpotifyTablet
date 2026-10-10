package com.almog.spotifytablet.lyrics.mobile.network.data

data class NormalizedLyricsSourcePreferences(
    val order: List<String>,
    val disabledSourceIds: Set<String>,
)

/** Pure migration logic shared by DataStore and tests. */
object LyricsSourcePreferenceNormalizer {
    fun normalize(
        storedOrder: List<String>?,
        storedDisabledSourceIds: Set<String>,
        descriptors: Collection<LyricsSourceDescriptor>,
    ): NormalizedLyricsSourcePreferences {
        val defaults = descriptors
            .sortedWith(compareBy<LyricsSourceDescriptor> { it.defaultPriority }.thenBy { it.id })
        val knownById = defaults.associateBy(LyricsSourceDescriptor::id)
        val originallyKnown = storedOrder
            ?.asSequence()
            ?.filter(knownById::containsKey)
            ?.distinct()
            ?.toList()

        val order = if (storedOrder == null) {
            defaults.map(LyricsSourceDescriptor::id).toMutableList()
        } else {
            originallyKnown.orEmpty().toMutableList().apply {
                for (descriptor in defaults) {
                    if (descriptor.id in this) continue
                    val defaultIndex = defaults.indexOf(descriptor)
                    val precedingId = defaults
                        .subList(0, defaultIndex)
                        .asReversed()
                        .firstOrNull { it.id in this }
                        ?.id
                    val insertionIndex = precedingId
                        ?.let { indexOf(it) + 1 }
                        ?: 0
                    add(insertionIndex, descriptor.id)
                }
            }
        }

        val newDefaultDisabled = defaults
            .asSequence()
            .filterNot(LyricsSourceDescriptor::defaultEnabled)
            .filter { storedOrder == null || it.id !in originallyKnown.orEmpty() }
            .map(LyricsSourceDescriptor::id)
            .toSet()
        val disabled = storedDisabledSourceIds
            .filterTo(mutableSetOf(), knownById::containsKey)
            .apply { addAll(newDefaultDisabled) }

        return NormalizedLyricsSourcePreferences(order, disabled)
    }
}
