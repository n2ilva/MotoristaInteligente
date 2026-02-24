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
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DrawerValue
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

private const val HIGH_DEMAND_THRESHOLD_PER_MINUTE = 5.0

private data class DemandTrendUi(
    val color: Color,
    val icon: ImageVector
)

private fun toOffersPerMinute(offersInWindow: Int): Double = offersInWindow / 10.0

private fun resolveDemandTrendUi(offersPerMinute: Double): DemandTrendUi {
    val trendColor = when {
        offersPerMinute <= 0.0 -> Color(0xFF757575)
        offersPerMinute > HIGH_DEMAND_THRESHOLD_PER_MINUTE -> Color(0xFFD32F2F)
        else -> Color(0xFF2E7D32)
    }

    val trendIcon = when {
        offersPerMinute > HIGH_DEMAND_THRESHOLD_PER_MINUTE -> Icons.Default.TrendingUp
        offersPerMinute < HIGH_DEMAND_THRESHOLD_PER_MINUTE -> Icons.Default.TrendingDown
        else -> Icons.Default.TrendingFlat
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

                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            isServiceRunning = FloatingAnalyticsService.instance != null
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                AppWithDrawer(
                    isServiceRunning = isServiceRunning,
                    onRequestOverlayPermission = { requestOverlayPermission() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onRequestLocationPermission = { requestLocationPermission() },
                    onStartService = {
                        startFloatingService()
                        Handler(Looper.getMainLooper()).postDelayed({
                            isServiceRunning = FloatingAnalyticsService.instance != null
                        }, 800)
                    },
                    onStopService = {
                        stopFloatingService()
                        isServiceRunning = false
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
        val service = "$packageName/${RideInfoAccessibilityService::class.java.canonicalName}"
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
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    val context = LocalContext.current
    val firestoreManager = remember { FirestoreManager(context) }

    // Forçar recomposição quando estado de auth muda
    var authRefresh by remember { mutableStateOf(0) }

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
                        onStartService = onStartService,
                        onStopService = onStopService,
                        onNavigateToPermissions = { currentScreen = Screen.PERMISSIONS },
                        firestoreManager = firestoreManager
                    )
                    Screen.RIDE_SETTINGS -> RideSettingsScreen()
                    Screen.DEMAND_BY_REGION -> DemandByRegionScreen(
                        firestoreManager = firestoreManager
                    )
                    Screen.WEEKLY_COMPARISON -> WeeklyComparisonScreen()
                    Screen.PERMISSIONS -> PermissionsScreen(
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onRequestLocationPermission = onRequestLocationPermission
                    )
                    Screen.TIPS -> TipsScreen()
                    Screen.LOGIN -> LoginScreen(
                        firestoreManager = firestoreManager,
                        onLoginSuccess = { authRefresh++ }
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
        icon = { Icon(screen.icon, contentDescription = screen.title) },
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
    onStartService: () -> Unit,
    onStopService: () -> Unit,
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
                // Toggle switch estilizado
                val trackColor by animateColorAsState(
                    targetValue = if (isServiceRunning) Color(0xFF4CAF50) else Color(0xFF555555),
                    animationSpec = tween(300), label = "trackColor"
                )
                val thumbOffset by animateDpAsState(
                    targetValue = if (isServiceRunning) 1.dp else 0.dp,
                    animationSpec = tween(300), label = "thumbOffset"
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(28.dp))
                        .background(trackColor)
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isStartingService && !isServiceRunning) {
                            Spacer(modifier = Modifier.width(14.dp))
                            androidx.compose.material3.CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "Iniciando...",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.95f),
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        } else if (!isServiceRunning) {
                            // Thumb à esquerda (desativado)
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("○", fontSize = 18.sp, color = Color(0xFF555555))
                            }
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "Desativado",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(end = 16.dp)
                            )
                        } else {
                            // Thumb à direita (ativado)
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = "Ativado",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.9f),
                                modifier = Modifier.padding(start = 16.dp)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("●", fontSize = 18.sp, color = Color(0xFF4CAF50))
                            }
                        }
                    }
                }

                // Botões de teste (só quando serviço ativo)
                if (isServiceRunning) {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Status dos serviços
                    val isAccessibilityActive = RideInfoAccessibilityService.isServiceConnected
                    val isLocationActive = ContextCompat.checkSelfPermission(
                        context, Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED
                    val isOverlayActive = android.provider.Settings.canDrawOverlays(context)

                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatusIndicatorRow("Acessibilidade", isAccessibilityActive)
                        StatusIndicatorRow("Localização", isLocationActive)
                        StatusIndicatorRow("Sobreposição de Tela", isOverlayActive)

                        // Contador de ofertas coletadas (sessão + Firebase do dia)
                        val rideCount = DemandTracker.getRideCount()
                        if (rideCount > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📡 $rideCount ofertas detectadas hoje",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Color(0xFF1E88E5)
                                )
                            }
                        }
                    }

                    if (!isAccessibilityActive) {
                        Text(
                            text = "Ative o serviço de acessibilidade nas configurações do Android para detectar corridas automaticamente.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 4.dp)
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
fun RideSettingsScreen() {
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

        DriverSettingsCard()

        Spacer(modifier = Modifier.height(24.dp))

        // ========================
        // Dados do Veículo
        // ========================
        VehicleSettingsCard()

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
    val historyManager = remember { RideHistoryManager(context) }
    val weekSummary = remember { historyManager.getWeekSummary() }
    val dailySummaries = remember { historyManager.getDailySummariesLast7Days() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (weekSummary.totalRides == 0) {
            EmptyStateCard(
                emoji = "📅",
                title = "Sem dados da semana",
                subtitle = "Aceite corridas para ver o resumo dos últimos 7 dias."
            )
        } else {
            // Cards diários
            Text(
                text = "📋 Resumo por Dia",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            dailySummaries.forEach { (dayName, dateBR, daySummary) ->
                DaySummaryCard(
                    dayName = dayName,
                    dateBR = dateBR,
                    summary = daySummary
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Card resumo da semana
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF7B1FA2).copy(alpha = 0.10f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "📅 Resumo da Semana",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7B1FA2)
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = String.format("R$ %.2f", weekSummary.totalEarnings),
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7B1FA2)
                    )
                    Text(
                        text = "ganhos em 7 dias",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("Corridas", "${weekSummary.totalRides}")
                        StatItem("Km total", String.format("%.1f", weekSummary.totalDistanceKm))
                        StatItem("Tempo", "${weekSummary.totalTimeMin} min")
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("R$/km", String.format("%.2f", weekSummary.avgPricePerKm))
                        StatItem("R$/hora", String.format("%.0f", weekSummary.avgEarningsPerHour))
                        StatItem("Média/corrida", String.format("R$ %.0f", weekSummary.avgRidePrice))
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Melhor e pior corrida da semana
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HighlightCard(
                            modifier = Modifier.weight(1f),
                            label = "Melhor corrida",
                            value = String.format("R$ %.2f", weekSummary.bestRidePrice),
                            color = Color(0xFF4CAF50)
                        )
                        HighlightCard(
                            modifier = Modifier.weight(1f),
                            label = "Pior corrida",
                            value = String.format("R$ %.2f", weekSummary.worstRidePrice),
                            color = Color(0xFFF44336)
                        )
                    }

                    // Melhor dia da semana
                    val bestDay = dailySummaries
                        .filter { it.third.totalRides > 0 }
                        .maxByOrNull { it.third.totalEarnings }
                    if (bestDay != null) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "⭐ Melhor dia: ${bestDay.first} (${bestDay.second}) — R$ ${String.format("%.2f", bestDay.third.totalEarnings)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF4CAF50)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
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
    var cities by remember { mutableStateOf<List<String>>(emptyList()) }
    var cityDemandMini by remember { mutableStateOf<List<FirestoreManager.CityDemandMini>>(emptyList()) }
    var selectedCity by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var expandedCityCard by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (firestoreManager == null) {
            isLoading = false
            return@LaunchedEffect
        }

        while (true) {
            firestoreManager.loadAvailableCitiesWithDrivers { loadedCities ->
                cities = loadedCities
                firestoreManager.loadCityDemandMini(loadedCities) { mini ->
                    cityDemandMini = mini
                    isLoading = false
                }
            }

            delay(20_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Demanda regional agregada dos últimos 10 minutos (todos os motoristas)",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )

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

        if (cities.isEmpty() && cityDemandMini.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Text(
                    text = "Sem dados de demanda para os últimos 10 minutos.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(16.dp),
                    textAlign = TextAlign.Center
                )
            }
            return@Column
        }

        val miniByCity = cityDemandMini.associateBy { it.city }
        val allCities = (cities + cityDemandMini.map { it.city }).distinct()
        val sortedCities = allCities.sortedWith(
            compareByDescending<String> { city -> miniByCity[city]?.offersLast15m ?: 0 }
                .thenBy { it }
        )

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sortedCities.forEach { city ->
                val cityMini = miniByCity[city]
                val offersLast15m = cityMini?.offersLast15m ?: 0
                val offersPerMinute = toOffersPerMinute(offersLast15m)
                val cityTrendUi = resolveDemandTrendUi(offersPerMinute)
                val isExpanded = expandedCityCard == city

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedCity = city
                            expandedCityCard = if (isExpanded) null else city
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = cityTrendUi.color.copy(alpha = 0.10f)
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
                                    text = String.format(Locale.US, "%.1f/min", offersPerMinute),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = cityTrendUi.color
                                )
                                Text(
                                    text = "U:${cityMini?.offersUberLast15m ?: 0} · 99:${cityMini?.offers99Last15m ?: 0}",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
                                )
                                Text(
                                    text = "Motoristas: ${cityMini?.activeDriversLast15m ?: 0}",
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
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
                                    text = "Sem bairros com ofertas nos últimos 10 minutos",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            } else {
                                cityMini?.neighborhoods?.forEach { neighborhoodMini ->
                                    val neighborhoodPerMinute = toOffersPerMinute(neighborhoodMini.offersLast15m)
                                    val neighborhoodTrendUi = resolveDemandTrendUi(neighborhoodPerMinute)

                                    Card(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = neighborhoodTrendUi.color.copy(alpha = 0.10f)
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
                                            Text(
                                                text = neighborhoodMini.neighborhood,
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Icon(
                                                imageVector = neighborhoodTrendUi.icon,
                                                contentDescription = null,
                                                tint = neighborhoodTrendUi.color,
                                                modifier = Modifier.padding(horizontal = 6.dp)
                                            )
                                            Column(horizontalAlignment = Alignment.End) {
                                                Text(
                                                    text = String.format(Locale.US, "%.1f/min", neighborhoodPerMinute),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = neighborhoodTrendUi.color
                                                )
                                                Text(
                                                    text = "U:${neighborhoodMini.offersUberLast15m} · 99:${neighborhoodMini.offers99Last15m}",
                                                    fontSize = 10.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.62f)
                                                )
                                                Text(
                                                    text = "Motoristas: ${neighborhoodMini.activeDriversLast15m}",
                                                    fontSize = 9.sp,
                                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.58f)
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
    onRequestOverlayPermission: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    val context = LocalContext.current
    val hasOverlayPermission = Settings.canDrawOverlays(context)
    val hasLocationPermission = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasAccessibility = isAccessibilityEnabled(context)

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
            title = "Começando",
            tips = listOf(
                "1. Conceda todas as permissões na seção de Permissões",
                "2. Toque em \"Iniciar Análise\" na tela inicial",
                "3. Um botão flutuante aparecerá na tela",
                "4. Abra o Uber ou 99 normalmente",
                "5. Quando uma corrida chegar, a análise aparece automaticamente"
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TipCard(
            emoji = "📊",
            title = "Entendendo a Análise",
            tips = listOf(
                "Score de 0 a 100: quanto maior, melhor a corrida",
                "🟢 Score ≥ 60: Compensa aceitar",
                "🟡 Score 40-59: Neutro, analise com cuidado",
                "🔴 Score < 40: Não compensa, melhor esperar",
                "O score considera: preço/km, ganho/hora, distância de embarque e horário"
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TipCard(
            emoji = "💡",
            title = "Botão Flutuante",
            tips = listOf(
                "Arraste o botão para qualquer posição na tela",
                "Segure o botão para ver o painel de demanda",
                "O painel mostra ganhos da sessão e dica de pausa inteligente",
                "Indicadores de demanda mostram se está aquecido ou frio"
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TipCard(
            emoji = "⚙️",
            title = "Personalizando",
            tips = listOf(
                "Acesse 'Configurar Corrida' no menu para ajustar seus critérios",
                "Defina seu valor mínimo por km aceito",
                "Ajuste a distância máxima para buscar o passageiro",
                "Corridas fora dos seus limites são penalizadas automaticamente"
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        TipCard(
            emoji = "📈",
            title = "Análise e Histórico",
            tips = listOf(
                "Acompanhe seus ganhos diários na 'Análise do Dia'",
                "Compare seu desempenho semanal em 'Comparação Semanal'",
                "O histórico guarda todas as corridas aceitas automaticamente",
                "Use os dados para identificar seus melhores horários e regiões"
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
    onLoginSuccess: () -> Unit = {}
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
                isGoogleLoggedIn = false
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
                                isLoading = false
                                onLoginSuccess()
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

@Composable
fun DriverSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { DriverPreferences(context) }

    var minPricePerKm by remember { mutableFloatStateOf(prefs.minPricePerKm.toFloat()) }
    var minEarningsPerHour by remember { mutableFloatStateOf(prefs.minEarningsPerHour.toFloat()) }
    var maxPickupDistance by remember { mutableFloatStateOf(prefs.maxPickupDistance.toFloat()) }
    var maxRideDistance by remember { mutableFloatStateOf(prefs.maxRideDistance.toFloat()) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF6F00).copy(alpha = 0.08f)
        )
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Suas Preferências",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(
                    onClick = {
                        prefs.resetToDefaults()
                        minPricePerKm = DriverPreferences.DEFAULT_MIN_PRICE_PER_KM.toFloat()
                        minEarningsPerHour = DriverPreferences.DEFAULT_MIN_EARNINGS_PER_HOUR.toFloat()
                        maxPickupDistance = DriverPreferences.DEFAULT_MAX_PICKUP_DISTANCE.toFloat()
                        maxRideDistance = DriverPreferences.DEFAULT_MAX_RIDE_DISTANCE.toFloat()
                    },
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Resetar", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            SettingSlider(
                label = "Valor mínimo por km",
                value = minPricePerKm,
                valueText = String.format("R$ %.2f/km", minPricePerKm),
                min = DriverPreferences.MIN_PRICE_PER_KM_FLOOR.toFloat(),
                max = DriverPreferences.MIN_PRICE_PER_KM_CEIL.toFloat(),
                steps = 44,
                onValueChange = { minPricePerKm = it },
                onValueChangeFinished = {
                    prefs.minPricePerKm = minPricePerKm.toDouble()
                    prefs.applyToAnalyzer()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSlider(
                label = "Ganho mínimo por hora",
                value = minEarningsPerHour,
                valueText = String.format("R$ %.0f/h", minEarningsPerHour),
                min = DriverPreferences.MIN_EARNINGS_FLOOR.toFloat(),
                max = DriverPreferences.MIN_EARNINGS_CEIL.toFloat(),
                steps = 18,
                onValueChange = { minEarningsPerHour = it },
                onValueChangeFinished = {
                    prefs.minEarningsPerHour = minEarningsPerHour.toDouble()
                    prefs.applyToAnalyzer()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSlider(
                label = "Distância máx. para buscar cliente",
                value = maxPickupDistance,
                valueText = String.format("%.1f km", maxPickupDistance),
                min = DriverPreferences.MAX_PICKUP_FLOOR.toFloat(),
                max = DriverPreferences.MAX_PICKUP_CEIL.toFloat(),
                steps = 38,
                onValueChange = { maxPickupDistance = it },
                onValueChangeFinished = {
                    prefs.maxPickupDistance = maxPickupDistance.toDouble()
                    prefs.applyToAnalyzer()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            SettingSlider(
                label = "Distância máx. da corrida",
                value = maxRideDistance,
                valueText = String.format("%.0f km", maxRideDistance),
                min = DriverPreferences.MAX_RIDE_DISTANCE_FLOOR.toFloat(),
                max = DriverPreferences.MAX_RIDE_DISTANCE_CEIL.toFloat(),
                steps = 19,
                onValueChange = { maxRideDistance = it },
                onValueChangeFinished = {
                    prefs.maxRideDistance = maxRideDistance.toDouble()
                    prefs.applyToAnalyzer()
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Color(0xFF424242).copy(alpha = 0.15f),
                        RoundedCornerShape(8.dp)
                    )
                    .padding(10.dp)
            ) {
                Text(
                    text = "📌 ${prefs.getSummary()}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

// ===============================================
// VEHICLE SETTINGS CARD (Dados do Veículo)
// ===============================================
@Composable
fun VehicleSettingsCard() {
    val context = LocalContext.current
    val prefs = remember { DriverPreferences(context) }

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
                text = "Monitoramento de Demanda",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                  text = if (!isGoogleLoggedIn) "Faça login com Google para visualizar demanda"
                      else if (hasFirebaseData) "Análise 100% Firebase (ofertas do dia)"
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
                    text = "Histórico do dia",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E88E5)
                )
                Spacer(modifier = Modifier.height(12.dp))

                // Totais
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    DemandStatColumn("Total", "${firebaseStats.totalOffersToday}", "ofertas hoje")
                    DemandStatColumn("Última 1h", "${firebaseStats.offersLast1h}", "ofertas")
                    DemandStatColumn("Últimas 3h", "${firebaseStats.offersLast3h}", "ofertas")
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "POR PLATAFORMA",
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
                                Color(0xFF000000).copy(alpha = 0.15f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "UBER",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${firebaseStats.offersUber} ofertas",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Médio: ${if (firebaseStats.avgPriceUber > 0) String.format("R$ %.2f", firebaseStats.avgPriceUber) else "—"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "${if (firebaseStats.avgPricePerKmUber > 0) String.format("R$ %.2f/km", firebaseStats.avgPricePerKmUber) else "—/km"} • ${if (firebaseStats.avgDistanceKmUber > 0) String.format("%.1f km", firebaseStats.avgDistanceKmUber) else "— km"} • ${if (firebaseStats.avgEstimatedTimeMinUber > 0) String.format("%.0f min", firebaseStats.avgEstimatedTimeMinUber) else "— min"}",
                                fontSize = 11.sp,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }

                    // Card 99
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(
                                Color(0xFFFF6F00).copy(alpha = 0.10f),
                                RoundedCornerShape(10.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Column {
                            Text(
                                text = "99",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${firebaseStats.offers99} ofertas",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Text(
                                text = "Médio: ${if (firebaseStats.avgPrice99 > 0) String.format("R$ %.2f", firebaseStats.avgPrice99) else "—"}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "${if (firebaseStats.avgPricePerKm99 > 0) String.format("R$ %.2f/km", firebaseStats.avgPricePerKm99) else "—/km"} • ${if (firebaseStats.avgDistanceKm99 > 0) String.format("%.1f km", firebaseStats.avgDistanceKm99) else "— km"} • ${if (firebaseStats.avgEstimatedTimeMin99 > 0) String.format("%.0f min", firebaseStats.avgEstimatedTimeMin99) else "— min"}",
                                fontSize = 11.sp,
                                color = Color(0xFFFF6F00)
                            )
                        }
                    }
                }

                // Melhor e pior R$/km do dia
                if (firebaseStats.bestPricePerKm > 0) {
                    Spacer(modifier = Modifier.height(12.dp))

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFF424242).copy(alpha = 0.2f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Melhor R$/km",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = String.format("R$ %.2f", firebaseStats.bestPricePerKm),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF4CAF50)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "Pior R$/km",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                                Text(
                                    text = String.format("R$ %.2f", firebaseStats.worstPricePerKm),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFF44336)
                                )
                            }
                        }
                    }
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
    val service = "${context.packageName}/${RideInfoAccessibilityService::class.java.canonicalName}"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.contains(service)
}
