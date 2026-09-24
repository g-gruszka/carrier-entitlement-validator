package com.carrier.entitlement.validator.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carrier.entitlement.validator.data.network.CamaraClient
import com.carrier.entitlement.validator.data.network.Ts43Client
import com.carrier.entitlement.validator.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun ConnectionScreen(
    ts43Client: Ts43Client,
    camaraClient: CamaraClient
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var esUrl by remember { mutableStateOf(ts43Client.getBaseUrl()) }
    var ogwUrl by remember { mutableStateOf(camaraClient.getBaseUrl()) }

    var esHealthStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var ogwHealthStatus by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var isCheckingEs by remember { mutableStateOf(false) }
    var isCheckingOgw by remember { mutableStateOf(false) }

    fun copyToClipboard(text: String, label: String = "Comando") {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "$label copiado al portapapeles", Toast.LENGTH_SHORT).show()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tarjeta de Presets Rápidos
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Presets de Conectividad",
                    style = Typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            esUrl = "http://127.0.0.1:18080"
                            ogwUrl = "http://127.0.0.1:8081"
                            ts43Client.updateBaseUrl(esUrl)
                            camaraClient.updateBaseUrl(ogwUrl)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("ADB USB (127.0.0.1)", fontSize = 11.sp)
                    }
                    OutlinedButton(
                        onClick = {
                            esUrl = "http://172.25.0.50:8080"
                            ogwUrl = "http://172.25.0.50:8081"
                            ts43Client.updateBaseUrl(esUrl)
                            camaraClient.updateBaseUrl(ogwUrl)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Lab Directo", fontSize = 11.sp)
                    }
                }
            }
        }

        // Configuración Entitlement Server (TS.43)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Entitlement Server (GSMA TS.43 / ap2014)",
                    style = Typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = esUrl,
                    onValueChange = {
                        esUrl = it
                        ts43Client.updateBaseUrl(it)
                    },
                    label = { Text("Base URL Entitlement Server") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = DarkBorder
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            isCheckingEs = true
                            coroutineScope.launch {
                                esHealthStatus = ts43Client.checkHealth()
                                isCheckingEs = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        enabled = !isCheckingEs
                    ) {
                        if (isCheckingEs) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Health Check")
                        }
                    }

                    esHealthStatus?.let { (ok, msg) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (ok) SuccessGreen else ErrorRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (ok) "En línea" else "Error",
                                color = if (ok) SuccessGreen else ErrorRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                esHealthStatus?.let { (_, msg) ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = msg,
                        style = Typography.labelSmall,
                        color = TextMuted,
                        maxLines = 2
                    )
                }
            }
        }

        // Configuración OpenGateway (CAMARA)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Open Gateway (CAMARA Number Verification)",
                    style = Typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = ogwUrl,
                    onValueChange = {
                        ogwUrl = it
                        camaraClient.updateBaseUrl(it)
                    },
                    label = { Text("Base URL OpenGateway") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        focusedBorderColor = PrimaryBlue,
                        unfocusedBorderColor = DarkBorder
                    ),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            isCheckingOgw = true
                            coroutineScope.launch {
                                ogwHealthStatus = camaraClient.checkHealth()
                                isCheckingOgw = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        enabled = !isCheckingOgw
                    ) {
                        if (isCheckingOgw) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = TextPrimary)
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Health Check")
                        }
                    }

                    ogwHealthStatus?.let { (ok, msg) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (ok) Icons.Default.CheckCircle else Icons.Default.Error,
                                contentDescription = null,
                                tint = if (ok) SuccessGreen else ErrorRed,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                if (ok) "En línea" else "Error",
                                color = if (ok) SuccessGreen else ErrorRed,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                ogwHealthStatus?.let { (_, msg) ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = msg,
                        style = Typography.labelSmall,
                        color = TextMuted,
                        maxLines = 2
                    )
                }
            }
        }

        // Asistente de comandos ADB para el Host
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Asistente ADB Reverse (Consola del Host)",
                    style = Typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Ejecuta estos comandos en tu PC para rutear el tráfico del teléfono por el cable USB:",
                    style = Typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(12.dp))

                AdbCommandRow(
                    label = "Reverse Entitlement Server (18080)",
                    command = "adb reverse tcp:18080 tcp:18080",
                    onCopy = { copyToClipboard(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                AdbCommandRow(
                    label = "Reverse OpenGateway CAMARA (8081)",
                    command = "adb reverse tcp:8081 tcp:8081",
                    onCopy = { copyToClipboard(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                AdbCommandRow(
                    label = "Permiso USIM EAP-AKA sin Root App",
                    command = "adb shell pm grant com.carrier.entitlement.validator.debug android.permission.MODIFY_PHONE_STATE",
                    onCopy = { copyToClipboard(it) }
                )
            }
        }
    }
}

@Composable
private fun AdbCommandRow(
    label: String,
    command: String,
    onCopy: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(CodeBackground, RoundedCornerShape(8.dp))
            .padding(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = Typography.bodySmall, color = TextSecondary)
            IconButton(
                onClick = { onCopy(command) },
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    Icons.Default.ContentCopy,
                    contentDescription = "Copiar",
                    tint = PrimaryBlue,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = command,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = AccentCyan
        )
    }
}
