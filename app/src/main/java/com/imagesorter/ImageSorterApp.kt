package com.imagesorter

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.VideoFrameDecoder

/**
 * Crea el cargador de imágenes una sola vez: así el caché de la precarga sobrevive a los cambios de
 * pantalla. Con VideoFrameDecoder los videos muestran su primer cuadro como miniatura.
 */
class ImageSorterApp : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this).components { add(VideoFrameDecoder.Factory()) }.build()
}
