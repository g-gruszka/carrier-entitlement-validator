package com.carrier.entitlement.validator.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
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
import com.carrier.entitlement.validator.sim.SimCardInfo
import com.carrier.entitlement.validator.sim.SimManager
import com.carrier.entitlement.validator.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun SimHardwareScreen(
    simManager: SimManager
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var simInfo by remember { mutableStateOf(simManager.getSimInfo()) }
    var isGrantingRoot by remember { mutableStateOf(false) }

    fun refresh() {
        simInfo = simManager.getSimInfo()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tarjeta de SIM Card
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tarjeta SIM Física", style = Typography.titleMedium, color = TextPrimary)
                    IconButton(onClick = { refresh() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refrescar", tint = PrimaryBlue)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                InfoRow("Estado SIM", simInfo.simState)
                InfoRow("Operador / SPN", simInfo.carrierName ?: "N/A")
                InfoRow("MCC / MNC", "${simInfo.mcc ?: "---"} / ${simInfo.mnc ?: "---"}")
                InfoRow("IMSI (Subscriber ID)", simInfo.subscriberId ?: "No disponible (sin permiso READ_PHONE_STATE)")
                InfoRow("Línea (MSISDN en SIM)", simInfo.phoneNumber ?: "No programado en SIM (EF_MSISDN)")
            }
        }

        // Tarjeta de Permisos y Root
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Privilegios y Acceso Criptográfico", style = Typography.titleMedium, color = TextPrimary)
                Spacer(modifier = Modifier.height(12.dp))

                PermissionRow("READ_PHONE_STATE", simInfo.hasPhoneStatePermission)
                Spacer(modifier = Modifier.height(6.dp))
                PermissionRow("MODIFY_PHONE_STATE (USIM EAP-AKA)", simInfo.hasModifyPhoneStatePermission)
                Spacer(modifier = Modifier.height(6.dp))
                PermissionRow("Dispositivo con Binario 'su' (Root)", simInfo.rootAvailable)

                Spacer(modifier = Modifier.height(14.dp))

                if (simInfo.rootAvailable && !simInfo.hasModifyPhoneStatePermission) {
                    Button(
                        onClick = {
                            isGrantingRoot = true
                            coroutineScope.launch {
                                val (ok, msg) = simManager.grantModifyPhoneStateViaSu()
                                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                                refresh()
                                isGrantingRoot = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isGrantingRoot
                    ) {
                        Icon(Icons.Default.AdminPanelSettings, contentDescription = null)
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("CONCEDER PERMISO USIM VÍA ROOT (SU)", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Tarjeta de Identificadores del Dispositivo
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Identificadores de Terminal (TS.43)", style = Typography.titleMedium, color = TextPrimary)
                Spacer(modifier = Modifier.height(10.dp))

                InfoRow("Fabricante (terminal_vendor)", Build.MANUFACTURER)
                InfoRow("Modelo (terminal_model)", Build.MODEL)
                InfoRow("Dispositivo", Build.DEVICE)
                InfoRow("Versión Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                InfoRow("Build ID (terminal_sw_version)", Build.DISPLAY)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, style = Typography.bodySmall, color = TextSecondary)
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = TextPrimary)
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = Typography.bodySmall, color = TextPrimary)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (granted) Icons.Default.CheckCircle else Icons.Default.Error,
                contentDescription = null,
                tint = if (granted) SuccessGreen else ErrorRed,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                if (granted) "Concedido" else "No concedido",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (granted) SuccessGreen else ErrorRed
            )
        }
    }
}
