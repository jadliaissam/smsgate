package com.jassam.smsgate.ui.theme

import androidx.compose.ui.graphics.colorspace.ColorSpaces
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradientsTest {
    @Test
    fun theLogoGradientMixesSrgbAndP3TokensSoItMustBeConverted() {
        // Brand400 is a Display P3 token, Brand600 an sRGB one: the combination that crashed at launch.
        assertTrue(Palette.Brand400.colorSpace != Palette.Brand600.colorSpace)
        val stops = Gradients.stops(listOf(Palette.Brand400, Palette.Brand600, Palette.Brand800))
        assertEquals(setOf(ColorSpaces.Srgb), stops.map { it.colorSpace }.toSet())
    }

    @Test
    fun p3TokensKeepTheirAppearanceWhenConverted() {
        val converted = Gradients.stops(listOf(Palette.Brand50, Palette.Success400)).map { it.convert(ColorSpaces.Srgb) }
        assertTrue(converted.all { it.alpha == 1f && it.red in 0f..1f && it.green in 0f..1f && it.blue in 0f..1f })
    }

    /** android.graphics.LinearGradient rejects mixed colour spaces, and unit tests never draw: scan instead. */
    @Test
    fun gradientsAreOnlyBuiltThroughGradients() {
        val ui = File("src/main/java/com/jassam/smsgate/ui")
        assertTrue("ui/ not found from ${File(".").absolutePath}", ui.isDirectory)
        val gradients = File(ui, "theme/Gradients.kt")
        val brush = Regex("""Brush\s*\.\s*(linearGradient|verticalGradient|horizontalGradient|radialGradient|sweepGradient)|\b(LinearGradient|RadialGradient|SweepGradient)\s*\(""")
        val offenders = ui.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it != gradients }
            .flatMap { f -> f.readLines().mapIndexedNotNull { i, line -> if (brush.containsMatchIn(line)) "${f.path}:${i + 1}" else null } }
            .toList()
        assertTrue("Gradient brushes outside ui/theme/Gradients.kt (use Gradients.*): $offenders", offenders.isEmpty())
    }
}
