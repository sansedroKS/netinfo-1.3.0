package com.example

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.telephony.CellInfo
import android.telephony.CellInfoCdma
import android.telephony.CellInfoGsm
import android.telephony.CellInfoLte
import android.telephony.CellInfoWcdma
import android.telephony.TelephonyManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface

// ==========================================
// STATE MODELS
// ==========================================

data class WifiInfoState(
    val isConnected: Boolean = false,
    val ssid: String = "",
    val frequency: Int = 0,
    val linkSpeed: Int = 0,
    val rssi: Int = -127,
    val ipAddress: String = "",
    val gateway: String = ""
) {
    val signalPercentage: Int
        get() = if (isConnected) {
            ((rssi + 100).coerceIn(0, 70) * 100 / 70)
        } else 0

    val signalLabel: String
        get() = when {
            !isConnected -> "Rozłączono"
            rssi >= -50 -> "Doskonała"
            rssi >= -60 -> "Dobra"
            rssi >= -70 -> "Średnia"
            rssi >= -85 -> "Słaba"
            else -> "Bardzo słaba"
        }

    val bandLabel: String
        get() = when {
            frequency in 2400..2500 -> "2.4 GHz"
            frequency in 4900..5900 -> "5 GHz"
            frequency in 5925..7125 -> "6 GHz"
            frequency > 0 -> "$frequency MHz"
            else -> "Nieznane"
        }
}

data class CellularInfoState(
    val isConnected: Boolean = false,
    val hasSimCard: Boolean = true,
    val operatorName: String = "",
    val networkType: String = "", // 2G, 3G, 4G, 5G
    val dataActivityType: String = "", // HSPA, LTE, NR etc.
    val signalDbm: Int = -127,
    val isRoaming: Boolean = false
) {
    val signalLabel: String
        get() = when {
            !hasSimCard -> "Brak karty SIM"
            !isConnected && (signalDbm == -127 || signalDbm >= 0) -> "Rozłączono"
            signalDbm == -127 || signalDbm >= 0 -> "Brak sygnału"
            signalDbm >= -70 -> "Doskonała"
            signalDbm >= -85 -> "Dobra"
            signalDbm >= -100 -> "Średnia"
            signalDbm >= -115 -> "Słaba"
            else -> "Bardzo słaba"
        }

    val signalPercentage: Int
        get() = if (signalDbm == -127 || signalDbm >= 0) 0 
                else ((signalDbm + 120).coerceIn(0, 70) * 100 / 70)
}

data class SystemNetworkState(
    val isOnline: Boolean = false,
    val hasEthernet: Boolean = false,
    val hasVpn: Boolean = false,
    val isWifi: Boolean = false,
    val isCellular: Boolean = false,
    val activeTransportDesc: String = "Brak połączenia"
)

// ==========================================
// VIEWMODEL FOR REAL-TIME DIAGNOSTICS & CYCLES
// ==========================================

class NetworkViewModel(application: Application) : AndroidViewModel(application) {
    private val database = AppDatabase.getDatabase(application)
    private val repository = HistoryRepository(database)

    val historyList: StateFlow<List<ConnectionHistoryEntry>> = repository.allHistory
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _wifiState = MutableStateFlow(WifiInfoState())
    val wifiState: StateFlow<WifiInfoState> = _wifiState.asStateFlow()

    private val _cellularState = MutableStateFlow(CellularInfoState())
    val cellularState: StateFlow<CellularInfoState> = _cellularState.asStateFlow()

    private val _systemState = MutableStateFlow(SystemNetworkState())
    val systemState: StateFlow<SystemNetworkState> = _systemState.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    // Dynamic Multi-language state
    private val _currentLanguage = MutableStateFlow(if (java.util.Locale.getDefault().language == "pl") "PL" else "EN")
    val currentLanguage: StateFlow<String> = _currentLanguage.asStateFlow()

    fun toggleLanguage() {
        _currentLanguage.value = if (_currentLanguage.value == "PL") "EN" else "PL"
    }

    // Dynamic Dark/Light Theme state
    private val _isDarkTheme = MutableStateFlow(true)
    val isDarkTheme: StateFlow<Boolean> = _isDarkTheme.asStateFlow()

    fun toggleTheme() {
        _isDarkTheme.value = !_isDarkTheme.value
    }

    private var isFirstRefresh = true
    private val refreshMutex = Mutex()
    private var debounceJob: Job? = null
    private var lastCellDbm: Int = -127
    private var lastCellDbmTimestamp: Long = 0L

