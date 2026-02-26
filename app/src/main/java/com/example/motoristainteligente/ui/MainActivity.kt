@file:Suppress("DEPRECATION")

package com.example.motoristainteligente

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ElectricCar
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.TrendingDown
import androidx.compose.material.icons.filled.TrendingFlat
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowDropUp
import androidx.compose.material.icons.filled.MonetizationOn
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.motoristainteligente.ui.theme.MotoristainteligenteTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

// ===============================================
// Definição das telas do menu
// ===============================================
enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Início", Icons.Default.Home),
    RIDE_SETTINGS("Configurar Corrida", Icons.Default.Settings),
    DEMAND_BY_REGION("Demanda por Região", Icons.Default.Map),
    WEEKLY_COMPARISON("Resumo Semanal", Icons.Default.CalendarMonth),
    PERMISSIONS("Permissões", Icons.Default.Lock),
    TIPS("Dicas de Uso", Icons.Default.Lightbulb),
    LOGIN("Login", Icons.Default.Person)
}

private data class DemandTrendUi(
    val color: Color,
    val icon: ImageVector
)

private fun resolveDemandTrendUi(trend: FirestoreManager.DemandPeakTrend): DemandTrendUi {
    val trendColor = when (trend) {
        FirestoreManager.DemandPeakTrend.RISING -> Color(0xFF2E7D32)
        FirestoreManager.DemandPeakTrend.FALLING -> Color(0xFFD32F2F)
        FirestoreManager.DemandPeakTrend.STABLE -> Color(0xFF757575)
    }

    val trendIcon = when (trend) {
        FirestoreManager.DemandPeakTrend.RISING -> Icons.Default.TrendingUp
        FirestoreManager.DemandPeakTrend.FALLING -> Icons.Default.TrendingDown
        FirestoreManager.DemandPeakTrend.STABLE -> Icons.Default.TrendingFlat
    }

    return DemandTrendUi(color = trendColor, icon = trendIcon)
}

// ===============================================
// Activity
// ===============================================
class MainActivity : ComponentActivity() {

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { /* Verificado no onResume via lifecycle observer */ }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* Verificado no onResume */ }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Não bloqueante */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MotoristainteligenteTheme {
                var isServiceRunning by remember {
                    mutableStateOf(FloatingAnalyticsService.instance != null)
                }
                var isAnalysisPaused by remember {
                    mutableStateOf(AnalysisServiceState.isPaused(this@MainActivity))
                }
                var lifecycleRefreshTick by remember { mutableStateOf(0) }

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isServiceRunning = FloatingAnalyticsService.instance != null
                            isAnalysisPaused = AnalysisServiceState.isPaused(this@MainActivity)
                            lifecycleRefreshTick++
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        val runningNow =
                            FloatingAnalyticsService.instance != null &&
                                AnalysisServiceState.isEnabled(this@MainActivity)
                        if (runningNow != isServiceRunning) {
                            isServiceRunning = runningNow
                            if (!runningNow) {
                                isAnalysisPaused = false
                            }
                        }

                        val pausedNow = AnalysisServiceState.isPaused(this@MainActivity)
                        if (pausedNow != isAnalysisPaused) {
                            isAnalysisPaused = pausedNow
                        }
                        delay(350)
                    }
                }

                AppWithDrawer(
                    isServiceRunning = isServiceRunning,
                    isAnalysisPaused = isAnalysisPaused,
                    refreshTick = lifecycleRefreshTick,
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onRequestLocationPermission = { requestLocationPermission() },
                    onStartService = {
                        startFloatingService()
                        AnalysisServiceState.setPaused(this@MainActivity, false)
                        isAnalysisPaused = false
                        Handler(Looper.getMainLooper()).postDelayed({
                            isServiceRunning = FloatingAnalyticsService.instance != null
                        }, 800)
                    },
                    onStopService = {
                        stopFloatingService()
                        AnalysisServiceState.setPaused(this@MainActivity, false)
                        isServiceRunning = false
                        isAnalysisPaused = false
                    },
                    onTogglePause = {
                        val next = !AnalysisServiceState.isPaused(this@MainActivity)
                        AnalysisServiceState.setPaused(this@MainActivity, next)
                        isAnalysisPaused = next
                    }
                )
            }
        }
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            overlayPermissionLauncher.launch(intent)
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        Toast.makeText(this, "Ative o serviço 'Motorista Inteligente'", Toast.LENGTH_LONG).show()
    }

    private fun requestLocationPermission() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun startFloatingService() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            Toast.makeText(this, "Permissão de sobreposição necessária", Toast.LENGTH_SHORT).show()
            return
        }
        if (!isAccessibilityServiceEnabled()) {
            openAccessibilitySettings()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestLocationPermission()
            Toast.makeText(this, "Permissão de localização necessária", Toast.LENGTH_SHORT).show()
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val intent = Intent(this, FloatingAnalyticsService::class.java)
        AnalysisServiceState.setEnabled(this, true)
        startForegroundService(intent)
        Toast.makeText(this, "Análise de corridas iniciada!", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
        AnalysisServiceState.setEnabled(this, false)
        stopService(Intent(this, FloatingAnalyticsService::class.java))
        Toast.makeText(this, "Serviço parado", Toast.LENGTH_SHORT).show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = "$packageName/${RideInfoOcrService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.contains(service)
    }
}

// ===============================================
// App com Drawer (Menu Hamburger)
// ===============================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppWithDrawer(
    isServiceRunning: Boolean,
    isAnalysisPaused: Boolean,
    refreshTick: Int,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onTogglePause: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    val context = LocalContext.current
    val firestoreManager = remember { FirestoreManager(context) }
    val driverPreferences = remember { DriverPreferences(context) }

    // Forçar recomposição quando estado de auth muda
    var authRefresh by remember { mutableStateOf(0) }

    LaunchedEffect(authRefresh) {
        driverPreferences.firestoreManager = firestoreManager
        if (firestoreManager.isGoogleUser) {
            firestoreManager.loadPreferences(driverPreferences)
        } else {
            driverPreferences.applyToAnalyzer()
        }
    }

    // Back button: fechar drawer > voltar para HOME > sair do app
    val activity = LocalContext.current as? Activity
    BackHandler(enabled = true) {
        when {
            drawerState.isOpen -> scope.launch { drawerState.close() }
            currentScreen != Screen.HOME -> currentScreen = Screen.HOME
            else -> activity?.finish()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                currentScreen = currentScreen,
                onScreenSelected = { screen ->
                    currentScreen = screen
                    scope.launch { drawerState.close() }
                },
                firestoreManager = firestoreManager,
                authRefresh = authRefresh
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            text = currentScreen.title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Default.Menu,
                                contentDescription = "Menu"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        titleContentColor = MaterialTheme.colorScheme.onBackground
                    )
                )
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                when (currentScreen) {
                    Screen.HOME -> HomeScreen(
                        isServiceRunning = isServiceRunning,
                        isAnalysisPaused = isAnalysisPaused,
                        onStartService = onStartService,
                        onStopService = onStopService,
                        onTogglePause = onTogglePause,
                        onNavigateToPermissions = { currentScreen = Screen.PERMISSIONS },
                        firestoreManager = firestoreManager
                    )
                    Screen.RIDE_SETTINGS -> RideSettingsScreen(
                        firestoreManager = firestoreManager
                    )
                    Screen.DEMAND_BY_REGION -> DemandByRegionScreen(
                        firestoreManager = firestoreManager
                    )
                    Screen.WEEKLY_COMPARISON -> WeeklyComparisonScreen()
                    Screen.PERMISSIONS -> PermissionsScreen(
                        refreshTick = refreshTick,
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onRequestLocationPermission = onRequestLocationPermission
                    )
                    Screen.TIPS -> TipsScreen()
                    Screen.LOGIN -> LoginScreen(
                        firestoreManager = firestoreManager,
                        onLoginSuccess = { authRefresh++ },
                        onLogoutSuccess = { authRefresh++ }
                    )
                }
            }
        }
    }
}

