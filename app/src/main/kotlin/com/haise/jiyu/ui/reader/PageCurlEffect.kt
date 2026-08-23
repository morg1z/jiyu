package com.haise.jiyu.ui.reader

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Počet sloupců sítě, na které se ohýbaný pás rozdělí pro [android.graphics.Canvas.drawBitmapMesh]
 * - vyšší číslo = plynulejší zakulacení. Mesh je bilineárně interpolovaný hardwarově, takže na
 * rozdíl od starého přístupu (diskrétní `drawBitmap` proužky) je i s vyšším počtem sloupců levný
 * a hladký - žádné viditelné "schody" mezi sloupci. */
private const val MESH_COLUMNS = 30

/** Počet řádků sítě - musí být > 1, aby [PageCurlGeometry.verticalTaper] měl co plynule
 * interpolovat (kónický ohyb, silnější u rohu než na druhém konci). Dřív tu byly jen 2 řádky
 * (nahoře/dole), protože geometrie byla čistě válcová (stejná po celé výšce). */
private const val MESH_ROWS = 24

/**
 * Vykreslí aktuální stránku s válcovým ohybem podle [geometry] (styl Google Play Books/iBooks -
 * obsah se u osy ohybu komprimuje/zakulacuje, ne zrcadlí jako dřív). [revealedPageBitmap] je
 * stránka odkrývaná pod ohybem (null na hranici kapitoly, kdy sousední stránka ještě neexistuje
 * jako bitmapa).
 */
