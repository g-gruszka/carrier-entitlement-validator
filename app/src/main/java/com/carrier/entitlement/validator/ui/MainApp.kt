package com.carrier.entitlement.validator.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import com.carrier.entitlement.validator.data.network.CamaraClient
import com.carrier.entitlement.validator.data.network.Ts43Client
import com.carrier.entitlement.validator.sim.SimManager
import com.carrier.entitlement.validator.ui.screens.*
import com.carrier.entitlement.validator.ui.theme.DarkBackground
import com.carrier.entitlement.validator.ui.theme.DarkSurface
import com.carrier.entitlement.validator.ui.theme.PrimaryBlue
import com.carrier.entitlement.validator.ui.theme.TextSecondary

enum class AppTab(val title: String, val icon: ImageVector) {
    CONNECTION("Conexión", Icons.Default.Cable),
    TS43("TS.43", Icons.Default.VpnKey),
    CAMARA("CAMARA", Icons.Default.VerifiedUser),
    TRACES("Trazas", Icons.Default.List),
    SIM("SIM / HW", Icons.Default.SimCard)
}

@Composable
fun MainApp(
    ts43Client: Ts43Client,
    camaraClient: CamaraClient,
    simManager: SimManager
) {
    var selectedTab by remember { mutableStateOf(AppTab.CONNECTION) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurface,
                contentColor = TextSecondary
            ) {
                AppTab.values().forEach { tab ->
                    NavigationBarItem(
                        icon = { Icon(tab.icon, contentDescription = tab.title) },
                        label = { Text(tab.title) },
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = PrimaryBlue,
                            selectedTextColor = PrimaryBlue,
                            indicatorColor = DarkBackground
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .padding(innerPadding)
                .background(DarkBackground)
        ) {
            when (selectedTab) {
                AppTab.CONNECTION -> ConnectionScreen(ts43Client, camaraClient)
                AppTab.TS43 -> Ts43Screen(ts43Client, simManager)
                AppTab.CAMARA -> CamaraScreen(camaraClient)
                AppTab.TRACES -> TracesScreen()
                AppTab.SIM -> SimHardwareScreen(simManager)
            }
        }
    }
}
