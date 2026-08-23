package com.haise.jiyu.ui.reader.glcurl

import android.graphics.Bitmap
import android.opengl.GLUtils
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.opengles.GL10

/**
 * Port `Page.java` z github.com/denis554/PlayLikeCurl (karacken curl, OpenGL ES 1.0) - základní
 * třída pro jednu "stránku" v mřížce [GRID]x[GRID], texturovanou zadanou bitmapou. Matematika
 * ohybu (posun Z podle sloupce/[curlCirclePosition]) je v podtřídách [GLPageFront]/[GLPageLeft]/
 * [GLPageRight] - beze změny oproti originálu, jen převedená 1:1 do Kotlinu.
 *
 * Oproti originálu: [loadTexture] bere rovnou [Bitmap] (naše appka má bitmapu už dekódovanou z
 * Coilu/GraphicsLayer), originál načítal z assets podle `res_id` řetězce - to tady nedává smysl.
 */
open class GLPage {

    var curlCirclePosition: Float = GRID.toFloat()
    var isActive: Boolean = false

    private var bitmap: Bitmap? = null
    private var needsTextureUpdate = false
    private val textures = IntArray(1)

    val vertices = FloatArray((GRID + 1) * (GRID + 1) * 3)
    private val texture = FloatArray((GRID + 1) * (GRID + 1) * 2)
    private val indices = ShortArray(GRID * GRID * 6)

    var vertexBuffer: FloatBuffer
    private val textureBuffer: FloatBuffer
    private val indexBuffer: ShortBuffer

    /** Poměr výška/šířka bitmapy - viz [h_w_ratio] v `calculateVerticesCoords`. Výchozí 1f, dokud
     * se nenačte první textura. */
    protected var bitmapRatio: Float = 1f

    protected var hWRatio: Float = 1f
    protected var hWCorrection: Float = 0f

    init {
        calculateFacesCoords()
        calculateTextureCoords()

        var byteBuf = ByteBuffer.allocateDirect(texture.size * 4)
        byteBuf.order(ByteOrder.nativeOrder())
        textureBuffer = byteBuf.asFloatBuffer()
        textureBuffer.put(texture)
        textureBuffer.position(0)

        byteBuf = ByteBuffer.allocateDirect(indices.size * 2)
        byteBuf.order(ByteOrder.nativeOrder())
        indexBuffer = byteBuf.asShortBuffer()
        indexBuffer.put(indices)
        indexBuffer.position(0)

        // Prazdny vertex buffer - naplni ho az prvni calculateVerticesCoords() volani z draw().
        byteBuf = ByteBuffer.allocateDirect(vertices.size * 4)
        byteBuf.order(ByteOrder.nativeOrder())
        vertexBuffer = byteBuf.asFloatBuffer()
    }

    /** Nastaví bitmapu, co se má na stránku natáhnout jako textura - skutečné nahrání do GL
     * proběhne až v [draw] (musí běžet na GL vlákně, na rozdíl od volání tohohle setteru). */
    fun setBitmap(newBitmap: Bitmap) {
        bitmap = newBitmap
        needsTextureUpdate = true
    }

    /**
     * Násobitel 0f..1f pro to, jak moc je ohyb vyjádřený na řádku [row] (0=horní okraj stránky,
     * [GRID]=dolní okraj) - KÓNICKÝ ohyb (jako by čtenář uchopil stránku za dolní roh a táhl
     * obloukem), ne rovnoměrný přes celou výšku. Bez tohohle vypadal ohyb u [GLPageFront]/
     * [GLPageLeft] identicky na každém řádku (uživatelská zpětná vazba: "vypadá to jak vlna na
     * moři" - tj. chybí ta postupná proměna síly ohybu podle výšky, co dělá vlnu vlnou, ne
     * jednolitou rourou). U dolního okraje (`row=GRID`) je násobitel 1f (plná síla), lineárně
     * klesá k `1f - TAPER_STRENGTH` na horním okraji.
     */
    protected fun verticalTaper(row: Int): Float {
        val rowT = row / GRID.toFloat()
        val dist = kotlin.math.abs(rowT - ANCHOR_ROW_T)
        return (1f - dist * TAPER_STRENGTH).coerceIn(1f - TAPER_STRENGTH, 1f)
    }

