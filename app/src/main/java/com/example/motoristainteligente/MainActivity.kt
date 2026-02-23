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
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.motoristainteligente.ui.theme.MotoristainteligenteTheme
import kotlinx.coroutines.launch

// ===============================================
// Definição das telas do menu
// ===============================================
enum class Screen(val title: String, val icon: ImageVector) {
    HOME("Início", Icons.Default.Home),
    RIDE_SETTINGS("Configurar Corrida", Icons.Default.Settings),
    DAILY_ANALYTICS("Análise do Dia", Icons.Default.Analytics),
    WEEKLY_COMPARISON("Comparação Semanal", Icons.Default.CalendarMonth),
    RIDE_HISTORY("Histórico de Corridas", Icons.Default.History),
    PERMISSIONS("Permissões", Icons.Default.Lock),
    TIPS("Dicas de Uso", Icons.Default.Lightbulb),
    LOGIN("Login", Icons.Default.Person)
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
        startForegroundService(intent)
        Toast.makeText(this, "Análise de corridas iniciada!", Toast.LENGTH_SHORT).show()
    }

    private fun stopFloatingService() {
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
                }
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
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
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
                        onNavigateToPermissions = { currentScreen = Screen.PERMISSIONS }
                    )
                    Screen.RIDE_SETTINGS -> RideSettingsScreen()
                    Screen.DAILY_ANALYTICS -> DailyAnalyticsScreen()
                    Screen.WEEKLY_COMPARISON -> WeeklyComparisonScreen()
                    Screen.RIDE_HISTORY -> RideHistoryScreen()
                    Screen.PERMISSIONS -> PermissionsScreen(
                        onRequestOverlayPermission = onRequestOverlayPermission,
                        onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                        onRequestLocationPermission = onRequestLocationPermission
                    )
                    Screen.TIPS -> TipsScreen()
                    Screen.LOGIN -> LoginScreen()
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
    onScreenSelected: (Screen) -> Unit
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
                        text = "Motorista Inteligente",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Faça login para sincronizar",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
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
            DrawerMenuItem(Screen.DAILY_ANALYTICS, currentScreen, onScreenSelected)
            DrawerMenuItem(Screen.WEEKLY_COMPARISON, currentScreen, onScreenSelected)
            DrawerMenuItem(Screen.RIDE_HISTORY, currentScreen, onScreenSelected)

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
    onNavigateToPermissions: () -> Unit = {}
) {
    val context = LocalContext.current
    val hasOverlay = Settings.canDrawOverlays(context)
    val hasLocation = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED
    val hasAccessibility = isAccessibilityEnabled(context)
    val pendingCount = listOf(hasOverlay, hasLocation, hasAccessibility).count { !it }

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

        // Logo
        Image(
            painter = painterResource(id = R.drawable.logo),
            contentDescription = "Logo",
            modifier = Modifier
                .size(90.dp)
                .clip(RoundedCornerShape(20.dp))
        )

        Spacer(modifier = Modifier.height(12.dp))

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
                            indication = null
                        ) {
                            if (isServiceRunning) onStopService() else onStartService()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!isServiceRunning) {
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
                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Testar o card de análise:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )

                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                FloatingAnalyticsService.instance?.simulateRide(AppSource.UBER)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Testar Uber", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                FloatingAnalyticsService.instance?.simulateRide(AppSource.NINETY_NINE)
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Testar 99", fontSize = 13.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

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

        // Monitoramento de Demanda (quando serviço ativo)
        if (isServiceRunning) {
            DemandMonitorCard()
            Spacer(modifier = Modifier.height(24.dp))
        }

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

        // Como analisamos suas corridas
        Text(
            text = "Como analisamos suas corridas",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Cada corrida recebe um score de 0 a 100",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
                emoji = "💲",
                title = "Preço/km",
                description = "Valor da corrida dividido pela distância",
                accent = Color(0xFF4CAF50)
            )
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                emoji = "📍",
                title = "Preço efetivo",
                description = "Inclui km até o ponto de embarque",
                accent = Color(0xFF1E88E5)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                emoji = "⏱",
                title = "Ganho/hora",
                description = "Estimativa de quanto ganha por hora",
                accent = Color(0xFFFF9800)
            )
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                emoji = "🚗",
                title = "Embarque",
                description = "Distância até o passageiro",
                accent = Color(0xFFF44336)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                emoji = "🕐",
                title = "Horário",
                description = "Bônus em horários de pico e noturno",
                accent = Color(0xFF7B1FA2)
            )
            AnalysisParamCard(
                modifier = Modifier.weight(1f),
                emoji = "📈",
                title = "Demanda",
                description = "Monitoramento em tempo real da região",
                accent = Color(0xFF00897B)
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Legenda de score
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ScoreLegendItem(color = Color(0xFF4CAF50), label = "60+", tag = "Compensa")
                ScoreLegendItem(color = Color(0xFFFF9800), label = "40-59", tag = "Neutro")
                ScoreLegendItem(color = Color(0xFFF44336), label = "-40", tag = "Evitar")
            }
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

        // Info adicional
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E88E5).copy(alpha = 0.08f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "💡 Como os valores afetam a análise",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E88E5)
                )
                Spacer(modifier = Modifier.height(12.dp))
                InfoRow("Valor mínimo/km", "Corridas abaixo deste valor recebem pontuação menor.")
                InfoRow("Ganho mínimo/h", "Calcularemos se a corrida atinge seu objetivo por hora.")
                InfoRow("Distância busca", "Corridas com embarque muito longe são penalizadas em -40%.")
                InfoRow("Distância corrida", "Corridas muito longas são penalizadas em -30%.")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ===============================================
