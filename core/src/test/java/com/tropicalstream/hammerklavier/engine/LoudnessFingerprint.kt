package com.tropicalstream.hammerklavier.engine

import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.KonzertzimmerAcoustics
import com.tropicalstream.hammerklavier.contract.ReverbMode
import com.tropicalstream.hammerklavier.contract.ViewId
import com.tropicalstream.hammerklavier.dsp.RoomAcoustics
import com.tropicalstream.hammerklavier.session.ListenerRooms
import kotlin.math.log10

/**
 * What the loudness table (`core/src/test/resources/loudness/table_after.csv`) was measured with:
 * the kit trims and every seat's final levelGain (model × residual), dB to 0.01. LoudnessProbeTest
 * writes it as the table's first line; LoudnessTableTest fails when the code no longer matches, i.e.
 * when a trim or the room model changed without re-running the probe.
 */
object LoudnessFingerprint {
    fun of(embeddedRoomDb: Map<InstrumentId, Float>): String {
        val sb = StringBuilder("# fingerprint")
        for ((k, v) in KitLoudness.TRIM_DB.toSortedMap()) sb.append(" kit:$k=%.2f".format(v))
        for (id in InstrumentId.entries) {
            val placement = KonzertzimmerAcoustics.PLACEMENTS.getValue(id)
            for ((view, framing) in listOf(ViewId.PLAYER to 0, ViewId.ACTION to 0, ViewId.ACTION to 1, ViewId.HALL to 0)) {
                val pose = ListenerRooms.resolve(id, null, placement, view, framing).pose
                val d = ListenerRooms.leveled(RoomAcoustics.design(KonzertzimmerAcoustics.GEOMETRY, placement, ListenerRooms.SOURCE.getValue(id),
                    pose, ReverbMode.ROOM, ListenerRooms.benchDistance(id, null), embeddedRoomDb.getValue(id)), id, view, framing)
                sb.append(" ${id.key}/${view.name.lowercase()}$framing=%.2f".format(20 * log10(d.levelGain.toDouble())))
            }
        }
        return sb.toString()
    }

    /** Each kit's (`kit` name, embeddedRoomDb) from its map.json in the app's assets. */
    fun kitsFromAssets(): Map<InstrumentId, Pair<String, Float>> = InstrumentId.entries.associateWith { id ->
        val m = org.json.JSONObject(java.io.File("../app/src/main/assets/instruments/${id.key}/map.json").readText())
        m.getString("kit") to m.getDouble("embeddedRoomDb").toFloat()
    }
}
