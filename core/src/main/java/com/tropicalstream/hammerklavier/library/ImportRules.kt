package com.tropicalstream.hammerklavier.library

import com.tropicalstream.hammerklavier.contract.InstrumentId
import java.text.Normalizer

/**
 * The pure import rules of PLAN §1.6, §1.7 and §4.8: limits, names, titles, composers and the
 * default-instrument rule (T9.3).
 */
object ImportRules {
    const val MIB = 1024 * 1024
    /** Companion upload of one MIDI file (§1.6). */
    const val MAX_UPLOAD_MIDI_BYTES = 4 * MIB
    /** A zip, uploaded or pushed (§1.6). */
    const val MAX_ZIP_BYTES = 20 * MIB
    const val MAX_ZIP_ENTRIES = 200
    /** A file pushed into Scores/ and every zip entry: the parser limit (§4.8). */
    const val MAX_MIDI_BYTES = 8 * MIB
    /** Bound on the uncompressed total of one zip (zip-bomb guard). */
    const val MAX_ZIP_TOTAL_BYTES = 64L * MIB
    const val MAX_DISPLAY_CHARS = 120

    val MIDI_EXTENSIONS = setOf("mid", "midi", "kar", "rmi")
    val HARPSICHORD_NAMES = listOf("bach", "scarlatti", "handel", "couperin", "rameau")

    /** Folder names that are taken as the composer (compared without case or diacritics). */
    private val COMPOSERS = listOf("Bach", "C.P.E. Bach", "Handel", "Händel", "Scarlatti", "Couperin", "Rameau", "Haydn",
        "Mozart", "Beethoven", "Clementi", "Schubert", "Schumann", "Chopin", "Liszt", "Mendelssohn", "Brahms", "Debussy",
        "Ravel", "Satie", "Grieg", "Tchaikovsky", "Rachmaninoff", "Rachmaninov", "Scriabin", "Mussorgsky", "Czerny",
        "Hummel", "Dussek", "Field", "Froberger", "Buxtehude", "Pachelbel", "Purcell", "Byrd", "Gibbons", "Frescobaldi",
        "Sweelinck", "Telemann", "Soler", "Albeniz", "Granados", "Joplin", "Gershwin", "Bartok", "Prokofiev", "Fauré",
        "Faure", "Franck", "Saint-Saëns", "Dvořák", "Dvorak", "Janáček", "Smetana")
    private val COMPOSER_KEYS: Map<String, String> = COMPOSERS.associateBy { fold(it) }

    fun extensionOf(name: String): String = name.substringAfterLast('/').substringAfterLast('.', "").lowercase()
    fun isMidiName(name: String): Boolean = extensionOf(name) in MIDI_EXTENSIONS
    fun isZipName(name: String): Boolean = extensionOf(name) == "zip"

