package com.sohbet.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

val DeepNavy = Color(0xFF0A1128)
val CardNavy = Color(0xFF121E42)
val ElectricBlue = Color(0xFF00B4D8)

enum class VpnProtocol {
    VLESS, VMESS, TROJAN, SHADOWSOCKS, HYSTERIA_V1, HYSTERIA_V2
}

data class ServerItem(
    val id: String,
    val name: String,
    val flag: String,
    val host: String,
    val port: Int,
    val protocol: VpnProtocol = VpnProtocol.VLESS,
    val rawUri: String = "",
    val latencyMs: Int? = null
)

object VpnStateHolder {
    private val _isConnected = MutableStateFlow(false)
    val isConnected = _isConnected.asStateFlow()

    private val _liveLatency = MutableStateFlow<Int?>(null)
    val liveLatency = _liveLatency.asStateFlow()

    private val _activeCountryFlag = MutableStateFlow("🌐")
    val activeCountryFlag = _activeCountryFlag.asStateFlow()

    private val _activeCountryName = MutableStateFlow("Bağlı Değil")
    val activeCountryName = _activeCountryName.asStateFlow()

    private val _servers = MutableStateFlow(
        listOf(
            ServerItem("auto", "Auto", "⚡", "1.1.1.1", 443, VpnProtocol.VLESS, "", 47),
            ServerItem("de-1", "Germania #1", "🇩🇪", "185.220.101.1", 443, VpnProtocol.VLESS, "vless://...", 47),
            ServerItem("nl-1", "Nederland #1", "🇳🇱", "185.220.101.2", 443, VpnProtocol.TROJAN, "trojan://...", 90),
            ServerItem("us-1", "United States #1", "🇺🇸", "185.220.101.3", 443, VpnProtocol.HYSTERIA_V2, "hysteria2://...", 140)
        )
    )
    val servers = _servers.asStateFlow()

    fun resolveBestAutoServer(): ServerItem {
        val candidates = _servers.value.filterNot { it.id == "auto" }
        return candidates.minByOrNull { it.latencyMs ?: Int.MAX_VALUE } ?: candidates.first()
    }

    fun setConnection(connected: Boolean, selectedServer: ServerItem? = null) {
        _isConnected.value = connected
        if (!connected) {
            _liveLatency.value = null
            _activeCountryFlag.value = selectedServer?.flag ?: "🌐"
            _activeCountryName.value = selectedServer?.name ?: "Bağlı Değil"
        } else if (selectedServer != null) {
            val target = if (selectedServer.id == "auto") resolveBestAutoServer() else selectedServer
            _activeCountryFlag.value = target.flag
            _activeCountryName.value = target.name
            _liveLatency.value = target.latencyMs
        }
    }

    fun updateLatency(serverId: String, ms: Int?) {
        _servers.value = _servers.value.map {
            if (it.id == serverId) it.copy(latencyMs = ms) else it
        }
    }

    fun setActiveTelemetry(flag: String, name: String, ms: Int?) {
        _activeCountryFlag.value = flag
        _activeCountryName.value = name
        _liveLatency.value = ms
    }

    fun addServersFromInput(input: String) {
        val lines = input.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val parsed = lines.mapIndexed { idx, line ->
            val proto = when {
                line.startsWith("vless://") -> VpnProtocol.VLESS
                line.startsWith("vmess://") -> VpnProtocol.VMESS
                line.startsWith("trojan://") -> VpnProtocol.TROJAN
                line.startsWith("ss://") -> VpnProtocol.SHADOWSOCKS
                line.startsWith("hysteria2://") || line.startsWith("hy2://") -> VpnProtocol.HYSTERIA_V2
                line.startsWith("hysteria://") -> VpnProtocol.HYSTERIA_V1
                else -> VpnProtocol.VLESS
            }
            val host = line.substringAfter("@", "").substringAfter("://", "").substringBefore(":").ifEmpty { "de.sohbetvpn.local" }
            val port = line.substringAfter("$host:", "").substringBefore("?").substringBefore("#").toIntOrNull() ?: 443
            val label = line.substringAfter("#", "").ifEmpty { "Sunucu #${_servers.value.size + idx + 1}" }
            val flag = when {
                label.contains("germania", true) || label.contains("almanya", true) || label.contains("de", true) -> "🇩🇪"
                label.contains("nederland", true) || label.contains("hollanda", true) || label.contains("nl", true) -> "🇳🇱"
                label.contains("france", true) || label.contains("fransa", true) || label.contains("fr", true) -> "🇫🇷"
                label.contains("united states", true) || label.contains("abd", true) || label.contains("us", true) -> "🇺🇸"
                label.contains("uk", true) || label.contains("ingiltere", true) || label.contains("gb", true) -> "🇬🇧"
                else -> "🌍"
            }
            ServerItem(
                id = "srv_${System.currentTimeMillis()}_$idx",
                name = label,
                flag = flag,
                host = host,
                port = port,
                protocol = proto,
                rawUri = line,
                latencyMs = null
            )
        }
        if (parsed.isNotEmpty()) {
            val currentAuto = _servers.value.firstOrNull { it.id == "auto" }
            val others = _servers.value.filterNot { it.id == "auto" } + parsed
            _servers.value = listOfNotNull(currentAuto) + others
        }
    }
}

