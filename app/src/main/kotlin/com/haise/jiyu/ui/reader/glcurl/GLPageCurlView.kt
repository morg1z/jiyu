package com.haise.jiyu.ui.reader.glcurl

import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * Compose obal nad [GLPageCurlRenderer] - port `karacken.curl` OpenGL efektu (viz
 * `GLPageCurlRenderer` dokumentace). Na rozdíl od originálu (`PageSurfaceView` s vlastním
 * `GestureDetector`) tenhle `GLSurfaceView` žádný dotek sám nezpracovává - jen vykresluje podle
 * [progress]/[forward], které řídí VOLAJÍCÍ (`MangaPageCurlReader`/`PageCurlNovelReader`) ze
 * svého vlastního, už existujícího gesto-stavu ([com.haise.jiyu.ui.reader.PageCurlState]).
 * Skutečné dotykové gesto (tažení/ťuknutí zón) tak zůstává beze změny v Compose vrstvě nad tímhle
 * viewem, přesně jako u dřívějšího `Canvas`+`drawPageCurl` přístupu, který tenhle view nahrazuje
 * pro [com.haise.jiyu.ui.reader.CurlStyle.ROLL].
 *
 * [mirrored] = RTL čtečka - ohyb se zrcadlí horizontálně (front se loupe zleva, left se
 * rozbaluje zprava) tak, aby odpovídal fyzické straně tahu prstem.
 */
@Composable
fun GLPageCurlView(
    currentBitmap: ImageBitmap,
    prevBitmap: ImageBitmap?,
    nextBitmap: ImageBitmap?,
    forward: Boolean,
    progress: Float,
    modifier: Modifier = Modifier,
    mirrored: Boolean = false,
) {
    val renderer = remember { GLPageCurlRenderer() }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

    AndroidView(
        modifier = modifier,
        // `update` bezi AZ PO factory - garanovane po prirazeni `glView` a po attachnuti
        // do okna. LaunchedEffect by mohl updateState+requestRender zavolat driv, nez
        // GLSurfaceView vubec existuje (request by se zahodil a prvni frame by zustal
        // zaspany az do dalsi zmeny stavu).
        update = { view ->
            renderer.updateState(
                current = currentBitmap.asAndroidBitmap(),
                prev = prevBitmap?.asAndroidBitmap(),
                next = nextBitmap?.asAndroidBitmap(),
                forward = forward,
                progress = progress,
                mirrored = mirrored,
            )
            view.requestRender()
        },
        factory = { context ->
            GLSurfaceView(context).apply {
                // GLSurfaceView je SurfaceView - ten se BEZ tohohle volani vykresluje na
                // samostatnem povrchu ZA oknem aplikace (diry v Compose UI), takže by byl
                // schovany za zbytkem obrazovky (staticka bitmapa aktualni stranky nad nim) a
                // cely efekt by pusobil jako by se vubec nerenderoval, presne jak to bylo videt
                // po nasazeni - zadna animace, jen skok na dalsi stranku po pusteni prstu.
                setZOrderOnTop(true)
                // Surface je mountnuty PERMANENTNE (stejne jako PageSurfaceView v originale),
                // takze v klidu musi byt pruhledny - TRANSLUCENT format + RGBA8888 EGL config
                // (alfa kanal) necha skrz transparentni clear (progress==0 -> renderer jen
                // cisti, nekresli stranky) prosvit zivou stranku pod nim. Bez alfa kanalu by
                // tu visela nepruhledna CERNA dira pres celou ctecku.
                holder.setFormat(PixelFormat.TRANSLUCENT)
                setEGLConfigChooser(8, 8, 8, 8, 16, 0)
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

    // GLSurfaceView se musí pozastavit i při odchodu appky do pozadí (ne jen při odchodu z
    // obrazovky), jinak renderovací vlákno běží dál; po návratu se zase probudí.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView?.onPause()
        }
    }
}