    /**
     * Display name: Unicode letters and digits and `._ ()-` are kept, everything else becomes `_`;
     * leading dots and spaces are dropped; at most 120 characters (code points); never empty.
     */
    fun sanitizeDisplayName(raw0: String): String {
        val raw = Normalizer.normalize(raw0, Normalizer.Form.NFC)
        val sb = StringBuilder()
        var count = 0
        var i = 0
        while (i < raw.length && count < MAX_DISPLAY_CHARS) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            val keep = Character.isLetterOrDigit(cp) || cp == '.'.code || cp == '_'.code || cp == ' '.code ||
                cp == '('.code || cp == ')'.code || cp == '-'.code
            if (!keep && Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) {
                sb.appendCodePoint(cp); continue      // combining accents of an NFD name stay with their letter
            }
            if (sb.isEmpty() && (cp == '.'.code || cp == ' '.code)) continue
            if (keep) sb.appendCodePoint(cp) else sb.append('_')
            count++
        }
        val s = sb.toString().trimEnd()
        return Normalizer.normalize(s.ifEmpty { "untitled" }, Normalizer.Form.NFC)
    }

    /** A relative name split into safe components: empty, `.` and `..` parts are dropped (never a path escape). */
    fun components(relativeName: String): List<String> =
        relativeName.split('/', '\\').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }

    /** The folder of a relative name (sanitised components joined by `/`), or null for a loose file. */
    fun folderOf(relativeName: String): String? {
        val c = components(relativeName)
        if (c.size < 2) return null
        return c.dropLast(1).joinToString("/") { sanitizeDisplayName(it) }
    }

    /** The file-name component of a relative name, sanitised. */
    fun fileNameOf(relativeName: String): String = sanitizeDisplayName(components(relativeName).lastOrNull() ?: relativeName)

    fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** Title (§1.7): the MIDI track name (ScoreFacts.title), else the file name without its extension. */
    fun titleOf(factsTitle: String?, relativeName: String): String {
        val t = factsTitle?.trim()?.takeIf { it.isNotEmpty() && it.any { c -> c.isLetterOrDigit() } && !isGenericTrackName(it) }
        return if (t != null) t.take(MAX_DISPLAY_CHARS) else stripExtension(fileNameOf(relativeName)).ifEmpty { "untitled" }
    }

    /** Sequencer boilerplate track names ("control track", "Track 1", "Piano", …) are not titles (M6 device check). */
    private val GENERIC = Regex("^(control|tempo|conductor|meta|untitled|unnamed|sequence|track|piano|grand piano|acoustic grand|acoustic grand piano|klavier|pianoforte|harpsichord|staff|part|midi|channel)( ?(track|part|staff))?( ?[-#]?\\d+)?$")
    fun isGenericTrackName(t: String): Boolean = GENERIC.matches(t.trim().lowercase())

    /** Composer (§1.7): a folder component that names a composer, else "Imported". */
    fun composerOf(folder: String?): String {
        if (folder == null) return "Imported"
        for (part in folder.split('/').asReversed()) COMPOSER_KEYS[fold(part)]?.let { return it }
        return "Imported"
    }

    /** Work-id slug: ASCII lower case, diacritics removed, runs of other characters → `-`, ≤ 40 chars. */
    fun slug(s: String): String {
        val f = fold(s)
        val sb = StringBuilder()
        for (c in f) {
            if (c in 'a'..'z' || c in '0'..'9') sb.append(c)
            else if (sb.isNotEmpty() && sb.last() != '-') sb.append('-')
        }
        return sb.toString().trim('-').take(40).trim('-').ifEmpty { "import" }
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase().trim()

    /**
     * The default instrument of an imported file (§1.7, T9.3): harpsichord only if the file has no
     * CC64, its range fits 29–89 with at most 2 notes in 1000 folded, and its folder or file name
     * contains bach, scarlatti, handel, couperin or rameau; otherwise grand. [folded] is the
     * harpsichord fold count (only consulted when the range does not fit).
     */
    fun defaultInstrument(hasSustain: Boolean, lowKey: Int, highKey: Int, noteCount: Int, folded: Int,
                          names: String): InstrumentId {
        if (hasSustain) return InstrumentId.GRAND
        val n = fold(names)
        if (HARPSICHORD_NAMES.none { n.contains(it) }) return InstrumentId.GRAND
        val fits = lowKey >= 29 && highKey <= 89
        if (!fits && folded.toLong() * 1000L > 2L * noteCount) return InstrumentId.GRAND
        return InstrumentId.HARPSICHORD
    }

    /** True when [defaultInstrument] needs the fold count (range outside 29–89 but otherwise eligible). */
    fun needsFoldCount(hasSustain: Boolean, lowKey: Int, highKey: Int, names: String): Boolean =
        !hasSustain && !(lowKey >= 29 && highKey <= 89) && HARPSICHORD_NAMES.any { fold(names).contains(it) }

    /** Filename order: case-insensitive, runs of digits compared as numbers ("2.mid" before "10.mid"). */
    val NATURAL: Comparator<String> = Comparator { a, b ->
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]; val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var ie = i; while (ie < a.length && a[ie].isDigit()) ie++
                var je = j; while (je < b.length && b[je].isDigit()) je++
                val na = a.substring(i, ie).trimStart('0'); val nb = b.substring(j, je).trimStart('0')
                if (na.length != nb.length) return@Comparator na.length - nb.length
                val c = na.compareTo(nb)
                if (c != 0) return@Comparator c
                i = ie; j = je
            } else {
                val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (c != 0) return@Comparator c
                i++; j++
            }
        }
        val r = (a.length - i) - (b.length - j)
        if (r != 0) r else a.compareTo(b)
    }
}
