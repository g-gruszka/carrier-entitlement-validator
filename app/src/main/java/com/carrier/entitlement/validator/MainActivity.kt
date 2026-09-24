package com.carrier.entitlement.validator

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.carrier.entitlement.validator.data.network.CamaraClient
import com.carrier.entitlement.validator.data.network.Ts43Client
import com.carrier.entitlement.validator.sim.SimManager
import com.carrier.entitlement.validator.ui.MainApp
import com.carrier.entitlement.validator.ui.theme.CarrierValidatorTheme

class MainActivity : ComponentActivity() {

    private lateinit var ts43Client: Ts43Client
    private lateinit var camaraClient: CamaraClient
    private lateinit var simManager: SimManager

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        // Al concederse los permisos, la UI podrá refrescar la lectura de la SIM
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ts43Client = Ts43Client()
        camaraClient = CamaraClient()
        simManager = SimManager(this)

        requestPermissions()

        setContent {
            CarrierValidatorTheme {
                MainApp(
                    ts43Client = ts43Client,
                    camaraClient = camaraClient,
                    simManager = simManager
                )
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_PHONE_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            permissions.add(Manifest.permission.READ_PHONE_NUMBERS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }
}