    open fun calculateVerticesCoords() {
        hWRatio = bitmapRatio
        hWCorrection = (hWRatio - 1f) / 2f
    }

    private fun calculateFacesCoords() {
        for (row in 0 until GRID) {
            for (col in 0 until GRID) {
                val pos = 6 * (row * GRID + col)
                indices[pos] = (row * (GRID + 1) + col).toShort()
                indices[pos + 1] = (row * (GRID + 1) + col + 1).toShort()
                indices[pos + 2] = ((row + 1) * (GRID + 1) + col).toShort()
                indices[pos + 3] = (row * (GRID + 1) + col + 1).toShort()
                indices[pos + 4] = ((row + 1) * (GRID + 1) + col + 1).toShort()
                indices[pos + 5] = ((row + 1) * (GRID + 1) + col).toShort()
            }
        }
    }

    private fun calculateTextureCoords() {
        for (row in 0..GRID) {
            for (col in 0..GRID) {
                val pos = 2 * (row * (GRID + 1) + col)
                texture[pos] = col / GRID.toFloat()
                texture[pos + 1] = 1f - row / GRID.toFloat()
            }
        }
    }

    fun draw(gl: GL10) {
        if (needsTextureUpdate) {
            needsTextureUpdate = false
            loadTexture(gl)
        }
        calculateVerticesCoords()

        val buf = ByteBuffer.allocateDirect(vertices.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(vertices)
        buf.position(0)
        vertexBuffer = buf

        gl.glBindTexture(GL10.GL_TEXTURE_2D, textures[0])

        gl.glEnableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glEnableClientState(GL10.GL_TEXTURE_COORD_ARRAY)

        gl.glFrontFace(GL10.GL_CCW)

        gl.glVertexPointer(3, GL10.GL_FLOAT, 0, vertexBuffer)
        gl.glTexCoordPointer(2, GL10.GL_FLOAT, 0, textureBuffer)

        gl.glDrawElements(GL10.GL_TRIANGLES, indices.size, GL10.GL_UNSIGNED_SHORT, indexBuffer)

        gl.glDisableClientState(GL10.GL_VERTEX_ARRAY)
        gl.glDisableClientState(GL10.GL_TEXTURE_COORD_ARRAY)
    }

    private fun loadTexture(gl: GL10) {
        val bmp = bitmap ?: return
        // Stejny problem jako drive u PageCurlEffect/drawBitmapMesh (viz tam) - bitmapa z
        // Compose GraphicsLayer.toImageBitmap() je na tomhle zarizeni Bitmap.Config.HARDWARE,
        // coz GLUtils.texImage2D bud tise vynecha, nebo shodi GL vlakno - v obou pripadech
        // stranka na obrazovce zustane plocha/staticka, i kdyz matematika ohybu bezi spravne.
        val safeBmp = if (bmp.config == Bitmap.Config.HARDWARE) {
            bmp.copy(Bitmap.Config.ARGB_8888, false)
        } else {
            bmp
        }
        bitmapRatio = safeBmp.height.toFloat() / safeBmp.width.toFloat()

        gl.glGenTextures(1, textures, 0)
        gl.glBindTexture(GL10.GL_TEXTURE_2D, textures[0])

        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MIN_FILTER, GL10.GL_NEAREST.toFloat())
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_MAG_FILTER, GL10.GL_LINEAR.toFloat())
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_S, GL10.GL_REPEAT.toFloat())
        gl.glTexParameterf(GL10.GL_TEXTURE_2D, GL10.GL_TEXTURE_WRAP_T, GL10.GL_REPEAT.toFloat())

        GLUtils.texImage2D(GL10.GL_TEXTURE_2D, 0, safeBmp, 0)
    }

    companion object {
        const val GRID = 25
        const val RADIUS = 0.18f

        /** Svislá pozice (0f nahoře, 1f dole) - dolní roh je nejběžnější místo, za které se
         * stránka při otáčení uchopí (viz [verticalTaper]). */
        private const val ANCHOR_ROW_T = 1f

        /** O kolik je ohyb slabší na opačném svislém konci stránky - 0.6 = na druhém konci
         * zůstane 40 % síly ohybu, ne 0 (úplně plochý konec by působil nepřirozeně ostře). */
        private const val TAPER_STRENGTH = 0.6f
    }
}
