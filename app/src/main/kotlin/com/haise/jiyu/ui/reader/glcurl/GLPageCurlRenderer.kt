package com.haise.jiyu.ui.reader.glcurl

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.opengl.GLU
import com.haise.jiyu.util.report
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Port `PageRenderer.java` - řídí tři [GLPage] objekty (předchozí/aktuální/další stránka) a
 * jejich perspektivu/vykreslení. Beze změny oproti originálu v samotné 3D matematice a nastavení
 * OpenGL stavu - jediný rozdíl je způsob řízení: originál měl vlastní dotykové ovládání
 * (`PageSurfaceView`, `GestureDetector`), tady se místo toho stav (rozestoupení/směr) nastavuje
 * zvenčí přes [updateState] - řídí to naše VLASTNÍ gesto/stav ([PageCurlState] atd.), který dělá
 * přesně tu samou práci (sledování tažení, snapping, hranice kapitoly).
 *
 * [GLPageFront] = aktuální stránka (aktivní při otáčení VPŘED, na další).
 * [GLPageLeft] = předchozí stránka (aktivní při otáčení VZAD, na předchozí).
 * [GLPageRight] = statická podkladová stránka (vždy plochá, nikdy sama neaktivní).
 */
class GLPageCurlRenderer : GLSurfaceView.Renderer {

    private val frontPage = GLPageFront()
    private val leftPage = GLPageLeft()
    private val rightPage = GLPageRight()

    @Volatile private var pendingCurrent: Bitmap? = null
    @Volatile private var pendingPrev: Bitmap? = null
    @Volatile private var pendingNext: Bitmap? = null
    @Volatile private var lastCurrent: Bitmap? = null
    @Volatile private var lastPrev: Bitmap? = null
    @Volatile private var lastNext: Bitmap? = null

    @Volatile private var forward: Boolean = true
    @Volatile private var progress: Float = 0f
    @Volatile private var mirrored: Boolean = false
    /** Hloubka kamery, pri ktere quad 1 x viewportRatio presne vyplni viewport -
     * pocita se v [onSurfaceChanged]. Vychozi 2f jen jako placeholder pred prvnim
     * onSurfaceChanged (puvodni pevna hodnota = ~1.31x overscan). */
    @Volatile private var fitDistance: Float = 2f

    /**
     * Zavolat z UI vlákna kdykoliv se změní bitmapy stránek nebo stav tažení - skutečné
     * promítnutí do GL (textury, aktivní stránka, [GLPage.curlCirclePosition]) proběhne až
     * uvnitř [onDrawFrame] (musí běžet na GL vlákně). `forward=true` = táhne se na DALŠÍ
     * stránku ([GLPageFront] aktivní, [rightPage] podklad), `false` = na PŘEDCHOZÍ
     * ([GLPageLeft] aktivní, [rightPage] zůstává podklad jen vizuálně vzadu/skrytý).
     * `mirrored` = RTL čtečka - ohyb se zrcadlí horizontálně (viz [GLPage.mirrored]).
     */
    fun updateState(current: Bitmap, prev: Bitmap?, next: Bitmap?, forward: Boolean, progress: Float, mirrored: Boolean) {
        pendingCurrent = current
        pendingPrev = prev
        pendingNext = next
        this.forward = forward
        this.progress = progress.coerceIn(0f, 1f)
        this.mirrored = mirrored
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig?) {
        // Novy EGL kontext (prvni vytvoreni i navrat po onPause/onResume): stara id textur jsou
        // neplatna, takze se zapomenou a bitmapy se nahraji znovu - bez tohohle by po ztrate
        // kontextu zustaly stranky prazdne, protoze last* uz ukazuji na stejne bitmapy a nic by
        // se znovu nenahralo. (Textury pri zniceni view uvolni samotny zanikajici kontext.)
        frontPage.onContextLost()
        leftPage.onContextLost()
        rightPage.onContextLost()
        lastCurrent = null
        lastPrev = null
        lastNext = null
        gl.glEnable(GL10.GL_TEXTURE_2D)
        gl.glShadeModel(GL10.GL_SMOOTH)
        gl.glClearColor(0f, 0f, 0f, 0f)
        gl.glClearDepthf(1f)
        gl.glEnable(GL10.GL_DEPTH_TEST)
        gl.glDepthFunc(GL10.GL_LEQUAL)
        gl.glHint(GL10.GL_PERSPECTIVE_CORRECTION_HINT, GL10.GL_NICEST)
    }

    override fun onSurfaceChanged(gl: GL10, width: Int, height: Int) {
        val safeHeight = if (height == 0) 1 else height
        gl.glViewport(0, 0, width, safeHeight)
        gl.glMatrixMode(GL10.GL_PROJECTION)
        gl.glLoadIdentity()
        // gluPerspective(fovy, aspect): aspect je VZDY sirka/vyska viewportu (puvodni
        // landscape vetev h/w byla chyba). Strankovy quad je velikosti 1 x viewportRatio
        // (textura = rasterizace celeho viewportu vcetne letterboxingu); hloubka
        // -fitDistance ho proto promite PRESNE na obrazovku. Driv tu bylo fixni -2f:
        // na z=-2 je viditelna plocha jen 0.765 x 1.657 jednotky, takze se quad
        // projekci nafoukl ~1.31x pres okraje = stranky se pri kazdem rollu vizualne
        // "zoomly" jako Fill Screen (hlaseny bug). Vzorec: viditelna vyska na hloubce
        // d je 2*tan(fovy/2)*d a ma se rovnat viewportRatio.
        GLU.gluPerspective(gl, 45.0f, width.toFloat() / safeHeight.toFloat(), 0.1f, 100.0f)
        fitDistance = (safeHeight.toFloat() / width.toFloat()) / TWO_TAN_HALF_FOV
        gl.glMatrixMode(GL10.GL_MODELVIEW)
        gl.glLoadIdentity()
    }