fun DrawScope.drawPageCurl(
    geometry: PageCurlGeometry,
    currentPageBitmap: ImageBitmap,
    revealedPageBitmap: ImageBitmap?,
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas
    val rawBitmap = currentPageBitmap.asAndroidBitmap()
    // `currentPageBitmap` je rasterizovaná GraphicsLayer vrstva - na řadě zařízení (potvrzeno na
    // Galaxy S24 Ultra) vychází v Config.HARDWARE (GPU-only paměť, žádný přímý přístup k pixelům
    // z CPU). Prosté `Canvas.drawBitmap` (plochá část níž, `revealedPageBitmap`) na tom funguje
    // v pohodě - je to čistě GPU kompozice. Ale ořez pásu pro `drawBitmapMesh`
    // (`Bitmap.createBitmap(source, x, y, w, h)`) na hardwarové bitmapě čte pixely, a na tomhle
    // zařízení tiše vrací bitmapu bez viditelného obsahu - bez pádu, bez chyby, prostě nic
    // nenakreslí. Proto ohýbaný pás vypadal jako plochý řez bez jakéhokoli zakřivení/stínování.
    // Převod na softwarovou kopii CELÉ bitmapy hned na začátku (ne až po ořezu) tomu předchází.
    val bitmap = if (rawBitmap.config == Bitmap.Config.HARDWARE) {
        rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        rawBitmap
    }

    revealedPageBitmap?.let {
        nativeCanvas.drawBitmap(it.asAndroidBitmap(), 0f, 0f, null)
    }

    val direction = if (geometry.turningFromRight) 1f else -1f

    // Plochá část stránky (mezi protější hranou a osou ohybu) - beze změny, bez warpu.
    val flatRect = if (geometry.turningFromRight) {
        Rect(0, 0, geometry.foldX.roundToInt().coerceIn(0, bitmap.width), bitmap.height)
    } else {
        Rect(geometry.foldX.roundToInt().coerceIn(0, bitmap.width), 0, bitmap.width, bitmap.height)
    }
    if (flatRect.width() > 0) {
        nativeCanvas.save()
        nativeCanvas.clipRect(flatRect)
        nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
        nativeCanvas.restore()
    }

    if (geometry.curlBandWidth < 0.5f) return

    // Stín na odkryté stránce kousek před ohýbaným pásem - simuluje, že zvednutý papír vrhá stín.
    drawAheadShadow(nativeCanvas, geometry, direction)

    // Ohýbaný pás: nejdřív oříznout bitmapu jen na pás, co se ohýbá (drawBitmapMesh warpuje
    // celou zdrojovou bitmapu rovnoměrně, nemá vlastní parametr pro texturové souřadnice).
    // `turningFromRight` určuje, který konec ořezu leží na ose ohybu (d=0) - viz mapování d(col)
    // níž.
    val bandLeftF = if (geometry.turningFromRight) geometry.foldX else geometry.foldX - geometry.curlBandWidth
    val bandRightF = if (geometry.turningFromRight) geometry.foldX + geometry.curlBandWidth else geometry.foldX
    val cropLeft = bandLeftF.roundToInt().coerceIn(0, bitmap.width)
    val cropRight = bandRightF.roundToInt().coerceIn(cropLeft, bitmap.width)
    val cropWidth = cropRight - cropLeft
    if (cropWidth <= 0) return
    val croppedBand = Bitmap.createBitmap(bitmap, cropLeft, 0, cropWidth, bitmap.height)
    // `currentPageBitmap` je rasterizovaná GraphicsLayer vrstva - na tomhle zařízení vychází v
    // Config.HARDWARE (GPU-only paměť). Ořez zůstává stejně HARDWARE a `drawBitmapMesh` na něm
    // TICHO nekreslí vůbec nic (bez pádu, bez chyby) - efekt tak vypadal jako plochý řez bez
    // jakéhokoli zakřivení. `drawBitmapMesh` potřebuje softwarově čitelné pixely.
    val bandBitmap = if (croppedBand.config == Bitmap.Config.HARDWARE) {
        croppedBand.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        croppedBand
    }

    // Mřížka MESH_COLUMNS x MESH_ROWS - pro každý bod spočítáme vzdálenost `d` od osy ohybu a
    // z ní warp (`warpedOffset`, komprese k ose) a stín (`shadeAt`), obojí navíc násobené
    // `verticalTaper(rowT)` - KÓNICKÝ ohyb, silnější poblíž rohu, za který se stránka "drží",
    // slabší na druhém konci (viz `PageCurlGeometry.kt`). Dřív tu byly jen 2 řádky a žádný taper,
    // takže ohyb vypadal jako rovnoměrný VÁLEC přes celou výšku, ne jako přirozené uchopení rohu.
    // `colors` pole násobí barvu bitmapy per-vertex - nahrazuje starý PorterDuffColorFilter, teď
    // ale plynule interpolovaný mezi vertexy místo skokového po proužcích.
    val vertsPerRow = MESH_COLUMNS + 1
    val vertsPerCol = MESH_ROWS + 1
    val verts = FloatArray(vertsPerRow * vertsPerCol * 2)
    val colors = IntArray(vertsPerRow * vertsPerCol)
    for (row in 0..MESH_ROWS) {
        val rowT = row.toFloat() / MESH_ROWS
        val y = geometry.pageHeight * rowT
        val taper = geometry.verticalTaper(rowT)
        for (col in 0..MESH_COLUMNS) {
            val frac = col.toFloat() / MESH_COLUMNS
            val d = if (geometry.turningFromRight) {
                geometry.curlBandWidth * frac
            } else {
                geometry.curlBandWidth * (1f - frac)
            }
            val x = geometry.foldX + direction * geometry.warpedOffset(d) * taper
            val idx = row * vertsPerRow + col
            verts[idx * 2] = x
            verts[idx * 2 + 1] = y

            val shade = 1f - (1f - geometry.shadeAt(d)) * taper
            val gray = (shade * 255).roundToInt().coerceIn(0, 255)
            colors[idx] = Color.argb(255, gray, gray, gray)
        }
    }

    val meshPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    nativeCanvas.drawBitmapMesh(bandBitmap, MESH_COLUMNS, MESH_ROWS, verts, 0, colors, 0, meshPaint)
}

