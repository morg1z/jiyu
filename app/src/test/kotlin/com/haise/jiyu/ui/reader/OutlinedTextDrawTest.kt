package com.haise.jiyu.ui.reader

import android.content.Context
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Regresní test na "tučný, nečitelný překlad": druhý průchod [drawOutlinedText] (výplň) se dřív
 * volal bez drawStyle. Compose ale null jako "vrať Fill" nebere - AndroidTextPaint.setDrawStyle(null)
 * hned vrací a paint sdílený oběma průchody (jeden TextLayoutResult) zůstal ve stylu Stroke z
 * prvního průchodu. Výplň se tak kreslila jako tlustý obrys v barvě textu a písmo bylo slité do
 * hrudek.
 *
 * Měří se skutečná rasterizace (NATIVE grafika): černý text s bílým obrysem na bílé ploše nesmí
 * mít víc tmavých pixelů než samotná výplň stejného textu.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OutlinedTextDrawTest {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val density = Density(1f)

    private fun renderDarkPixels(draw: DrawScope.(measurer: TextMeasurer) -> Unit): Int {
        val width = 420
        val height = 120
        val bitmap = ImageBitmap(width, height)
        val measurer = TextMeasurer(createFontFamilyResolver(context), density, LayoutDirection.Ltr, 8)
        CanvasDrawScope().draw(density, LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) {
            drawRect(Color.White)
            draw(measurer)
        }
        val pixels = IntArray(width * height)
        bitmap.asAndroidBitmap().getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels.count { (it and 0xFF) < 128 }
    }

    private val style = TextStyle(fontSize = 56.sp)

    @Test
    fun `fill pass is not drawn as a thick stroke`() {
        val fillOnly = renderDarkPixels { measurer ->
            drawText(measurer.measure("Wag", style), color = Color.Black)
        }
        val outlined = renderDarkPixels { measurer ->
            drawOutlinedText(measurer.measure("Wag", style), Color.Black, Color.White, strokeWidthPx = 10f)
        }
        assertTrue("sanity: fill-only text must produce ink, got $fillOnly px", fillOnly > 500)
        assertTrue(
            "outlined text has $outlined dark px vs $fillOnly for plain fill - fill pass is fattened by a stroke",
            outlined <= fillOnly * 1.1,
        )
    }
}
