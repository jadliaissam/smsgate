package com.jassam.smsgate.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * UI code uses the generated tokens only (Global Constraints, D9): no `Color(0x…)`
 * literals anywhere under ui/ except ui/theme/, which holds the generated Tokens.kt.
 */
class NoColorLiteralsTest {
    @Test
    fun noColorLiteralsOutsideTheme() {
        val ui = File("src/main/java/com/jassam/smsgate/ui")
        assertTrue("ui/ not found from ${File(".").absolutePath}", ui.isDirectory)
        val theme = File(ui, "theme")
        val literal = Regex("""Color\(\s*0x""")
        val offenders = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.startsWith(theme) }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (literal.containsMatchIn(line)) "${f.path}:${i + 1}" else null } }
            .toList()
        assertTrue("Colour literals outside ui/theme (use SmsGateTheme.colors / Palette): $offenders", offenders.isEmpty())
    }
}
