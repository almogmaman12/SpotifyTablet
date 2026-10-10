package com.almog.spotifytablet.lyrics.mobile.network.data

import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendDoc
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendDocuments
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendDonor
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendDonors
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendQuality
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.BlendText
import com.almog.spotifytablet.lyrics.mobile.network.data.blend.LyricsBlender

/**
 * A blend: the lines of whatever ranks above it, under a donor's word timing, with a second donor
 * for the lines the first cannot place. It costs no request of its own: the donors are providers already in the walk.
 */
data class LyricsBlendDefinition(
    val id: String,
    val displayName: String,
    /** Whose word timing this is: the donor that decides where the blend is ranked. */
    val timingSourceId: String,
    val spareSourceId: String? = null,
) {
    val donorIds: List<String> get() = listOfNotNull(timingSourceId, spareSourceId)

    val descriptor = LyricsSourceDescriptor(
        id = id,
        displayName = displayName,
        defaultPriority = Int.MAX_VALUE,
        capabilities = setOf(LyricsCapability.WORD_SYNC),
        upstreamFamily = "blend",
        releaseChannel = SourceReleaseChannel.EXPERIMENTAL,
        defaultEnabled = false,
    )
}

object LyricsBlends {
    val ALL = listOf(
        LyricsBlendDefinition("blend_qq", "Blend: QQ Music timing", "qq_music"),
        LyricsBlendDefinition("blend_kugou", "Blend: Kugou timing", "kugou"),
        LyricsBlendDefinition("blend_netease", "Blend: NetEase timing", "netease"),
        LyricsBlendDefinition("blend_netease_qq", "Blend: NetEase timing, QQ Music fill", "netease", "qq_music"),
        LyricsBlendDefinition("blend_netease_kugou", "Blend: NetEase timing, Kugou fill", "netease", "kugou"),
    )

    /**
     * The walk's running order with each live blend placed immediately above the highest-ranked
     * source it borrows from: above its donors, since it is their
     * clock plus something they lack, and below everything that lent it nothing. A blend is live
     * when switched on and every donor is too. Blends sharing a home go in `blend_rank` order.
     */
    fun chain(providerOrder: List<String>, live: List<LyricsBlendDefinition>): List<String> {
        val homes = live.sortedWith(rankComparator(providerOrder))
            .groupBy { blend -> blend.donorIds.minBy(providerOrder::indexOf) }
        return providerOrder.flatMap { id -> homes[id].orEmpty().map(LyricsBlendDefinition::id) + id }
    }

    /** The switched-on blends whose donors are all switched on too. */
    fun live(enabledProviderIds: Set<String>, enabledBlendIds: Set<String>): List<LyricsBlendDefinition> =
        ALL.filter { it.id in enabledBlendIds && it.donorIds.all(enabledProviderIds::contains) }

    /** (timing donor's rank, a blend with a filler first, the filler's rank): `blend_rank`. */
    private fun rankComparator(order: List<String>) = compareBy<LyricsBlendDefinition>(
        { order.indexOf(it.timingSourceId) },
        { -it.donorIds.size },
        { it.spareSourceId?.let(order::indexOf) ?: -1 },
    )

    fun byId(id: String): LyricsBlendDefinition? = ALL.firstOrNull { it.id == id }

    /**
     * Builds [blend] from what the walk already holds. [above] is every
     * usable answer ranked above the blend, in rank order; the best of them by timing is the base,
     * [fallbackBase] only where none of them has any lyrics.
     */
    internal fun build(
        blend: LyricsBlendDefinition,
        request: LyricsLookupRequest,
        above: List<Pair<LyricsSourceDescriptor, RemoteLyricsPayload>>,
        fallbackBase: Pair<LyricsSourceDescriptor, RemoteLyricsPayload>?,
        donors: Map<String, RemoteLyricsPayload?>,
        nameOf: (String) -> String,
    ): ProviderResult {
        val picks = above.mapNotNull { (source, payload) ->
            BlendDocuments.from(payload)?.let { Base(source, payload, it) }
        }.filter { BlendText.quality(it.doc) != BlendQuality.NONE }
            .sortedByDescending { BlendText.quality(it.doc).rank }
        val base = picks.firstOrNull()
            ?: fallbackBase?.let { (source, payload) -> BlendDocuments.from(payload)?.let { Base(source, payload, it) } }
            ?: return ProviderResult.Miss

        fun donor(id: String?): BlendDonor? {
            id ?: return null
            val doc = donors[id]?.let(BlendDocuments::from)?.let { BlendDonors.shape(it, request.title, request.artist) }
            return BlendDonor(doc, nameOf(id), id)
        }
        val timing = donor(blend.timingSourceId)!!
        val spare = donor(blend.spareSourceId)
        if (timing.doc == null && spare?.doc == null) return ProviderResult.Miss

        val words = base.payload.attribution?.let { it.originName ?: it.providerName } ?: base.source.displayName
        val outcome = LyricsBlender.blended(base.doc, words, base.source.id, timing, spare) ?: return ProviderResult.Miss
        // Nothing borrowed: the base's own document, which already stands above this blend.
        if (outcome.via == null && outcome.alone == base.source.id) return ProviderResult.Miss
        if (outcome.quality == BlendQuality.NONE) return ProviderResult.Miss
        val writers = (base.payload.attribution?.songwriters.orEmpty() + outcome.doc.songwriters).distinct()
        return ProviderResult.Hit(RemoteLyricsPayload(
            ttmlLyrics = BlendDocuments.toTtml(BlendDoc(outcome.doc.lines), outcome.quality),
            attribution = LyricsAttribution(
                providerName = blend.displayName,
                originName = outcome.via ?: outcome.alone?.let(nameOf),
                songwriters = writers,
            ),
        ))
    }

    private class Base(val source: LyricsSourceDescriptor, val payload: RemoteLyricsPayload, val doc: BlendDoc)
}
