package com.carrier.entitlement.validator.sim

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.carrier.entitlement.validator.data.model.EapAuthResult
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

data class SimCardInfo(
    val isPresent: Boolean,
    val simState: String,
    val subscriberId: String?, // IMSI
    val mccMnc: String?,
    val mcc: String?,
    val mnc: String?,
    val carrierName: String?,
    val phoneNumber: String?,
    val slotIndex: Int = 0,
    val hasPhoneStatePermission: Boolean,
    val hasModifyPhoneStatePermission: Boolean,
    val rootAvailable: Boolean,
    val diagnosisMsg: String? = null
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

    private val subscriptionManager: SubscriptionManager? =
        context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

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
        val cmds = listOf(
            "pm grant $pkgName android.permission.READ_PHONE_STATE",
            "pm grant $pkgName android.permission.MODIFY_PHONE_STATE",
            "pm grant $pkgName android.permission.READ_PRIVILEGED_PHONE_STATE"
        )
        return try {
            val combinedCmd = cmds.joinToString(" && ")
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", combinedCmd))
            val errReader = BufferedReader(InputStreamReader(process.errorStream))
            val outReader = BufferedReader(InputStreamReader(process.inputStream))
            val exitCode = process.waitFor()
            val error = errReader.readText()
            val output = outReader.readText()
            if (exitCode == 0) {
                Pair(true, "Permisos concedidos exitosamente vía SU.")
            } else {
                Pair(false, "Fallo al ejecutar su: $error $output (Exit code: $exitCode)")
            }
        } catch (e: Exception) {
            Pair(false, "Excepción ejecutando su: ${e.message}")
        }
    }

    fun queryImsiViaRoot(): String? {
        // Método 1: dumpsys iphonesubinfo
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys iphonesubinfo"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val text = reader.readText()
            proc.waitFor()
            val matcher = Pattern.compile("Subscriber ID\\s*=\\s*([0-9]{14,16})", Pattern.CASE_INSENSITIVE).matcher(text)
            if (matcher.find()) {
                val imsi = matcher.group(1)
                if (!imsi.isNullOrBlank()) return imsi
            }
        } catch (ignored: Exception) {}

        // Método 2: content query telephony siminfo
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "content query --uri content://telephony/siminfo --projection imsi"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val text = reader.readText()
            proc.waitFor()
            val matcher = Pattern.compile("imsi=([0-9]{14,16})").matcher(text)
            if (matcher.find()) {
                val imsi = matcher.group(1)
                if (!imsi.isNullOrBlank()) return imsi
            }
        } catch (ignored: Exception) {}

        // Método 3: dumpsys telephony.registry
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "dumpsys telephony.registry | grep -i mSubscriberId"))
            val reader = BufferedReader(InputStreamReader(proc.inputStream))
            val text = reader.readText()
            proc.waitFor()
            val matcher = Pattern.compile("mSubscriberId=([0-9]{14,16})").matcher(text)
            if (matcher.find()) {
                val imsi = matcher.group(1)
                if (!imsi.isNullOrBlank()) return imsi
            }
        } catch (ignored: Exception) {}

        return null
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

        val rootAvail = isRootAvailable()

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
                rootAvailable = rootAvail,
                diagnosisMsg = "Servicio de telefonía no disponible en el hardware."
            )
        }

        // Inspeccionar suscripciones activas vía SubscriptionManager (soporta Dual SIM / eSIM)
        var activeSub: SubscriptionInfo? = null
        if (hasReadPhoneState && subscriptionManager != null) {
            try {
                val subList = subscriptionManager.activeSubscriptionInfoList
                if (!subList.isNullOrEmpty()) {
                    activeSub = subList[0] // Tomar la primera SIM activa
                }
            } catch (ignored: Exception) {}
        }

        // Obtener TelephonyManager específico de la suscripción activa si existe
        val activeTm = if (activeSub != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                tm.createForSubscriptionId(activeSub.subscriptionId)
            } catch (e: Exception) {
                tm
            }
        } else {
            tm
        }

        val stateStr = when (activeTm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "ABSENT"
            TelephonyManager.SIM_STATE_PIN_REQUIRED -> "PIN_REQUIRED"
            TelephonyManager.SIM_STATE_PUK_REQUIRED -> "PUK_REQUIRED"
            TelephonyManager.SIM_STATE_NETWORK_LOCKED -> "NETWORK_LOCKED"
            TelephonyManager.SIM_STATE_READY -> "READY"
            TelephonyManager.SIM_STATE_NOT_READY -> "NOT_READY"
            TelephonyManager.SIM_STATE_PERM_DISABLED -> "PERM_DISABLED"
            TelephonyManager.SIM_STATE_CARD_IO_ERROR -> "CARD_IO_ERROR"
            TelephonyManager.SIM_STATE_CARD_RESTRICTED -> "CARD_RESTRICTED"
            else -> "UNKNOWN (${activeTm.simState})"
        }

        var imsi: String? = null
        var phone: String? = null
        var diagnosis = ""

        // 1. Intentar lectura nativa de IMSI (restringido en Android 10+ para apps no privilegiadas)
        if (hasReadPhoneState) {
            try {
                imsi = activeTm.subscriberId
            } catch (se: SecurityException) {
                diagnosis = "Android 10+ restringió getSubscriberId."
            } catch (ignored: Exception) {}

            try {
                phone = activeTm.line1Number
            } catch (ignored: Exception) {}
        } else {
            diagnosis = "Permiso READ_PHONE_STATE no concedido."
        }

        // 2. Si el IMSI vino nulo y tenemos Root, consultarlo vía Root
        if (imsi.isNullOrBlank() && rootAvail) {
            val rootImsi = queryImsiViaRoot()
            if (!rootImsi.isNullOrBlank()) {
                imsi = rootImsi
                diagnosis = "IMSI leído exitosamente vía Root (su)."
            }
        }

        // Operador, MCC y MNC
        var operatorNumeric = activeTm.simOperator
        if (operatorNumeric.isNullOrEmpty() && activeSub != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val subMcc = activeSub.mccString
                val subMnc = activeSub.mncString
                if (!subMcc.isNullOrEmpty() && !subMnc.isNullOrEmpty()) {
                    operatorNumeric = "$subMcc$subMnc"
                }
            }
        }

        var mcc: String? = null
        var mnc: String? = null
        if (!operatorNumeric.isNullOrEmpty() && operatorNumeric.length >= 5) {
            mcc = operatorNumeric.substring(0, 3)
            mnc = operatorNumeric.substring(3)
        }

        val carrierName = activeSub?.carrierName?.toString() ?: activeTm.simOperatorName

        return SimCardInfo(
            isPresent = activeTm.simState != TelephonyManager.SIM_STATE_ABSENT,
            simState = stateStr,
            subscriberId = imsi,
            mccMnc = operatorNumeric,
            mcc = mcc,
            mnc = mnc,
            carrierName = carrierName,
            phoneNumber = phone ?: (activeSub?.number),
            slotIndex = activeSub?.simSlotIndex ?: 0,
            hasPhoneStatePermission = hasReadPhoneState,
            hasModifyPhoneStatePermission = hasModifyPhoneState,
            rootAvailable = rootAvail,
            diagnosisMsg = diagnosis
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
                "En la pestaña 'SIM / HW' puedes usar el botón 'Conceder Permiso USIM vía Root'.",
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
