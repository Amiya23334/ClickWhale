package io.github.dshmobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 主题档位。SYSTEM 跟随系统深浅色，LIGHT/DARK 是用户手动锁死。 */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/**
 * 强调色预设。
 *
 * 两点经验：
 * ① 必须跨越色相和明度。第一版给的全是同亮度同饱和度的柔和色，
 *    放在深色底的小面积上（一个圆点、两个字）根本分不出来。
 * ② 光换 Accent 不够——它只影响按钮和链接那几处，切换后整体观感几乎不变。
 *    所以底色也跟着染一点（见 [Palette.Bg]），整套界面的气氛才会变。
 */
enum class AccentPreset(
    val label: String,
    val dark: Color,
    val light: Color,
) {
    BLUE("蓝", Color(0xFF5B9DFF), Color(0xFF2C6BE0)),
    TEAL("青", Color(0xFF2DD4BF), Color(0xFF0D9488)),
    GREEN("绿", Color(0xFF4ADE80), Color(0xFF1BA05A)),
    AMBER("琥珀", Color(0xFFFFC531), Color(0xFFB78800)),
    ORANGE("橙", Color(0xFFFF9F45), Color(0xFFD2691E)),
    RED("红", Color(0xFFFF5C5C), Color(0xFFD62E2E)),
    PINK("粉", Color(0xFFF472B6), Color(0xFFDB2777)),
    PURPLE("紫", Color(0xFFC084FC), Color(0xFF7E22CE)),
}

/** 字体档位。都用系统自带的三个族，不打包字体文件（APK 会大好几 MB）。 */
enum class FontPreset(val label: String, val family: androidx.compose.ui.text.font.FontFamily) {
    SYSTEM("系统", androidx.compose.ui.text.font.FontFamily.Default),
    SERIF("衬线", androidx.compose.ui.text.font.FontFamily.Serif),
    MONO("等宽", androidx.compose.ui.text.font.FontFamily.Monospace),

    /** 用户自己选的 ttf/otf。实际字体见 [Palette.fontFamily]，这里的 family 只是兜底。 */
    CUSTOM("自定义", androidx.compose.ui.text.font.FontFamily.Default),
}

/**
 * 配色与尺寸。
 *
 * 风格定为「深色克制」：层级靠间距和字号区分，不用边框和阴影。
 * 理由：手机要滚长对话，玻璃拟态每层实时模糊会掉帧，而且气泡叠模糊背景
 * 对比度会糊，代码块尤其看不清楚。日间模式沿用同一套克制原则，
 * 只是把「深底浅字」翻成「浅底深字」，对比关系不变。
 *
 * ## 为什么不是一堆常量
 *
 * 全 App 有一百多处直接写 `Palette.Xxx`。要做日夜切换，最干净的是
 * CompositionLocal，但那要把每一处都改成 `LocalColors.current.Xxx`。
 * 这里改成「每个色读一个 MutableState 的 getter」：调用点一行不用动，
 * 读状态时 Compose 自动建立订阅，切换就是改一个变量的事。
 * 代价是它是全局可变状态，不适合多窗口/多主题并存——这个 App 不需要。
 */
object Palette {
    /** 当前档位。改它会触发所有读到配色的界面重组。 */
    var mode by mutableStateOf(ThemeMode.SYSTEM)

    /**
     * 系统当前是否深色。由 Compose 层用 `isSystemInDarkTheme()` 同步进来——
     * 这个类不是 @Composable，拿不到那个值，只能被动接收。
     */
    var systemDark by mutableStateOf(true)

    /** 实际是不是深色。所有颜色分支都读它，所以跟随系统时也能自动跟。 */
    val dark: Boolean
        get() = when (mode) {
            ThemeMode.DARK -> true
            ThemeMode.LIGHT -> false
            ThemeMode.SYSTEM -> systemDark
        }

    // ── 夜间（原来那套） ──
    private val dBg = Color(0xFF141416)
    private val dSurface = Color(0xFF1E1E22)
    private val dSurfaceHigh = Color(0xFF26262C)
    private val dTextPrimary = Color(0xFFEDEDF0)
    private val dTextSecondary = Color(0xFF8A8A93)
    private val dTextTertiary = Color(0xFF5A5A63)
    private val dAccent = Color(0xFF5B9DFF)
    private val dAccentWarm = Color(0xFFFFA94D)
    private val dDivider = Color(0xFF2A2A30)
    private val dError = Color(0xFFFF6B6B)
    private val dOnline = Color(0xFF4ADE80)
    private val dOffline = Color(0xFF6B6B75)
    private val dCodeBg = Color(0xFF121214)

    // ── 日间 ──
    // 底色不用纯白（#FFF 大面积很刺眼），用带一点点冷调的灰白；
    // 卡片反而用纯白浮起来，层级关系跟夜间正好反过来。
    private val lBg = Color(0xFFF5F6F8)
    private val lSurface = Color(0xFFFFFFFF)
    private val lSurfaceHigh = Color(0xFFEAECF0)
    private val lTextPrimary = Color(0xFF1B1C21)
    private val lTextSecondary = Color(0xFF5C5E68)
    private val lTextTertiary = Color(0xFF989AA4)
    // 夜间那支蓝是给深底用的，白底上偏亮、对比度不够，日间压深一档
    private val lAccent = Color(0xFF2C6BE0)
    private val lAccentWarm = Color(0xFFD97A26)
    private val lDivider = Color(0xFFE2E4EA)
    private val lError = Color(0xFFD33A3A)
    private val lOnline = Color(0xFF1BA05A)
    private val lOffline = Color(0xFFA4A6B0)
    private val lCodeBg = Color(0xFFF0F1F4)