// ===============================================
// Drawer Content (Menu lateral)
// ===============================================
@Composable
fun DrawerContent(
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit,
    firestoreManager: FirestoreManager? = null,
    @Suppress("UNUSED_PARAMETER") authRefresh: Int = 0
) {
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(rememberScrollState())
        ) {
            // Header com foto de perfil
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Foto de perfil (placeholder com logo)
                    Image(
                        painter = painterResource(id = R.drawable.logo),
                        contentDescription = "Foto de perfil",
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color.White, CircleShape),
                        contentScale = ContentScale.Crop
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = if (firestoreManager?.isGoogleUser == true)
                            firestoreManager.displayName ?: "Motorista"
                        else
                            "Motorista Inteligente",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = if (firestoreManager?.isGoogleUser == true)
                            firestoreManager.email ?: "Conta Google conectada"
                        else
                            "Faça login para sincronizar",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Menu principal
            DrawerMenuItem(Screen.HOME, currentScreen, onScreenSelected)
            DrawerMenuItem(Screen.RIDE_SETTINGS, currentScreen, onScreenSelected)

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // Seção Análise
            Text(
                text = "ANÁLISES",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 4.dp)
            )
            DrawerMenuItem(Screen.WEEKLY_COMPARISON, currentScreen, onScreenSelected)
            DrawerMenuItem(Screen.DEMAND_BY_REGION, currentScreen, onScreenSelected)

            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))

            // Seção Config
            Text(
                text = "CONFIGURAÇÃO",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(start = 28.dp, top = 8.dp, bottom = 4.dp)
            )
            DrawerMenuItem(Screen.PERMISSIONS, currentScreen, onScreenSelected)
            DrawerMenuItem(Screen.TIPS, currentScreen, onScreenSelected)
            DrawerMenuItem(Screen.LOGIN, currentScreen, onScreenSelected)

            Spacer(modifier = Modifier.weight(1f))

            // Versão no rodapé
            Text(
                text = "v1.0 — Motorista Inteligente",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun DrawerMenuItem(
    screen: Screen,
    currentScreen: Screen,
    onScreenSelected: (Screen) -> Unit
) {
    NavigationDrawerItem(
        icon = {
            Image(
                painter = painterResource(id = R.drawable.logo),
                contentDescription = screen.title,
                modifier = Modifier.size(20.dp)
            )
        },
        label = { Text(screen.title) },
        selected = currentScreen == screen,
        onClick = { onScreenSelected(screen) },
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
    )
}

// ===============================================
// HOME SCREEN
// ===============================================
@Composable
fun HomeScreen(
    isServiceRunning: Boolean,
    isAnalysisPaused: Boolean,
    onStartService: () -> Unit,
    onStopService: () -> Unit,
    onTogglePause: () -> Unit,
    onNavigateToPermissions: () -> Unit = {},
    firestoreManager: FirestoreManager? = null
) {
    val context = LocalContext.current
    var isStartingService by remember { mutableStateOf(false) }

    val hasOverlay = Settings.canDrawOverlays(context)
    val hasLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasAccessibility = isAccessibilityEnabled(context)
    val pendingCount = listOf(hasOverlay, hasLocation, hasAccessibility).count { !it }

    LaunchedEffect(isServiceRunning) {
        if (isServiceRunning) {
            isStartingService = false
        }
    }

    LaunchedEffect(isStartingService) {
        if (isStartingService) {
            delay(8000)
            if (!isServiceRunning) {
                isStartingService = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Banner de permissões pendentes
        if (pendingCount > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFFF9800).copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "⚠️",
                        fontSize = 24.sp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (pendingCount == 1) "1 permissão pendente"
                                   else "$pendingCount permissões pendentes",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFFE65100)
                        )
                        Text(
                            text = "Toque para verificar",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                    TextButton(onClick = onNavigateToPermissions) {
                        Text("Ver", color = Color(0xFFE65100), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Motorista Inteligente",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Analise corridas do Uber e 99 em tempo real",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Card de Status / Controle
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isServiceRunning)
                    Color(0xFF1B5E20).copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Botões de controle lado a lado (energia + pausa/play)
                val powerTrackColor by animateColorAsState(
                    targetValue = if (isServiceRunning) Color(0xFF4CAF50) else Color(0xFF555555),
                    animationSpec = tween(300), label = "powerTrackColor"
                )
                val analysisTrackColor by animateColorAsState(
                    targetValue = if (!isServiceRunning) Color(0xFF616161)
                    else if (isAnalysisPaused) Color(0xFF555555)
                    else Color(0xFF4CAF50),
                    animationSpec = tween(300), label = "analysisTrackColor"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(powerTrackColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = !isStartingService
                            ) {
                                if (isServiceRunning) {
                                    isStartingService = false
                                    onStopService()
                                } else {
                                    isStartingService = true
                                    onStartService()
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isStartingService && !isServiceRunning) {
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.PowerSettingsNew,
                                contentDescription = "Ligar ou desligar análise",
                                modifier = Modifier.size(20.dp),
                                tint = Color.White
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(analysisTrackColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = isServiceRunning
                            ) {
                                onTogglePause()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isAnalysisPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = if (isAnalysisPaused) "Retomar análise" else "Pausar análise",
                            modifier = Modifier.size(20.dp),
                            tint = Color.White.copy(alpha = if (isServiceRunning) 1f else 0.8f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Monitoramento de Demanda (sempre visível — usa Firebase para histórico)
        DemandMonitorCard(
            isServiceRunning = isServiceRunning,
            firestoreManager = firestoreManager
        )
        Spacer(modifier = Modifier.height(24.dp))

        // Quick stats do dia (resuminho)
        val context = LocalContext.current
        val historyManager = remember { RideHistoryManager(context) }
        val todaySummary = remember { historyManager.getTodaySummary() }

        if (todaySummary.totalRides > 0) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1E88E5).copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "📊 Resumo de Hoje",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("Corridas", "${todaySummary.totalRides}")
                        StatItem("Ganho", String.format("R$ %.0f", todaySummary.totalEarnings))
                        StatItem("R$/km", String.format("%.2f", todaySummary.avgPricePerKm))
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Card de Combustível e Valor Mínimo Sugerido
        FuelRecommendationCard()

        Spacer(modifier = Modifier.height(24.dp))

        // Como analisamos suas corridas
        Text(
            text = "Como analisamos suas corridas",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Grid 2 colunas
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.AttachMoney,
                title = "Preço/km",
                description = "Valor da corrida dividido pela distância",
                accent = Color(0xFF00C853)
            )
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.LocalOffer,
                title = "Preço efetivo",
                description = "Inclui km até o ponto de embarque",
                accent = Color(0xFF2979FF)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.MonetizationOn,
                title = "Ganho/hora",
                description = "Estimativa de quanto ganha por hora",
                accent = Color(0xFFFF6D00)
            )
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.DirectionsCar,
                title = "Embarque",
                description = "Distância até o passageiro",
                accent = Color(0xFFFF1744)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.AccessTime,
                title = "Horário",
                description = "Bônus em horários de pico e noturno",
                accent = Color(0xFFAA00FF)
            )
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                icon = Icons.Default.ShowChart,
                title = "Demanda",
                description = "Monitoramento em tempo real da região",
                accent = Color(0xFF00BFA5)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ===============================================
// RIDE SETTINGS SCREEN (Configurar Corrida)
// ===============================================
@Composable
fun RideSettingsScreen(
    firestoreManager: FirestoreManager? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Configure seus critérios de avaliação de corridas",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        DriverSettingsCard(
            firestoreManager = firestoreManager
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ========================
        // Dados do Veículo
        // ========================
        VehicleSettingsCard(
            firestoreManager = firestoreManager
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ========================
        // O que o app analisa
        // ========================
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "O que analisamos por você",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Funções ativas em tempo real enquanto você dirige",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
                Spacer(modifier = Modifier.height(16.dp))

                FeatureItem(
                    icon = Icons.Default.AttachMoney,
                    color = Color(0xFF00C853),
                    title = "Preço por km",
                    description = "Compara o valor da corrida com seu mínimo configurado"
                )
                FeatureItem(
                    icon = Icons.Default.MonetizationOn,
                    color = Color(0xFFFF6D00),
                    title = "Ganho estimado por hora",
                    description = "Calcula se a corrida atinge seu objetivo de R$/hora"
                )
                FeatureItem(
                    icon = Icons.Default.DirectionsCar,
                    color = Color(0xFFFF1744),
                    title = "Distância de embarque",
                    description = "Penaliza corridas onde o passageiro está muito longe"
                )
                FeatureItem(
                    icon = Icons.Default.LocalGasStation,
                    color = Color(0xFF00897B),
                    title = "Custo de combustível",
                    description = "Usa os dados do seu carro para calcular o custo real por km"
                )
                FeatureItem(
                    icon = Icons.Default.LocalOffer,
                    color = Color(0xFF2979FF),
                    title = "Preço efetivo",
                    description = "Desconta o deslocamento vazio até o passageiro do valor real"
                )
                FeatureItem(
                    icon = Icons.Default.ShowChart,
                    color = Color(0xFFAA00FF),
                    title = "Demanda em tempo real",
                    description = "Monitora ofertas por hora e compara com a hora anterior"
                )
                FeatureItem(
                    icon = Icons.Default.AccessTime,
                    color = Color(0xFF6D4C41),
                    title = "Horários de baixa demanda",
                    description = "Avisa quando é melhor parar e economizar combustível"
                )
                FeatureItem(
                    icon = Icons.Default.Speed,
                    color = Color(0xFFD50000),
                    title = "Valor mínimo sugerido",
                    description = "Calcula o R$/km mínimo para cobrir custos + lucro"
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ===============================================
// WEEKLY SUMMARY SCREEN (Resumo Semanal)
// ===============================================
@Composable
fun WeeklyComparisonScreen() {
    val context = LocalContext.current
    val firestoreManager = remember { FirestoreManager(context) }

    var isLoading by remember { mutableStateOf(true) }
    var weeklyData by remember { mutableStateOf<List<FirestoreManager.WeeklyPlatformDayAnalytics>>(emptyList()) }

    LaunchedEffect(Unit) {
        firestoreManager.loadCurrentWeekDailyAnalytics { result ->
            weeklyData = result
            isLoading = false
        }
    }

    val uberRows = weeklyData.map {
        WeeklyPlatformRow(
            dateKey = it.dateKey,
            dayOfWeek = it.dayOfWeek,
            offers = it.offersUber,
            avgPrice = it.avgPriceUber,
            avgPricePerKm = it.avgPricePerKmUber
        )
    }
    val ninetyNineRows = weeklyData.map {
        WeeklyPlatformRow(
            dateKey = it.dateKey,
            dayOfWeek = it.dayOfWeek,
            offers = it.offers99,
            avgPrice = it.avgPrice99,
            avgPricePerKm = it.avgPricePerKm99
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Resumo Semanal (Domingo a Sábado)",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Visão geral da semana atual com destaque de demanda por dia.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (isLoading) {
            EmptyStateCard(
                emoji = "⏳",
                title = "Carregando resumo semanal",
                subtitle = "Buscando resultados salvos no Firebase..."
            )
        } else if (weeklyData.isEmpty()) {
            EmptyStateCard(
                emoji = "📅",
                title = "Sem dados da semana",
                subtitle = "Assim que os dados forem salvos no Firebase, o resumo aparecerá aqui."
            )
        } else {
            WeeklyGeneralSummaryCard(weeklyData = weeklyData)

            Spacer(modifier = Modifier.height(12.dp))

            WeeklyPlatformCard(
                title = "Uber",
                backgroundColor = Color(0xFF0D1B3D),
                accentColor = Color(0xFFFFFFFF),
                rows = uberRows
            )

            Spacer(modifier = Modifier.height(12.dp))

            WeeklyPlatformCard(
                title = "99",
                backgroundColor = Color(0xFFFFEB3B),
                accentColor = Color(0xFF5D4037),
                rows = ninetyNineRows
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun WeeklyGeneralSummaryCard(
    weeklyData: List<FirestoreManager.WeeklyPlatformDayAnalytics>
) {
    val totalOffers = weeklyData.sumOf { it.totalOffers }
    val totalUber = weeklyData.sumOf { it.offersUber }
    val total99 = weeklyData.sumOf { it.offers99 }

    val avgDailyOffers = if (weeklyData.isNotEmpty()) {
        totalOffers.toDouble() / weeklyData.size
    } else 0.0

    fun averageNonZero(values: List<Double>): Double {
        val filtered = values.filter { it > 0.0 }
        return if (filtered.isNotEmpty()) filtered.average() else 0.0
    }

    val avgUberPrice = averageNonZero(weeklyData.map { it.avgPriceUber })
    val avg99Price = averageNonZero(weeklyData.map { it.avgPrice99 })
    val avgUberPerKm = averageNonZero(weeklyData.map { it.avgPricePerKmUber })
    val avg99PerKm = averageNonZero(weeklyData.map { it.avgPricePerKm99 })

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF7B1FA2).copy(alpha = 0.10f)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Análise geral (7 dias)",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF7B1FA2)
            )
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Total ofertas", "$totalOffers")
                StatItem("Uber", "$totalUber")
                StatItem("99", "$total99")
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("Média/dia", String.format("%.1f", avgDailyOffers))
                StatItem("Méd Uber", if (avgUberPrice > 0) String.format("R$ %.2f", avgUberPrice) else "—")
                StatItem("Méd 99", if (avg99Price > 0) String.format("R$ %.2f", avg99Price) else "—")
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatItem("R$/km Uber", if (avgUberPerKm > 0) String.format("%.2f", avgUberPerKm) else "—")
                StatItem("R$/km 99", if (avg99PerKm > 0) String.format("%.2f", avg99PerKm) else "—")
                StatItem("Semana", "Dom-Sáb")
            }
        }
    }
}

private data class WeeklyPlatformRow(
    val dateKey: String,
    val dayOfWeek: Int,
    val offers: Int,
    val avgPrice: Double,
    val avgPricePerKm: Double
)

private fun formatDayLabel(dayOfWeek: Int): String = when (dayOfWeek) {
    Calendar.SUNDAY -> "Dom"
    Calendar.MONDAY -> "Seg"
    Calendar.TUESDAY -> "Ter"
    Calendar.WEDNESDAY -> "Qua"
    Calendar.THURSDAY -> "Qui"
    Calendar.FRIDAY -> "Sex"
    Calendar.SATURDAY -> "Sáb"
    else -> "Dia"
}

private fun formatDateKeyToBr(dateKey: String): String {
    val parts = dateKey.split("-")
    return if (parts.size == 3) "${parts[2]}/${parts[1]}" else dateKey
}

@Composable
private fun WeeklyPlatformCard(
    title: String,
    backgroundColor: Color,
    accentColor: Color,
    rows: List<WeeklyPlatformRow>
) {
    val isDarkBackground = backgroundColor.luminance() < 0.5f
    val contentColor = if (isDarkBackground) Color.White else Color(0xFF212121)
    val weeklyOffersAverage = rows.sumOf { it.offers }.toDouble() / 7.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor
            )

            Spacer(modifier = Modifier.height(10.dp))

            if (rows.isEmpty()) {
                Text(
                    text = "Sem dados na semana.",
                    fontSize = 13.sp,
                    color = contentColor.copy(alpha = 0.75f)
                )
            } else {
                rows.forEachIndexed { index, row ->
                    val comparisonColor = if (row.offers < weeklyOffersAverage) {
                        Color(0xFFD32F2F)
                    } else {
                        Color(0xFF2E7D32)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${formatDayLabel(row.dayOfWeek)} ${formatDateKeyToBr(row.dateKey)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = contentColor
                        )

                        Text(
                            text = "${row.offers} ofertas",
                            fontSize = 12.sp,
                            color = comparisonColor
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Média: ${if (row.avgPrice > 0) String.format("R$ %.2f", row.avgPrice) else "—"}",
                            fontSize = 12.sp,
                            color = comparisonColor
                        )
                        Text(
                            text = "R$/km: ${if (row.avgPricePerKm > 0) String.format("%.2f", row.avgPricePerKm) else "—"}",
                            fontSize = 12.sp,
                            color = comparisonColor
                        )
                    }

                    if (index < rows.lastIndex) {
                        Spacer(modifier = Modifier.height(8.dp))
                        HorizontalDivider(color = contentColor.copy(alpha = 0.20f))
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun DaySummaryCard(
    dayName: String,
    dateBR: String,
    summary: RideHistoryManager.PeriodSummary
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (summary.totalRides > 0)
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$dayName  $dateBR",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                if (summary.totalRides > 0) {
                    Text(
                        text = String.format("R$ %.2f", summary.totalEarnings),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                } else {
                    Text(
                        text = "Sem corridas",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                    )
                }
            }

            if (summary.totalRides > 0) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${summary.totalRides} corridas",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = String.format("%.1f km", summary.totalDistanceKm),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = String.format("R$ %.2f/km", summary.avgPricePerKm),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "${summary.totalTimeMin} min",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}

// ===============================================
// DEMAND BY REGION SCREEN (Demanda por Região)
// ===============================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemandByRegionScreen(firestoreManager: FirestoreManager?) {
    var cityDemandMini by remember { mutableStateOf<List<FirestoreManager.CityDemandMini>>(emptyList()) }
    var selectedCity by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedCityCard by remember { mutableStateOf<String?>(null) }
    var lastRefreshAt by remember { mutableStateOf(0L) }
    var isFirebaseReady by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (firestoreManager == null) {
            isLoading = false
            return@LaunchedEffect
        }

        if (firestoreManager.isAuthenticated) {
            isFirebaseReady = true
        } else {
            firestoreManager.signInAnonymously(
                onSuccess = { isFirebaseReady = true },
                onError = {
                    isFirebaseReady = false
                    isLoading = false
                }
            )
        }

        while (!isFirebaseReady) {
            delay(150)
        }

        while (true) {
            firestoreManager.loadCityDemandMini { mini ->
                cityDemandMini = mini
                isLoading = false
                lastRefreshAt = System.currentTimeMillis()
            }

            delay(5_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Demanda regional agregada dos últimos 30 minutos (todos os motoristas)",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

        if (lastRefreshAt > 0L) {
            val updatedAtText = android.text.format.DateFormat.format("HH:mm:ss", lastRefreshAt)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Atualizado às $updatedAtText",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Cidades",
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            letterSpacing = 1.sp
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
            return@Column
        }

        if (cityDemandMini.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "Sem dados de demanda para os últimos 30 minutos.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        val miniByCity = cityDemandMini.associateBy { it.city }
        val sortedCities = cityDemandMini.map { it.city }.distinct().sortedWith(
            compareByDescending<String> { city -> miniByCity[city]?.offersLast15m ?: 0 }
                .thenBy { it }
        )

        fun rankColor(index: Int, total: Int): Color {
            if (total <= 0) return Color(0xFF66BB6A)
            if (index <= 0) return Color(0xFF2E7D32) // Top 1
            if (index == 1) return Color(0xFF66BB6A) // Top 2 (verde mais claro)

            val ratio = index.toFloat() / (total - 1).coerceAtLeast(1).toFloat()
            return when {
                ratio < 0.60f -> Color(0xFFFF9800) // intermediários
                else -> Color(0xFFE53935) // piores
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sortedCities.forEachIndexed { cityIndex, city ->
                val cityMini = miniByCity[city]
                val cityTrendUi = resolveDemandTrendUi(
                    cityMini?.demandPeakTrend ?: FirestoreManager.DemandPeakTrend.STABLE
                )
                val cityRankColor = rankColor(cityIndex, sortedCities.size)
                val isExpanded = expandedCityCard == city

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedCity = city
                            expandedCityCard = if (isExpanded) null else city
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = cityRankColor.copy(alpha = 0.12f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = city,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = cityTrendUi.icon,
                                contentDescription = null,
                                tint = cityTrendUi.color,
                                modifier = Modifier.padding(horizontal = 6.dp)
                            )
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Ofertas: ${cityMini?.offersLast15m ?: 0}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = cityRankColor
                                )
                                Text(
                                    text = "U:${cityMini?.offersUberLast15m ?: 0} · 99:${cityMini?.offers99Last15m ?: 0}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                            }
                            Icon(
                                imageVector = if (isExpanded) Icons.Default.ArrowDropUp else Icons.Default.ArrowDropDown,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                        }

                        if (isExpanded) {
                            Spacer(modifier = Modifier.height(10.dp))
                            if (cityMini?.neighborhoods.isNullOrEmpty()) {
                                Text(
                                    text = "Sem bairros com ofertas nos últimos 30 minutos",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            } else {
                                cityMini?.neighborhoods?.forEachIndexed { index, neighborhoodMini ->
                                    val neighborhoodTrendUi = resolveDemandTrendUi(neighborhoodMini.demandPeakTrend)
                                    val neighborhoodRankColor = rankColor(index, cityMini.neighborhoods.size)
                                    val topLabel = when (index) {
                                        0 -> "TOP 1"
                                        1 -> "TOP 2"
                                        2 -> "TOP 3"
                                        else -> null
                                    }

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = neighborhoodRankColor.copy(alpha = 0.12f)
                                        ),
                                        shape = RoundedCornerShape(10.dp),
                                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(
                                                modifier = Modifier.weight(1f),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Text(
                                                    text = neighborhoodMini.neighborhood,
                                                    fontSize = 12.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier.weight(1f, fill = false)
                                                )
                                                if (topLabel != null) {
                                                    Box(
                                                        modifier = Modifier
                                                            .background(
                                                                neighborhoodRankColor.copy(alpha = 0.16f),
                                                                RoundedCornerShape(6.dp)
                                                            )
                                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                                    ) {
                                                        Text(
                                                            text = topLabel,
                                                            fontSize = 9.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = neighborhoodRankColor
                                                        )
                                                    }
                                                }
                                            }
                                            Icon(
                                                imageVector = neighborhoodTrendUi.icon,
                                                contentDescription = null,
                                                tint = neighborhoodTrendUi.color,
                                                modifier = Modifier.padding(horizontal = 6.dp)
                                            )
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = "Ofertas: ${neighborhoodMini.offersLast15m}",
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = neighborhoodRankColor
                                                )
                                                Text(
                                                    text = "U:${neighborhoodMini.offersUberLast15m} · 99:${neighborhoodMini.offers99Last15m}",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RegionalStatColumn(label: String, value: String, subtitle: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Text(
            text = subtitle,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

// ===============================================
// PERMISSIONS SCREEN (Permissões)
// ===============================================
@Composable
fun PermissionsScreen(
    refreshTick: Int = 0,
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasAccessibility by remember { mutableStateOf(isAccessibilityEnabled(context)) }

    fun refreshPermissionsState() {
        hasOverlayPermission = Settings.canDrawOverlays(context)
        hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        hasAccessibility = isAccessibilityEnabled(context)
    }

    LaunchedEffect(refreshTick) {
        refreshPermissionsState()
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshPermissionsState()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val allGranted = hasOverlayPermission && hasLocationPermission && hasAccessibility

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // Status geral
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (allGranted)
                    Color(0xFF4CAF50).copy(alpha = 0.1f)
                else Color(0xFFFF9800).copy(alpha = 0.1f)
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (allGranted) "✅ Tudo configurado!" else "⚠️ Permissões pendentes",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (allGranted)
                        "Todas as permissões estão ativas."
                    else "Algumas permissões são necessárias para o funcionamento.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        PermissionCard(
            title = "Sobreposição de Tela",
            description = "Mostrar botão flutuante e cards de análise sobre outros apps",
            isGranted = hasOverlayPermission,
            onRequest = onRequestOverlayPermission
        )

        Spacer(modifier = Modifier.height(8.dp))

        PermissionCard(
            title = "Acessibilidade",
            description = "Ler informações de corridas do Uber e 99",
            isGranted = hasAccessibility,
            onRequest = onOpenAccessibilitySettings
        )

        Spacer(modifier = Modifier.height(8.dp))

        PermissionCard(
            title = "Localização",
            description = "Calcular distância até o embarque e análise de demanda regional",
            isGranted = hasLocationPermission,
            onRequest = onRequestLocationPermission
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Explicação
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "ℹ️ Por que essas permissões?",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "• Sobreposição: Exibir análise em tempo real sobre o app de corrida\n" +
                            "• Acessibilidade: Detectar e extrair dados de novas corridas\n" +
                            "• Localização: Calcular distância ao embarque e monitorar demanda",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ===============================================
// TIPS SCREEN (Dicas de Uso)
// ===============================================
@Composable
fun TipsScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        TipCard(
            emoji = "🚀",
            title = "Começando (Fluxo Atual)",
            tips = listOf(
                "1. Conceda Sobreposição, Acessibilidade e Localização na seção Permissões",
                "2. Na Home, ative o serviço principal (status Ativado)",
                "3. Use o botão verde 'Pausar análise' para pausar sem desligar o app",
                "4. Se estiver pausado, o botão fica cinza com 'Ativar análise'",
                "5. Abra Uber ou 99 normalmente para iniciar as análises em tempo real"
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TipCard(
            emoji = "📊",
            title = "Entendendo o Card de Corrida",
            tips = listOf(
                "O card mostra recomendação: COMPENSA, EVITAR ou NEUTRO",
                "As métricas principais são valor, km até passageiro, km destino, R$/km e R$/h",
                "Considere as razões exibidas no insight rápido para decisão final",
                "A recomendação usa suas referências configuradas (preço/km e ganho/hora)",
                "Endereços de embarque e destino ajudam a validar contexto da corrida"
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TipCard(
            emoji = "💡",
            title = "Botão Flutuante e Status",
            tips = listOf(
                "Arraste o botão para qualquer posição na tela",
                "Segure o botão para abrir o card 'Status'",
                "No Status, use Pausar/Ativar análise sem encerrar o serviço",
                "Borda do card de análise: verde quando ativa, vermelha quando pausada/desativada",
                "Borda do botão flutuante segue o mesmo estado (ativo x pausado)"
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TipCard(
            emoji = "📈",
            title = "Monitoramento de Demanda",
            tips = listOf(
                "Com login Google, o card mostra histórico de ofertas do dia",
                "Mini card da Uber usa fundo azul-marinho e mini card da 99 usa fundo amarelo",
                "Compare ofertas por plataforma, preço médio, R$/km, distância e tempo",
                "Use os dados para ajustar estratégia por horário e região"
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TipCard(
            emoji = "⚙️",
            title = "Funções que Permanecem",
            tips = listOf(
                "Configurar Corrida continua sendo o local para ajustar seus critérios",
                "Permissões continuam essenciais para leitura e análise em tempo real",
                "Análise do Dia e Comparação Semanal seguem como base de acompanhamento",
                "Demanda por Região ajuda a decidir onde e quando operar"
            )
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ===============================================
// LOGIN SCREEN (Google Sign-In com Credential Manager)
// ===============================================
@Composable
fun LoginScreen(
    firestoreManager: FirestoreManager,
    onLoginSuccess: () -> Unit = {},
    onLogoutSuccess: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isGoogleLoggedIn by remember { mutableStateOf(firestoreManager.isGoogleUser) }

    DisposableEffect(firestoreManager) {
        val auth = FirebaseAuth.getInstance()
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            isGoogleLoggedIn = firebaseAuth.currentUser?.providerData?.any {
                it.providerId == GoogleAuthProvider.PROVIDER_ID
            } == true
        }

        auth.addAuthStateListener(listener)
        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    // Se já está logado com Google, mostrar tela de perfil
    if (isGoogleLoggedIn) {
        LoggedInScreen(firestoreManager = firestoreManager, onLogout = {
            scope.launch {
                try {
                    val credentialManager = androidx.credentials.CredentialManager.create(context)
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (_: Exception) {
                }

                firestoreManager.signOut()
                DriverPreferences(context).applyToAnalyzer()
                isGoogleLoggedIn = false
                onLogoutSuccess()
            }
        })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // Avatar placeholder
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Avatar",
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "Faça Login",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Sincronize seus dados entre dispositivos\ne acesse recursos premium",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Botão Google Sign-In
        OutlinedButton(
            onClick = {
                if (isLoading) return@OutlinedButton
                isLoading = true
                errorMessage = null

                scope.launch {
                    try {
                        val credentialManager = androidx.credentials.CredentialManager.create(context)
                        val serverClientId = context.getString(R.string.default_web_client_id)

                        if (serverClientId.isBlank()) {
                            throw IllegalStateException("default_web_client_id não encontrado. Verifique o google-services.json")
                        }

                        val activity = context as Activity

                        val result = try {
                            // Tenta primeiro contas já autorizadas (fluxo mais rápido)
                            val authorizedOption = com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
                                .setFilterByAuthorizedAccounts(true)
                                .setServerClientId(serverClientId)
                                .build()

                            val authorizedRequest = androidx.credentials.GetCredentialRequest.Builder()
                                .addCredentialOption(authorizedOption)
                                .build()

                            credentialManager.getCredential(
                                context = activity,
                                request = authorizedRequest
                            )
                        } catch (_: androidx.credentials.exceptions.NoCredentialException) {
                            // Sem credenciais autorizadas: abrir seletor de contas Google
                            val anyAccountOption = com.google.android.libraries.identity.googleid.GetGoogleIdOption.Builder()
                                .setFilterByAuthorizedAccounts(false)
                                .setServerClientId(serverClientId)
                                .build()

                            val anyAccountRequest = androidx.credentials.GetCredentialRequest.Builder()
                                .addCredentialOption(anyAccountOption)
                                .build()

                            credentialManager.getCredential(
                                context = activity,
                                request = anyAccountRequest
                            )
                        }

                        val credential = result.credential
                        val googleIdTokenCredential = com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.createFrom(credential.data)
                        val idToken = googleIdTokenCredential.idToken

                        // Autenticar no Firebase com o token
                        firestoreManager.signInWithGoogle(
                            idToken = idToken,
                            onSuccess = {
                                val prefs = DriverPreferences(context).apply {
                                    this.firestoreManager = firestoreManager
                                }
                                firestoreManager.loadPreferences(prefs) {
                                    isLoading = false
                                    onLoginSuccess()
                                }
                            },
                            onError = { e ->
                                isLoading = false
                                errorMessage = "Erro ao autenticar: ${e.localizedMessage}"
                            }
                        )
                    } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
                        isLoading = false
                        // Usuário cancelou, não mostrar erro
                    } catch (e: androidx.credentials.exceptions.NoCredentialException) {
                        isLoading = false
                        errorMessage = "Nenhuma credencial Google disponível neste aparelho. Verifique se há conta Google adicionada e Play Services atualizado."
                    } catch (e: Exception) {
                        isLoading = false
                        errorMessage = "Erro: ${e.localizedMessage}"
                        Log.e("LoginScreen", "Erro Google Sign-In", e)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp),
            enabled = !isLoading
        ) {
            if (isLoading) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text("Entrando...", fontSize = 16.sp)
            } else {
                Text("Entrar com Google", fontSize = 16.sp)
            }
        }

        // Mensagem de erro
        if (errorMessage != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF44336).copy(alpha = 0.08f)
                )
            ) {
                Text(
                    text = errorMessage!!,
                    fontSize = 13.sp,
                    color = Color(0xFFF44336),
                    modifier = Modifier.padding(16.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ===============================================
// TELA LOGADO (Perfil do usuário)
// ===============================================
@Composable
fun LoggedInScreen(
    firestoreManager: FirestoreManager,
    onLogout: () -> Unit
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        // Avatar
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Avatar",
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentScale = ContentScale.Crop
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = firestoreManager.displayName ?: "Motorista",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = firestoreManager.email ?: "",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF4CAF50).copy(alpha = 0.08f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Conta conectada",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF4CAF50)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Seus dados estão sendo sincronizados com a nuvem automaticamente.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Botão de logout
        if (showLogoutConfirm) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFFF44336).copy(alpha = 0.08f)
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Deseja sair da conta?",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(onClick = { showLogoutConfirm = false }) {
                            Text("Cancelar")
                        }
                        Button(
                            onClick = {
                                onLogout()
                                showLogoutConfirm = false
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF44336)
                            )
                        ) {
                            Text("Sair", color = Color.White)
                        }
                    }
                }
            }
        } else {
            OutlinedButton(
                onClick = { showLogoutConfirm = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Sair da conta", fontSize = 15.sp, color = Color(0xFFF44336))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ===============================================
// Componentes compartilhados
// ===============================================

// ===============================================
// VEHICLE SETTINGS CARD (Dados do Veículo)
// ===============================================
@Composable
fun VehicleSettingsCard(
    firestoreManager: FirestoreManager? = null
) {
    val context = LocalContext.current
    val prefs = remember { DriverPreferences(context) }
    prefs.firestoreManager = firestoreManager

    var vehicleType by remember { mutableStateOf(prefs.vehicleType) }
    var fuelType by remember { mutableStateOf(prefs.fuelType) }
    var kmPerLiterGasoline by remember { mutableFloatStateOf(prefs.kmPerLiterGasoline.toFloat()) }
    var kmPerLiterEthanol by remember { mutableFloatStateOf(prefs.kmPerLiterEthanol.toFloat()) }
    var gasolinePrice by remember { mutableFloatStateOf(prefs.gasolinePrice.toFloat()) }
    var ethanolPrice by remember { mutableFloatStateOf(prefs.ethanolPrice.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF00897B).copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.DirectionsCar,
                    contentDescription = null,
                    tint = Color(0xFF00897B),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Dados do Veículo",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Tipo de veículo
            Text(
                text = "Tipo do veículo",
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SelectableChip(
                    modifier = Modifier.weight(1f),
                    label = "Combustão",
                    icon = Icons.Default.LocalGasStation,
                    selected = vehicleType == "combustion",
                    color = Color(0xFFFF6D00),
                    onClick = {
                        vehicleType = "combustion"
                        prefs.vehicleType = "combustion"
                    }
                )
                SelectableChip(
                    modifier = Modifier.weight(1f),
                    label = "Elétrico",
                    icon = Icons.Default.EvStation,
                    selected = vehicleType == "electric",
                    color = Color(0xFF00C853),
                    onClick = {
                        vehicleType = "electric"
                        prefs.vehicleType = "electric"
                    }
                )
            }

            // Combustíveis (só para combustão)
            if (vehicleType == "combustion") {
                Spacer(modifier = Modifier.height(16.dp))

                // Tipo de combustível
                Text(
                    text = "Combustível principal",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectableChip(
                        modifier = Modifier.weight(1f),
                        label = "Gasolina",
                        icon = Icons.Default.LocalGasStation,
                        selected = fuelType == "gasoline",
                        color = Color(0xFFF44336),
                        onClick = {
                            fuelType = "gasoline"
                            prefs.fuelType = "gasoline"
                        }
                    )
                    SelectableChip(
                        modifier = Modifier.weight(1f),
                        label = "Etanol",
                        icon = Icons.Default.LocalGasStation,
                        selected = fuelType == "ethanol",
                        color = Color(0xFF4CAF50),
                        onClick = {
                            fuelType = "ethanol"
                            prefs.fuelType = "ethanol"
                        }
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Km/L Gasolina
                StepperControl(
                    label = "Km/L (Gasolina)",
                    value = kmPerLiterGasoline,
                    valueText = String.format("%.1f km/L", kmPerLiterGasoline),
                    min = 3f,
                    max = 25f,
                    step = 0.5f,
                    onValueChange = {
                        kmPerLiterGasoline = it
                        prefs.kmPerLiterGasoline = it.toDouble()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Km/L Etanol
                StepperControl(
                    label = "Km/L (Etanol)",
                    value = kmPerLiterEthanol,
                    valueText = String.format("%.1f km/L", kmPerLiterEthanol),
                    min = 2f,
                    max = 20f,
                    step = 0.5f,
                    onValueChange = {
                        kmPerLiterEthanol = it
                        prefs.kmPerLiterEthanol = it.toDouble()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Preço Gasolina
                StepperControl(
                    label = "Preço Gasolina (R$/L)",
                    value = gasolinePrice,
                    valueText = String.format("R$ %.2f", gasolinePrice),
                    min = 2f,
                    max = 10f,
                    step = 0.01f,
                    onValueChange = {
                        val rounded = (Math.round(it * 100.0) / 100.0).toFloat()
                        gasolinePrice = rounded
                        prefs.gasolinePrice = rounded.toDouble()
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Preço Etanol
                StepperControl(
                    label = "Preço Etanol (R$/L)",
                    value = ethanolPrice,
                    valueText = String.format("R$ %.2f", ethanolPrice),
                    min = 1.5f,
                    max = 8f,
                    step = 0.01f,
                    onValueChange = {
                        val rounded = (Math.round(it * 100.0) / 100.0).toFloat()
                        ethanolPrice = rounded
                        prefs.ethanolPrice = rounded.toDouble()
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Resumo de custos
                val custGas = prefs.gasolineCostPerKm
                val custEth = prefs.ethanolCostPerKm
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFF424242).copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(10.dp)
                ) {
                    Column {
                        Text(
                            text = "Custo/km: Gasolina R$ ${String.format("%.2f", custGas)} | Etanol R$ ${String.format("%.2f", custEth)}",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                        Text(
                            text = if (prefs.isEthanolBetter) "Etanol compensa mais na sua cidade"
                                   else "Gasolina compensa mais na sua cidade",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (prefs.isEthanolBetter) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SelectableChip(
    modifier: Modifier = Modifier,
    label: String,
    icon: ImageVector,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit
) {
    val bgColor = if (selected) color.copy(alpha = 0.15f) else Color.Transparent
    val borderColor = if (selected) color else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)

    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = bgColor),
        border = androidx.compose.foundation.BorderStroke(
            width = if (selected) 2.dp else 1.dp,
            color = borderColor
        )
    ) {
        Icon(
            imageVector = if (selected) Icons.Default.Check else icon,
            contentDescription = null,
            tint = if (selected) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) color else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
    }
}

// ===============================================
// FUEL RECOMMENDATION CARD (Página Inicial)
// ===============================================
@Composable
fun FuelRecommendationCard() {
    val context = LocalContext.current
    val prefs = remember { DriverPreferences(context) }

    // Só mostrar para veículos a combustão
    if (prefs.vehicleType == "electric") return

    val isEthanolBetter = prefs.isEthanolBetter
    val custGas = prefs.gasolineCostPerKm
    val custEth = prefs.ethanolCostPerKm
    val suggestedMin = prefs.suggestedMinPricePerKm()
    val fuelCost = prefs.fuelCostPerKm

    val recommendedColor = if (isEthanolBetter) Color(0xFF00C853) else Color(0xFFFF6D00)
    val recommendedLabel = if (isEthanolBetter) "Etanol" else "Gasolina"
    val recommendedIcon = Icons.Default.LocalGasStation

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = recommendedColor.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(recommendedColor.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = recommendedIcon,
                        contentDescription = null,
                        tint = recommendedColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "Melhor combustível: $recommendedLabel",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = recommendedColor
                    )
                    Text(
                        text = "Baseado nos preços da sua cidade",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Comparação
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Gasolina", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Text(
                        text = "R$ ${String.format("%.2f", custGas)}/km",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (!isEthanolBetter) Color(0xFF00C853) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Etanol", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Text(
                        text = "R$ ${String.format("%.2f", custEth)}/km",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEthanolBetter) Color(0xFF00C853) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Seu custo", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
                    Text(
                        text = "R$ ${String.format("%.2f", fuelCost)}/km",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = recommendedColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Valor mínimo sugerido
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(recommendedColor.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Valor mínimo sugerido",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                        Text(
                            text = "Para cobrir custos + lucro",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                        )
                    }
                    Text(
                        text = "R$ ${String.format("%.2f", suggestedMin)}/km",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = recommendedColor
                    )
                }
            }
        }
    }
}

@Composable
fun StepperControl(
    label: String,
    value: Float,
    valueText: String,
    min: Float,
    max: Float,
    step: Float,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Color(0xFF424242).copy(alpha = 0.12f),
                    RoundedCornerShape(12.dp)
                )
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = {
                    val newVal = (value - step).coerceIn(min, max)
                    onValueChange(newVal)
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Remove,
                    contentDescription = "Diminuir",
                    tint = Color(0xFFFF6F00),
                    modifier = Modifier.size(28.dp)
                )
            }

            Text(
                text = valueText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF6F00),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )

            IconButton(
                onClick = {
                    val newVal = (value + step).coerceIn(min, max)
                    onValueChange(newVal)
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Aumentar",
                    tint = Color(0xFFFF6F00),
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}

@Composable
fun SettingSlider(
    label: String,
    value: Float,
    valueText: String,
    min: Float,
    max: Float,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(
                text = valueText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF6F00)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = min..max,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFFF6F00),
                activeTrackColor = Color(0xFFFF6F00)
            )
        )
    }
}

@Composable
fun DemandMonitorCard(
    isServiceRunning: Boolean = false,
    firestoreManager: FirestoreManager? = null
) {
    val isGoogleLoggedIn = firestoreManager?.isGoogleUser == true

    // Firebase stats — carrega ofertas do dia
    var firebaseStats by remember { mutableStateOf(FirestoreManager.RideOfferStats()) }

    LaunchedEffect(firestoreManager, isGoogleLoggedIn) {
        while (true) {
            if (isGoogleLoggedIn) {
                firestoreManager?.loadTodayRideOfferStats { result ->
                    firebaseStats = result
                }
            } else {
                firebaseStats = FirestoreManager.RideOfferStats()
            }
            delay(15_000)
        }
    }

    // Firebase como fonte única da análise de demanda
    val hasFirebaseData = firebaseStats.loaded && firebaseStats.totalOffersToday > 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1B5E20).copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "Histórico do dia",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                  text = if (!isGoogleLoggedIn) "Faça login com Google para visualizar demanda"
                      else if (hasFirebaseData) "Análise da plataforma"
                      else if (firebaseStats.loaded) "Sem ofertas na plataforma hoje"
                      else "Carregando dados da base...",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (!isGoogleLoggedIn) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFFFFC107).copy(alpha = 0.18f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Dados de demanda indisponíveis sem login.\nEntre com Google para liberar o histórico e as métricas.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f),
                        textAlign = TextAlign.Center
                    )
                }
                return@Column
            }

            // ========== DADOS DO FIREBASE (histórico do dia) ==========
            if (hasFirebaseData) {
                Text(
                    text = "Por plataforma",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))

                // Breakdown por plataforma (organizado em grid)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Card Uber
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                Color(0xFF0D1B3D),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "UBER",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.8f),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "↑ ${if (firebaseStats.maxOfferPriceUber > 0) String.format("R$ %.2f", firebaseStats.maxOfferPriceUber) else "—"}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF81C784)
                                )
                                Text(
                                    text = "↓ ${if (firebaseStats.minOfferPriceUber > 0) String.format("R$ %.2f", firebaseStats.minOfferPriceUber) else "—"}",
                                    fontSize = 10.sp,
                                    color = Color(0xFFEF9A9A)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${firebaseStats.offersUber} ofertas",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Médio: ${if (firebaseStats.avgPriceUber > 0) String.format("R$ %.2f", firebaseStats.avgPriceUber) else "—"}",
                                fontSize = 11.sp,
                                color = Color.White.copy(alpha = 0.85f)
                            )
                            Text(
                                text = "${if (firebaseStats.avgPricePerKmUber > 0) String.format("R$ %.2f/km", firebaseStats.avgPricePerKmUber) else "—/km"} • ${if (firebaseStats.avgDistanceKmUber > 0) String.format("%.1f km", firebaseStats.avgDistanceKmUber) else "— km"} • ${if (firebaseStats.avgEstimatedTimeMinUber > 0) String.format("%.0f min", firebaseStats.avgEstimatedTimeMinUber) else "— min"}",
                                fontSize = 11.sp,
                                color = Color(0xFF90CAF9)
                            )
                        }
                    }

                    // Card 99
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                Color(0xFFFFEB3B),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "99",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF212121),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "↑ ${if (firebaseStats.maxOfferPrice99 > 0) String.format("R$ %.2f", firebaseStats.maxOfferPrice99) else "—"}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF2E7D32)
                                )
                                Text(
                                    text = "↓ ${if (firebaseStats.minOfferPrice99 > 0) String.format("R$ %.2f", firebaseStats.minOfferPrice99) else "—"}",
                                    fontSize = 10.sp,
                                    color = Color(0xFFC62828)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${firebaseStats.offers99} ofertas",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF212121)
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Médio: ${if (firebaseStats.avgPrice99 > 0) String.format("R$ %.2f", firebaseStats.avgPrice99) else "—"}",
                                fontSize = 11.sp,
                                color = Color(0xFF212121).copy(alpha = 0.85f)
                            )
                            Text(
                                text = "${if (firebaseStats.avgPricePerKm99 > 0) String.format("R$ %.2f/km", firebaseStats.avgPricePerKm99) else "—/km"} • ${if (firebaseStats.avgDistanceKm99 > 0) String.format("%.1f km", firebaseStats.avgDistanceKm99) else "— km"} • ${if (firebaseStats.avgEstimatedTimeMin99 > 0) String.format("%.0f min", firebaseStats.avgEstimatedTimeMin99) else "— min"}",
                                fontSize = 11.sp,
                                color = Color(0xFF5D4037)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Ofertas hoje: ${firebaseStats.totalOffersToday}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                    Text(
                        text = "Última 1h: ${firebaseStats.offersLast1h}",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }

            // Mensagem quando não há dados
            if (!hasFirebaseData && firebaseStats.loaded) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFF424242).copy(alpha = 0.15f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nenhuma oferta salva na plataforma hoje.\nDeixe o serviço ativo para coletar novas ofertas.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
fun DemandStatColumn(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = unit,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
fun StatItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
fun StatusIndicatorRow(label: String, isActive: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = if (isActive) "● $label: Ativa" else "● $label: Desativada",
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = if (isActive) Color(0xFF4CAF50) else Color(0xFFF44336)
        )
    }
}

@Composable
fun PermissionCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onRequest: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFF4CAF50).copy(alpha = 0.08f)
            else Color(0xFFF44336).copy(alpha = 0.08f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Text(
                    text = description,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            if (isGranted) {
                Text(
                    text = "✓",
                    fontSize = 24.sp,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
            } else {
                TextButton(onClick = onRequest) {
                    Text("Ativar", color = Color(0xFF1E88E5))
                }
            }
        }
    }
}

@Composable
fun AnalysisParamCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    title: String,
    description: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.10f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(accent.copy(alpha = 0.20f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = title,
                    tint = accent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = accent
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun RideHistoryItem(ride: RideHistoryManager.AcceptedRide) {
    val recColor = when (ride.recommendation) {
        "COMPENSA" -> Color(0xFF4CAF50)
        "EVITAR" -> Color(0xFFF44336)
        else -> Color(0xFFFF9800)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = recColor.copy(alpha = 0.06f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Recomendação badge
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(recColor.copy(alpha = 0.2f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when (ride.recommendation) {
                        "COMPENSA" -> "✓"
                        "EVITAR" -> "✗"
                        else -> "~"
                    },
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = recColor
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = ride.appSource,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = String.format("R$ %.2f", ride.price),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = recColor
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${ride.dateDisplay} ${ride.time}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    Text(
                        text = String.format("%.1fkm • R$%.2f/km", ride.distanceKm, ride.pricePerKm),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

@Composable
fun HighlightCard(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    color: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.08f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = color
            )
        }
    }
}

@Composable
fun BarChartCard(
    data: List<Pair<String, Double>>,
    formatValue: (Double) -> String,
    barColor: Color
) {
    val maxValue = data.maxOfOrNull { it.second } ?: 1.0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            data.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(40.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // Barra
                    val fraction = if (maxValue > 0) (value / maxValue).toFloat() else 0f
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(20.dp)
                            .background(
                                Color(0xFF424242).copy(alpha = 0.1f),
                                RoundedCornerShape(4.dp)
                            )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction)
                                .background(barColor.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = formatValue(value),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.width(52.dp),
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
fun TipCard(emoji: String, title: String, tips: List<String>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = "$emoji $title",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
            tips.forEach { tip ->
                Text(
                    text = tip,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(vertical = 3.dp),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
fun FeatureItem(
    icon: ImageVector,
    color: Color,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(text = title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = description,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                lineHeight = 16.sp
            )
        }
    }
}

@Composable
fun InfoRow(label: String, description: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(text = "• $label", fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            text = description,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
fun EmptyStateCard(emoji: String, title: String, subtitle: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = emoji, fontSize = 48.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtitle,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val service = "${context.packageName}/${RideInfoOcrService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(service)
}
