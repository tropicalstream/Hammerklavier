package com.tropicalstream.hammerklavier.contract.stub

import com.tropicalstream.hammerklavier.contract.ImportResult
import com.tropicalstream.hammerklavier.contract.ImportScan
import com.tropicalstream.hammerklavier.contract.InstrumentId
import com.tropicalstream.hammerklavier.contract.LibraryModel
import com.tropicalstream.hammerklavier.contract.LibraryService
import com.tropicalstream.hammerklavier.contract.Movement
import com.tropicalstream.hammerklavier.contract.PedalMode
import com.tropicalstream.hammerklavier.contract.RejectReason
import com.tropicalstream.hammerklavier.contract.Shelf
import com.tropicalstream.hammerklavier.contract.Source
import com.tropicalstream.hammerklavier.contract.SyntheticSpecs
import com.tropicalstream.hammerklavier.contract.Work
import java.io.File

/**
 * LibraryService before WP9 (PLAN §2.3): resolves `asset:<path>` (assets/<path>),
 * `test:<file>` (assets/midi/test/<file>.mid) and `synth:<kind>` ids directly, and lists the test
 * twins as one shelf "test" (one work, one movement per twin, id `test:<name>`, asset
 * `midi/test/<name>.mid`). `synth:` movements have no bytes (an empty array): compile them with
 * ScoreCompiler.synthetic. Imports are refused (IO_ERROR). [readAsset] reads an asset path
 * (AssetManager on the device, a directory in tests) and returns null when it is missing.
 */
class StubLibrary(
    private val readAsset: (String) -> ByteArray? = { null },
    override val scoresDir: File = File("Scores"),
    private val twinNames: List<String> = SyntheticSpecs.NAMES.values.toList()) : LibraryService {

    override fun load(): LibraryModel {
        val movements = LinkedHashMap<String, Movement>()
        for (name in twinNames) {
            val id = "test:$name"
            movements[id] = Movement(id = id, workId = "test", title = name, asset = "midi/test/$name.mid", file = null,
                sha1 = "", durationSec = 0f, lowKey = 21, highKey = 108, hasSustain = false, hasSoft = false,
                hasSostenuto = false, pedalMode = PedalMode.NONE)
        }
        val work = Work(id = "test", composer = "Hammerklavier", composerShort = "HK", title = "Test scores",
            shortTitle = "Tests", catalogue = null, year = null, era = "test", defaultInstrument = InstrumentId.GRAND,
            altInstruments = InstrumentId.entries.toList(), sourceId = "hk", tier = "test", velocityPolicy = "as-is",
            tuning = null, movementIds = movements.keys.toList(), imported = false)
        val source = Source(id = "hk", credit = "Hammerklavier test scores", licence = "CC0-1.0", licenceUrl = "",
            sourceUrl = "", licenceFile = null, performanceType = "synthetic", tier = "test", exportAllowed = true)
        return LibraryModel(shelves = listOf(Shelf("test", "Test scores", listOf("test"))), works = mapOf("test" to work),
            movements = movements, sources = mapOf("hk" to source), startHere = movements.keys.take(3))
    }

    override fun readBytes(m: Movement): ByteArray {
        val id = m.id
        return when {
            id.startsWith("synth:") -> ByteArray(0)
            id.startsWith("asset:") -> readAsset(id.removePrefix("asset:")) ?: ByteArray(0)
            id.startsWith("test:") -> readAsset("midi/test/${id.removePrefix("test:")}.mid") ?: ByteArray(0)
            m.asset != null -> readAsset(m.asset) ?: ByteArray(0)
            m.file != null -> File(m.file).takeIf { it.isFile }?.readBytes() ?: ByteArray(0)
            else -> ByteArray(0)
        }
    }

    override fun rescan(): ImportScan = ImportScan(added = 0, rejected = emptyList(), scoresDirForeign = false)

    override fun importFile(tmp: File, relativeName: String): ImportResult =
        ImportResult(ok = false, name = relativeName, movementId = null, reason = RejectReason.IO_ERROR, detail = "StubLibrary does not import")

    override fun importZip(tmp: File): List<ImportResult> = listOf(importFile(tmp, tmp.name))

    override fun delete(movementId: String): Boolean = false
}
