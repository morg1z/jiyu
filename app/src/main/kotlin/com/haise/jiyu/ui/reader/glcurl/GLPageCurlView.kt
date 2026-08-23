package com.haise.jiyu.ui.reader.glcurl

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Compose obal nad [GLPageCurlRenderer] - port `karacken.curl` OpenGL efektu (viz
 * `GLPageCurlRenderer` dokumentace). Na rozdíl od originálu (`PageSurfaceView` s vlastním
 * `GestureDetector`) tenhle `GLSurfaceView` žádný dotek sám nezpracovává - jen vykresluje podle
 * [progress]/[forward], které řídí VOLAJÍCÍ (`MangaPageCurlReader`/`PageCurlNovelReader`) ze
 * svého vlastního, už existujícího gesto-stavu ([com.haise.jiyu.ui.reader.PageCurlState]).
 * Skutečné dotykové gesto (tažení/ťuknutí zón) tak zůstává beze změny v Compose vrstvě nad tímhle
 * viewem, přesně jako u dřívějšího `Canvas`+`drawPageCurl` přístupu, který tenhle view nahrazuje
 * pro [com.haise.jiyu.ui.reader.CurlStyle.ROLL].
 */
@Composable
fun GLPageCurlView(
    currentBitmap: ImageBitmap,
    prevBitmap: ImageBitmap?,
    nextBitmap: ImageBitmap?,
    forward: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val renderer = remember { GLPageCurlRenderer() }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            GLSurfaceView(context).apply {
                // GLSurfaceView je SurfaceView - ten se BEZ tohohle volani vykresluje na
                // samostatnem povrchu ZA oknem aplikace (diry v Compose UI), takže by byl
                // schovany za zbytkem obrazovky (staticka bitmapa aktualni stranky nad nim) a
                // cely efekt by pusobil jako by se vubec nerenderoval, presne jak to bylo videt
                // po nasazeni - zadna animace, jen skok na dalsi stranku po pusteni prstu.
                setZOrderOnTop(true)
                // Zadny setEGLContextClientVersion() - stejne jako originalni
                // PageSurfaceView.java, ktery ho taky nevola. Renderer pouziva klasicke
                // GL10 (pevna funkcni roura, OpenGL ES 1.x), GLSurfaceView si na to sam
                // vybere spravny EGL config bez explicitni verze.
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                isFocusable = false
                isClickable = false
            }.also { glView = it }
        },
    )

    LaunchedEffect(currentBitmap, prevBitmap, nextBitmap, forward, progress) {
        renderer.updateState(
            current = currentBitmap.asAndroidBitmap(),
            prev = prevBitmap?.asAndroidBitmap(),
            next = nextBitmap?.asAndroidBitmap(),
            forward = forward,
            progress = progress,
        )
        glView?.requestRender()
    }

    DisposableEffect(Unit) {
        onDispose {
            glView?.onPause()
        }
    }
}
