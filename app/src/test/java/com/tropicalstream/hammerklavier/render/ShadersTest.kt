package com.tropicalstream.hammerklavier.render

import com.tropicalstream.hammerklavier.render.gl.Shaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Every program compiles as GLSL ES 1.00 (glslangValidator, when installed), stays within the
 * uniform budget (§5.8: ≤ 68 vec4 by our count, the device asserts ≥ 128), and avoids reserved words.
 */
class ShadersTest {
    private fun validator(): String? = listOf("/opt/homebrew/bin/glslangValidator", "/usr/local/bin/glslangValidator")
        .firstOrNull { File(it).canExecute() }

    @Test fun compileAsGlslEs100() {
        val v = validator()
        Assume.assumeTrue("glslangValidator not installed", v != null)
        val dir = kotlin.io.path.createTempDirectory("hkglsl").toFile()
        for ((name, vs, fs) in Shaders.ALL) {
            for ((stage, src) in listOf("vert" to vs, "frag" to fs)) {
                val f = File(dir, "$name.$stage"); f.writeText("#version 100\n" + src)
                val p = ProcessBuilder(v, "-S", stage, f.path).redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                assertEquals("$name.$stage:\n$out", 0, p.waitFor())
            }
        }
    }

    private fun vec4s(src: String): Int {
        var n = 0
        val re = Regex("""uniform\s+(\w+)\s+(\w+)(?:\[(\d+)])?\s*;""")
        for (m in re.findAll(src)) {
            val count = m.groupValues[3].ifEmpty { "1" }.toInt()
            n += count * when (m.groupValues[1]) { "mat4" -> 4; "mat3" -> 3; else -> 1 }
        }
        return n
    }

    @Test fun uniformBudget() {
        for ((name, vs, _) in Shaders.ALL) assertTrue("$name vertex uses ${vec4s(vs)} vec4", vec4s(vs) <= 68)
    }

    @Test fun noReservedWords() {
        val reserved = Regex("""\b(half|fixed|input|output|filter|sample|cast|namespace|using|long|short|double|unsigned|superp)\b""")
        for ((name, vs, fs) in Shaders.ALL) {
            assertFalse(name, reserved.containsMatchIn(vs.replace(Regex("//.*"), "")))
            assertFalse(name, reserved.containsMatchIn(fs.replace(Regex("//.*"), "")))
        }
    }
}
