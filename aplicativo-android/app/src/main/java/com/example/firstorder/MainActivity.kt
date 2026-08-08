package com.example.firstorder

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.*
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.model.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.material3.TopAppBarDefaults

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*

import androidx.compose.ui.graphics.vector.ImageVector




class MainActivity : ComponentActivity() {

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private val LOCATION_PERMISSION_REQUEST_CODE = 1001

    private fun directionToIndex(direction: String): Int {
        return when (direction) {
            "Norte" -> 0
            "Nordeste" -> 1
            "Leste" -> 2
            "Sudeste" -> 3
            "Sul" -> 4
            "Sudoeste" -> 5
            "Oeste" -> 6
            "Noroeste" -> 7
            else -> 0
        }
    }

    private fun distanceToFrequency(distanceMeters: Double): Float {
        val minDist = 3.0
        val maxDist = 1500.0

        val minFreq = 0.1
        val maxFreq = 1.0

        val d = distanceMeters.coerceIn(minDist, maxDist)
        val t = (d - minDist) / (maxDist - minDist)

        val adjusted = Math.pow(t, 0.7)

        val freq = maxFreq + (minFreq - maxFreq) * adjusted

        return freq.toFloat()
    }

    private fun shouldSendNow(
        nowMs: Long,
        lastSendMs: Long,
        dir: Int,
        lastDir: Int,
        freq: Float,
        lastFreq: Float,
        minIntervalMs: Long = 250L,
        minFreqDelta: Float = 0.2f
    ): Boolean {
        if (nowMs - lastSendMs < minIntervalMs) return false
        if (dir != lastDir) return true
        if (kotlin.math.abs(freq - lastFreq) >= minFreqDelta) return true
        return false
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)

