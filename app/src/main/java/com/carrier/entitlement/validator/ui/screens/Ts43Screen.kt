package com.carrier.entitlement.validator.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SimCard
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carrier.entitlement.validator.data.model.EapChallengeData
import com.carrier.entitlement.validator.data.model.TemporaryTokenResult
import com.carrier.entitlement.validator.data.model.Ts43VerifyResult
import com.carrier.entitlement.validator.data.network.Ts43Client
import com.carrier.entitlement.validator.sim.EapAkaEngine
import com.carrier.entitlement.validator.sim.SimManager
import com.carrier.entitlement.validator.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun Ts43Screen(
    ts43Client: Ts43Client,
    simManager: SimManager
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    val simInfo = remember { simManager.getSimInfo() }

    // Parámetros de Identidad
    var imsi by remember { mutableStateOf(simInfo.subscriberId ?: "722340000000001") }
    var mcc by remember { mutableStateOf(simInfo.mcc ?: "722") }
    var mnc by remember { mutableStateOf(simInfo.mnc ?: "034") }
    var msisdnToVerify by remember { mutableStateOf(simInfo.phoneNumber ?: "541179999999") }
    var requestorId by remember { mutableStateOf("00000000-0000-4000-8000-0000000000b1") }

    val rootNai = remember(imsi, mcc, mnc) {
        val cleanImsi = imsi.trim()
        val m = mcc.trim().padStart(3, '0')
        val n = mnc.trim().padStart(3, '0')
        "0$cleanImsi@nai.epc.mnc$n.mcc$m.3gppnetwork.org"
    }

    // Estados de ejecución
    var round1Result by remember { mutableStateOf<EapChallengeData?>(null) }
    var round2Result by remember { mutableStateOf<TemporaryTokenResult?>(null) }
    var verifyResult by remember { mutableStateOf<Ts43VerifyResult?>(null) }

    var lastResHex by remember { mutableStateOf<String?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    var executionStatusText by remember { mutableStateOf<String?>(null) }
    var executionErrorText by remember { mutableStateOf<String?>(null) }

    fun executeRound1() {
        coroutineScope.launch {
            isExecuting = true
            executionStatusText = "Ejecutando Ronda 1 (EAP Init)..."
            executionErrorText = null
            try {
                val challenge = ts43Client.acquireTemporaryTokenRound1(rootNai)
                round1Result = challenge
                executionStatusText = "Ronda 1 Completada: Desafío EAP recibido."
            } catch (e: Exception) {
                executionErrorText = "Fallo en Ronda 1: ${e.message}"
            } finally {
                isExecuting = false
            }
        }
    }

    fun executeRound2() {
        val r1 = round1Result ?: return
        coroutineScope.launch {
            isExecuting = true
            executionStatusText = "Extrayendo desafío y autenticando con la SIM..."
            executionErrorText = null
            try {
                // 1. Extraer RAND y AUTN del paquete EAP
                val extracted = EapAkaEngine.parseEapChallenge(r1.eapRelayPacket)

                // 2. Ejecutar autenticación contra la SIM física
                val authRes = simManager.authenticateWithPhysicalUsim(extracted.randHex, extracted.autnHex)
                if (authRes.isSyncFailure) {
                    throw IllegalStateException("Fallo de sincronización USIM (AUTS: ${authRes.autsHex})")
                }
                lastResHex = authRes.resHex

                // 3. Construir paquete EAP-Response
                val eapResponseHex = EapAkaEngine.buildEapResponse(
                    eapId = extracted.eapId,
                    resHex = authRes.resHex,
                    macHex = extracted.macHex
                )

                // 4. Enviar Ronda 2 al Entitlement Server
                executionStatusText = "Enviando respuesta EAP al servidor..."
                val r2 = ts43Client.acquireTemporaryTokenRound2(
                    eapResponsePacketHex = eapResponseHex,
                    eapSession = r1.eapSession
                )
                round2Result = r2
                executionStatusText = "Ronda 2 Completada: TemporaryToken obtenido."
            } catch (e: Exception) {
                executionErrorText = "Fallo en Ronda 2: ${e.message}"
            } finally {
                isExecuting = false
            }
        }
    }

    fun executeRound3() {
        val r2 = round2Result ?: return
        coroutineScope.launch {
            isExecuting = true
            executionStatusText = "Validando número telefónico con TS.43..."
            executionErrorText = null
            try {
                val res = ts43Client.verifyPhoneNumber(
                    temporaryToken = r2.token,
                    msisdn = msisdnToVerify,
                    requestorId = requestorId
                )
                verifyResult = res
                executionStatusText = if (res.isMatch) "¡Validación exitosa! Coincidencia confirmada." else "Número no coincide."
            } catch (e: Exception) {
                executionErrorText = "Fallo en VerifyPhoneNumber: ${e.message}"
            } finally {
                isExecuting = false
            }
        }
    }

    fun executeFullE2E() {
        coroutineScope.launch {
            isExecuting = true
            executionErrorText = null
            executionStatusText = "[1/3] Iniciando Ronda 1..."
            try {
                // Paso 1
                val r1 = ts43Client.acquireTemporaryTokenRound1(rootNai)
                round1Result = r1

                // Paso 2
                executionStatusText = "[2/3] Autenticando con USIM física..."
                val extracted = EapAkaEngine.parseEapChallenge(r1.eapRelayPacket)
                val authRes = simManager.authenticateWithPhysicalUsim(extracted.randHex, extracted.autnHex)
                if (authRes.isSyncFailure) {
                    throw IllegalStateException("Fallo de sincronización USIM (AUTS: ${authRes.autsHex})")
                }
                lastResHex = authRes.resHex

                val eapResponseHex = EapAkaEngine.buildEapResponse(
                    eapId = extracted.eapId,
                    resHex = authRes.resHex,
                    macHex = extracted.macHex
                )

                val r2 = ts43Client.acquireTemporaryTokenRound2(eapResponseHex, r1.eapSession)
                round2Result = r2

                // Paso 3
                executionStatusText = "[3/3] Validando MSISDN contra ECS..."
                val res = ts43Client.verifyPhoneNumber(
                    temporaryToken = r2.token,
                    msisdn = msisdnToVerify,
                    requestorId = requestorId
                )
                verifyResult = res
                executionStatusText = if (res.isMatch) "✅ Flujo E2E completado con ÉXITO." else "⚠️ Flujo finalizado: MSISDN no coincide."
            } catch (e: Exception) {
                executionErrorText = "Error en el flujo E2E: ${e.message}"
            } finally {
                isExecuting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tarjeta de Identidad y NAI
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
                    Text("Identidad de Abonado (USIM)", style = Typography.titleMedium, color = TextPrimary)
                    TextButton(onClick = {
                        val fresh = simManager.getSimInfo()
                        if (fresh.subscriberId != null) imsi = fresh.subscriberId
                        if (fresh.mcc != null) mcc = fresh.mcc
                        if (fresh.mnc != null) mnc = fresh.mnc
                        if (fresh.phoneNumber != null) msisdnToVerify = fresh.phoneNumber
                        Toast.makeText(context, "Datos cargados desde la SIM física", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.SimCard, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Leer de SIM", fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = imsi,
                    onValueChange = { imsi = it },
                    label = { Text("IMSI") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = mcc,
                        onValueChange = { mcc = it },
                        label = { Text("MCC") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = mnc,
                        onValueChange = { mnc = it },
                        label = { Text("MNC") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                Spacer(modifier = Modifier.height(10.dp))
                Text("NAI Raíz Calculado (3GPP TS 23.003):", style = Typography.bodySmall, color = TextSecondary)
                Text(
                    text = rootNai,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = AccentCyan
                )
            }
        }

        // Botón de Ejecución E2E 1-Click
        Button(
            onClick = { executeFullE2E() },
            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            enabled = !isExecuting
        ) {
            if (isExecuting) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TextPrimary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Procesando...", fontWeight = FontWeight.Bold)
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(6.dp))
                Text("EJECUTAR VALIDACIÓN E2E (1-CLICK)", fontWeight = FontWeight.Bold)
            }
        }

        // Mensaje de Estado / Error
        executionStatusText?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = DarkSurfaceVariant),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(
                    text = it,
                    modifier = Modifier.padding(12.dp),
                    style = Typography.bodySmall,
                    color = TextPrimary
                )
            }
        }
        executionErrorText?.let {
            Card(
                colors = CardDefaults.cardColors(containerColor = ErrorRedBg),
                shape = RoundedCornerShape(8.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = ErrorRed)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = it, style = Typography.bodySmall, color = ErrorRed)
                }
            }
        }

        // Paso 1: Ronda 1 (Init)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Paso 1: Ronda 1 (AcquireTemporaryToken Init)", style = Typography.titleMedium, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Envía la identidad NAI y recibe el desafío RAND + AUTN del HSS.", style = Typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { executeRound1() },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    enabled = !isExecuting
                ) {
                    Text("Ejecutar Paso 1")
                }
                round1Result?.let { r1 ->
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("Session ID: ${r1.sessionId}", style = Typography.labelSmall, color = SuccessGreen)
                    Text("EAP Packet (Hex): ${r1.eapRelayPacket.take(32)}...", style = Typography.labelSmall, color = TextSecondary)
                }
            }
        }

        // Paso 2: Ronda 2 (Challenge-Response con USIM)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Paso 2: Ronda 2 (USIM Hardware + TemporaryToken)", style = Typography.titleMedium, color = TextPrimary)
                Spacer(modifier = Modifier.height(6.dp))
                Text("Pasa el reto a la SIM física (getIccAuthentication) y envía el RES.", style = Typography.bodySmall, color = TextSecondary)
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { executeRound2() },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    enabled = !isExecuting && round1Result != null
                ) {
                    Text("Ejecutar Paso 2")
                }
                lastResHex?.let { res ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("USIM RES (Hex): $res", style = Typography.labelSmall, color = AccentCyan)
                }
                round2Result?.let { r2 ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Token Emitido: ${r2.token}", style = Typography.labelSmall, color = SuccessGreen)
                }
            }
        }

        // Paso 3: VerifyPhoneNumber (TS.43 13.1.2)
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Paso 3: VerifyPhoneNumber (TS.43 Sección 13.1.2)", style = Typography.titleMedium, color = TextPrimary)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = msisdnToVerify,
                    onValueChange = { msisdnToVerify = it },
                    label = { Text("MSISDN a Contrastar") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = requestorId,
                    onValueChange = { requestorId = it },
                    label = { Text("Requestor ID") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { executeRound3() },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    enabled = !isExecuting && round2Result != null
                ) {
                    Text("Ejecutar Paso 3")
                }

                verifyResult?.let { res ->
                    Spacer(modifier = Modifier.height(12.dp))
                    val bg = if (res.isMatch) SuccessGreenBg else ErrorRedBg
                    val fg = if (res.isMatch) SuccessGreen else ErrorRed
                    Card(colors = CardDefaults.cardColors(containerColor = bg)) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (res.isMatch) Icons.Default.CheckCircle else Icons.Default.Error, contentDescription = null, tint = fg)
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (res.isMatch) "COINCIDENCIA EXACTA (OperationResult=1)" else "SIN COINCIDENCIA (OperationResult=0)",
                                    fontWeight = FontWeight.Bold,
                                    color = fg
                                )
                                Text("El HSS confirmó la validación de identidad.", style = Typography.bodySmall, color = TextPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}
