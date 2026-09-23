package com.tropicalstream.hammerklavier.kit

/**
 * What the voicer does next for one kit (PLAN §3.3): the unit order, where a killed decode resumes,
 * which ready units must be CRC-checked after an unclean shutdown, and whether storage allows the
 * full kit, only the reduced grand, or nothing. Pure; built on HKVoicer/HKLoader from the files'
 * state.
 *
 * @param order unit ids to voice, in decode order (reduced when storage is short)
 * @param pending the units of [order] not yet ready, in order; [next] is the first
 * @param verify ready units to CRC-check before trusting the mask (`.ok` missing or BOOT_COUNT
 *   changed → the newest two ready units, newest = latest in decode order)
 * @param playableSet the first sustain unit plus releases and pedals (those the kit has)
 */
class DecodePlan(
    val order: IntArray, val pending: IntArray, val verify: IntArray, val playableSet: IntArray,
    val reduced: Boolean, val storageOk: Boolean, val requiredBytes: Long, val remainingBytes: Long) {

    val next: Int get() = if (pending.isEmpty()) -1 else pending[0]
    val complete: Boolean get() = pending.isEmpty()

    fun playable(mask: Long): Boolean = playableSet.isNotEmpty() && playableSet.all { (mask ushr it) and 1L == 1L }

    /** Fraction of [order]'s bytes ready. */
    fun fraction(index: KitIndex, mask: Long): Float {
        var all = 0L; var done = 0L
        for (u in order) { val b = unitBytes(index, u); all += b; if ((mask ushr u) and 1L == 1L) done += b }
        return if (all == 0L) 1f else done.toFloat() / all
    }

    companion object {
        const val MARGIN_BYTES = 64L * 1024 * 1024
        /** Units present in both grand kits, voiced when storage is short. */
        val REDUCED_LABELS = setOf("v4", "v13")

        /** Cache bytes of one unit's regions (each 4 KiB-aligned). */
        fun unitBytes(index: KitIndex, unit: Int): Long {
            var b = 0L
            for (r in index.regions) if (r.unit == unit) b += PcmCacheFormat.align(r.frames.toLong() * PcmCacheFormat.BYTES_PER_FRAME)
            return b
        }

        /**
         * @param ready the `.ready` contents (null = none)
         * @param okPresent whether `.ok` exists
         * @param bootCount the current `Settings.Global.BOOT_COUNT` (-1 unknown)
         * @param freeBytes usable bytes on the cache's file system
         * @param cacheAllocated whether the pre-sized `.pcm` already exists (its space is taken)
         */
        fun plan(index: KitIndex, ready: PcmCacheFormat.ReadyState?, okPresent: Boolean, bootCount: Long,
                 freeBytes: Long, cacheAllocated: Boolean): DecodePlan {
            val mask = ready?.mask ?: 0L
            fun isReady(u: Int) = (mask ushr u) and 1L == 1L
            val full = index.decodeOrder
            val firstSustain = full.firstOrNull { it in 0..61 }
            val playableSet = listOfNotNull(firstSustain,
                KitIndex.UNIT_RELEASES.takeIf { index.unit(it) != null },
                KitIndex.UNIT_PEDALS.takeIf { index.unit(it) != null }).toIntArray()

            // Verification after an unclean reboot: the two newest ready units.
            val needVerify = ready != null && mask != 0L && (!okPresent || bootCount < 0 || ready.bootCount != bootCount)
            val verify = if (!needVerify) IntArray(0) else full.filter { isReady(it) }.takeLast(2).toIntArray()

            fun remaining(units: IntArray): Long =
                units.filter { !isReady(it) }.sumOf { unitBytes(index, it) }
            fun required(units: IntArray): Long =
                (if (cacheAllocated) 0L else remaining(units)) + MARGIN_BYTES

            var order = full
            var reduced = false
            var ok = freeBytes >= required(order)
            if (!ok && index.instrument == "grand") {
                val keep = index.units.filter { it.label in REDUCED_LABELS }.map { it.id }.toSet() +
                    setOf(KitIndex.UNIT_RELEASES, KitIndex.UNIT_PEDALS)
                val r = full.filter { it in keep }.toIntArray()
                if (r.any { it in 0..61 } && freeBytes >= required(r)) { order = r; reduced = true; ok = true }
            }
            val pending = order.filter { !isReady(it) }.toIntArray()
            val reducedPlayable = if (reduced) listOfNotNull(order.firstOrNull { it in 0..61 },
                KitIndex.UNIT_RELEASES.takeIf { it in order }, KitIndex.UNIT_PEDALS.takeIf { it in order }).toIntArray() else playableSet
            return DecodePlan(order = order, pending = pending, verify = verify, playableSet = reducedPlayable,
                reduced = reduced, storageOk = ok, requiredBytes = required(order), remainingBytes = remaining(order))
        }

        /** Cache files in [names] that belong to [id] but not to [sha8] (deleted on open). */
        fun staleFiles(names: List<String>, id: String, sha8: String): List<String> =
            names.filter { n ->
                n.startsWith("$id-") && (n.endsWith(".pcm") || n.endsWith(".ready") || n.endsWith(".ok") || n.endsWith(".tmp")) &&
                    !n.startsWith("$id-$sha8.")
            }
    }
}
