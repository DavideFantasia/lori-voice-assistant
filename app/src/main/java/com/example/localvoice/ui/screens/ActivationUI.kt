package com.example.localvoice.ui.screens

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment

@Composable
fun AssistantOverlayScreen(
    transcriptionText: String,
    audioVolume: Float
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter // Spinge il contenuto in basso
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            // Arrotonda solo gli angoli superiori
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 24.dp, vertical = 32.dp)
                    .navigationBarsPadding() // Evita sovrapposizioni con la navbar di Android
            ) {
                Text(
                    text = transcriptionText.ifEmpty { "In ascolto..." },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(32.dp))
                LinearAudioIndicator(volume = audioVolume)
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun LinearAudioIndicator(volume: Float) {
    val animatedVolume by animateFloatAsState(
        targetValue = volume,
        animationSpec = tween(durationMillis = 100, easing = LinearEasing),
        label = "volume"
    )
    val indicatorColor = MaterialTheme.colorScheme.primary

    Canvas(modifier = Modifier.fillMaxWidth().height(24.dp)) {
        val centerY = size.height / 2
        val strokeThickness = 2.dp.toPx() + (animatedVolume * 12.dp.toPx())
        val horizontalPadding = (1f - animatedVolume) * (size.width / 4)

        drawLine(
            color = indicatorColor,
            start = Offset(horizontalPadding, centerY),
            end = Offset(size.width - horizontalPadding, centerY),
            strokeWidth = strokeThickness,
            cap = StrokeCap.Round
        )
    }
}