object LatencyProbe {
    suspend fun measureMs(host: String, port: Int, timeoutMs: Int = 2500): Int? = withContext(Dispatchers.IO) {
        val start = System.nanoTime()
        return@withContext try {
            Socket().use { socket ->
                socket.soTimeout = timeoutMs
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            ((System.nanoTime() - start) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun refreshAllServers() = coroutineScope {
        val list = VpnStateHolder.servers.value
        val jobs = list.filterNot { it.id == "auto" }.map { srv ->
            async {
                val ms = measureMs(srv.host, srv.port)
                VpnStateHolder.updateLatency(srv.id, ms)
                ms
            }
        }
        val bestAuto = jobs.awaitAll().filterNotNull().minOrNull()
        VpnStateHolder.updateLatency("auto", bestAuto)
    }
}

fun buildSingBoxConfigJson(server: ServerItem): String {
    val outboundType = when (server.protocol) {
        VpnProtocol.VLESS -> "vless"
        VpnProtocol.VMESS -> "vmess"
        VpnProtocol.TROJAN -> "trojan"
        VpnProtocol.SHADOWSOCKS -> "shadowsocks"
        VpnProtocol.HYSTERIA_V1 -> "hysteria"
        VpnProtocol.HYSTERIA_V2 -> "hysteria2"
    }
    return """
        {
          "log": { "level": "warn" },
          "dns": {
            "servers": [{ "tag": "dns-remote", "address": "1.1.1.1" }],
            "strategy": "prefer_ipv4"
          },
          "inbounds": [
            {
              "type": "tun",
              "tag": "tun-in",
              "interface_name": "tun0",
              "inet4_address": "172.19.0.1/30",
              "mtu": 1400,
              "auto_route": true,
              "strict_route": true,
              "stack": "mixed"
            }
          ],
          "outbounds": [
            {
              "type": "$outboundType",
              "tag": "proxy",
              "server": "${server.host}",
              "server_port": ${server.port}
            },
            { "type": "direct", "tag": "direct" }
          ],
          "route": {
            "final": "proxy",
            "auto_detect_interface": true
          }
        }
    """.trimIndent()
}

class HourlyServerUpdateWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        LatencyProbe.refreshAllServers()
        return Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<HourlyServerUpdateWorker>(1, TimeUnit.HOURS)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "sohbet_vpn_hourly_update",
                ExistingPeriodicWorkPolicy.UPDATE,
                req
            )
        }
    }
}