// DAILY ANALYTICS SCREEN (Análise do Dia)
// ===============================================
@Composable
fun DailyAnalyticsScreen() {
    val context = LocalContext.current
    val historyManager = remember { RideHistoryManager(context) }
    val summary = remember { historyManager.getTodaySummary() }
    val todayRides = remember { historyManager.getToday() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (summary.totalRides == 0) {
            EmptyStateCard(
                emoji = "📊",
                title = "Nenhuma corrida hoje",
                subtitle = "As corridas aceitas aparecem aqui com a análise completa do dia."
            )
        } else {
            // Card resumo do dia
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "💰 Ganhos de Hoje",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    // Valor total grande
                    Text(
                        text = String.format("R$ %.2f", summary.totalEarnings),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("Corridas", "${summary.totalRides}")
                        StatItem("Km total", String.format("%.1f", summary.totalDistanceKm))
                        StatItem("R$/km", String.format("%.2f", summary.avgPricePerKm))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("R$/hora", String.format("%.0f", summary.avgEarningsPerHour))
                        StatItem("R$/km médio", String.format("%.2f", summary.avgPricePerKm))
                        StatItem("Tempo", "${summary.totalTimeMin} min")
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Melhor e pior corrida
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HighlightCard(
                    modifier = Modifier.weight(1f),
                    label = "Melhor corrida",
                    value = String.format("R$ %.2f", summary.bestRidePrice),
                    color = Color(0xFF4CAF50)
                )
                HighlightCard(
                    modifier = Modifier.weight(1f),
                    label = "Pior corrida",
                    value = String.format("R$ %.2f", summary.worstRidePrice),
                    color = Color(0xFFF44336)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Lista de corridas do dia
            Text(
                text = "Corridas de Hoje",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            todayRides.forEach { ride ->
                RideHistoryItem(ride)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ===============================================
// WEEKLY COMPARISON SCREEN (Comparação Semanal)
// ===============================================
@Composable
fun WeeklyComparisonScreen() {
    val context = LocalContext.current
    val historyManager = remember { RideHistoryManager(context) }
    val weekSummary = remember { historyManager.getWeekSummary() }
    val dailyEarnings = remember { historyManager.getDailyEarningsLast7Days() }
    val dailyRides = remember { historyManager.getDailyRidesLast7Days() }

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
                subtitle = "Aceite corridas para ver a comparação dos últimos 7 dias."
            )
        } else {
            // Resumo semanal
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF7B1FA2).copy(alpha = 0.08f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "📅 Semana (7 dias)",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = String.format("R$ %.2f", weekSummary.totalEarnings),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF7B1FA2)
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("Corridas", "${weekSummary.totalRides}")
                        StatItem("Km total", String.format("%.1f", weekSummary.totalDistanceKm))
                        StatItem("R$/km médio", String.format("%.2f", weekSummary.avgPricePerKm))
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        StatItem("R$/h médio", String.format("%.0f", weekSummary.avgEarningsPerHour))
                        StatItem("R$/km médio", String.format("%.2f", weekSummary.avgPricePerKm))
                        StatItem("Corrida média", String.format("R$ %.0f", weekSummary.avgRidePrice))
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Gráfico de barras — ganhos por dia
            Text(
                text = "💰 Ganhos por Dia",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            BarChartCard(
                data = dailyEarnings,
                formatValue = { String.format("R$%.0f", it) },
                barColor = Color(0xFF4CAF50)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Gráfico de barras — corridas por dia
            Text(
                text = "🚗 Corridas por Dia",
                fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))

            BarChartCard(
                data = dailyRides.map { Pair(it.first, it.second.toDouble()) },
                formatValue = { String.format("%.0f", it) },
                barColor = Color(0xFF1E88E5)
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ===============================================
// RIDE HISTORY SCREEN (Histórico de Corridas)
// ===============================================
@Composable
fun RideHistoryScreen() {
    val context = LocalContext.current
    val historyManager = remember { RideHistoryManager(context) }
    val rides = remember { historyManager.getAll() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (rides.isEmpty()) {
            EmptyStateCard(
                emoji = "📋",
                title = "Nenhuma corrida registrada",
                subtitle = "As corridas aceitas aparecerão aqui automaticamente."
            )
        } else {
            // Resumo geral
            Text(
                text = "${rides.size} corridas registradas",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            rides.forEach { ride ->
                RideHistoryItem(ride)
                Spacer(modifier = Modifier.height(6.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
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
// LOGIN SCREEN (Layout pronto, sem funcionalidade)
// ===============================================
@Composable
fun LoginScreen() {
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

        // Botão Google (layout pronto)
        OutlinedButton(
            onClick = { /* TODO: Implementar login com Google */ },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("🔵  Entrar com Google", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Botão Email (layout pronto)
        OutlinedButton(
            onClick = { /* TODO: Implementar login com email */ },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("✉️  Entrar com E-mail", fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Info
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFF9800).copy(alpha = 0.08f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "🚧 Em desenvolvimento",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFFF9800)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "O login será implementado em breve. " +
                            "Por enquanto, todos os dados são salvos localmente no dispositivo.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
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
fun DemandMonitorCard() {
    val stats = DemandTracker.getStats()
    val context = LocalContext.current
    val historyManager = remember { RideHistoryManager(context) }
    val todaySummary = remember { historyManager.getTodaySummary() }
    val dailyEarnings = todaySummary.totalEarnings + stats.sessionTotalEarnings

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
                text = "Acompanhe a atividade em tempo real",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "DEMANDA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stats.demandLevel.displayText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(stats.demandLevel.color)
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "TENDÊNCIA",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stats.trend.displayText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Divider sutil
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                DemandStatColumn("15 min", "${stats.ridesLast15Min}", "corridas")
                DemandStatColumn("30 min", "${stats.ridesLast30Min}", "corridas")
                DemandStatColumn("1 hora", "${stats.ridesLastHour}", "corridas")
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "PREÇO MÉDIO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (stats.avgPricePerKmLast15Min > 0)
                            String.format("R$ %.2f/km", stats.avgPricePerKmLast15Min)
                        else "—",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "GANHO DIÁRIO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = String.format("R$ %.0f", dailyEarnings),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            if (stats.sessionDurationMin > 0) {
                Spacer(modifier = Modifier.height(12.dp))
                val sessionHours = stats.sessionDurationMin / 60
                val sessionMins = stats.sessionDurationMin % 60
                val sessionText = if (sessionHours > 0) "${sessionHours}h ${sessionMins}min"
                else "${sessionMins}min"

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Color(0xFF424242).copy(alpha = 0.3f),
                            RoundedCornerShape(8.dp)
                        )
                        .padding(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Sessão: $sessionText",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        if (stats.sessionAvgEarningsPerHour > 0) {
                            Text(
                                text = String.format("R$ %.0f/h", stats.sessionAvgEarningsPerHour),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
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
    emoji: String,
    title: String,
    description: String,
    accent: Color
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = accent.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(text = emoji, fontSize = 18.sp)
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
fun ScoreLegendItem(color: Color, label: String, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column {
            Text(text = label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Text(text = tag, fontSize = 10.sp, color = color)
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