    private val connectivityManager = application.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            scheduleDebouncedRefresh(300L)
        }
        override fun onLost(network: Network) {
            scheduleDebouncedRefresh(300L)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            scheduleDebouncedRefresh(600L)
        }
    }

    init {
        try {
            connectivityManager?.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
        refreshAll()
    }

    override fun onCleared() {
        super.onCleared()
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    fun clearHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.clearAll()
        }
    }

    fun refreshAll() {
        debounceJob?.cancel()
        viewModelScope.launch {
            performRefresh()
        }
    }

    private fun scheduleDebouncedRefresh(delayMs: Long) {
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(delayMs)
            performRefresh()
        }
    }

    private suspend fun performRefresh() {
        if (refreshMutex.isLocked) return

        refreshMutex.withLock {
            _isRefreshing.value = true
            try {
                val context = getApplication<Application>().applicationContext
                val prevWifi = _wifiState.value
                val prevCellular = _cellularState.value
                val prevSystem = _systemState.value

                val (freshWifi, freshCellular, freshSystem) = withContext(Dispatchers.IO) {
                    val wifi = try {
                        fetchWifiDetails(context)
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        WifiInfoState(isConnected = false)
                    }
                    val cellular = try {
                        fetchCellularDetails(context)
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        CellularInfoState(isConnected = false)
                    }
                    val system = try {
                        fetchSystemDetails(context)
                    } catch (t: Throwable) {
                        t.printStackTrace()
                        SystemNetworkState()
                    }
                    Triple(wifi, cellular, system)
                }

                val nextWifi = if (freshWifi.isConnected) freshWifi else prevWifi.copy(isConnected = false)
                val nextCellular = if (freshCellular.isConnected) freshCellular else prevCellular.copy(isConnected = false)
                val nextSystem = freshSystem

                _wifiState.value = nextWifi
                _cellularState.value = nextCellular
                _systemState.value = nextSystem

                val now = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    if (isFirstRefresh) {
                        isFirstRefresh = false
                        if (nextWifi.isConnected) {
                            repository.insert(
                                ConnectionHistoryEntry(
                                    timestamp = now,
                                    connectionType = "Wi-Fi",
                                    isConnected = true,
                                    identifier = nextWifi.ssid,
                                    networkType = nextWifi.bandLabel,
                                    signalStrength = "${nextWifi.rssi} dBm"
                                )
                            )
                        }
                        if (nextCellular.isConnected) {
                            repository.insert(
                                ConnectionHistoryEntry(
                                    timestamp = now,
                                    connectionType = "Mobile",
                                    isConnected = true,
                                    identifier = nextCellular.operatorName,
                                    networkType = nextCellular.networkType,
                                    signalStrength = if (nextCellular.signalDbm != -127) "${nextCellular.signalDbm} dBm" else "N/A"
                                )
                            )
                        }
                        if (nextSystem.hasEthernet && !nextWifi.isConnected && !nextCellular.isConnected) {
                            repository.insert(
                                ConnectionHistoryEntry(
                                    timestamp = now,
                                    connectionType = "Ethernet",
                                    isConnected = true,
                                    identifier = "Sieć przewodowa / Ethernet",
                                    networkType = "LAN",
                                    signalStrength = "100%"
                                )
                            )
                        }
                        if (!nextWifi.isConnected && !nextCellular.isConnected && !nextSystem.hasEthernet) {
                            repository.insert(
                                ConnectionHistoryEntry(
                                    timestamp = now,
                                    connectionType = "Offline",
                                    isConnected = false,
                                    identifier = "Urządzenie offline / Device offline",
                                    networkType = "N/A",
                                    signalStrength = "N/A"
                                )
                            )
                        }
                    } else {
                        logConnectionChangesToHistory(context, prevWifi, nextWifi, prevCellular, nextCellular, prevSystem, nextSystem)
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
            } finally {
                delay(300)
                _isRefreshing.value = false
            }
        }
    }

    private suspend fun logConnectionChangesToHistory(
        context: Context,
        prevWifi: WifiInfoState,
        nextWifi: WifiInfoState,
        prevCellular: CellularInfoState,
        nextCellular: CellularInfoState,
        prevSystem: SystemNetworkState,
        nextSystem: SystemNetworkState
    ) {
        val now = System.currentTimeMillis()

        // 1. Wi-Fi connection changes
        val wifiConnectedChanged = prevWifi.isConnected != nextWifi.isConnected
        val wifiSsidChanged = nextWifi.isConnected && prevWifi.ssid.isNotEmpty() && prevWifi.ssid != nextWifi.ssid

        if (wifiConnectedChanged || wifiSsidChanged) {
            repository.insert(
                ConnectionHistoryEntry(
                    timestamp = now,
                    connectionType = "Wi-Fi",
                    isConnected = nextWifi.isConnected,
                    identifier = if (nextWifi.isConnected) nextWifi.ssid else "N/A",
                    networkType = if (nextWifi.isConnected) nextWifi.bandLabel else "N/A",
                    signalStrength = if (nextWifi.isConnected) "${nextWifi.rssi} dBm (${nextWifi.signalPercentage}%)" else "N/A"
                )
            )
        }

        // 2. Cellular connection changes
        val cellularConnectedChanged = prevCellular.isConnected != nextCellular.isConnected
        val cellularOperatorTypeChanged = nextCellular.isConnected &&
                (prevCellular.operatorName != nextCellular.operatorName || prevCellular.networkType != nextCellular.networkType)

        if (cellularConnectedChanged || cellularOperatorTypeChanged) {
            repository.insert(
                ConnectionHistoryEntry(
                    timestamp = now,
                    connectionType = "Mobile",
                    isConnected = nextCellular.isConnected,
                    identifier = if (nextCellular.isConnected) nextCellular.operatorName else "N/A",
                    networkType = if (nextCellular.isConnected) nextCellular.networkType else "N/A",
                    signalStrength = if (nextCellular.isConnected && nextCellular.signalDbm != -127) "${nextCellular.signalDbm} dBm" else "N/A"
                )
            )
        }

        // 3. Ethernet changes
        if (prevSystem.hasEthernet != nextSystem.hasEthernet && nextSystem.hasEthernet) {
            repository.insert(
                ConnectionHistoryEntry(
                    timestamp = now,
                    connectionType = "Ethernet",
                    isConnected = true,
                    identifier = "Sieć przewodowa / Ethernet",
                    networkType = "LAN",
                    signalStrength = "100%"
                )
            )
        }

        // 4. Overall offline transition change (all went offline from online)
        val wasOnline = prevWifi.isConnected || prevCellular.isConnected || prevSystem.hasEthernet
        val isNowOnline = nextWifi.isConnected || nextCellular.isConnected || nextSystem.hasEthernet
        if (wasOnline && !isNowOnline) {
            repository.insert(
                ConnectionHistoryEntry(
                    timestamp = now,
                    connectionType = "Offline",
                    isConnected = false,
                    identifier = "Urządzenie offline / Device offline",
                    networkType = "N/A",
                    signalStrength = "N/A"
                )
            )
        }
    }

    private fun fetchWifiDetails(context: Context): WifiInfoState {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return WifiInfoState(isConnected = false)

        val activeNet = connMgr.activeNetwork
        val caps = connMgr.getNetworkCapabilities(activeNet)

        val isWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        if (!isWifi) {
            return WifiInfoState(isConnected = false)
        }

        try {
            val wifiMgr = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo: WifiInfo? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (caps.transportInfo as? WifiInfo) ?: wifiMgr?.connectionInfo
            } else {
                wifiMgr?.connectionInfo
            }

            if (wifiInfo == null) {
                return WifiInfoState(isConnected = true, ssid = "Połączono z Wi-Fi")
            }

            // Format SSID
            var ssid = wifiInfo.ssid ?: "<unknown ssid>"
            if (ssid == "\"<unknown ssid>\"" || ssid == "<unknown ssid>") {
                val hasLoc = ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                ssid = if (hasLoc) "Połączono z Wi-Fi" else "Zezwól na GPS & Lokalizację"
            } else {
                ssid = ssid.replace("\"", "")
            }

            val frequency = wifiInfo.frequency
            val speed = wifiInfo.linkSpeed
            val rssi = wifiInfo.rssi

            // Format IPv4
            val ipInt = wifiInfo.ipAddress
            val ipAddress = if (ipInt != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    (ipInt and 0xff),
                    (ipInt shr 8 and 0xff),
                    (ipInt shr 16 and 0xff),
                    (ipInt shr 24 and 0xff)
                )
            } else {
                getLocalIpAddress() ?: "Brak IP"
            }

            // Gateway IP
            val dhcp = wifiMgr?.dhcpInfo
            val gateway = if (dhcp != null && dhcp.gateway != 0) {
                String.format(
                    "%d.%d.%d.%d",
                    (dhcp.gateway and 0xff),
                    (dhcp.gateway shr 8 and 0xff),
                    (dhcp.gateway shr 16 and 0xff),
                    (dhcp.gateway shr 24 and 0xff)
                )
            } else {
                "Brak Bramy"
            }

            return WifiInfoState(
                isConnected = true,
                ssid = ssid,
                frequency = frequency,
                linkSpeed = speed,
                rssi = rssi,
                ipAddress = ipAddress,
                gateway = gateway
            )
        } catch (t: Throwable) {
            t.printStackTrace()
            return WifiInfoState(isConnected = true, ssid = "Sieć Wi-Fi", rssi = -65)
        }
    }

    private fun fetchCellularDetails(context: Context): CellularInfoState {
        val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            ?: return CellularInfoState(isConnected = false, hasSimCard = false, operatorName = "Brak modemu")

        try {
            // Sim State Check
            val simState = try { telephonyManager.simState } catch (t: Throwable) { TelephonyManager.SIM_STATE_UNKNOWN }
            val hasSim = simState != TelephonyManager.SIM_STATE_ABSENT && simState != TelephonyManager.SIM_STATE_UNKNOWN

            // Connection Check
            val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = connMgr?.activeNetwork
            val caps = connMgr?.getNetworkCapabilities(activeNet)
            val isMobileConnected = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true

            // Operator Name
            var operator = try { telephonyManager.networkOperatorName } catch (t: Throwable) { null }
            if (operator.isNullOrEmpty()) {
                operator = try { telephonyManager.simOperatorName } catch (t: Throwable) { null }
            }
            if (operator.isNullOrEmpty()) {
                operator = if (hasSim) "Wyszukiwanie sieci..." else "Brak karty SIM"
            }

            // Roaming State
            val isRoaming = try { telephonyManager.isNetworkRoaming } catch (t: Throwable) { false }

            // Network standards mapping
            var generation = "Brak"
            var transmissionCode = "Rozłączono"

            if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                try {
                    val netType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        telephonyManager.dataNetworkType
                    } else {
                        @Suppress("DEPRECATION")
                        telephonyManager.networkType
                    }
                    val (gen, desc) = estimateCellularGeneration(netType)
                    generation = gen
                    transmissionCode = desc
                } catch (t: Throwable) {
                    generation = "Zablokowano"
                    transmissionCode = "Zezwól na telefon"
                }
            } else {
                generation = "Brak uprawnień"
                transmissionCode = "Zezwól na telefon"
            }

            val signalDbm = getCellInfoSignalDbm(context, telephonyManager)

            return CellularInfoState(
                isConnected = isMobileConnected && hasSim,
                hasSimCard = hasSim,
                operatorName = operator,
                networkType = if (isMobileConnected) generation else "brak",
                dataActivityType = if (isMobileConnected) transmissionCode else "Brak transmisji",
                signalDbm = signalDbm,
                isRoaming = isRoaming
            )
        } catch (t: Throwable) {
            t.printStackTrace()
            return CellularInfoState(isConnected = false, hasSimCard = false, operatorName = "Błąd modemu")
        }
    }

    private fun fetchSystemDetails(context: Context): SystemNetworkState {
        val connMgr = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return SystemNetworkState()
        val activeNet = connMgr.activeNetwork
        val caps = connMgr.getNetworkCapabilities(activeNet) ?: return SystemNetworkState()

        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        val isEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val isVpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)

        val desc = when {
            isWifi -> "Wi-Fi"
            isCellular -> "Komórkowa / Cellular"
            isEthernet -> "Ethernet / LAN"
            isVpn -> "VPN"
            else -> "Inna sieć / Other"
        }

        return SystemNetworkState(
            isOnline = isWifi || isCellular || isEthernet || isVpn,
            hasEthernet = isEthernet,
            hasVpn = isVpn,
            isWifi = isWifi,
            isCellular = isCellular,
            activeTransportDesc = desc
        )
    }

    private fun estimateCellularGeneration(networkType: Int): Pair<String, String> {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS,
            TelephonyManager.NETWORK_TYPE_EDGE,
            TelephonyManager.NETWORK_TYPE_CDMA,
            TelephonyManager.NETWORK_TYPE_1xRTT,
            TelephonyManager.NETWORK_TYPE_IDEN -> Pair("2G", getNetworkTypeName(networkType))

            TelephonyManager.NETWORK_TYPE_UMTS,
            TelephonyManager.NETWORK_TYPE_EVDO_0,
            TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA,
            TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
            TelephonyManager.NETWORK_TYPE_EHRPD,
            TelephonyManager.NETWORK_TYPE_HSPAP -> Pair("3G", getNetworkTypeName(networkType))

            TelephonyManager.NETWORK_TYPE_LTE -> Pair("4G/LTE", "LTE")

            20 -> Pair("5G", "NR (5G)") // NETWORK_TYPE_NR is 20 in Android Q SDK

            else -> Pair("Inne", "GSM/CDMA")
        }
    }

    private fun getNetworkTypeName(networkType: Int): String {
        return when (networkType) {
            TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
            TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
            TelephonyManager.NETWORK_TYPE_CDMA -> "CDMA"
            TelephonyManager.NETWORK_TYPE_1xRTT -> "1xRTT"
            TelephonyManager.NETWORK_TYPE_IDEN -> "iDEN"
            TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
            TelephonyManager.NETWORK_TYPE_EVDO_0 -> "EVDO"
            TelephonyManager.NETWORK_TYPE_EVDO_A -> "EVDO A"
            TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
            TelephonyManager.NETWORK_TYPE_HSUPA -> "HSUPA"
            TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
            TelephonyManager.NETWORK_TYPE_EVDO_B -> "EVDO B"
            TelephonyManager.NETWORK_TYPE_EHRPD -> "eHRPD"
            TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
            TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
            20 -> "NR (5G)"
            else -> "Nieznany"
        }
    }

    private fun getCellInfoSignalDbm(context: Context, telephonyManager: TelephonyManager): Int {
        val now = System.currentTimeMillis()
        if (now - lastCellDbmTimestamp < 3000L && lastCellDbm != -127) {
            return lastCellDbm
        }

        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return -127
        }

        try {
            val cellList: List<CellInfo>? = telephonyManager.allCellInfo
            if (!cellList.isNullOrEmpty()) {
                for (info in cellList) {
                    if (info.isRegistered) {
                        val dbm = when (info) {
                            is CellInfoLte -> info.cellSignalStrength.dbm
                            is CellInfoGsm -> info.cellSignalStrength.dbm
                            is CellInfoWcdma -> info.cellSignalStrength.dbm
                            is CellInfoCdma -> info.cellSignalStrength.dbm
                            else -> {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && info is android.telephony.CellInfoNr) {
                                    info.cellSignalStrength.dbm
                                } else {
                                    -127
                                }
                            }
                        }
                        if (dbm in -140..(-40)) {
                            lastCellDbm = dbm
                            lastCellDbmTimestamp = now
                            return dbm
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        return -127
    }

    private fun getLocalIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val value = interfaces.nextElement() ?: continue
                val addresses = value.inetAddresses ?: continue
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement() ?: continue
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        return null
    }
}

// ==========================================
// THE MAIN ACTIVITY & INTERFACE CORE
// ==========================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: NetworkViewModel = viewModel()
            val isDarkTheme by viewModel.isDarkTheme.collectAsState()
            MyApplicationTheme(darkTheme = isDarkTheme) {
                MainDiagnosticApp(viewModel = viewModel)
            }
        }
    }
}

