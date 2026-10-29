package io.github.dshmobile.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.dshmobile.R

/**
 * 吉祥物：肥鱼。
 *
 * 之前这里是 Canvas 手画的 [Whale]，线条干净但跟角色立绘不是同一个人。
 * 现在直接用立绘，**白底在打包时就抠掉了**（`res/drawable-nodpi/feiyu.png`）——
 * 原图是纯白背景，直接贴到深色界面上会是一个白方块，所以不能原样塞进来。
 *
 * 竖版构图，所以只锁高度、宽度让它按比例走，不要用 `size()` 挤成正方形。
 */
@Composable
fun Feiyu(
    modifier: Modifier = Modifier,
    height: Dp = 132.dp,
) {
    Image(
        painter = painterResource(R.drawable.feiyu),
        contentDescription = "肥鱼",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(height),
    )
}
