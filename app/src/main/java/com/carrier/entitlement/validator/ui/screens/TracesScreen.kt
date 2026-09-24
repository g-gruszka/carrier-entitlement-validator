package com.carrier.entitlement.validator.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carrier.entitlement.validator.data.model.TraceEntry
import com.carrier.entitlement.validator.data.repository.TraceRepository
import com.carrier.entitlement.validator.ui.theme.*

@Composable
fun TracesScreen() {
    val context = LocalContext.current
    val traces by TraceRepository.traces.collectAsState()

    fun copyToClipboard(text: String, label: String = "Contenido") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copiado", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Visor Wire-Level HTTP", style = Typography.titleMedium, color = TextPrimary)
                Text("${traces.size} transacciones registradas", style = Typography.bodySmall, color = TextSecondary)
            }
            if (traces.isNotEmpty()) {
                IconButton(onClick = { TraceRepository.clear() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Limpiar", tint = TextSecondary)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (traces.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No hay trazas registradas todavía.\nEjecuta una prueba TS.43 o CAMARA.", style = Typography.bodyMedium, color = TextMuted)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(traces, key = { it.id }) { trace ->
                    TraceCard(trace = trace, onCopy = { copyToClipboard(it, "Comando cURL") })
                }
            }
        }
    }
}

@Composable
private fun TraceCard(
    trace: TraceEntry,
    onCopy: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    val statusColor = when {
        trace.responseCode in 200..299 -> SuccessGreen
        trace.responseCode in 400..499 -> ErrorRed
        trace.responseCode >= 500 -> WarningOrange
        else -> TextMuted
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = DarkSurface),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .clickable { expanded = !expanded }
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = statusColor.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = if (trace.responseCode > 0) "${trace.responseCode}" else "ERR",
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(trace.method, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = AccentCyan)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(trace.tag, style = Typography.labelSmall, color = TextMuted)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${trace.durationMs} ms", fontSize = 11.sp, color = TextSecondary)
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = TextSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = trace.url,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = TextPrimary,
                maxLines = if (expanded) Int.MAX_VALUE else 1
            )

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(color = DarkBorder, thickness = 1.dp)
                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Hora: ${trace.formattedTime}", style = Typography.labelSmall, color = TextMuted)
                        TextButton(
                            onClick = { onCopy(trace.toCurl()) },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Copiar cURL", fontSize = 11.sp)
                        }
                    }

                    if (trace.requestHeaders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Request Headers:", style = Typography.bodySmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                        trace.requestHeaders.forEach { (k, v) ->
                            Text("$k: $v", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
                        }
                    }

                    trace.requestBody?.let { body ->
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Request Body:", style = Typography.bodySmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                        Text(body, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = AccentCyan)
                    }

                    if (trace.responseHeaders.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Response Headers:", style = Typography.bodySmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                        trace.responseHeaders.forEach { (k, v) ->
                            Text("$k: $v", fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextMuted)
                        }
                    }

                    trace.responseBody?.let { body ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Response Body:", style = Typography.bodySmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                        Text(body, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = TextPrimary)
                    }

                    trace.errorMessage?.let { err ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Error: $err", style = Typography.bodySmall, color = ErrorRed)
                    }
                }
            }
        }
    }
}
