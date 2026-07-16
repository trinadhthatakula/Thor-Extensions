package com.valhalla.thor.ext.antivirus

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * A faithful copy of Thor's "Asgardian" theme so the automation extension's config UI (which runs in
 * this extension's OWN process and can't read Thor's Compose theme) matches Thor exactly. Thor passes
 * its resolved settings — theme mode, dynamic color, AMOLED — as CONFIGURE-intent extras; [ThemeArgs]
 * carries them and [AutomationTheme] applies the same logic as Thor's ThorTheme(). Keep the color
 * values here in sync with Thor presentation/theme/Color.kt + Theme.kt if that palette changes.
 */
data class ThemeArgs(
    val themeMode: String,   // "LIGHT" | "DARK" | "SYSTEM"
    val dynamicColor: Boolean,
    val amoled: Boolean,
)

// --- DARK ("Asgardian Terminal") palette ---
private val Primary = Color(0xfff0ffd7)
private val OnPrimary = Color(0xff4c672c)
private val PrimaryContainer = Color(0xffd5f6ab)
private val OnPrimaryContainer = Color(0xff445e25)
private val Secondary = Color(0xffc7c4dd)
private val OnSecondary = Color(0xff3f3e52)
private val SecondaryContainer = Color(0xff242436)
private val OnSecondaryContainer = Color(0xffa4a1b9)
private val Tertiary = Color(0xffc8bfff)
private val OnTertiary = Color(0xff3f3386)
private val TertiaryContainer = Color(0xffbaafff)
private val OnTertiaryContainer = Color(0xff35287c)
private val Error = Color(0xfffe7453)
private val OnError = Color(0xff450900)
private val ErrorContainer = Color(0xff881f05)
private val Background = Color(0xff0e0e0e)
private val OnBackground = Color(0xffe5e5e5)
private val Surface = Color(0xff0e0e0e)
private val OnSurface = Color(0xffe5e5e5)
private val SurfaceVariant = Color(0xff262626)
private val OnSurfaceVariant = Color(0xffababab)
private val Outline = Color(0xff757575)
private val OutlineVariant = Color(0xff484848)
private val InverseSurface = Color(0xfff9f9f9)
private val InverseOnSurface = Color(0xff555555)
private val InversePrimary = Color(0xff4c672c)
private val SurfaceTint = Color(0xfff0ffd7)
private val SurfaceContainerLowest = Color(0xff000000)
private val SurfaceContainerLow = Color(0xff131313)
private val SurfaceContainer = Color(0xff191919)
private val SurfaceContainerHigh = Color(0xff1f1f1f)
private val SurfaceContainerHighest = Color(0xff262626)

// --- LIGHT ("Asgardian Technical Alchemist") palette ---
private val LightSurface = Color(0xfff8faf3)
private val LightSurfaceContainer = Color(0xffedefe8)
private val LightSurfaceContainerHighest = Color(0xffe1e3dd)
private val LightSurfaceContainerHigh = Color(0xffe7e9e2)
private val LightSurfaceContainerLow = Color(0xfff2f4ed)
private val LightSurfaceContainerLowest = Color(0xffffffff)
private val LightPrimary = Color(0xff354e15)
private val LightOnPrimary = Color(0xffffffff)
private val LightPrimaryContainer = Color(0xff4c662b)
private val LightOnPrimaryContainer = Color(0xfff0ffd7)
private val LightSecondary = Color(0xff55624c)
private val LightOnSecondary = Color(0xffffffff)
private val LightSecondaryContainer = Color(0xffd9e7cb)
private val LightOnSecondaryContainer = Color(0xff131f0d)
private val LightTertiary = Color(0xff66355d)
private val LightOnTertiary = Color(0xffffffff)
private val LightTertiaryContainer = Color(0xfff8d8ee)
private val LightOnTertiaryContainer = Color(0xff2d112b)
private val LightOnSurface = Color(0xff191c18)
private val LightOnSurfaceVariant = Color(0xff43493e)
private val LightOutline = Color(0xff74796d)
private val LightOutlineVariant = Color(0xffc3c8bc)
private val LightError = Color(0xffba1a1a)
private val LightOnError = Color(0xffffffff)
private val LightErrorContainer = Color(0xffffdad6)

private val AsgardianDarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
    inverseSurface = InverseSurface,
    inverseOnSurface = InverseOnSurface,
    inversePrimary = InversePrimary,
    surfaceTint = SurfaceTint,
    background = Background,
    surfaceContainerLowest = SurfaceContainerLowest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
)

private val AsgardianLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightOnPrimary,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightSecondary,
    onSecondary = LightOnSecondary,
    secondaryContainer = LightSecondaryContainer,
    onSecondaryContainer = LightOnSecondaryContainer,
    tertiary = LightTertiary,
    onTertiary = LightOnTertiary,
    tertiaryContainer = LightTertiaryContainer,
    onTertiaryContainer = LightOnTertiaryContainer,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceContainer,
    onSurfaceVariant = LightOnSurfaceVariant,
    outline = LightOutline,
    outlineVariant = LightOutlineVariant,
    background = LightSurface,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
)

/** Pure-black override for AMOLED dark, matching ThorTheme. */
private fun ColorScheme.amoled(): ColorScheme =
    copy(background = Color.Black, surface = Color.Black, surfaceVariant = Color.Black)

/**
 * Applies Thor's theme from the launch [args]. Mirrors ThorTheme(): SYSTEM resolves against the
 * device night config here (so "follow system" is live), dynamic color is used on Android 12+ when
 * Thor has it on, and AMOLED forces pure black in dark. Falls back to Thor's defaults when [args]
 * is null (extension opened directly, e.g. via adb).
 */
@Composable
fun AntivirusTheme(args: ThemeArgs?, content: @Composable () -> Unit) {
    val dark = when (args?.themeMode) {
        "LIGHT" -> false
        "DARK" -> true
        else -> isSystemInDarkTheme() // SYSTEM or unknown
    }
    val dynamic = args?.dynamicColor == true
    val amoled = args?.amoled == true
    val ctx = LocalContext.current

    val colorScheme = when {
        dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(ctx).let { if (amoled) it.amoled() else it }
            else dynamicLightColorScheme(ctx)
        dark -> if (amoled) AsgardianDarkColorScheme.amoled() else AsgardianDarkColorScheme
        else -> AsgardianLightColorScheme
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