/** Kolik vodorovných pruhů se použije na vykreslení stínu - musí sledovat stejný kónický taper
 * jako hlavní mesh (viz [PageCurlGeometry.verticalTaper]), jinak by stín zůstal rovný pruh i
 * když je samotný ohyb nahoře/dole slabší - vypadalo by to nesourodě. */
private const val SHADOW_STRIPS = 12

/** Měkký vržený stín na odkryté stránce těsně před ohýbaným pásem - simuluje, že zvednutý papír
 * vrhá stín na to, co je pod ním. Šířka škáluje s poloměrem ohybu (u širšího/pozvolnějšího ohybu
 * i stín dopadá dál), víc mezikroků v gradientu dělá dopad postupnější (ne jeden ostrý schod) a
 * vodorovné pruhy s [PageCurlGeometry.verticalTaper] kopírují kónický tvar hlavního ohybu, místo
 * jednoho rovného obdélníku přes celou výšku. */
private fun drawAheadShadow(canvas: android.graphics.Canvas, geometry: PageCurlGeometry, direction: Float) {
    val paint = Paint()
    for (i in 0 until SHADOW_STRIPS) {
        val rowT0 = i.toFloat() / SHADOW_STRIPS
        val rowT1 = (i + 1).toFloat() / SHADOW_STRIPS
        val taper = geometry.verticalTaper((rowT0 + rowT1) / 2f)
        val shadowWidth = geometry.radius * 0.6f * taper
        val edgeX = geometry.foldX + direction * geometry.curlBandWidth * taper
        val farX = edgeX + direction * shadowWidth
        paint.shader = LinearGradient(
            edgeX, 0f, farX, 0f,
            intArrayOf(0x40000000, 0x18000000, 0x00000000),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP,
        )
        val left = minOf(edgeX, farX)
        val right = maxOf(edgeX, farX)
        canvas.drawRect(left, geometry.pageHeight * rowT0, right, geometry.pageHeight * rowT1, paint)
    }
}

/** Kolik sloupců/řádků má síť pro [drawWaveCurl] - stejné hustoty jako [MESH_COLUMNS]/
 * [MESH_ROWS], jen samostatné konstanty, kdyby si vlna časem žádala jinou hustotu. */
private const val WAVE_MESH_COLUMNS = 30
private const val WAVE_MESH_ROWS = 24

/** O kolik dál než lineární pozici (0f) se vrchol vlny (`frac`=0.5) vyboulí navenek, jako násobek
 * [PageCurlGeometry.curlBandWidth] - čistě estetický parametr, ladit podle dojmu na reálném
 * zařízení (viz komentář u volajícího místa, zatím neověřeno naživo). */
private const val WAVE_BULGE_STRENGTH = 0.28f

/** Jak moc tmavší jsou okraje vlny (`frac`=0/1, kde se láme do ploché části/hřebenu) oproti
 * vrcholu (`frac`=0.5, plný jas) - vertex-color multiply umí jen ZTMAVIT (ne zesvětlit nad
 * texturu), takže "nasvícení" hřebenu je jen relativní - vrchol zůstává na plném jasu textury,
 * okraje jsou o tenhle podíl tmavší. */
private const val WAVE_EDGE_DARKEN = 0.3f

