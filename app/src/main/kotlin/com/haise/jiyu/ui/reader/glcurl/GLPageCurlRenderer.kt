package com.haise.jiyu.ui.reader.glcurl

import android.graphics.Bitmap
import android.opengl.GLSurfaceView
import android.opengl.GLU
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

    /**
     * Zavolat z UI vlákna kdykoliv se změní bitmapy stránek nebo stav tažení - skutečné
     * promítnutí do GL (textury, aktivní stránka, [GLPage.curlCirclePosition]) proběhne až
     * uvnitř [onDrawFrame] (musí běžet na GL vlákně). `forward=true` = táhne se na DALŠÍ
     * stránku ([GLPageFront] aktivní, [rightPage] podklad), `false` = na PŘEDCHOZÍ
     * ([GLPageLeft] aktivní, [rightPage] zůstává podklad jen vizuálně vzadu/skrytý).
     */
    fun updateState(current: Bitmap, prev: Bitmap?, next: Bitmap?, forward: Boolean, progress: Float) {
        pendingCurrent = current
        pendingPrev = prev
        pendingNext = next
        this.forward = forward
        this.progress = progress.coerceIn(0f, 1f)
    }

    override fun onSurfaceCreated(gl: GL10, config: EGLConfig?) {
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
        if (safeHeight > width) {
            GLU.gluPerspective(gl, 45.0f, width.toFloat() / safeHeight.toFloat(), 0.1f, 100.0f)
        } else {
            GLU.gluPerspective(gl, 45.0f, safeHeight.toFloat() / width.toFloat(), 0.1f, 100.0f)
        }
        gl.glMatrixMode(GL10.GL_MODELVIEW)
        gl.glLoadIdentity()
    }

    override fun onDrawFrame(gl: GL10) {
        val current = pendingCurrent
        if (current != null && current !== lastCurrent) {
            frontPage.setBitmap(current)
            lastCurrent = current
        }
        val prev = pendingPrev
        if (prev != null && prev !== lastPrev) {
            leftPage.setBitmap(prev)
            lastPrev = prev
        }
        val next = pendingNext
        if (next != null && next !== lastNext) {
            rightPage.setBitmap(next)
            lastNext = next
        }

        // Kterakoliv stranka je "aktivni" (ohyba se), ta druha (front/left) zustava plocha na
        // sve male pevne hloubce - viz dokumentace tridy vyse a `GLPage.isActive`.
        val goingForward = forward
        frontPage.isActive = goingForward
        leftPage.isActive = !goingForward

        val curlValue = GLPage.GRID * (1f - progress)
        if (goingForward) {
            frontPage.curlCirclePosition = curlValue
        } else {
            leftPage.curlCirclePosition = curlValue
        }

        gl.glClear(GL10.GL_COLOR_BUFFER_BIT or GL10.GL_DEPTH_BUFFER_BIT)
        gl.glLoadIdentity()

        gl.glPushMatrix()
        gl.glTranslatef(0f, 0f, -2f)
        gl.glTranslatef(-0.5f, -0.5f, 0f)
        leftPage.draw(gl)
        gl.glPopMatrix()

        gl.glPushMatrix()
        gl.glTranslatef(0f, 0f, -2f)
        gl.glTranslatef(-0.5f, -0.5f, 0f)
        frontPage.draw(gl)
        gl.glPopMatrix()

        gl.glPushMatrix()
        gl.glTranslatef(0f, 0f, -2f)
        gl.glTranslatef(-0.5f, -0.5f, 0f)
        rightPage.draw(gl)
        gl.glPopMatrix()
    }
}