        setContent {
            MaterialTheme {
                val context = LocalContext.current

                // ---------- Estado de UI / Log BLE ----------
                var bleLog by remember { mutableStateOf("—") }
                var bleReady by remember { mutableStateOf(false) }

                // ---------- Permissões BLE (Android 12+) ----------
                val blePermissions = remember {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        arrayOf(
                            Manifest.permission.BLUETOOTH_SCAN,
                            Manifest.permission.BLUETOOTH_CONNECT
                        )
                    } else {
                        // Em versões antigas, scan costuma exigir Location
                        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }

                val permLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { result ->
                    val ok = result.values.all { it }
                    bleLog = if (ok) "Permissões concedidas ✅" else "Permissões negadas ❌"
                }

                fun ensureBlePermissions() {
                    val allGranted = blePermissions.all { p ->
                        ActivityCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
                    }
                    if (!allGranted) permLauncher.launch(blePermissions)
                }

                // ✅ Aqui está o “pulo do gato”: usar onLog como NOMEADO
                val bleClient = remember {
                    BleClient(
                        context = context,
                        onLog = { msg -> bleLog = msg },
                        onReadyChanged = { ready ->
                            bleReady = ready
                            bleLog = if (ready) "Conectado ao dispositivo ✅" else "Conectando ao dispositivo..."
                        }
                    )
                }

                // Desconecta ao sair da tela
                DisposableEffect(Unit) {
                    onDispose { bleClient.disconnect() }
                }

                // ---------- States de localização/mapa ----------
                var userLocation by remember { mutableStateOf<Location?>(null) }
                var savedLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
                var localizacaoDestino by remember { mutableStateOf<Pair<Double, Double>?>(null) }
                var currentLocation by remember { mutableStateOf<Pair<Double, Double>?>(null) }
                var distanceBetween by remember { mutableStateOf<Double?>(null) }
                var currentDistance by remember { mutableStateOf<Double?>(null) }

                var mapMarker: Marker? by remember { mutableStateOf(null) }
                var destinationMarker: Marker? by remember { mutableStateOf(null) }
                var googleMap: GoogleMap? by remember { mutableStateOf(null) }
                var routePolyline: Polyline? by remember { mutableStateOf(null) }
                var directionText by remember { mutableStateOf("") }

                var lastSendMs by remember { mutableStateOf(0L) }
                var lastDirSent by remember { mutableStateOf(-1) }
                var lastFreqSent by remember { mutableStateOf(-1f) }

                var lastSentDirectionText by remember { mutableStateOf("—") }
                var lastSentDirectionIndex by remember { mutableStateOf(-1) }
                var lastSentFrequency by remember { mutableStateOf(0f) }

                var lastLocationForSpeed by remember { mutableStateOf<Location?>(null) }
                var lastTimeMsForSpeed by remember { mutableStateOf<Long?>(null) }
                var currentSpeedKmh by remember { mutableStateOf(0f) }

                // Função central: calcula e manda (sem depender de state antigo)
                fun trySendBle(current: Pair<Double, Double>?, dest: Pair<Double, Double>?) {
                    if (current == null || dest == null) return
                    if (!bleReady) {
                        // Não spamma: só informa
                        bleLog = "Aguardando conexão com o dispositivo..."
                        return
                    }

                    val distNow = calculateDistance(current, dest) ?: return
                    val arrivalRadiusMeters = 5.0

                    if (distNow <= arrivalRadiusMeters) {

                        val dir = 8
                        val freq = 2f
                        val now = SystemClock.elapsedRealtime()

                        if (shouldSendNow(
                                now,
                                lastSendMs,
                                dir,
                                lastDirSent,
                                freq,
                                lastFreqSent,
                                minIntervalMs = 500L
                            )) {

                            bleClient.sendCommand(dir, freq)

                            lastSendMs = now
                            lastDirSent = dir
                            lastFreqSent = freq

                            lastSentDirectionText = "Destino alcançado"
                            lastSentDirectionIndex = dir
                            lastSentFrequency = freq

                            directionText = "Destino"
                        }

                        return
                    }

                    val dirTxtNow = calculateDirection(current, dest)
                    if (dirTxtNow.isEmpty()) return

                    val dir = directionToIndex(dirTxtNow)
                    val freq = distanceToFrequency(distNow)
                    val now = SystemClock.elapsedRealtime()

                    val minInterval = speedToMinIntervalMs(currentSpeedKmh)

                    if (shouldSendNow(
                            now,
                            lastSendMs,
                            dir,
                            lastDirSent,
                            freq,
                            lastFreqSent,
                            minIntervalMs = minInterval
                        )) {
                        bleClient.sendCommand(dir, freq)

                        lastSendMs = now
                        lastDirSent = dir
                        lastFreqSent = freq

                        // feedback visual na interface
                        lastSentDirectionText = dirTxtNow
                        lastSentDirectionIndex = dir
                        lastSentFrequency = freq
                    }
                }

                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text("Aplicação de Localização") },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.background
                            )
                        )
                    }
                ) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .padding(16.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {

                        CardSection(title = "Ações", icon = Icons.Default.RocketLaunch) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        MainButton(
                                            text = "Capturar",
                                            buttonIcon = Icons.Default.MyLocation,
                                            onClick = {
                                                getCurrentLocation(context) { location ->
                                                    if (location != null) {
                                                        userLocation = location
                                                        savedLocation = Pair(location.latitude, location.longitude)

                                                        googleMap?.let { map ->
                                                            val currentLatLng = LatLng(location.latitude, location.longitude)
                                                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                                                            mapMarker?.remove()
                                                            mapMarker = map.addMarker(
                                                                MarkerOptions().position(currentLatLng).title("Você está aqui")
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            isPrimary = true
                                        )
                                    }

                                    Box(modifier = Modifier.weight(1f)) {
                                        MainButton(
                                            text = "Maps",
                                            buttonIcon = Icons.Default.Map,
                                            onClick = {
                                                val gmmIntentUri = Uri.parse("geo:0,0?q=")
                                                val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                                                mapIntent.setPackage("com.google.android.apps.maps")
                                                context.startActivity(mapIntent)
                                            },
                                            isPrimary = false
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        MainButton(
                                            text = "Conectar",
                                            buttonIcon = Icons.Default.Bluetooth,
                                            onClick = {
                                                ensureBlePermissions()
                                                bleClient.startScan("ESP32_DIRECAO")
                                            },
                                            isPrimary = true
                                        )
                                    }

                                    Box(modifier = Modifier.weight(1f)) {
                                        MainButton(
                                            text = "Desconectar",
                                            buttonIcon = Icons.Default.BluetoothDisabled,
                                            onClick = { bleClient.disconnect() },
                                            isPrimary = false
                                        )
                                    }
                                }
                            }
                        }

                        CardSection(title = "BLE Status", icon = Icons.Default.SettingsInputAntenna) {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                InfoRow("Status", if (bleReady) "🟢 Conectado" else "🔴 Desconectado")
                                if (!bleLog.startsWith("Enviando")) {
                                    Text(
                                        text = bleLog,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }

                        CardSection(title = "Status", icon = Icons.Default.Dashboard) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    StatusBox(
                                        label = "Ponto Inicial",
                                        value = savedLocation?.let { "Lat=${"%.5f".format(it.first)}\nLon=${"%.5f".format(it.second)}" } ?: "—",
                                        icon = Icons.Default.Place,
                                        modifier = Modifier.weight(1f)
                                    )
                                    StatusBox(
                                        label = "Local Atual",
                                        value = currentLocation?.let { "Lat=${"%.5f".format(it.first)}\nLon=${"%.5f".format(it.second)}" } ?: "—",
                                        icon = Icons.Default.MyLocation,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    StatusBox(
                                        label = "Destino",
                                        value = localizacaoDestino?.let { "Lat=${"%.5f".format(it.first)}\nLon=${"%.5f".format(it.second)}" } ?: "—",
                                        icon = Icons.Default.Flag,
                                        modifier = Modifier.weight(1f)
                                    )
                                    StatusBox(
                                        label = "Distância Total",
                                        value = distanceBetween?.let { "${"%.2f".format(it)} m" } ?: "—",
                                        icon = Icons.Default.Route,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    StatusBox(
                                        label = "Distância Atual",
                                        value = currentDistance?.let { "${"%.2f".format(it)} m" } ?: "—",
                                        icon = Icons.Default.NearMe,
                                        modifier = Modifier.weight(1f)
                                    )
                                    StatusBox(
                                        label = "Direção",
                                        value = if (directionText.isNotEmpty()) directionText else "—",
                                        icon = Icons.Default.Navigation,
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                StatusBox(
                                    label = "Velocidade",
                                    value = "${"%.2f".format(currentSpeedKmh)} km/h",
                                    icon = Icons.Default.Speed,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        CardSection(title = "Feedback Enviado", icon = Icons.Default.Send) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {

                                StatusBox(
                                    label = "Direção",
                                    value = lastSentDirectionText,
                                    icon = Icons.Default.Navigation,
                                    modifier = Modifier.weight(1f)
                                )

                                StatusBox(
                                    label = "Índice",
                                    value = if (lastSentDirectionIndex >= 0)
                                        lastSentDirectionIndex.toString()
                                    else "—",
                                    icon = Icons.Default.Tag,
                                    modifier = Modifier.weight(1f)
                                )

                                StatusBox(
                                    label = "Frequência",
                                    value = "${"%.2f".format(lastSentFrequency)} Hz",
                                    icon = Icons.Default.GraphicEq,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        CardSection(title = "Direção no Canvas", icon = Icons.Default.Explore) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(220.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val activeColor = MaterialTheme.colorScheme.primary
                                val inactiveColor = Color.LightGray

                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    val centerX = size.width / 2
                                    val centerY = size.height / 2
                                    val radius = size.minDimension / 3

                                    for (i in 0 until 8) {
                                        val rotatedIndex = (i + 6) % 8
                                        val angle = rotatedIndex * (2 * PI / 8)
                                        val x = centerX + radius * cos(angle).toFloat()
                                        val y = centerY + radius * sin(angle).toFloat()

                                        val isArrivalMode = directionText == "Destino"

                                        val isActive =
                                            isArrivalMode || directionText == getDirectionFromIndex(i)

                                        drawCircle(
                                            color = if (isActive) activeColor else inactiveColor,
                                            radius = if (isActive) 24f else 18f,
                                            center = Offset(x, y)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surface
                            ) {
                                Text(
                                    text = if (directionText.isNotEmpty()) "Ativo: $directionText" else "Ativo: —",
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        CardSection(title = "Mapa", icon = Icons.Default.Map) {
                            AndroidView(
                                factory = { ctx ->
                                    MapView(ctx).apply {
                                        onCreate(null)
                                        onResume()
                                        getMapAsync(OnMapReadyCallback { map ->
                                            googleMap = map
                                            map.uiSettings.isZoomControlsEnabled = true

                                            map.setOnMapClickListener { latLng ->
                                                val destPair = Pair(latLng.latitude, latLng.longitude)
                                                localizacaoDestino = destPair

                                                destinationMarker?.remove()
                                                destinationMarker = map.addMarker(
                                                    MarkerOptions().position(latLng).title("Destino")
                                                )

                                                drawRoute(savedLocation, destPair, map, routePolyline)
                                                    ?.let { poly -> routePolyline = poly }

                                                distanceBetween = calculateDistance(savedLocation, destPair)

                                                val curr = currentLocation
                                                currentDistance = calculateDistance(curr, destPair)
                                                directionText = calculateDirection(curr, destPair)

                                                // manda BLE imediatamente ao trocar destino
                                                trySendBle(curr, destPair)
                                            }

                                            if (ActivityCompat.checkSelfPermission(
                                                    ctx,
                                                    Manifest.permission.ACCESS_FINE_LOCATION
                                                ) == PackageManager.PERMISSION_GRANTED
                                            ) {
                                                map.isMyLocationEnabled = true

                                                fusedLocationClient.requestLocationUpdates(
                                                    LocationRequest.Builder(
                                                        Priority.PRIORITY_HIGH_ACCURACY,
                                                        5000L
                                                    ).build(),
                                                    object : LocationCallback() {
                                                        override fun onLocationResult(result: LocationResult) {
                                                            result.lastLocation?.let { location ->
                                                                val currPair = Pair(location.latitude, location.longitude)
                                                                val currentLatLng = LatLng(location.latitude, location.longitude)
                                                                val nowMs = System.currentTimeMillis()

                                                                currentSpeedKmh = calculateSpeedKmh(
                                                                    newLocation = location,
                                                                    oldLocation = lastLocationForSpeed,
                                                                    oldTimeMs = lastTimeMsForSpeed,
                                                                    newTimeMs = nowMs
                                                                )

                                                                lastLocationForSpeed = location
                                                                lastTimeMsForSpeed = nowMs



                                                                if (mapMarker == null) {
                                                                    mapMarker = map.addMarker(
                                                                        MarkerOptions().position(currentLatLng).title("Você está aqui")
                                                                    )
                                                                } else {
                                                                    mapMarker?.position = currentLatLng
                                                                }

                                                                map.animateCamera(CameraUpdateFactory.newLatLngZoom(currentLatLng, 15f))
                                                                currentLocation = currPair

                                                                val dest = localizacaoDestino
                                                                currentDistance = calculateDistance(currPair, dest)
                                                                directionText = calculateDirection(currPair, dest)

                                                                // manda BLE em cada atualização real de localização
                                                                trySendBle(currPair, dest)
                                                            }
                                                        }
                                                    },
                                                    null
                                                )
                                            }
                                        })
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(320.dp)
                            )
                        }

                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }

    // ---------- Lógica ----------
    private fun calculateDirection(current: Pair<Double, Double>?, destination: Pair<Double, Double>?): String {
        if (current == null || destination == null) return ""

        val dx = destination.second - current.second
        val dy = destination.first - current.first

        val angleRad = kotlin.math.atan2(dy, dx)
        var angleDeg = Math.toDegrees(angleRad)
        if (angleDeg < 0) angleDeg += 360.0

        return when {
            angleDeg >= 337.5 || angleDeg < 22.5 -> "Leste"
            angleDeg >= 22.5 && angleDeg < 67.5 -> "Nordeste"
            angleDeg >= 67.5 && angleDeg < 112.5 -> "Norte"
            angleDeg >= 112.5 && angleDeg < 157.5 -> "Noroeste"
            angleDeg >= 157.5 && angleDeg < 202.5 -> "Oeste"
            angleDeg >= 202.5 && angleDeg < 247.5 -> "Sudoeste"
            angleDeg >= 247.5 && angleDeg < 292.5 -> "Sul"
            angleDeg >= 292.5 && angleDeg < 337.5 -> "Sudeste"
            else -> ""
        }
    }

    private fun getDirectionFromIndex(index: Int): String {
        return when (index) {
            0 -> "Norte"
            1 -> "Nordeste"
            2 -> "Leste"
            3 -> "Sudeste"
            4 -> "Sul"
            5 -> "Sudoeste"
            6 -> "Oeste"
            7 -> "Noroeste"
            else -> ""
        }
    }

    private fun getCurrentLocation(context: Context, onSuccess: (Location?) -> Unit) {
        if (ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                onSuccess(location)
            }
        } else {
            onSuccess(null)
        }
    }

    private fun calculateDistance(start: Pair<Double, Double>?, end: Pair<Double, Double>?): Double? {
        if (start == null || end == null) return null
        val result = FloatArray(1)
        Location.distanceBetween(start.first, start.second, end.first, end.second, result)
        return result[0].toDouble()
    }

    private fun calculateSpeedKmh(
        newLocation: Location,
        oldLocation: Location?,
        oldTimeMs: Long?,
        newTimeMs: Long
    ): Float {
        if (oldLocation == null || oldTimeMs == null) return 0f

        val distanceMeters = oldLocation.distanceTo(newLocation)
        val deltaTimeSeconds = (newTimeMs - oldTimeMs) / 1000f

        if (deltaTimeSeconds <= 0f) return 0f

        val speedMps = distanceMeters / deltaTimeSeconds
        var speedKmh = speedMps * 3.6f

        // filtro de ruído do GPS
        if (speedKmh < 5f) speedKmh = 0f

        return speedKmh
    }

    private fun speedToMinIntervalMs(speedKmh: Float): Long {
        return when {
            speedKmh > 40f -> 120L
            speedKmh > 15f -> 180L
            speedKmh > 3f  -> 300L
            else           -> 500L
        }
    }

    private fun drawRoute(
        start: Pair<Double, Double>?,
        end: Pair<Double, Double>?,
        map: GoogleMap,
        polyline: Polyline?
    ): Polyline? {
        if (start == null || end == null) return null
        polyline?.remove()
        return map.addPolyline(
            PolylineOptions()
                .add(LatLng(start.first, start.second), LatLng(end.first, end.second))
                .color(0xFFFF0000.toInt())
                .width(5f)
        )
    }
}

// ---------- Composables ----------
@Composable
fun CardSection(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            content()
        }
    }
}

@Composable
fun MainButton(
    text: String,
    onClick: () -> Unit,
    isPrimary: Boolean = true,
    buttonIcon: ImageVector
) {
    val containerColor = if (isPrimary)
        MaterialTheme.colorScheme.primary
    else
        MaterialTheme.colorScheme.surface

    val contentColor = if (isPrimary)
        MaterialTheme.colorScheme.onPrimary
    else
        MaterialTheme.colorScheme.primary

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        )
    ) {
        Icon(
            imageVector = buttonIcon,
            contentDescription = text
        )
        Spacer(Modifier.width(8.dp))
        Text(text)
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value)
    }
}

@Composable
fun StatusBox(
    label: String,
    value: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(6.dp))

                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3
            )
        }
    }
}