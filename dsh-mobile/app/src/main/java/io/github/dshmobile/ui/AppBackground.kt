package io.github.dshmobile.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage

/**
 * 全 App 的背景层。
 *
 * 以前每个页面自己画一层 `Palette.Bg`，现在统一收到这里：底色永远有，
 * 用户设了背景图就在上面再叠一张。所以自定义透明图（带 alpha 的 PNG）
 * 放进来会自然露出底色，而不是变成黑底或者白底。
 *
 * 图片支持模糊和不透明度：
 * - 模糊 0/8/16/28dp 四档。注意 `Modifier.blur` 只在 Android 12+ 生效，
 *   低版本自动退回不模糊（不会崩，但也就没有效果）。
 * - 不透明度越低，图片和底色混得越多。底色是跟着强调色染过的，
 *   所以同一个透明度在不同强调色下观感不同，这是有意的。
 *
 * 注意各页面的 `.background(Palette.Bg)` 都已去掉——留着会把这层盖死。
 */
@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Bg)
    ) {
        val uri = Palette.backgroundUri
        if (uri.isNotEmpty()) {
            val blurDp = when (Palette.bgBlur) {
                1 -> 8.dp
                2 -> 16.dp
                3 -> 28.dp
                else -> 0.dp
            }
            // Unbounded：模糊会把边缘像素往外抹，默认的矩形裁剪会在四周留一圈硬边。
            AsyncImage(
                model = Uri.parse(uri),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(Palette.bgAlpha)
                    .blur(blurDp, edgeTreatment = BlurredEdgeTreatment.Unbounded),
            )
        }
        content()
    }
}
