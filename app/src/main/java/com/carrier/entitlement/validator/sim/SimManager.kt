package com.carrier.entitlement.validator.sim

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.carrier.entitlement.validator.data.model.EapAuthResult
import java.io.BufferedReader
import java.io.InputStreamReader

data class SimCardInfo(
    val isPresent: Boolean,
    val simState: String,
    val subscriberId: String?, // IMSI
    val mccMnc: String?,
    val mcc: String?,
    val mnc: String?,
    val carrierName: String?,
    val phoneNumber: String?,
    val hasPhoneStatePermission: Boolean,
    val hasModifyPhoneStatePermission: Boolean,
    val rootAvailable: Boolean
) {
    fun buildRootNai(): String {
        val cleanImsi = subscriberId?.trim() ?: "722340000000001"
        val m = mcc?.trim()?.padStart(3, '0') ?: "722"
        val n = mnc?.trim()?.padStart(3, '0') ?: "034"
        return "0$cleanImsi@nai.epc.mnc$n.mcc$m.3gppnetwork.org"
    }
}

class SimManager(private val context: Context) {

    private val telephonyManager: TelephonyManager? =
        context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager

    fun isRootAvailable(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("which", "su"))
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val line = reader.readLine()
            process.waitFor()
            !line.isNullOrBlank()
        } catch (e: Exception) {
            false
        }
    }

    fun grantModifyPhoneStateViaSu(): Pair<Boolean, String> {
        val pkgName = context.packageName
        val cmd = "pm grant $pkgName android.permission.MODIFY_PHONE_STATE"
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val exitCode = process.waitFor()
            val error = errReader.readText()
            val output = outReader.readText()
            if (exitCode == 0) {
                Pair(true, "Permiso concedido exitosamente vía SU.")
            } else {
                Pair(false, "Fallo al ejecutar su: $error $output (Exit code: $exitCode)")
            }
        } catch (e: Exception) {
            Pair(false, "Excepción ejecutando su: ${e.message}")
        }
    }

    @SuppressLint("HardwareIds")
    fun getSimInfo(): SimCardInfo {
        val tm = telephonyManager
        val hasReadPhoneState = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val hasModifyPhoneState = ContextCompat.checkSelfPermission(
            context,
            "android.permission.MODIFY_PHONE_STATE"
        ) == PackageManager.PERMISSION_GRANTED

        if (tm == null) {
            return SimCardInfo(
                isPresent = false,
                simState = "TELEPHONY_NOT_AVAILABLE",
                subscriberId = null,
                mccMnc = null,
                mcc = null,
                mnc = null,
                carrierName = null,
                phoneNumber = null,
                hasPhoneStatePermission = hasReadPhoneState,
                hasModifyPhoneStatePermission = hasModifyPhoneState,
                rootAvailable = isRootAvailable()
            )
        }

        val stateStr = when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
            else -> "UNKNOWN (${tm.simState})"
        }

        var imsi: String? = null
        var phone: String? = null

        if (hasReadPhoneState) {
            try {
                imsi = tm.subscriberId
            } catch (ignored: Exception) {}

            try {
                phone = tm.line1Number
            } catch (ignored: Exception) {}
        }

        val operatorNumeric = tm.simOperator // MCC+MNC (ej: 722034 o 72234)
        var mcc: String? = null
        var mnc: String? = null
        if (!operatorNumeric.isNullOrEmpty() && operatorNumeric.length >= 5) {
            mcc = operatorNumeric.substring(0, 3)
            mnc = operatorNumeric.substring(3)
        }

        val carrierName = tm.simOperatorName

        return SimCardInfo(
            isPresent = tm.simState != TelephonyManager.SIM_STATE_ABSENT,
            simState = stateStr,
            subscriberId = imsi,
            mccMnc = operatorNumeric,
            mcc = mcc,
            mnc = mnc,
            carrierName = carrierName,
            phoneNumber = phone,
            hasPhoneStatePermission = hasReadPhoneState,
            hasModifyPhoneStatePermission = hasModifyPhoneState,
            rootAvailable = isRootAvailable()
        )
    }

    /**
     * Ejecuta el comando de autenticación EAP-AKA contra la tarjeta SIM física
     * utilizando TelephonyManager.getIccAuthentication (APPTYPE_USIM, AUTHTYPE_EAP_AKA).
     */
    fun authenticateWithPhysicalUsim(randHex: String, autnHex: String): EapAuthResult {
        val tm = telephonyManager ?: throw IllegalStateException("TelephonyManager no está disponible en este dispositivo.")

        // 1. Construir el reto en formato 3GPP TS 31.102 Base64
        val challengeBase64 = EapAkaEngine.buildIccAuthChallenge(randHex, autnHex)

        // 2. Invocar getIccAuthentication
        val base64Response: String? = try {
            tm.getIccAuthentication(
                TelephonyManager.APPTYPE_USIM,
                TelephonyManager.AUTHTYPE_EAP_AKA,
                challengeBase64
            )
        } catch (se: SecurityException) {
            throw SecurityException(
                "Permiso denegado al invocar getIccAuthentication (requiere MODIFY_PHONE_STATE o Carrier Privileges). " +
                "En dispositivos con root puedes usar el botón 'Conceder Permiso vía Root'.",
                se
            )
        }

        if (base64Response.isNullOrBlank()) {
            throw IllegalStateException("La tarjeta USIM devolvió una respuesta vacía al desafío AKA.")
        }

        // 3. Parsear la respuesta Base64 de la USIM
        return EapAkaEngine.parseIccAuthResponse(base64Response)
    }
}