/**
 * Vykreslí aktuální stránku s efektem "mořské vlny" - VLASTNÍ sinusová geometrie, ne válcová
 * ([PageCurlGeometry.warpedOffset]/[shadeAt] se tu nepoužívají). [geometry] se ale počítá stejnou
 * [computePageCurlGeometry] funkcí se stylem [CurlStyle.WAVE] - [PageCurlGeometry.foldX] je
 * hranice mezi plochou částí a vlněním (kde vlna vyrůstá z roviny stránky, výška 0), a
 * [PageCurlGeometry.curlBandWidth] je šířka vlnícího se pásu; jeho vzdálenější konec (`front`)
 * je "hřeben" - výška je 0 i tam (vlna vyroste a zase klesne, ne monotónně roste jako u
 * [CurlStyle.CLASSIC]/[CurlStyle.ROLL]). Za hřebenem je jen měkký stín na odkryté stránce, žádný
 * zrcadlený "lip" přehyb - ten byl na zařízení potvrzeně rozbitý (viz git historie), odstraněn
 * místo dalšího ladění naslepo bez přístupu k zařízení.
 *
 * Sílu vlnění řídí [PageCurlGeometry.progress] přes `envelope = sin(π·progress)` - 0 v klidu
 * (na začátku i na konci tažení), maximum v polovině tažení, takže "moře" je klidné, dokud se
 * stránka nezačne otáčet, a zase se uklidní, jakmile se otočení dokončí.
 */
