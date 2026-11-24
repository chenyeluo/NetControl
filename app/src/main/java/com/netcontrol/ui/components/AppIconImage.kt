package com.netcontrol.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

/**
 * 120Hz 零抖动 LRU 应用图标缓存：
 * - 同步内存命中（getCached）：滚回屏幕时首帧直出，零协程开销、零占位闪烁、零二次重组；
 * - 450 条 84px 缩略图缓存（峰值约 12MB），覆盖全部常用应用不反复淘汰；
 * - 限流 3 并发解码（limitedParallelism），快速 Fling 时不阻塞 Binder 线程池与 GC。
 */
@OptIn(ExperimentalCoroutinesApi::class)
object AppIconCache {
    private const val ICON_PX = 84
    private val cache = LruCache<String, ImageBitmap>(450)
    private val iconDispatcher = Dispatchers.IO.limitedParallelism(3)

    fun getCached(packageName: String): ImageBitmap? = cache.get(packageName)

    suspend fun load(context: Context, packageName: String): ImageBitmap? {
        cache.get(packageName)?.let { return it }
        return withContext(iconDispatcher) {
            cache.get(packageName)?.let { return@withContext it }
            try {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bmp = drawable.toScaledBitmap(ICON_PX).asImageBitmap()
                cache.put(packageName, bmp)
                bmp
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun Drawable.toScaledBitmap(sizePx: Int): Bitmap {
        if (this is BitmapDrawable && bitmap != null && !bitmap.isRecycled) {
            return Bitmap.createScaledBitmap(bitmap, sizePx, sizePx, true)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        setBounds(0, 0, canvas.width, canvas.height)
        draw(canvas)
        return bitmap
    }
}

private val IconClipShape = RoundedCornerShape(10.dp)

@Composable
fun AppIconImage(
    packageName: String,
    appName: String,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val initialCached = remember(packageName) { AppIconCache.getCached(packageName) }
    var bitmap by remember(packageName) { mutableStateOf(initialCached) }

    if (bitmap == null) {
        LaunchedEffect(packageName) {
            bitmap = AppIconCache.load(context, packageName)
        }
    }

    val currentBitmap = bitmap
    if (currentBitmap != null) {
        Image(
            bitmap = currentBitmap,
            contentDescription = appName,
            modifier = modifier
                .size(size)
                .clip(IconClipShape)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(IconClipShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = appName.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}