class SohbetVpnService : VpnService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeServer: ServerItem = VpnStateHolder.servers.value.first()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopVpnSession()
                return START_NOT_STICKY
            }
            ACTION_SELECT_SERVER -> {
                val id = intent.getStringExtra(EXTRA_SERVER_ID)
                VpnStateHolder.servers.value.find { it.id == id }?.let { activeServer = it }
            }
        }
        val target = if (activeServer.id == "auto") VpnStateHolder.resolveBestAutoServer() else activeServer
        startForegroundNotification(target)
        establishTunAndSingBox(target)
        observeNetworkTransitions()
        startLatencyTelemetry()
        return START_STICKY
    }

    private fun establishTunAndSingBox(target: ServerItem) {
        val builder = Builder()
            .setSession("Sohbet VPN")
            .setMtu(1400)
            .addAddress("172.19.0.1", 30)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        val pfd = builder.establish() ?: return
        val singBoxJson = buildSingBoxConfigJson(target)
        val _unusedConfig = singBoxJson
        val _unusedFd = pfd
        VpnStateHolder.setConnection(true, activeServer)
    }

    private fun observeNetworkTransitions() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                setUnderlyingNetworks(arrayOf(network))
            }
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                setUnderlyingNetworks(arrayOf(network))
            }
        }
        networkCallback = cb
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            cb
        )
    }

    private fun startLatencyTelemetry() {
        serviceScope.launch {
            while (isActive && VpnStateHolder.isConnected.value) {
                val target = if (activeServer.id == "auto") VpnStateHolder.resolveBestAutoServer() else activeServer
                val ms = LatencyProbe.measureMs(target.host, target.port)
                VpnStateHolder.updateLatency(target.id, ms)
                if (activeServer.id == "auto") {
                    val bestMs = VpnStateHolder.servers.value.filterNot { it.id == "auto" }.mapNotNull { it.latencyMs }.minOrNull()
                    VpnStateHolder.updateLatency("auto", bestMs)
                }
                VpnStateHolder.setActiveTelemetry(target.flag, target.name, ms)
                delay(2000)
            }
        }
    }

    private fun stopVpnSession() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        networkCallback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        serviceScope.coroutineContext.cancelChildren()
        VpnStateHolder.setConnection(false, activeServer)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun startForegroundNotification(target: ServerItem) {
        val channelId = "sohbet_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Sohbet VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Sohbet VPN")
                .setContentText("Bağlandı • ${target.flag} ${target.name}")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Sohbet VPN")
                .setContentText("Bağlandı • ${target.flag} ${target.name}")
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .build()
        }
        startForeground(1001, notif)
    }

    override fun onDestroy() {
        stopVpnSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.sohbet.vpn.STOP"
        const val ACTION_SELECT_SERVER = "com.sohbet.vpn.SELECT_SERVER"
        const val EXTRA_SERVER_ID = "extra_server_id"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HourlyServerUpdateWorker.schedule(this)
        setContent {
            var showSplash by remember { mutableStateOf(true) }
            LaunchedEffect(Unit) {
                delay(1800)
                showSplash = false
            }
            if (showSplash) {
                SplashScreen()
            } else {
                MainDashboard(
                    onToggleVpn = { connect, server ->
                        val intent = Intent(this, SohbetVpnService::class.java).apply {
                            if (!connect) {
                                action = SohbetVpnService.ACTION_STOP
                            } else {
                                action = SohbetVpnService.ACTION_SELECT_SERVER
                                putExtra(SohbetVpnService.EXTRA_SERVER_ID, server.id)
                            }
                        }
                        if (connect) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                startForegroundService(intent)
                            } else {
                                startService(intent)
                            }
                        } else {
                            startService(intent)
                        }
                    },
                    onSaveSubscription = { url ->
                        getSharedPreferences("sohbet_vpn_prefs", Context.MODE_PRIVATE)
                            .edit()
                            .putString("subscription_url", url)
                            .apply()
                    }
                )
            }
        }
    }
}

@Composable
fun SplashScreen() {
    val pulse = rememberInfiniteTransition(label = "splash")
    val alpha by pulse.animateFloat(
        initialValue = 0.25f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse), label = "alpha"
    )
    Box(
        modifier = Modifier.fillMaxSize().background(DeepNavy),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "S",
            color = ElectricBlue,
            fontSize = 96.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.alpha(alpha)
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainDashboard(
    onToggleVpn: (Boolean, ServerItem) -> Unit,
    onSaveSubscription: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val isConnected by VpnStateHolder.isConnected.collectAsState()
    val liveLatency by VpnStateHolder.liveLatency.collectAsState()
    val servers by VpnStateHolder.servers.collectAsState()
    val activeFlag by VpnStateHolder.activeCountryFlag.collectAsState()
    val activeName by VpnStateHolder.activeCountryName.collectAsState()

    var selectedServer by remember { mutableStateOf(servers.first()) }
    var showAddMenu by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }
    var showServerSheet by remember { mutableStateOf(false) }
    var subUrl by remember { mutableStateOf("") }
    var qrRawInput by remember { mutableStateOf("") }

    LaunchedEffect(servers) {
        servers.find { it.id == selectedServer.id }?.let { selectedServer = it }
    }

    val pulse = rememberInfiniteTransition(label = "ring")
    val ringScale by pulse.animateFloat(
        initialValue = 1f, targetValue = if (isConnected) 1.35f else 1.08f,
        animationSpec = infiniteRepeatable(tween(if (isConnected) 900 else 1600), RepeatMode.Restart), label = "scale"
    )
    val ringAlpha by pulse.animateFloat(
        initialValue = 0.75f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(if (isConnected) 900 else 1600), RepeatMode.Restart), label = "ringAlpha"
    )

    Column(
        modifier = Modifier.fillMaxSize().background(DeepNavy).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { scope.launch { LatencyProbe.refreshAllServers() } }) {
                Icon(Icons.Default.Refresh, contentDescription = "Güncelle", tint = ElectricBlue)
            }
            Text("Sohbet VPN", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Box {
                IconButton(onClick = { showAddMenu = true }) {
                    Icon(
