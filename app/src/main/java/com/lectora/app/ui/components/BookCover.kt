package com.lectora.app.ui.components

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val coverCache = LruCache<String, ImageBitmap>(64)

@Composable
fun BookCover(path: String?, title: String, modifier: Modifier = Modifier) {
    val bitmap by produceState(initialValue = path?.let { coverCache.get(it) }, path) {
        if (path == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 300) sample *= 2
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?.asImageBitmap()
                    ?.also { coverCache.put(path, it) }
            }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(6.dp)
    val current = bitmap
    if (current != null) {
        Image(current, contentDescription = title, contentScale = ContentScale.Crop, modifier = modifier.clip(shape))
    } else {
        val hue = (title.hashCode() and 0x7FFFFFFF) % 360
        Box(
            modifier
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.hsv(hue.toFloat(), 0.35f, 0.55f), Color.hsv(hue.toFloat(), 0.45f, 0.35f))
                    )
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.titleSmall,
                fontFamily = FontFamily.Serif,
                textAlign = TextAlign.Center,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}
