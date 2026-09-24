package com.carrier.entitlement.validator.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.carrier.entitlement.validator.data.model.CamaraVerifyResult
import com.carrier.entitlement.validator.data.network.CamaraClient
import com.carrier.entitlement.validator.ui.theme.*
import kotlinx.coroutines.launch

@Composable
fun CamaraScreen(
    camaraClient: CamaraClient
) {
    val coroutineScope = rememberCoroutineScope()
    val scrollState = rememberScrollState()

    var phoneNumber by remember { mutableStateOf("+541170000005") }
    var bearerToken by remember { mutableStateOf("dev-test-token") }
    var result by remember { mutableStateOf<CamaraVerifyResult?>(null) }
    var isExecuting by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = DarkSurface),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "CAMARA Number Verification (v2.1.0)",
                    style = Typography.titleMedium,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Verifica la coincidencia del número telefónico asociado al dispositivo frente al Gateway del Operador.",
                    style = Typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(14.dp))

                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("Número E.164 (con '+')") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = bearerToken,
                    onValueChange = { bearerToken = it },
                    label = { Text("Bearer Access Token (JWT / OIDC)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        coroutineScope.launch {
                            isExecuting = true
                            errorText = null
                            result = null
                            try {
                                result = camaraClient.verifyPhoneNumber(
                                    phoneNumber = phoneNumber,
                                    bearerToken = bearerToken
                                )
                            } catch (e: Exception) {
                                errorText = "Error al invocar OpenGateway: ${e.message}"
                            } finally {
                                isExecuting = false
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryBlue),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    enabled = !isExecuting
                ) {
                    if (isExecuting) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = TextPrimary)
                    } else {
                        Icon(Icons.Default.VerifiedUser, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("VERIFICAR CON CAMARA", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        errorText?.let {
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

        result?.let { res ->
            val isVerified = res.devicePhoneNumberVerified
            val cardBg = if (isVerified) SuccessGreenBg else if (res.statusCode == 200) DarkSurfaceVariant else ErrorRedBg
            val statusColor = if (isVerified) SuccessGreen else if (res.statusCode == 200) WarningOrange else ErrorRed

            Card(
                colors = CardDefaults.cardColors(containerColor = cardBg),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (isVerified) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (isVerified) "NÚMERO VERIFICADO" else "NO VERIFICADO / RECHAZADO",
                            style = Typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    Text("HTTP Status: ${res.statusCode}", style = Typography.bodySmall, color = TextPrimary)
                    res.xCorrelator?.let {
                        Text("x-correlator: $it", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = TextSecondary)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Respuesta del Gateway:", style = Typography.bodySmall, color = TextSecondary)
                    Text(
                        text = res.rawResponse,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = AccentCyan
                    )
                }
            }
        }
    }
}