@Composable
fun MainDiagnosticApp(viewModel: NetworkViewModel) {
    val context = LocalContext.current
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    fun trans(pl: String, en: String): String = if (currentLanguage == "PL") pl else en

    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var phoneStateGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val hasLoc = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasPhone = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_PHONE_STATE
                ) == PackageManager.PERMISSION_GRANTED

                if (hasLoc != locationGranted || hasPhone != phoneStateGranted) {
                    locationGranted = hasLoc
                    phoneStateGranted = hasPhone
                    viewModel.refreshAll()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    var skipPermissionsRationale by remember { mutableStateOf(false) }

    val permissionRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val hasFine = results[android.Manifest.permission.ACCESS_FINE_LOCATION] == true
        val hasCoarse = results[android.Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (results.containsKey(android.Manifest.permission.ACCESS_FINE_LOCATION) ||
            results.containsKey(android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            locationGranted = hasFine || hasCoarse
        }
        if (results.containsKey(android.Manifest.permission.READ_PHONE_STATE)) {
            phoneStateGranted = results[android.Manifest.permission.READ_PHONE_STATE] == true
        }
        // Always allow proceeding after user interacts with permission prompt
        skipPermissionsRationale = true
        viewModel.refreshAll()
    }

    // Show beautiful educational Permission Screen if not fully approved
    if (!(locationGranted && phoneStateGranted) && !skipPermissionsRationale) {
        PermissionsRationaleScreen(
            locationGranted = locationGranted,
            phoneGranted = phoneStateGranted,
            onRequestPermissions = {
                permissionRequestLauncher.launch(
                    arrayOf(
                        android.Manifest.permission.ACCESS_FINE_LOCATION,
                        android.Manifest.permission.ACCESS_COARSE_LOCATION,
                        android.Manifest.permission.READ_PHONE_STATE
                    )
                )
            },
            onSkip = {
                skipPermissionsRationale = true
                viewModel.refreshAll()
            },
            trans = ::trans
        )
    } else {
        DashboardScreen(viewModel = viewModel)
    }
}

// ==========================================
// RATIONALE ONBOARDING SCREEN DESIGN
// ==========================================

@Composable
fun PermissionsRationaleScreen(
    locationGranted: Boolean,
    phoneGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onSkip: () -> Unit,
    trans: (String, String) -> String
) {
    val scrollState = rememberScrollState()

    // Handle back button on rationale screen to continue to dashboard
    BackHandler {
        onSkip()
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Spacer(modifier = Modifier.height(16.dp))

            // Elegant glowing circle icon with Clean Minimalism theme
            Box(
                modifier = Modifier
                    .size(76.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), Color.Transparent)
                        )
                    )
                    .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Shield,
                    contentDescription = trans("Ochrona i uprawnienia", "Shield and permissions"),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(36.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = trans("Wymagane Uprawnienia", "Permissions Required"),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = trans("Aby wyświetlić szczegółowe parametry Wi-Fi oraz sieci komórkowej, Android wymaga przyznania dwóch uprawnień systemowych. Zapewniamy pełne bezpieczeństwo danych.", "To display fine-grained parameters for Wi-Fi and mobile networks, Android requires two system permissions. We assure complete privacy & security of your data."),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Rationale status items in container style
            PermissionRequirementCard(
                title = trans("1. Lokalizacja urządzenia (ACCESS_FINE_LOCATION)", "1. Device Location (ACCESS_FINE_LOCATION)"),
                description = trans("Wymagane przez system Android do odczytania nazwy sieci Wi-Fi (SSID) oraz siły sygnału nadajników.", "Required by the Android system to read the Wi-Fi network name (SSID) and transmitter signal strength."),
                isGranted = locationGranted,
                icon = Icons.Default.LocationOn,
                accentColor = MaterialTheme.colorScheme.primary,
                trans = trans
            )

            Spacer(modifier = Modifier.height(14.dp))

            PermissionRequirementCard(
                title = trans("2. Stan Telefonu i Połączeń (READ_PHONE_STATE)", "2. Phone State & Calls (READ_PHONE_STATE)"),
                description = trans("Niezbędne do identyfikacji nazwy operatora, generacji sieci mobilnej (LTE/5G) oraz transmisji danych.", "Necessary to identify operator name, mobile generation (LTE/5G), and cellular data activity status."),
                isGranted = phoneGranted,
                icon = Icons.Default.CellTower,
                accentColor = MaterialTheme.colorScheme.secondary,
                trans = trans
            )

            Spacer(modifier = Modifier.height(30.dp))

            Button(
                onClick = onRequestPermissions,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("grant_permissions_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = trans("Przyznaj Uprawnienia", "Grant Permissions"),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("skip_permissions_button"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = trans("Przejdź do aplikacji (pomiń)", "Proceed to app (skip)"),
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
fun PermissionRequirementCard(
    title: String,
    description: String,
    isGranted: Boolean,
    icon: ImageVector,
    accentColor: Color,
    trans: (String, String) -> String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (isGranted) null else androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(accentColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Indicator badge with Clean Minimalism green/red
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (isGranted) Color(0xFF00390A).copy(alpha = 0.6f)
                        else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                    )
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Text(
                    text = if (isGranted) trans("Aktywne", "Active") else trans("Brak", "Missing"),
                    color = if (isGranted) Color(0xFFB3F2AD) else MaterialTheme.colorScheme.error,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// ==========================================
// THE CORE MAIN DASHBOARD SCREEN (TABROW VIEW)
// ==========================================

@Composable
fun DashboardScreen(viewModel: NetworkViewModel) {
    var selectedTab by remember { mutableStateOf(0) }
    var pendingLinkToOpen by remember { mutableStateOf<String?>(null) }
    var pendingLinkName by remember { mutableStateOf<String?>(null) }
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val isDarkTheme by viewModel.isDarkTheme.collectAsState()
    val context = LocalContext.current

    val wifiState by viewModel.wifiState.collectAsState()
    val cellularState by viewModel.cellularState.collectAsState()
    val systemState by viewModel.systemState.collectAsState()
    
    val currentLanguage by viewModel.currentLanguage.collectAsState()
    fun trans(pl: String, en: String): String = if (currentLanguage == "PL") pl else en

    val isOnline = wifiState.isConnected || cellularState.isConnected || systemState.hasEthernet
    val isOffline = !isOnline

    // BackHandler to navigate back to Home tab (Wi-Fi) if on sub-tab
    BackHandler(enabled = selectedTab != 0) {
        selectedTab = 0
    }

    // Smooth Rotate animation for the Floating Action Button when updating
    val infiniteTransition = rememberInfiniteTransition(label = "RefreshAnimation")
    val rotationAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Rotation"
    )

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 10.dp)
                    .fillMaxWidth()
            ) {
                // ROW 1: Logo + "netinfo" + Coffee cup on left, Theme + Language on right (aligned to right!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.NetworkCheck,
                                contentDescription = trans("Logo aplikacji", "App Logo"),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "netinfo",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Medium,
                            letterSpacing = (-0.5).sp,
                            fontFamily = FontFamily.SansSerif
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = {
                                pendingLinkToOpen = "https://cuplink.to/sansedro"
                                pendingLinkName = "cuplink.to/sansedro"
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .size(36.dp)
                                .testTag("coffee_link_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocalCafe,
                                contentDescription = trans("Postaw kawę (cuplink.to/sansedro)", "Buy a coffee (cuplink.to/sansedro)"),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }

                    // Wyrównane do prawej ikony: Tryb wyświetlania (motyw) oraz Język
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Dynamic Dark/Light Theme Switcher
                        IconButton(
                            onClick = { viewModel.toggleTheme() },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .size(36.dp)
                                .testTag("theme_toggle_button")
                        ) {
                            Icon(
                                imageVector = if (isDarkTheme) Icons.Default.LightMode else Icons.Default.DarkMode,
                                contentDescription = trans("Zmień motyw", "Change Theme"),
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Language Toggle button
                        IconButton(
                            onClick = { viewModel.toggleLanguage() },
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                                .size(36.dp)
                                .testTag("language_toggle_button")
                        ) {
                            Text(
                                text = if (currentLanguage == "PL") "EN" else "PL",
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Black,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(10.dp))

                // ROW 2 (Linijkę niżej): Napis Live / Offline wyrównany czytelnie
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = trans("Diagnostyka w czasie rzeczywistym", "Real-time diagnostics"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )

                    // Minimalist green real-time indicator badge (Na Żywo / Offline)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                            .testTag("live_indicator_badge")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (isOffline) MaterialTheme.colorScheme.error else Color(0xFFB3F2AD))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (isOffline) trans("Offline", "Offline") else trans("Na Żywo", "Live"),
                            color = if (isOffline) MaterialTheme.colorScheme.error else Color(0xFFB3F2AD),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))
                
                // TAB NAVIGATION - ELEGANT CLEAN FLAT SURFACE with 3 tabs!
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                    indicator = { tabPositions ->
                        TabRowDefaults.SecondaryIndicator(
                            modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                            color = MaterialTheme.colorScheme.primary,
                            height = 3.dp
                        )
                    },
                    divider = {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                            thickness = 1.dp
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("wifi_tab"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.Wifi,
                                contentDescription = trans("Niski sygnał Wi-Fi", "Wi-Fi"),
                                tint = if (selectedTab == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("cellular_tab"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.SignalCellularAlt,
                                contentDescription = trans("Połączenie komórkowe", "Cellular Connection"),
                                tint = if (selectedTab == 1) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    )
                    Tab(
                        selected = selectedTab == 2,
                        onClick = { selectedTab = 2 },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("history_tab"),
                        icon = {
                            Icon(
                                imageVector = Icons.Default.History,
                                contentDescription = trans("Historia", "History"),
                                tint = if (selectedTab == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    )
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { viewModel.refreshAll() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .navigationBarsPadding()
                    .testTag("refresh_fab")
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = trans("Odśwież dane sieci", "Refresh network data"),
                    modifier = Modifier
                        .size(24.dp)
                        .rotate(if (isRefreshing) rotationAngle else 0f)
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // High-contrast Offline notice banner when offline
            if (isOffline) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("offline_banner"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudOff,
                                contentDescription = "Offline indicator",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = trans("Tryb Offline", "Offline Mode"),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = trans(
                                    "Brak aktywnego połączenia Wi-Fi lub komórkowego. Wyświetlamy ostatnie znane parametry.",
                                    "No active Wi-Fi or cellular connection. Displaying last known parameters."
                                ),
                                color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            } else if (systemState.hasEthernet && !wifiState.isConnected && !cellularState.isConnected) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp)
                        .testTag("ethernet_banner"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    shape = RoundedCornerShape(16.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.SettingsEthernet,
                                contentDescription = "Ethernet indicator",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = trans("Połączenie Ethernet / LAN", "Ethernet / LAN Connection"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = trans(
                                    "Aktywne połączenie kablowe/emulatora (Ethernet). Dostęp do sieci internetowej jest aktywny.",
                                    "Active wired/emulator connection (Ethernet). Internet access is fully operational."
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                lineHeight = 15.sp
                            )
                        }
                    }
                }
            }

            Box(modifier = Modifier.weight(1f)) {
                // AnimatedContent to provide slick screen transitions
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        if (targetState > initialState) {
                            slideInHorizontally { width -> width } + fadeIn() togetherWith
                                    slideOutHorizontally { width -> -width } + fadeOut()
                        } else {
                            slideInHorizontally { width -> -width } + fadeIn() togetherWith
                                    slideOutHorizontally { width -> width } + fadeOut()
                        }.using(
                            SizeTransform(clip = false)
                        )
                    },
                    label = "TabContentAnimation"
                ) { targetIndex ->
                    when (targetIndex) {
                        0 -> WifiTabContent(wifiState = wifiState, trans = ::trans)
                        1 -> CellularTabContent(cellularState = cellularState, trans = ::trans)
                        2 -> HistoryTabContent(viewModel = viewModel, trans = ::trans)
                    }
                }
            }

            // Interactive Footer with links to mailto:sansedro@gmail.com and Google AI Studio (Gemini)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp, top = 8.dp)
                    .testTag("app_footer"),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Idea by ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Text(
                    text = "Sansedro",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable {
                            pendingLinkToOpen = "mailto:sansedro@gmail.com"
                            pendingLinkName = "Sansedro"
                        }
                        .padding(horizontal = 2.dp)
                        .testTag("footer_sansedro_link")
                )
                Text(
                    text = ", powered by ",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
                Text(
                    text = "Gemini (ver 1.3.0)",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier
                        .clickable {
                            pendingLinkToOpen = "https://aistudio.google.com/"
                            pendingLinkName = "Gemini"
                        }
                        .padding(horizontal = 2.dp)
                        .testTag("footer_gemini_link")
                )
                Text(
                    text = ".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 11.sp
                )
            }
        }
    }

    // Confirmation dialog before opening the chosen link
    if (pendingLinkToOpen != null) {
        AlertDialog(
            onDismissRequest = {
                pendingLinkToOpen = null
                pendingLinkName = null
            },
            title = {
                Text(text = trans("Otworzyć odnośnik?", "Open link?"))
            },
            text = {
                Text(
                    text = trans(
                        "Czy chcesz otworzyć ${pendingLinkName} (${pendingLinkToOpen})?",
                        "Do you want to open ${pendingLinkName} (${pendingLinkToOpen})?"
                    )
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val link = pendingLinkToOpen ?: return@TextButton
                        val intent = if (link.startsWith("mailto:")) {
                            Intent(Intent.ACTION_SENDTO).apply {
                                data = Uri.parse(link)
                            }
                        } else {
                            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                        }
                        try {
                            context.startActivity(Intent.createChooser(intent, trans("Wybierz aplikację", "Choose an application")))
                        } catch (e: Exception) {
                            Toast.makeText(context, trans("Brak odpowiedniej aplikacji", "No suitable app found"), Toast.LENGTH_SHORT).show()
                        }
                        pendingLinkToOpen = null
                        pendingLinkName = null
                    }
                ) {
                    Text(trans("Otwórz", "Open"))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        pendingLinkToOpen = null
                        pendingLinkName = null
                    }
                ) {
                    Text(trans("Anuluj", "Cancel"))
                }
            },
            modifier = Modifier.testTag("confirmation_dialog")
        )
    }
}

// ==========================================
// VIEW 1: WI-FI DIAGNOSTICS SCREEN
// ==========================================

@Composable
fun WifiTabContent(wifiState: WifiInfoState, trans: (String, String) -> String) {
    val context = LocalContext.current

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main Header Signal Strength Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header connection type info
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = trans("Stan Wi-Fi", "Wi-Fi Status"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Connected / Disconnected Badge with Clean Minimalism colors
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (wifiState.isConnected) Color(0xFF00390A).copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (wifiState.isConnected) trans("POŁĄCZONO", "CONNECTED") else trans("ROZŁĄCZONO", "DISCONNECTED"),
                                color = if (wifiState.isConnected) Color(0xFFB3F2AD) else MaterialTheme.colorScheme.error,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Circular Indicator representation of DBm strength
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.size(140.dp)
                    ) {
                        // Background sweep track (Minimal grey)
                        CircularProgressIndicator(
                            progress = { 1.0f },
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            strokeWidth = 8.dp,
                            gapSize = 0.dp
                        )

                        // Active sweep representing actual strength
                        CircularProgressIndicator(
                            progress = { if (wifiState.isConnected) wifiState.signalPercentage.toFloat() / 100f else 0f },
                            modifier = Modifier.fillMaxSize(),
                            color = MaterialTheme.colorScheme.primary,
                            strokeWidth = 8.dp,
                            gapSize = 0.dp
                        )

                        // Inner circular text elements
                        val sigLabel = when (wifiState.signalLabel) {
                            "Doskonała" -> trans("Doskonała", "Excellent")
                            "Dobra" -> trans("Dobra", "Good")
                            "Średnia" -> trans("Średnia", "Fair")
                            "Słaba" -> trans("Słaba", "Weak")
                            else -> trans("Brak sygnału", "No Signal")
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (wifiState.isConnected) "${wifiState.rssi} dBm" else "N/A",
                                color = MaterialTheme.colorScheme.onBackground,
                                fontSize = 26.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = sigLabel,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (wifiState.isConnected) "${trans("Moc", "Strength")}: ${wifiState.signalPercentage}%" else "",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp
                              )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Secondary info: Network Name / SSID
                    Text(
                        text = trans("Aktualnie Połączony z:", "Currently Connected to:"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (wifiState.isConnected) wifiState.ssid else trans("Sieć Wi-Fi nieaktywna", "Wi-Fi Network Inactive"),
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )

                    // Helper informational text regarding Location restriction on android SSID
                    if (wifiState.isConnected && wifiState.ssid.lowercase().contains("zezwól")) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.1f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = trans("💡 Wskazówka: Nazwa SSID wymaga przyznanej lokalizacji GPS oraz włączonej usługi lokalizacyjnej w ustawieniach systemu.", "💡 Hint: SSID name requires granted GPS location and location services enabled in system settings."),
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 10.sp,
                                lineHeight = 14.sp,
                                modifier = Modifier.padding(10.dp),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // Secondary detail rows
        item {
            Text(
                text = trans("Parametry Techniczne", "Technical Parameters"),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }

        // Row containing Band and Link Speed side by side
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                // Częstotliwość Pasma Card
                DetailMiniCard(
                    modifier = Modifier.weight(1f),
                    title = trans("Częstotliwość", "Frequency"),
                    value = wifiState.bandLabel,
                    subtext = trans("Pasmo radiowe", "Radio band"),
                    icon = Icons.Default.SignalWifiStatusbar4Bar,
                    accentColor = MaterialTheme.colorScheme.primary
                )

                // Link Speed (Prędkość) Card
                DetailMiniCard(
                    modifier = Modifier.weight(1f),
                    title = trans("Prędkość", "Speed"),
                    value = if (wifiState.isConnected) "${wifiState.linkSpeed} Mbps" else "N/A",
                    subtext = trans("Maks. przepustowość", "Max throughput"),
                    icon = Icons.Default.Speed,
                    accentColor = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Client Local IP Card & Copy Click Option
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        if (wifiState.isConnected && wifiState.ipAddress.isNotEmpty()) {
                            val clipMgr = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clipData = ClipData.newPlainText("IP Adres", wifiState.ipAddress)
                            clipMgr.setPrimaryClip(clipData)
                            Toast.makeText(context, trans("Sklonowano adres IP!", "IP address copied!"), Toast.LENGTH_SHORT).show()
                        }
                    }
                    .testTag("copy_ip_button"),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Computer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = trans("Lokalny Adres IP", "Local IP Address"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (wifiState.isConnected) wifiState.ipAddress else trans("Rozłączono", "Disconnected"),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    if (wifiState.isConnected) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = trans("Kopiuj adres IP", "Copy IP Address"),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // Gateway Connection Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Router,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column {
                        Text(
                            text = trans("Brama domyślna (Gateway)", "Default Gateway"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = if (wifiState.isConnected) wifiState.gateway else trans("Rozłączono", "Disconnected"),
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// ==========================================
// VIEW 2: CELLULAR (MOBILE) DIAGNOSTICS SCREEN
// ==========================================

@Composable
fun CellularTabContent(cellularState: CellularInfoState, trans: (String, String) -> String) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Main Operator Card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Connection Type
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = trans("Połączenie Mobilne", "Mobile Connection"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Status Badge with Clean Minimalism colors
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(
                                    if (!cellularState.hasSimCard) Color(0xFF601410).copy(alpha = 0.5f)
                                    else if (cellularState.isConnected) Color(0xFF00390A).copy(alpha = 0.6f)
                                    else MaterialTheme.colorScheme.error.copy(alpha = 0.15f)
                                )
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (!cellularState.hasSimCard) trans("BRAK SIM", "NO SIM")
                                       else if (cellularState.isConnected) trans("POŁĄCZONO", "CONNECTED") 
                                       else trans("ROZŁĄCZONO", "DISCONNECTED"),
                                color = if (!cellularState.hasSimCard) MaterialTheme.colorScheme.error 
                                        else if (cellularState.isConnected) Color(0xFFB3F2AD) 
                                        else MaterialTheme.colorScheme.error,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Black
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Minimalist Cellular Tower Operator Representation
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.CellTower,
                            contentDescription = "Wieża komórkowa",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val operatorText = if (!cellularState.hasSimCard) {
                        trans("Brak karty SIM", "No SIM card")
                    } else {
                        cellularState.operatorName
                    }

                    Text(
                        text = operatorText,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.ExtraBold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Generation chip e.g. 5G / 4G LTE with cohesive design
                    if (cellularState.isConnected) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primary)
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = cellularState.networkType,
                                color = MaterialTheme.colorScheme.onPrimary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                    } else {
                        Text(
                            text = trans("Brak aktywnego przesyłu danych", "No active data transfer"),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Section title
        item {
            Text(
                text = trans("Parametry Połączenia mobilnego", "Mobile Connection Parameters"),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }

        // Transmission Type & Roaming Information Side-by-Side
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                
                // Typ Transmisji danych Card
                DetailMiniCard(
                    modifier = Modifier.weight(1f),
                    title = trans("Standard", "Standard"),
                    value = cellularState.dataActivityType,
                    subtext = trans("Sygnał nośny", "Carrier signal"),
                    icon = Icons.Default.NetworkCheck,
                    accentColor = MaterialTheme.colorScheme.primary
                )

                // Roaming Card
                DetailMiniCard(
                    modifier = Modifier.weight(1f),
                    title = trans("Roaming", "Roaming"),
                    value = if (cellularState.isRoaming) trans("Tak", "Yes") else trans("Nie", "No"),
                    subtext = trans("Transmisja obca", "Foreign transmission"),
                    icon = Icons.Default.Public,
                    accentColor = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Cell Signal Strength (RSSI/dBm) slider card
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.SignalCellularAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = trans("Moc sygnału stacji", "Station Signal Strength"),
                                color = MaterialTheme.colorScheme.onBackground,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                        }

                        // Quality Label
                        val cellSigLabel = when (cellularState.signalLabel) {
                            "Doskonała" -> trans("Doskonała", "Excellent")
                            "Dobra" -> trans("Dobra", "Good")
                            "Średnia" -> trans("Średnia", "Fair")
                            "Słaba" -> trans("Słaba", "Weak")
                            else -> trans("Brak sygnału", "No Signal")
                        }

                        Text(
                            text = cellSigLabel,
                            color = if (cellularState.signalLabel == "Doskonała" || cellularState.signalLabel == "Dobra") Color(0xFFB3F2AD)
                                    else if (cellularState.signalLabel == "Średnia") Color(0xFFFBBF24)
                                    else MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (cellularState.signalDbm != -127) "${cellularState.signalDbm} dBm" else "N/A",
                            color = MaterialTheme.colorScheme.onBackground,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.width(90.dp)
                        )

                        // Signal Quality linear diagram scale with Minimalist layout
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(if (cellularState.signalDbm != -127) cellularState.signalPercentage.toFloat() / 100f else 0f)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.horizontalGradient(
                                            listOf(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.primary)
                                        )
                                    )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = trans("Komórki stacji są odpytywane z zarejestrowanych pasm sieciowych. Może brakować danych w systemach symulowanych lub przy wyłączonej lokalizacji.", "Station cells are queried from registered network bands. Data may be missing in simulated systems or with location disabled."),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        lineHeight = 14.sp
                    )
                }
            }
        }
    }
}

// ==========================================
// VIEW 3: CONNECTION HISTORY SCREEN
// ==========================================

@Composable
fun HistoryTabContent(viewModel: NetworkViewModel, trans: (String, String) -> String) {
    val historyEntries by viewModel.historyList.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = trans("Historia połączeń (ostatnie 200 zmian)", "Connection history (last 200 changes)"),
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            if (historyEntries.isNotEmpty()) {
                TextButton(
                    onClick = { viewModel.clearHistory() },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    ),
                    modifier = Modifier.testTag("clear_history_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Clear history icon",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = trans("Wyczyść", "Clear"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        if (historyEntries.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.HistoryToggleOff,
                        contentDescription = "No history available icon",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = trans("Brak zarejestrowanych zmian", "No registered changes"),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = trans(
                            "Aplikacja automatycznie zbiera historię zmian połączeń przy odświeżaniu lub zmianie sieci.",
                            "The app automatically records connection changes upon refresh or state transition."
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .weight(1f),
                contentPadding = PaddingValues(bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    count = historyEntries.size,
                    key = { index -> historyEntries[index].id }
                ) { index ->
                    val entry = historyEntries[index]
                    HistoryEntryCard(entry = entry, trans = trans)
                }
            }
        }
    }
}

@Composable
fun HistoryEntryCard(entry: ConnectionHistoryEntry, trans: (String, String) -> String) {
    val dateStr = remember(entry.timestamp) {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        sdf.format(java.util.Date(entry.timestamp))
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Circle connection type representation
            val (icon, tintBg, tintFg, typeLabel) = when (entry.connectionType) {
                "Wi-Fi" -> {
                    if (entry.isConnected) {
                        Quadruple(Icons.Default.Wifi, Color(0xFF00390A), Color(0xFFB3F2AD), "Wi-Fi")
                    } else {
                        Quadruple(Icons.Default.WifiOff, Color(0xFF601410), MaterialTheme.colorScheme.error, "Wi-Fi")
                    }
                }
                "Mobile" -> {
                    if (entry.isConnected) {
                        Quadruple(Icons.Default.CellTower, MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), MaterialTheme.colorScheme.primary, trans("Sieć komórkowa", "Cellular Network"))
                    } else {
                        Quadruple(Icons.Default.SignalCellularConnectedNoInternet4Bar, Color(0xFF601410), MaterialTheme.colorScheme.error, trans("Sieć komórkowa", "Cellular Network"))
                    }
                }
                else -> {
                    Quadruple(Icons.Default.CloudOff, Color(0xFF601410).copy(alpha = 0.5f), MaterialTheme.colorScheme.error, trans("Offline", "Offline"))
                }
            }

            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tintBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "Event type icon",
                    tint = tintFg,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = typeLabel,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = dateStr,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                        fontSize = 11.sp
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Detail entries depending on connection type
                if (entry.isConnected) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = "${trans("Nazwa", "Name")}: ${entry.identifier}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (entry.networkType != "N/A" && entry.networkType.isNotEmpty()) {
                            Text(
                                text = "${trans("Zasięg/Standard", "Band/Standard")}: ${entry.networkType}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                        if (entry.signalStrength != "N/A" && entry.signalStrength.isNotEmpty()) {
                            Text(
                                text = "${trans("Moc sygnału", "Signal strength")}: ${entry.signalStrength}",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    Text(
                        text = if (typeLabel == trans("Offline", "Offline")) {
                            trans("Urządzenie całkowicie odłączone od sieci", "Device fully disconnected from network")
                        } else {
                            trans("Połączenie zostało zerwane", "Connection has been broken / lost")
                        },
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// Simple Quadruple helper class for clean structuring
data class Quadruple<A, B, C, D>(
    val first: A,
    val second: B,
    val third: C,
    val fourth: D
)

// ==========================================
// CENTRAL DESIGN HELPER COMPONENTS
// ==========================================

@Composable
fun DetailMiniCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String,
    subtext: String,
    icon: ImageVector,
    accentColor: Color
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )

                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = value,
                color = MaterialTheme.colorScheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(2.dp))

            Text(
                text = subtext,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