fun DrawScope.drawWaveCurl(
    geometry: PageCurlGeometry,
    currentPageBitmap: ImageBitmap,
    revealedPageBitmap: ImageBitmap?,
) {
    val nativeCanvas = drawContext.canvas.nativeCanvas
    val rawBitmap = currentPageBitmap.asAndroidBitmap()
    // Stejná past jako v drawPageCurl - HARDWARE bitmapa (GPU-only pamet) tise nevrati zadna
    // data pri Bitmap.createBitmap ořezu ani pri drawBitmapMesh, bez pádu/chyby.
    val bitmap = if (rawBitmap.config == Bitmap.Config.HARDWARE) {
        rawBitmap.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        rawBitmap
    }

    revealedPageBitmap?.let {
        nativeCanvas.drawBitmap(it.asAndroidBitmap(), 0f, 0f, null)
    }

    val direction = if (geometry.turningFromRight) 1f else -1f
    val envelope = sin(PI.toFloat() * geometry.progress).coerceIn(0f, 1f)

    // Plocha cast pred vlnou (jeste nedosazena) - stejna hranice jako flatRect v drawPageCurl.
    val flatRect = if (geometry.turningFromRight) {
        Rect(0, 0, geometry.foldX.roundToInt().coerceIn(0, bitmap.width), bitmap.height)
    } else {
        Rect(geometry.foldX.roundToInt().coerceIn(0, bitmap.width), 0, bitmap.width, bitmap.height)
    }
    if (flatRect.width() > 0) {
        nativeCanvas.save()
        nativeCanvas.clipRect(flatRect)
        nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
        nativeCanvas.restore()
    }

    // POZOR: i pri envelope blizko 0 (klidne more na zacatku/konci tazeni) se pas porad musi
    // vykreslit (jen plochy, bez vlneni) - jinak by tu zbyla nevykreslena mezera (prosvitala by
    // skrz ni revealedPageBitmap), protoze flatRect vyse konci presne na foldX, ne az za pasem.
    if (geometry.curlBandWidth < 0.5f) return

    // "front" = vzdalenejsi konec vlnicího se pásu od foldX (smerem k hrane, ze ktere se otáci) -
    // tam se vlna lame (viz lip nize). Zdrojovy orez pro sit jde od foldX (hranice s plochou
    // castí) po front (hranice se skrytou/jiz "zlomenou" castí).
    val front = geometry.foldX + direction * geometry.curlBandWidth
    val cropLeft = minOf(geometry.foldX, front).roundToInt().coerceIn(0, bitmap.width)
    val cropRight = maxOf(geometry.foldX, front).roundToInt().coerceIn(cropLeft, bitmap.width)
    val cropWidth = cropRight - cropLeft
    if (cropWidth <= 0) return
    val croppedBand = Bitmap.createBitmap(bitmap, cropLeft, 0, cropWidth, bitmap.height)
    val bandBitmap = if (croppedBand.config == Bitmap.Config.HARDWARE) {
        croppedBand.copy(Bitmap.Config.ARGB_8888, false)
    } else {
        croppedBand
    }

    // Sit WAVE_MESH_COLUMNS x WAVE_MESH_ROWS - pro kazdy sloupec spocitame sinusovy "hrb"
    // (bulge, 0 na obou koncich pásu, vrchol uprostred) a z nej vodorovny posun (extra vyboulení
    // navenek pres linearni pozici) a stín (svetlejsi na vrcholu, tmavsi na okrajich).
    val vertsPerRow = WAVE_MESH_COLUMNS + 1
    val vertsPerCol = WAVE_MESH_ROWS + 1
    val verts = FloatArray(vertsPerRow * vertsPerCol * 2)
    val colors = IntArray(vertsPerRow * vertsPerCol)
    for (row in 0..WAVE_MESH_ROWS) {
        val rowT = row.toFloat() / WAVE_MESH_ROWS
        val y = geometry.pageHeight * rowT
        val taper = geometry.verticalTaper(rowT)
        for (col in 0..WAVE_MESH_COLUMNS) {
            // srcFrac indexuje SLOUPCE zdrojove bandBitmap (0=cropLeft, 1=cropRight, vzdy
            // vzestupne v bitmapovych souradnicich - drawBitmapMesh je bere v tomhle poradi bez
            // ohledu na smer otaceni). `d` je pojmova vzdalenost od foldX smerem k front (0..
            // curlBandWidth) - u turningFromRight=false je bandBitmap orezana [front..foldX], tedy
            // OBRACENE (cropLeft=front), takze se musi prehodit, jinak by se sirka pasu vykreslila
            // zrcadlove (obsah co patri k foldX by skoncil u front a naopak).
            val srcFrac = col.toFloat() / WAVE_MESH_COLUMNS
            val d = if (geometry.turningFromRight) geometry.curlBandWidth * srcFrac else geometry.curlBandWidth * (1f - srcFrac)
            val bulge = sin((d / geometry.curlBandWidth) * PI.toFloat()) * envelope
            val extra = geometry.curlBandWidth * WAVE_BULGE_STRENGTH * bulge
            val x = geometry.foldX + direction * (d + extra) * taper
            val idx = row * vertsPerRow + col
            verts[idx * 2] = x
            verts[idx * 2 + 1] = y

            val shade = 1f - (1f - bulge) * WAVE_EDGE_DARKEN * taper
            val gray = (shade.coerceIn(0f, 1f) * 255).roundToInt()
            colors[idx] = Color.argb(255, gray, gray, gray)
        }
    }

    val meshPaint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
    }
    nativeCanvas.drawBitmapMesh(bandBitmap, WAVE_MESH_COLUMNS, WAVE_MESH_ROWS, verts, 0, colors, 0, meshPaint)

    // Stin za "front" (kde by se vlna lamala) na odkrytou stranku - naznaci hloubku bez
    // zrcadleneho "lip" pruhu, ktery byl na zarizeni potvrzene rozbity (roztrhany/posunuty
    // obsah, viz nahlaseny bug) - zrcadleni platna + drawBitmap(src,dst) pres nej byla jedina
    // technika v tehle funkci bez overeneho vzoru jinde v kodu, proto prvni podezrely a
    // odstraneny, misto dalsiho slepeho ladeni bez pristupu k zarizeni.
    if (envelope > 0.05f) {
        val shadowNear = front
        val shadowFar = front + direction * geometry.curlBandWidth * 0.25f
        val shadowPaint = Paint().apply {
            shader = LinearGradient(
                shadowNear, 0f, shadowFar, 0f,
                intArrayOf(Color.argb((110 * envelope).roundToInt(), 0, 0, 0), 0x00000000),
                null, Shader.TileMode.CLAMP,
            )
        }
        val shadowLeft = minOf(shadowNear, shadowFar)
        val shadowRight = maxOf(shadowNear, shadowFar)
        nativeCanvas.drawRect(shadowLeft, 0f, shadowRight, geometry.pageHeight, shadowPaint)
    }
}