    override fun onDrawFrame(gl: GL10) {
        val current = pendingCurrent
        if (current != null && current !== lastCurrent) {
            frontPage.setBitmap(current)
            lastCurrent = current
        }
        // Chybejici sousedni stranka (hranice kapitoly) dostane texturu aktualni stranky -
        // stejne jako originalni `updatePageRes(0,0,1)` na pozici 0. Bez toho by mimo obrazovku
        // vykukujici prouzek neaktivni leftPage kreslil texture-id 0 = bilou skvrnu.
        val prev = pendingPrev ?: pendingCurrent
        if (prev != null && prev !== lastPrev) {
            leftPage.setBitmap(prev)
            lastPrev = prev
        }
        val next = pendingNext ?: pendingCurrent
        if (next != null && next !== lastNext) {
            rightPage.setBitmap(next)
            lastNext = next
        }

        // Kterakoliv stranka je "aktivni" (ohyba se), ta druha (front/left) zustava plocha na
        // sve male pevne hloubce - viz dokumentace tridy vyse a `GLPage.isActive`.
        val goingForward = forward
        frontPage.isActive = goingForward
        leftPage.isActive = !goingForward
        frontPage.mirrored = mirrored
        leftPage.mirrored = mirrored
        rightPage.mirrored = false

        // Neaktivni stranka se vzdy vraci na svuj KLIDOVY curlCirclePosition (front naplocho,
        // left svinuta mimo obrazovku) - jinak by si po predchozim tahu drzela posledni pozici a
        // napr. leftPage na konci dokonceneho tahu zpatky by zustala naplocho na -0.001 a
        // prekryvala by vsechno pod sebou starou prev-texturou.
        if (goingForward) {
            frontPage.curlCirclePosition = rollCurlPosition(true, progress)
            leftPage.curlCirclePosition = LEFT_REST
        } else {
            leftPage.curlCirclePosition = rollCurlPosition(false, progress)
            frontPage.curlCirclePosition = GLPage.GRID.toFloat()
        }

        gl.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
        gl.glLoadIdentity()

        // IDLE stav (progress == 0): surface je mountnuty permanentne a pruhledny - stranky
        // se NEkresli (transparentni clear -> prosvita ziva Compose stranka pod nimi), jen se
        // synchronizuji textury. Bez tohohle by prvni curl frame cekal na nahratí ~3 velkych
        // textur az uprostred gesta a zacatek animace by se vizuálne ztratil.
        if (progress <= 0f) {
            frontPage.syncTexture(gl)
            leftPage.syncTexture(gl)
            rightPage.syncTexture(gl)
            return
        }

        // Kazde .draw() obalene zvlast - GLThread nema zadny globalni handler nezachycenych
        // vyjimek jako hlavni vlakno, takze by pad pri vykreslovani JEDNE stranky (napr.
        // loadTexture na uz recyklovane bitmape) shodil celou appku. Sam GLPage.loadTexture uz
        // vyjimky chyta (viz tam), tohle je jen dalsi vrstva pro cokoliv necekaneho v
        // calculateVerticesCoords()/zbytku draw().
        try {
            gl.glPushMatrix()
            gl.glTranslatef(0f, 0f, -fitDistance)
            gl.glTranslatef(-0.5f, -0.5f, 0f)
            leftPage.draw(gl)
            gl.glPopMatrix()

            gl.glPushMatrix()
            gl.glTranslatef(0f, 0f, -fitDistance)
            gl.glTranslatef(-0.5f, -0.5f, 0f)
            frontPage.draw(gl)
            gl.glPopMatrix()

            gl.glPushMatrix()
            gl.glTranslatef(0f, 0f, -fitDistance)
            gl.glTranslatef(-0.5f, -0.5f, 0f)
            rightPage.draw(gl)
            gl.glPopMatrix()
        } catch (e: Exception) {
            e.report("reader:glcurl:onDrawFrame")
        }
    }

    companion object {
        /** Klidová pozice [GLPageLeft] - svinutá trubička mimo obrazovku vlevo (mirrored:
         * vpravo). Odpovídá originálnímu `GRID * (PAGE_RGHT/100) = 25 * -0.05`. */
        const val LEFT_REST: Float = GLPage.GRID * -0.05f

        /**
         * Mapuje `progress` (0 = klid, 1 = dokončený obrat) na `curlCirclePosition` aktivní
         * stránky - protějšek originálních `ACTION_MOVE` výpočtů z `PageSurfaceView`, jen se
         * rozsahem protáhnutým o klidový overshoot [LEFT_REST], aby `progress = 1` znamenalo
         * úplně dokončený obrat (používá se i dojetí animace po puštění prstu):
         * - vpřed: [GLPageFront] GRID → [LEFT_REST] (stránka se svinuje zprava a odjede vlevo),
         * - vzad: [GLPageLeft] [LEFT_REST] → GRID (předchozí stránka se rozbaluje zleva naplocho).
         */
        fun rollCurlPosition(forward: Boolean, progress: Float): Float {
            val span = GLPage.GRID - LEFT_REST
            return if (forward) {
                GLPage.GRID - progress.coerceIn(0f, 1f) * span
            } else {
                LEFT_REST + progress.coerceIn(0f, 1f) * span
            }
        }

        /** `2 * tan(45° / 2)` - viditelna vyska frustu na jednotku hloubky pro fovy 45°. */
        private const val TWO_TAN_HALF_FOV = 0.8284271247461903f
    }
}
