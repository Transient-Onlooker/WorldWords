package com.example.wordapp.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color

import com.example.wordapp.SettingsManager

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

@Composable
fun WordAppTheme(
    // darkTheme parameter is no longer needed, it will be determined internally
    dynamicColor: Boolean = false, // Force disable to use our custom colors
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val themeSetting = SettingsManager.getThemeSetting(context)
    val useDarkTheme = when (themeSetting) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }

    val primaryColor = SettingsManager.getPrimaryColor(context)
    val secondaryContainerColor = SettingsManager.getSecondaryContainerColor(context)
    val primaryDarkColor = SettingsManager.getPrimaryDarkColor(context)
    val secondaryContainerDarkColor = SettingsManager.getSecondaryContainerDarkColor(context)

    val customLightColorScheme = lightColorScheme(
        primary = Color(primaryColor),
        secondaryContainer = Color(secondaryContainerColor)
    )

    val customDarkColorScheme = darkColorScheme(
        primary = Color(primaryDarkColor),
        secondaryContainer = Color(secondaryContainerDarkColor)
    )

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> customDarkColorScheme
        else -> customLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}