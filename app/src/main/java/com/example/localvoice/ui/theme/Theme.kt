package com.example.localvoice.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

val LoriTeal = Color(0xFF009488)
// La palette custom: OLED Black + Teal
private val CustomOledColorScheme = darkColorScheme(
    primary = LoriTeal,
    secondary = LoriTeal,
    tertiary = LoriTeal,
    background = Color.Black,
    surface = Color(0xFF121212), // Grigio scurissimo per distinguere le Card dallo sfondo
    surfaceVariant = Color(0xFF1A1A1A),
    onPrimary = Color.White,
    onBackground = Color.White,
    onSurface = Color.White
)

@Composable
fun LoriTheme(
    useSystemTheme: Boolean = true, // Parametro aggiunto
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    // Se l'utente vuole il sistema (e c'è Android 12+), usa il Material You dinamico
    val colorScheme = if (useSystemTheme && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicDarkColorScheme(context)
    } else {
        // Altrimenti forza i colori dell'app
        CustomOledColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}