    /**
     * 页面底色：在基础底色里掺一点点强调色。
     *
     * 比例是试出来的——掺太多会脏（浅色模式尤其），太少又感觉不到。
     * 深色 0.055、浅色 0.03，切换强调色时整个界面会跟着换气氛，
     * 而不只是几个按钮变色。
     */
    val Bg: Color
        get() = if (dark) {
            lerp(dBg, accent.dark, 0.055f)
        } else {
            lerp(lBg, accent.light, 0.03f)
        }

    /** 卡片同样掺一点，但更少，保证它还能从底色上浮起来 */
    val Surface: Color
        get() = if (dark) {
            lerp(dSurface, accent.dark, 0.035f)
        } else {
            lerp(lSurface, accent.light, 0.015f)
        }
    val SurfaceHigh: Color get() = if (dark) dSurfaceHigh else lSurfaceHigh
    val TextPrimary: Color get() = if (dark) dTextPrimary else lTextPrimary
    val TextSecondary: Color get() = if (dark) dTextSecondary else lTextSecondary
    val TextTertiary: Color get() = if (dark) dTextTertiary else lTextTertiary
    /** 当前强调色档位。改它所有用 Accent 的地方跟着变。 */
    var accent by mutableStateOf(AccentPreset.BLUE)

    /** 当前字体档位。由 Root() 通过 CompositionLocal 作用到所有 Text。 */
    var font by mutableStateOf(FontPreset.SYSTEM)

    /**
     * 自定义背景图 URI（content://…）。空串表示不画。
     *
     * 图片叠在底色之上，所以透明 PNG 的透明部分会露出底色——
     * 这正是「自定义透明图」想要的效果。
     */
    var backgroundUri by mutableStateOf("")

    /** 背景图模糊档：0 不模糊 / 1 轻 / 2 中 / 3 重 */
    var bgBlur by mutableStateOf(0)

    /** 背景图不透明度：1.0 全显，越低越让底色和图片混在一起 */
    var bgAlpha by mutableStateOf(1f)

    /** 用户选的字体文件路径（App 私有目录里的副本）和显示名 */
    var customFontPath by mutableStateOf("")
    var customFontName by mutableStateOf("")

    // FontFamily(Font(File)) 每次调用都会新建对象，而 fontFamily 会在每次重组时被读到，
    // 不缓存的话等于每帧都去解析一遍字体文件。按路径缓存一份就够。
    private var cachedFontPath = ""
    private var cachedFontFamily: androidx.compose.ui.text.font.FontFamily =
        androidx.compose.ui.text.font.FontFamily.Default

    /**
     * 当前实际使用的字族。
     *
     * 必须是 MutableState：以前这里是普通 getter，`setFont()` 改了 `font` 之后
     * 读它的 CompositionLocalProvider 不会重组，切字体看着就没反应（只在启动时算了一次）。
     * CUSTOM 档现算用户字体，解析失败退回系统字体。
     */
    var fontFamily by mutableStateOf(resolveFontFamily())
        private set

    /** 档位或字体文件变了之后调用，重算并把新值发布出去。 */
    fun refreshFontFamily() {
        fontFamily = resolveFontFamily()
    }

    private fun resolveFontFamily(): androidx.compose.ui.text.font.FontFamily {
        if (font != FontPreset.CUSTOM) return font.family
        val path = customFontPath
        if (path.isEmpty()) return androidx.compose.ui.text.font.FontFamily.Default
        if (path == cachedFontPath) return cachedFontFamily
        cachedFontFamily = runCatching {
            androidx.compose.ui.text.font.FontFamily(
                androidx.compose.ui.text.font.Font(java.io.File(path))
            )
        }.getOrDefault(androidx.compose.ui.text.font.FontFamily.Default)
        cachedFontPath = path
        return cachedFontFamily
    }

    val Accent: Color get() = if (dark) accent.dark else accent.light
    /** 强调色在深/浅底上都先按夜间的值算，用于需要固定色的场合（如状态点） */
    val AccentWarm: Color get() = if (dark) dAccentWarm else lAccentWarm
    val Divider: Color get() = if (dark) dDivider else lDivider
    val Error: Color get() = if (dark) dError else lError
    val Online: Color get() = if (dark) dOnline else lOnline
    val Offline: Color get() = if (dark) dOffline else lOffline
    /** 代码块底色：比卡片再暗/亮一档，才能跟正文分开 */
    val CodeBg: Color get() = if (dark) dCodeBg else lCodeBg

    /** 顶栏/状态栏那层：日间比页面底色再白一点，夜间跟底色一致 */
    val BarBg: Color get() = if (dark) dBg else Color(0xFFFFFFFF)
}

object Dims {
    val screenPad = 16.dp
    val cardRadius = 16.dp
    val bubbleRadius = 18.dp
    val rowGap = 10.dp
    val sectionGap = 24.dp

    val titleSize = 17.sp
    val bodySize = 15.sp
    val metaSize = 12.sp
    val codeSize = 13.sp
}
