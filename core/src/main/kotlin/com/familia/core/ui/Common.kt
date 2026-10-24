package com.familia.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Fmt {
    private val loc = Locale("pt", "BR")
    fun dateTime(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return runCatching {
            if (iso.length == 10) LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd/MM/yyyy", loc))
            else Instant.parse(iso).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", loc))
        }.getOrDefault(iso)
    }
    fun day(date: String): String = runCatching {
        LocalDate.parse(date).format(DateTimeFormatter.ofPattern("EEE, dd/MM", loc))
    }.getOrDefault(date)
}

@Composable
fun FamiliaTheme(light: Color, dark: Color, content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = dark) else lightColorScheme(primary = light)
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
fun Pill(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50)) {
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp))
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun KeyValue(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = MaterialTheme.typography.bodyMedium)
        Text(v, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

val StatusLabels = mapOf(
    "none" to "Sem limpeza pendente", "needed" to "Limpeza necessária", "running" to "Limpeza em execução",
    "awaiting" to "Aguardando confirmação do subadmin", "rejected" to "Confirmação rejeitada — revisar", "confirmed" to "Limpeza confirmada",
)

fun parseColor(hex: String?, fallback: Color = Color(0xFF0E7490)): Color =
    try { Color(android.graphics.Color.parseColor(hex ?: "")) } catch (e: Exception) { fallback }
