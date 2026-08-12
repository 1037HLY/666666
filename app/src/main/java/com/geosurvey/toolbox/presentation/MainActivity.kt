package com.geosurvey.toolbox.presentation

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.geosurvey.toolbox.R
import com.geosurvey.toolbox.data.repository.AttitudeData
import com.geosurvey.toolbox.domain.model.Constellation
import com.geosurvey.toolbox.domain.model.LocationQuality
import com.geosurvey.toolbox.presentation.viewmodel.LocationUiState
import com.geosurvey.toolbox.presentation.viewmodel.LocationViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlin.math.*

// --- 1. 定义导航路由 ---
sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Analysis : Screen("analysis")
    object Record : Screen("record")
    object Camera : Screen("camera")
}

// --- 2. 主Activity ---
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF0F4F8)
                ) {
                    val homeState = remember { HomeScreenState() }
                    val analysisState = remember { AnalysisScreenState() }
                    val recordState = remember { RecordScreenState() }
                    val cameraState = remember { CameraScreenState() }

                    MainScreen(
                        homeState = homeState,
                        analysisState = analysisState,
                        recordState = recordState,
                        cameraState = cameraState
                    )
                }
            }
        }
    }
}

// --- 3. 页面状态类 ---
class HomeScreenState {
    var isGPSFullscreen by mutableStateOf(false)
    var isSatelliteFullscreen by mutableStateOf(false)
    var isTrackFullscreen by mutableStateOf(false)
}

class AnalysisScreenState {
    var isPreview1Fullscreen by mutableStateOf(false)
    var isPreview2Fullscreen by mutableStateOf(false)
    var isPreview3Fullscreen by mutableStateOf(false)
}

class RecordScreenState {
    var isPreview1Fullscreen by mutableStateOf(false)
    var isPreview2Fullscreen by mutableStateOf(false)
}

class CameraScreenState {
    var isPreview1Fullscreen by mutableStateOf(false)
    var isPreview2Fullscreen by mutableStateOf(false)
}

// --- 4. 主界面 ---
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun MainScreen(
    homeState: HomeScreenState,
    analysisState: AnalysisScreenState,
    recordState: RecordScreenState,
    cameraState: CameraScreenState
) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: Screen.Home.route

    var fullscreenContent by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }

    Scaffold(
        bottomBar = {
            GlassBottomNavigation(
                currentRoute = currentRoute,
                onTabSelected = { screen ->
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Home.route) {
                    HomeScreen(
                        state = homeState,
                        showFullscreen = { content -> fullscreenContent = content }
                    )
                }
                composable(Screen.Analysis.route) {
                    AnalysisScreen(
                        state = analysisState,
                        showFullscreen = { content -> fullscreenContent = content }
                    )
                }
                composable(Screen.Record.route) {
                    RecordScreen(
                        state = recordState,
                        showFullscreen = { content -> fullscreenContent = content }
                    )
                }
                composable(Screen.Camera.route) {
                    CameraScreen(
                        state = cameraState,
                        showFullscreen = { content -> fullscreenContent = content }
                    )
                }
            }
        }
    }

    // 全屏对话框
    fullscreenContent?.let { content ->
        Dialog(
            onDismissRequest = { fullscreenContent = null },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFFF0F4F8))
                    .clickable { fullscreenContent = null }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .clickable { /* 阻止点击穿透 */ },
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .shadow(
                                elevation = 24.dp,
                                shape = RoundedCornerShape(24.dp),
                                ambientColor = Color.Black.copy(alpha = 0.15f)
                            ),
                        shape = RoundedCornerShape(24.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White.copy(alpha = 0.98f)
                        )
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(20.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 8.dp)
                                    .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp, vertical = 6.dp)
                            ) {
                                Text("点击外部关闭", fontSize = 12.sp, color = Color(0xFF94A3B8))
                            }
                            content()
                        }
                    }
                }
            }
        }
    }
}

// --- 5. 底部导航栏 ---
@Composable
fun GlassBottomNavigation(
    currentRoute: String,
    onTabSelected: (Screen) -> Unit
) {
    val items = listOf(
        Triple(Screen.Home, Icons.Default.GpsFixed, stringResource(R.string.nav_home)),
        Triple(Screen.Analysis, Icons.Default.Analytics, stringResource(R.string.nav_analysis)),
        Triple(Screen.Record, Icons.Default.List, stringResource(R.string.nav_record)),
        Triple(Screen.Camera, Icons.Default.PhotoCamera, stringResource(R.string.nav_camera))
    )

    NavigationBar(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(32.dp),
                ambientColor = Color.Black.copy(alpha = 0.15f),
                spotColor = Color.Black.copy(alpha = 0.1f)
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.8f),
                        Color.White.copy(alpha = 0.6f)
                    )
                ),
                shape = RoundedCornerShape(32.dp)
            ),
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0)
    ) {
        items.forEach { (screen, icon, label) ->
            val selected = currentRoute == screen.route
            NavigationBarItem(
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        modifier = Modifier.size(26.dp)
                    )
                },
                label = {
                    Text(
                        text = label,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                },
                selected = selected,
                onClick = { onTabSelected(screen) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF0EA5E9),
                    selectedTextColor = Color(0xFF0EA5E9),
                    unselectedIconColor = Color.Gray,
                    unselectedTextColor = Color.Gray,
                    indicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .background(
                        if (selected) Color(0xFF0EA5E9).copy(alpha = 0.15f) else Color.Transparent,
                        RoundedCornerShape(20.dp)
                    )
            )
        }
    }
}

// --- 6. 卫星极坐标图 ---
@Composable
fun SatellitePolarChart(
    satellites: List<com.geosurvey.toolbox.domain.model.SatelliteInfo>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = min(size.width, size.height) / 2f - 20f

        drawCircle(
            color = Color(0xFF64748B).copy(alpha = 0.3f),
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1f)
        )

        drawCircle(
            color = Color(0xFF64748B).copy(alpha = 0.2f),
            radius = radius * 0.5f,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1f)
        )

        for (i in 0..3) {
            val angle = i.toFloat() * PI.toFloat() / 2f
            val endX = centerX + radius * cos(angle)
            val endY = centerY + radius * sin(angle)
            drawLine(
                color = Color(0xFF64748B).copy(alpha = 0.2f),
                start = Offset(centerX, centerY),
                end = Offset(endX, endY),
                strokeWidth = 1f
            )
        }

        val labels = listOf("N", "E", "S", "W")
        for (i in labels.indices) {
            val angle = i.toFloat() * PI.toFloat() / 2f - PI.toFloat() / 2f
            val labelRadius = radius + 16f
            val labelX = centerX + labelRadius * cos(angle)
            val labelY = centerY + labelRadius * sin(angle)
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#64748B")
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText(labels[i], labelX, labelY + 8f, paint)
            }
        }

        satellites.forEach { satellite ->
            val elevation = satellite.elevation
            val azimuth = satellite.azimuth

            val elevationRad = (elevation / 90f).coerceIn(0f, 1f)
            val r = radius * elevationRad

            val angleRad = Math.toRadians(azimuth.toDouble()).toFloat() - PI.toFloat() / 2f

            val x = centerX + r * cos(angleRad)
            val y = centerY + r * sin(angleRad)

            val color = when (satellite.constellation) {
                Constellation.GPS -> Color(0xFF4CAF50)
                Constellation.GLONASS -> Color(0xFF2196F3)
                Constellation.GALILEO -> Color(0xFFFF9800)
                Constellation.BEIDOU -> Color(0xFFE91E63)
                else -> Color(0xFF9E9E9E)
            }

            val size = (8f + satellite.snr / 6f).coerceIn(4f, 16f)

            drawCircle(
                color = color.copy(alpha = if (satellite.usedInFix) 1f else 0.4f),
                radius = size,
                center = Offset(x, y)
            )

            drawCircle(
                color = color.copy(alpha = 0.2f),
                radius = size * 1.8f,
                center = Offset(x, y)
            )
        }

        drawCircle(
            color = Color(0xFF0EA5E9),
            radius = 6f,
            center = Offset(centerX, centerY)
        )
        drawCircle(
            color = Color(0xFF0EA5E9).copy(alpha = 0.3f),
            radius = 14f,
            center = Offset(centerX, centerY)
        )
    }
}

// --- 7. 海拔变化曲线图 ---
@Composable
fun ElevationChart(
    tracks: List<com.geosurvey.toolbox.data.database.TrackEntity>,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        if (tracks.isEmpty()) {
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#94A3B8")
                    textSize = 24f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText("暂无海拔数据", size.width / 2, size.height / 2, paint)
            }
            return@Canvas
        }

        val padding = 20f
        val chartWidth = size.width - padding * 2
        val chartHeight = size.height - padding * 2

        val elevations = tracks.map { it.altitude }
        val maxElev = elevations.maxOrNull() ?: 100.0
        val minElev = elevations.minOrNull() ?: 0.0
        val range = if (maxElev - minElev < 1) 10.0 else maxElev - minElev

        for (i in 0..4) {
            val y = padding + chartHeight * i / 4
            drawLine(
                color = Color(0xFFE2E8F0),
                start = Offset(padding, y),
                end = Offset(size.width - padding, y),
                strokeWidth = 1f
            )
            val label = (maxElev - range * i / 4).toInt()
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#94A3B8")
                    textSize = 18f
                }
                drawText("${label}m", padding - 40f, y + 6f, paint)
            }
        }

        val path = Path()
        val points = tracks.takeLast(50)
        
        points.forEachIndexed { index, track ->
            val x = padding + chartWidth * index / (points.size - 1).coerceAtLeast(1)
            val y = padding + chartHeight * (1 - ((track.altitude - minElev) / range)).toFloat()
            if (index == 0) {
                path.moveTo(x.toFloat(), y)
            } else {
                path.lineTo(x.toFloat(), y)
            }
        }

        drawPath(
            path = path,
            color = Color(0xFF0EA5E9),
            style = Stroke(width = 3f)
        )

        val fillPath = Path()
        points.forEachIndexed { index, track ->
            val x = padding + chartWidth * index / (points.size - 1).coerceAtLeast(1)
            val y = padding + chartHeight * (1 - ((track.altitude - minElev) / range)).toFloat()
            if (index == 0) {
                fillPath.moveTo(x.toFloat(), y)
            } else {
                fillPath.lineTo(x.toFloat(), y)
            }
        }
        if (points.isNotEmpty()) {
            val lastX = padding + chartWidth * (points.size - 1) / (points.size - 1).coerceAtLeast(1)
            fillPath.lineTo(lastX.toFloat(), padding + chartHeight)
            fillPath.lineTo(padding, padding + chartHeight)
            fillPath.close()
        }

        drawPath(
            path = fillPath,
            color = Color(0xFF0EA5E9).copy(alpha = 0.2f)
        )
    }
}

// --- 8. 小窗口卡片 ---
@Composable
fun SmallWindowCard(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .shadow(
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(
                    animationSpec = spring(
                        dampingRatio = 0.7f,
                        stiffness = Spring.StiffnessMedium
                    )
                )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0f),
                                Color.White.copy(alpha = 0.9f),
                                Color.White.copy(alpha = 0f)
                            )
                        )
                    )
                    .align(Alignment.TopCenter)
            )

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E293B)
                    )
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "全屏",
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 80.dp)
                ) {
                    content()
                }
            }
        }
    }
}

// --- 9. HomeScreen ---
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    state: HomeScreenState,
    showFullscreen: (@Composable () -> Unit) -> Unit
) {
    val viewModel: LocationViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val location by viewModel.currentLocation.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val satellites by viewModel.satellites.collectAsState()
    val locationName by viewModel.locationName.collectAsState()
    val detailedAddress by viewModel.detailedAddress.collectAsState()
    val tracks by viewModel.trackPoints.collectAsState()
    val context = LocalContext.current

    val fineLocationPermissionState = rememberPermissionState(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    LaunchedEffect(Unit) {
        if (!fineLocationPermissionState.status.isGranted) {
            fineLocationPermissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(fineLocationPermissionState.status.isGranted) {
        if (fineLocationPermissionState.status.isGranted) {
            viewModel.restartLocation()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SmallWindowCard(
            title = "GPS 定位",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    GPSFullscreenContent(
                        viewModel = viewModel,
                        uiState = uiState,
                        location = location,
                        isTracking = isTracking,
                        satellites = satellites,
                        locationName = locationName,
                        detailedAddress = detailedAddress,
                        isGpsEnabled = isGpsEnabled,
                        isNetworkEnabled = isNetworkEnabled,
                        permissionState = fineLocationPermissionState
                    )
                }
            }
        ) {
            if (location != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = if (locationName.isNotEmpty() && locationName != "地址获取失败" && locationName != "正在获取地址...") 
                            locationName 
                        else "坐标: ${String.format("%.4f", location!!.latitude)}°, ${String.format("%.4f", location!!.longitude)}°",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1E293B),
                        maxLines = 1
                    )
                    Text(
                        text = "${String.format("%.4f", location!!.latitude)}°, ${String.format("%.4f", location!!.longitude)}°",
                        fontSize = 13.sp,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        text = "海拔: ${String.format("%.1f", location!!.altitude)}m | 精度: ${String.format("%.1f", location!!.accuracy)}m",
                        fontSize = 12.sp,
                        color = Color(0xFF94A3B8)
                    )
                    Text(
                        text = "🛰️ ${satellites.size}颗卫星",
                        fontSize = 12.sp,
                        color = Color(0xFF8B5CF6)
                    )
                }
            } else {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFF0EA5E9),
                        strokeWidth = 2.dp
                    )
                    Text("搜索GPS信号...", fontSize = 13.sp, color = Color.Gray)
                }
            }
        }

        SmallWindowCard(
            title = "卫星",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    SatelliteFullscreenContent(
                        satellites = satellites,
                        uiState = uiState
                    )
                }
            }
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("🛰️ ${satellites.size}颗", fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color(0xFF1E293B))
                    val usedCount = satellites.count { it.usedInFix }
                    Text("✅ 已锁定: ${usedCount}颗", fontSize = 14.sp, color = Color(0xFF4CAF50))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    val gps = satellites.count { it.constellation == Constellation.GPS }
                    val glonass = satellites.count { it.constellation == Constellation.GLONASS }
                    val galileo = satellites.count { it.constellation == Constellation.GALILEO }
                    val beidou = satellites.count { it.constellation == Constellation.BEIDOU }
                    Text("GPS:$gps", fontSize = 12.sp, color = Color(0xFF4CAF50))
                    Text("GLO:$glonass", fontSize = 12.sp, color = Color(0xFF2196F3))
                    Text("Gal:$galileo", fontSize = 12.sp, color = Color(0xFFFF9800))
                    Text("北斗:$beidou", fontSize = 12.sp, color = Color(0xFFE91E63))
                }
            }
        }

        SmallWindowCard(
            title = "轨迹与导航",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    TrackFullscreenContent(
                        viewModel = viewModel,
                        tracks = tracks,
                        isTracking = isTracking,
                        uiState = uiState
                    )
                }
            }
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text(
                        if (tracks.isNotEmpty()) "📍 ${tracks.size}个点" else "📍 无轨迹",
                        fontSize = 13.sp,
                        color = Color(0xFF64748B)
                    )
                    Text(
                        if (isTracking) "🔴 记录中" else "⏸️ 已暂停",
                        fontSize = 13.sp,
                        color = if (isTracking) Color(0xFFEF4444) else Color(0xFF94A3B8)
                    )
                    if (tracks.isNotEmpty()) {
                        val distance = calculateDistance(tracks)
                        Text(
                            "📏 ${String.format("%.1f", distance)}m",
                            fontSize = 13.sp,
                            color = Color(0xFF0EA5E9)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(4.dp))
                
                ElevationChart(
                    tracks = tracks.takeLast(50),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// --- 10. GPS全屏内容 ---
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun GPSFullscreenContent(
    viewModel: LocationViewModel,
    uiState: LocationUiState,
    location: com.geosurvey.toolbox.domain.model.LocationData?,
    isTracking: Boolean,
    satellites: List<com.geosurvey.toolbox.domain.model.SatelliteInfo>,
    locationName: String,
    detailedAddress: com.geosurvey.toolbox.data.repository.LocationRepository.DetailedAddress?,
    isGpsEnabled: Boolean,
    isNetworkEnabled: Boolean,
    permissionState: PermissionState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!permissionState.status.isGranted) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text("⚠️ 缺少定位权限", fontSize = 20.sp, color = Color.Red)
                Button(onClick = { permissionState.launchPermissionRequest() }) {
                    Text("授予权限")
                }
            }
        } else if (!isGpsEnabled && !isNetworkEnabled) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text("⚠️ 定位服务未开启", fontSize = 20.sp, color = Color.Red)
                Text("请打开GPS或网络定位", fontSize = 16.sp, color = Color.Gray)
                Button(onClick = { viewModel.restartLocation() }) {
                    Text("重新尝试定位")
                }
            }
        } else if (location != null) {
            val gpsCount = satellites.count { it.constellation == Constellation.GPS }
            val glonassCount = satellites.count { it.constellation == Constellation.GLONASS }
            val galileoCount = satellites.count { it.constellation == Constellation.GALILEO }
            val beidouCount = satellites.count { it.constellation == Constellation.BEIDOU }
            val usedCount = satellites.count { it.usedInFix }
            val avgSnr = if (satellites.isNotEmpty()) satellites.map { it.snr }.average().toFloat() else 0f

            Text("📍 当前位置", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = if (locationName.isNotEmpty() && locationName != "地址获取失败" && locationName != "正在获取地址...") 
                            locationName 
                        else "当前坐标: ${String.format("%.4f", location.latitude)}°, ${String.format("%.4f", location.longitude)}°",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF1E293B)
                    )
                    if (detailedAddress != null && detailedAddress.fullAddress.isNotEmpty()) {
                        Text(
                            text = detailedAddress.fullAddress,
                            fontSize = 14.sp,
                            color = Color(0xFF475569)
                        )
                    }
                }
            }

            Text("📍 坐标信息", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("纬度: ${String.format("%.6f", location.latitude)}°", fontSize = 15.sp)
                    Text("经度: ${String.format("%.6f", location.longitude)}°", fontSize = 15.sp)
                    Text("海拔: ${String.format("%.1f", location.altitude)} m", fontSize = 15.sp)
                    Text("坐标基准: WGS84", fontSize = 15.sp, color = Color(0xFF64748B))
                }
            }

            Text("📊 精度与速度", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("水平精度: ${String.format("%.1f", location.accuracy)} m", fontSize = 15.sp)
                    Text("速度: ${String.format("%.1f", location.speed)} m/s", fontSize = 15.sp)
                    Text("方向: ${String.format("%.1f", location.bearing)}°", fontSize = 15.sp)
                    Text("定位源: ${location.provider}", fontSize = 15.sp)
                    Text("定位时间: ${android.text.format.DateFormat.format("yyyy-MM-dd HH:mm:ss", location.time)}", fontSize = 15.sp)
                }
            }

            Text("🛰️ 卫星信息", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8B5CF6))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("GPS: $gpsCount  GLONASS: $glonassCount", fontSize = 15.sp)
                    Text("Galileo: $galileoCount  北斗: $beidouCount", fontSize = 15.sp)
                    Text("解算卫星数: $usedCount / ${satellites.size}", fontSize = 15.sp)
                    Text("平均信噪比: ${String.format("%.1f", avgSnr)} dB", fontSize = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (isTracking) viewModel.stopTracking()
                        else viewModel.startTracking()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isTracking) Color(0xFFEF4444) else Color(0xFF0EA5E9)
                    )
                ) {
                    Text(if (isTracking) "⏹ 停止记录" else "▶ 开始记录", fontSize = 14.sp)
                }
                Button(
                    onClick = { viewModel.restartLocation() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF59E0B)
                    )
                ) {
                    Text("🔄 刷新定位", fontSize = 14.sp)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { viewModel.loadTracks() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF10B981)
                    )
                ) {
                    Text("📂 加载轨迹", fontSize = 14.sp)
                }
                Text(
                    text = "轨迹点: ${uiState.tracks.size}",
                    modifier = Modifier
                        .weight(1f)
                        .padding(8.dp)
                        .background(Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                        .wrapContentSize(Alignment.Center),
                    fontSize = 14.sp,
                    color = Color(0xFF475569)
                )
            }
        } else {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                CircularProgressIndicator(modifier = Modifier.size(48.dp), color = Color(0xFF0EA5E9))
                Spacer(modifier = Modifier.height(16.dp))
                Text("正在搜索GPS信号...", fontSize = 18.sp, color = Color.Gray)
                Text("请在室外开阔地带等待", fontSize = 14.sp, color = Color.Gray)
                Button(onClick = { viewModel.restartLocation() }) {
                    Text("重新尝试定位")
                }
            }
        }
    }
}

// --- 11. 卫星全屏内容 ---
@Composable
fun SatelliteFullscreenContent(
    satellites: List<com.geosurvey.toolbox.domain.model.SatelliteInfo>,
    uiState: LocationUiState
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFF1A2332), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            if (satellites.isNotEmpty()) {
                SatellitePolarChart(
                    satellites = satellites,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column {
                        Text("可见: ${satellites.size}颗", fontSize = 16.sp, color = Color.White)
                        Text("锁定: ${satellites.count { it.usedInFix }}颗", fontSize = 16.sp, color = Color(0xFF4CAF50))
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column {
                        Text("● GPS", fontSize = 12.sp, color = Color(0xFF4CAF50))
                        Text("● GLONASS", fontSize = 12.sp, color = Color(0xFF2196F3))
                        Text("● Galileo", fontSize = 12.sp, color = Color(0xFFFF9800))
                        Text("● 北斗", fontSize = 12.sp, color = Color(0xFFE91E63))
                    }
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📡", fontSize = 64.sp)
                    Text("等待卫星信号...", fontSize = 16.sp, color = Color.Gray)
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(8.dp)
        ) {
            if (satellites.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(satellites) { satellite ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (satellite.usedInFix) Color(0xFFE8F5E9) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${satellite.constellation.name} #${satellite.prn}",
                                fontSize = 14.sp,
                                fontWeight = if (satellite.usedInFix) FontWeight.Bold else FontWeight.Normal,
                                color = if (satellite.usedInFix) Color(0xFF1E293B) else Color(0xFF94A3B8)
                            )
                            Text(
                                "SNR:${String.format("%.1f", satellite.snr)}dB",
                                fontSize = 14.sp,
                                color = if (satellite.usedInFix) Color(0xFF4CAF50) else Color(0xFF94A3B8)
                            )
                            Text(
                                "仰角:${String.format("%.1f", satellite.elevation)}°",
                                fontSize = 14.sp,
                                color = Color(0xFF64748B)
                            )
                            Text(
                                "方位:${String.format("%.1f", satellite.azimuth)}°",
                                fontSize = 14.sp,
                                color = Color(0xFF64748B)
                            )
                            Text(
                                if (satellite.usedInFix) "✅" else "",
                                fontSize = 14.sp,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }
            } else {
                Text(
                    "等待卫星信号...",
                    fontSize = 16.sp,
                    color = Color.Gray,
                    modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center)
                )
            }
        }
    }
}

// --- 12. 轨迹全屏内容 ---
@Composable
fun TrackFullscreenContent(
    viewModel: LocationViewModel,
    tracks: List<com.geosurvey.toolbox.data.database.TrackEntity>,
    isTracking: Boolean,
    uiState: LocationUiState
) {
    val isNavigating by viewModel.isNavigating.collectAsState()
    val navigationTarget by viewModel.navigationTarget.collectAsState()
    val navigationDistance by viewModel.navigationDistance.collectAsState()
    val navigationBearing by viewModel.navigationBearing.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize().padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("🗺️ 实时轨迹", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(
                                    if (isTracking) Color(0xFFEF4444) else Color(0xFF94A3B8),
                                    RoundedCornerShape(50)
                                )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (isTracking) "记录中" else "已暂停",
                            fontSize = 12.sp,
                            color = if (isTracking) Color(0xFFEF4444) else Color(0xFF94A3B8)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (tracks.isNotEmpty()) {
                    val distance = calculateDistance(tracks)
                    val elevations = tracks.map { it.altitude }
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${tracks.size}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
                            Text("轨迹点", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${String.format("%.1f", distance)}m", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF10B981))
                            Text("总里程", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("${String.format("%.1f", elevations.maxOrNull() ?: 0.0)}m", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFF9800))
                            Text("最高海拔", fontSize = 11.sp, color = Color(0xFF64748B))
                        }
                    }

                    if (isNavigating && navigationTarget != null) {
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("🧭", fontSize = 24.sp)
                                Text("导航中", fontSize = 11.sp, color = Color(0xFF0EA5E9))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${String.format("%.0f", navigationDistance)}m", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF59E0B))
                                Text("目标距离", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("${String.format("%.0f", navigationBearing)}°", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8B5CF6))
                                Text("方位角", fontSize = 11.sp, color = Color(0xFF64748B))
                            }
                        }
                        Text(
                            "目标: ${String.format("%.4f", navigationTarget!!.latitude)}, ${String.format("%.4f", navigationTarget!!.longitude)}",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    ) {
                        val points = tracks.takeLast(50)
                        if (points.size > 1) {
                            val padding = 20f
                            val step = (size.width - padding * 2) / (points.size - 1)
                            val maxElev = points.maxOf { it.altitude }
                            val minElev = points.minOf { it.altitude }
                            val range = if (maxElev - minElev > 0) maxElev - minElev else 1.0
                            
                            val path = Path()
                            points.forEachIndexed { index, point ->
                                val x = padding + step * index
                                val y = padding + (size.height - padding * 2) * (1 - ((point.altitude - minElev) / range).toFloat())
                                if (index == 0) {
                                    path.moveTo(x.toFloat(), y)
                                } else {
                                    path.lineTo(x.toFloat(), y)
                                }
                            }
                            drawPath(
                                path = path,
                                color = Color(0xFF0EA5E9),
                                style = Stroke(width = 3f)
                            )
                            
                            val first = points.first()
                            val last = points.last()
                            val firstX = padding
                            val firstY = padding + (size.height - padding * 2) * (1 - ((first.altitude - minElev) / range).toFloat())
                            val lastX = padding + step * (points.size - 1)
                            val lastY = padding + (size.height - padding * 2) * (1 - ((last.altitude - minElev) / range).toFloat())
                            
                            drawCircle(
                                color = Color(0xFF10B981),
                                radius = 6f,
                                center = Offset(firstX.toFloat(), firstY)
                            )
                            drawCircle(
                                color = Color(0xFFEF4444),
                                radius = 6f,
                                center = Offset(lastX.toFloat(), lastY)
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("📭", fontSize = 48.sp)
                        Text("暂无轨迹数据", fontSize = 16.sp, color = Color.Gray)
                        Text("点击「开始记录」开始收集轨迹", fontSize = 13.sp, color = Color(0xFF94A3B8))
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxSize()
            ) {
                Text("🧭 轨迹导航", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                
                Spacer(modifier = Modifier.height(8.dp))
                
                if (tracks.isNotEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.7f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text("当前轨迹: ${tracks.size} 个点", fontSize = 14.sp)
                            val distance = calculateDistance(tracks)
                            Text("总里程: ${String.format("%.2f", distance)}m", fontSize = 14.sp, color = Color(0xFF0EA5E9))
                            if (tracks.isNotEmpty()) {
                                val first = tracks.first()
                                val last = tracks.last()
                                Text("起点: ${String.format("%.4f", first.latitude)}, ${String.format("%.4f", first.longitude)}", fontSize = 12.sp, color = Color(0xFF64748B))
                                Text("终点: ${String.format("%.4f", last.latitude)}, ${String.format("%.4f", last.longitude)}", fontSize = 12.sp, color = Color(0xFF64748B))
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (tracks.isNotEmpty()) {
                                    viewModel.setNavigationTarget(tracks.last())
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isNavigating) Color(0xFFF59E0B) else Color(0xFF0EA5E9)
                            )
                        ) {
                            Text(
                                if (isNavigating) "📍 导航中" else "🎯 设为目标",
                                fontSize = 13.sp
                            )
                        }
                        Button(
                            onClick = {
                                viewModel.setNavigationTarget(null)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444)
                            ),
                            enabled = isNavigating
                        ) {
                            Text("⏹ 取消", fontSize = 13.sp)
                        }
                    }
                    
                    if (isNavigating && navigationTarget != null) {
                        Text(
                            "🎯 目标已锁定，距离 ${String.format("%.0f", navigationDistance)}m",
                            fontSize = 12.sp,
                            color = Color(0xFF10B981),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        if (currentLocation != null && navigationDistance < 10) {
                            Text(
                                "✅ 已到达目标!",
                                fontSize = 14.sp,
                                color = Color(0xFF4CAF50),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Text(
                            "选择轨迹终点作为导航目标",
                            fontSize = 12.sp,
                            color = Color(0xFF94A3B8)
                        )
                    }
                } else {
                    Text("请先记录轨迹数据", fontSize = 14.sp, color = Color(0xFF94A3B8))
                    Text("点击「开始记录」收集轨迹", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
                
                Spacer(modifier = Modifier.weight(1f))
                Text("点击外部关闭", fontSize = 11.sp, color = Color(0xFF94A3B8))
            }
        }
    }
}

// --- 计算轨迹总里程 ---
private fun calculateDistance(tracks: List<com.geosurvey.toolbox.data.database.TrackEntity>): Double {
    if (tracks.size < 2) return 0.0
    var totalDistance = 0.0
    for (i in 0 until tracks.size - 1) {
        val p1 = tracks[i]
        val p2 = tracks[i + 1]
        totalDistance += haversine(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
    }
    return totalDistance
}

private fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val R = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
    return R * 2 * atan2(sqrt(a), sqrt(1 - a))
}

// --- 13. AnalysisScreen ---
@Composable
fun AnalysisScreen(
    state: AnalysisScreenState,
    showFullscreen: (@Composable () -> Unit) -> Unit
) {
    val viewModel: LocationViewModel = viewModel()
    val currentAttitude by viewModel.currentAttitude.collectAsState()
    val attitudeHistory by viewModel.attitudeHistory.collectAsState()
    val currentLocation by viewModel.currentLocation.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SmallWindowCard(
            title = "产状测量",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    AttitudeFullscreenContent(
                        viewModel = viewModel,
                        currentAttitude = currentAttitude,
                        attitudeHistory = attitudeHistory,
                        currentLocation = currentLocation
                    )
                }
            }
        ) {
            Column {
                if (currentAttitude != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Text("倾向: ${String.format("%.0f", currentAttitude!!.dipDirection)}°", fontSize = 14.sp, color = Color(0xFF0EA5E9))
                        Text("倾角: ${String.format("%.0f", currentAttitude!!.dip)}°", fontSize = 14.sp, color = Color(0xFF10B981))
                        Text("走向: ${String.format("%.0f", currentAttitude!!.strike)}°", fontSize = 14.sp, color = Color(0xFFFF9800))
                    }
                    Text("已记录: ${attitudeHistory.size} 组", fontSize = 12.sp, color = Color(0xFF64748B))
                } else {
                    Text("将手机贴合岩面测量", fontSize = 13.sp, color = Color(0xFF64748B))
                }
            }
        }

        SmallWindowCard(
            title = "赤平投影 & 玫瑰花图",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    ProjectionFullscreenContent(
                        viewModel = viewModel,
                        attitudeHistory = attitudeHistory
                    )
                }
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("🔴 赤平投影", fontSize = 13.sp, color = Color(0xFF64748B))
                Text("🌹 玫瑰花图", fontSize = 13.sp, color = Color(0xFF64748B))
            }
        }
        
        SmallWindowCard(
            title = "钻孔计算 & 绘制",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    Column {
                        Text("🕳️ 钻孔计算 & 绘制", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📐 钻孔计算", fontSize = 18.sp)
                                Text("输入钻孔参数进行计算", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📊 钻孔绘制", fontSize = 18.sp)
                                Text("显示钻孔柱状图", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Text("📐 钻孔计算", fontSize = 13.sp, color = Color(0xFF64748B))
                Text("📊 钻孔绘制", fontSize = 13.sp, color = Color(0xFF64748B))
            }
        }
    }
}

// --- 14. 产状测量全屏内容 ---
@Composable
fun AttitudeFullscreenContent(
    viewModel: LocationViewModel,
    currentAttitude: AttitudeData?,
    attitudeHistory: List<AttitudeData>,
    currentLocation: com.geosurvey.toolbox.domain.model.LocationData?
) {
    var noteText by remember { mutableStateOf("") }
    var showHistory by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("📐 产状测量", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
        Text("将手机背面贴合岩层面", fontSize = 14.sp, color = Color(0xFF64748B))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (currentAttitude != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("倾向", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(
                                "${String.format("%.0f", currentAttitude.dipDirection)}°",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0EA5E9)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("倾角", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(
                                "${String.format("%.0f", currentAttitude.dip)}°",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF10B981)
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("走向", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text(
                                "${String.format("%.0f", currentAttitude.strike)}°",
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFFF9800)
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    AttitudeCompass(dipDirection = currentAttitude.dipDirection)
                    
                    if (currentLocation != null) {
                        Text(
                            "📍 ${String.format("%.4f", currentLocation.latitude)}, ${String.format("%.4f", currentLocation.longitude)}",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            "海拔: ${String.format("%.1f", currentLocation.altitude)}m",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp), color = Color(0xFF0EA5E9))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("正在测量...", fontSize = 14.sp, color = Color.Gray)
                    Text("请将手机贴合岩面", fontSize = 12.sp, color = Color.Gray)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = noteText,
                onValueChange = { noteText = it },
                label = { Text("备注") },
                modifier = Modifier.weight(2f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF0EA5E9)
                )
            )
            Button(
                onClick = {
                    viewModel.saveAttitude(noteText)
                    noteText = ""
                },
                modifier = Modifier.weight(1f),
                enabled = currentAttitude != null,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF0EA5E9)
                )
            ) {
                Text("📝 记录", fontSize = 14.sp)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "📋 历史记录 (${attitudeHistory.size}组)",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1E293B)
            )
            TextButton(onClick = { showHistory = !showHistory }) {
                Text(if (showHistory) "收起" else "展开", fontSize = 12.sp)
            }
        }

        if (showHistory && attitudeHistory.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                shape = RoundedCornerShape(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                ) {
                    items(attitudeHistory.reversed()) { attitude ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                                .background(
                                    if (attitude.note.isNotEmpty()) Color(0xFFE8F5E9) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                ),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(
                                    "倾向:${String.format("%.0f", attitude.dipDirection)}° 倾角:${String.format("%.0f", attitude.dip)}°",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "${android.text.format.DateFormat.format("HH:mm:ss", attitude.time)}",
                                    fontSize = 11.sp,
                                    color = Color(0xFF64748B)
                                )
                                if (attitude.note.isNotEmpty()) {
                                    Text(
                                        "📝 ${attitude.note}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF475569)
                                    )
                                }
                            }
                            Text(
                                "📍 ${String.format("%.4f", attitude.latitude)}, ${String.format("%.4f", attitude.longitude)}",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Divider()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "点击外部关闭",
            fontSize = 11.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// --- 15. 产状指南针 ---
@Composable
fun AttitudeCompass(dipDirection: Float) {
    Canvas(
        modifier = Modifier
            .size(120.dp)
            .background(Color(0xFFE2E8F0), RoundedCornerShape(60))
    ) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val radius = size.width / 2f - 8f
        
        drawCircle(
            color = Color(0xFF94A3B8),
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 2f)
        )
        
        val directions = listOf("N", "E", "S", "W")
        for (i in 0..3) {
            val angle = i * PI / 2 - PI / 2
            val textRadius = radius - 16f
            val x = centerX + textRadius * cos(angle).toFloat()
            val y = centerY + textRadius * sin(angle).toFloat()
            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#64748B")
                    textSize = 16f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                drawText(directions[i], x, y + 6, paint)
            }
        }
        
        val angleRad = Math.toRadians(dipDirection.toDouble()) - PI / 2
        val pointerLength = radius * 0.7f
        val endX = centerX + pointerLength * cos(angleRad).toFloat()
        val endY = centerY + pointerLength * sin(angleRad).toFloat()
        
        drawLine(
            color = Color(0xFFEF4444),
            start = Offset(centerX, centerY),
            end = Offset(endX, endY),
            strokeWidth = 4f
        )
        
        drawCircle(
            color = Color(0xFF0EA5E9),
            radius = 6f,
            center = Offset(centerX, centerY)
        )
        
        drawContext.canvas.nativeCanvas.apply {
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.parseColor("#1E293B")
                textSize = 18f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            drawText("${String.format("%.0f", dipDirection)}°", centerX, centerY + radius + 20, paint)
        }
    }
}

// --- 16. 赤平投影和玫瑰花图全屏内容 ---
@Composable
fun ProjectionFullscreenContent(
    viewModel: LocationViewModel,
    attitudeHistory: List<AttitudeData>
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("赤平投影", "玫瑰花图")

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("📊 赤平投影 & 玫瑰花图", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            tabs.forEachIndexed { index, label ->
                Button(
                    onClick = { selectedTab = index },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedTab == index) Color(0xFF0EA5E9) else Color(0xFFE2E8F0),
                        contentColor = if (selectedTab == index) Color.White else Color(0xFF64748B)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(label, fontSize = 14.sp)
                }
            }
        }

        if (attitudeHistory.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text("📭", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("暂无产状数据", fontSize = 16.sp, color = Color.Gray)
                    Text("请先记录产状数据", fontSize = 13.sp, color = Color(0xFF94A3B8))
                }
            }
        } else {
            when (selectedTab) {
                0 -> StereographicProjectionChart(attitudeHistory = attitudeHistory)
                1 -> RoseDiagramChart(attitudeHistory = attitudeHistory)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "点击外部关闭",
            fontSize = 11.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// --- 17. 赤平投影图 ---
@Composable
fun StereographicProjectionChart(attitudeHistory: List<AttitudeData>) {
    Card(
        modifier = Modifier.fillMaxWidth().height(400.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val radius = min(size.width, size.height) / 2f - 20f

            drawCircle(
                color = Color(0xFF1E293B),
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2f)
            )

            for (r in listOf(0.33f, 0.66f)) {
                drawCircle(
                    color = Color(0xFF94A3B8),
                    radius = radius * r,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
                )
            }

            for (i in 0..3) {
                val angle = i * PI / 2
                val endX = centerX + radius * cos(angle).toFloat()
                val endY = centerY + radius * sin(angle).toFloat()
                drawLine(
                    color = Color(0xFF94A3B8),
                    start = Offset(centerX, centerY),
                    end = Offset(endX, endY),
                    strokeWidth = 1f
                )
            }

            val dirs = listOf("N", "E", "S", "W")
            for (i in 0..3) {
                val angle = i * PI / 2 - PI / 2
                val labelRadius = radius + 16f
                val x = centerX + labelRadius * cos(angle).toFloat()
                val y = centerY + labelRadius * sin(angle).toFloat()
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#1E293B")
                        textSize = 16f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawText(dirs[i], x, y + 6, paint)
                }
            }

            val colors = listOf(
                Color(0xFFEF4444),
                Color(0xFF3B82F6),
                Color(0xFF10B981),
                Color(0xFFF59E0B),
                Color(0xFF8B5CF6)
            )
            
            attitudeHistory.forEachIndexed { index, attitude ->
                val dipRad = Math.toRadians(attitude.dip.toDouble())
                val dipDirRad = Math.toRadians(attitude.dipDirection.toDouble())
                
                val r = radius * tan(PI / 4 - dipRad / 2).toFloat()
                val x = centerX + r * sin(dipDirRad).toFloat()
                val y = centerY - r * cos(dipDirRad).toFloat()
                
                drawCircle(
                    color = colors[index % colors.size],
                    radius = 6f,
                    center = Offset(x, y)
                )
                
                drawCircle(
                    color = colors[index % colors.size].copy(alpha = 0.3f),
                    radius = 12f,
                    center = Offset(x, y),
                    style = Stroke(width = 1f)
                )
            }

            if (attitudeHistory.size <= 5) {
                attitudeHistory.forEachIndexed { index, attitude ->
                    val labelX = 16f
                    val labelY = 32f + index * 20f
                    drawCircle(
                        color = colors[index % colors.size],
                        radius = 4f,
                        center = Offset(labelX, labelY)
                    )
                    drawContext.canvas.nativeCanvas.apply {
                        val paint = android.graphics.Paint().apply {
                            color = android.graphics.Color.parseColor("#1E293B")
                            textSize = 12f
                        }
                        drawText(
                            "#${index + 1}: ${String.format("%.0f", attitude.dipDirection)}°/${String.format("%.0f", attitude.dip)}°",
                            labelX + 10,
                            labelY + 4,
                            paint
                        )
                    }
                }
            }
        }
    }
}

// --- 18. 玫瑰花图 ---
@Composable
fun RoseDiagramChart(attitudeHistory: List<AttitudeData>) {
    Card(
        modifier = Modifier.fillMaxWidth().height(400.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFAFAFA)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize().padding(16.dp)
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val maxRadius = min(size.width, size.height) / 2f - 20f

            val bins = 36
            val binSize = 10.0
            val counts = IntArray(bins)
            
            attitudeHistory.forEach { attitude ->
                var strike = attitude.strike
                if (strike > 180) strike -= 180
                val binIndex = (strike / binSize).toInt().coerceIn(0, bins - 1)
                counts[binIndex]++
            }

            val maxCount = counts.maxOrNull()?.toFloat() ?: 1f

            drawCircle(
                color = Color(0xFF1E293B),
                radius = maxRadius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1f)
            )

            for (i in 1..3) {
                val r = maxRadius * i / 4
                drawCircle(
                    color = Color(0xFFE2E8F0),
                    radius = r,
                    center = Offset(centerX, centerY),
                    style = Stroke(width = 1f)
                )
            }

            val dirs = listOf("N", "E", "S", "W")
            for (i in 0..3) {
                val angle = i * PI / 2 - PI / 2
                val labelRadius = maxRadius + 16f
                val x = centerX + labelRadius * cos(angle).toFloat()
                val y = centerY + labelRadius * sin(angle).toFloat()
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#1E293B")
                        textSize = 14f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawText(dirs[i], x, y + 6, paint)
                }
            }

            val roseColor = Color(0xFF0EA5E9)
            
            for (i in 0 until bins) {
                val angle = i.toDouble() * 2 * PI / bins
                val count = counts[i]
                val r = if (maxCount > 0) (count / maxCount) * maxRadius else 0f
                
                val nextAngle = (i + 1).toDouble() * 2 * PI / bins
                
                val path = Path()
                path.moveTo(centerX, centerY)
                
                val p1x = centerX + r * sin(angle).toFloat()
                val p1y = centerY - r * cos(angle).toFloat()
                val p2x = centerX + r * sin(nextAngle).toFloat()
                val p2y = centerY - r * cos(nextAngle).toFloat()
                
                path.lineTo(p1x, p1y)
                
                val steps = 10
                for (j in 1..steps) {
                    val t = j.toDouble() / steps
                    val a = angle + t * (nextAngle - angle)
                    val px = centerX + r * sin(a).toFloat()
                    val py = centerY - r * cos(a).toFloat()
                    path.lineTo(px, py)
                }
                
                path.lineTo(p2x, p2y)
                path.close()
                
                val alpha = 0.3f + 0.7f * (count / maxCount)
                drawPath(
                    path = path,
                    color = roseColor.copy(alpha = alpha)
                )
                
                drawPath(
                    path = path,
                    color = roseColor.copy(alpha = 0.8f),
                    style = Stroke(width = 1f)
                )
            }

            drawCircle(
                color = Color(0xFF1E293B),
                radius = 4f,
                center = Offset(centerX, centerY)
            )

            drawContext.canvas.nativeCanvas.apply {
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.parseColor("#64748B")
                    textSize = 12f
                }
                drawText(
                    "总数据: ${attitudeHistory.size}组",
                    16f,
                    32f,
                    paint
                )
                drawText(
                    "最大频率: ${maxCount.toInt()}次",
                    16f,
                    52f,
                    paint
                )
            }
        }
    }
}

// --- 19. RecordScreen ---
@Composable
fun RecordScreen(
    state: RecordScreenState,
    showFullscreen: (@Composable () -> Unit) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SmallWindowCard(
            title = "普通样本",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    Column {
                        Text("📋 普通样本", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
                        Text("共 0 个样本", fontSize = 16.sp, color = Color(0xFF64748B))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无样本数据", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        ) {
            Text("📋 共 0 个样本", fontSize = 14.sp, color = Color(0xFF64748B))
        }
        
        SmallWindowCard(
            title = "钻孔样本",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    Column {
                        Text("🕳️ 钻孔样本", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
                        Text("共 0 个钻孔", fontSize = 16.sp, color = Color(0xFF64748B))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无钻孔数据", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        ) {
            Text("🕳️ 共 0 个钻孔", fontSize = 14.sp, color = Color(0xFF64748B))
        }
    }
}

// --- 20. CameraScreen ---
@Composable
fun CameraScreen(
    state: CameraScreenState,
    showFullscreen: (@Composable () -> Unit) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        SmallWindowCard(
            title = "水印相机",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    Column {
                        Text("📷 水印相机", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
                        Text("相机预览", fontSize = 16.sp, color = Color(0xFF64748B))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("水印设置: 坐标 | 时间 | 地点", fontSize = 14.sp, color = Color.Gray)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {}) { Text("📸 拍照", fontSize = 16.sp) }
                    }
                }
            }
        ) {
            Text("📷 点击打开相机", fontSize = 14.sp, color = Color(0xFF64748B))
        }
        
        SmallWindowCard(
            title = "相册",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    Column {
                        Text("🖼️ 相册", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
                        Text("共 0 张照片", fontSize = 16.sp, color = Color(0xFF64748B))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("暂无照片", fontSize = 14.sp, color = Color.Gray)
                    }
                }
            }
        ) {
            Text("🖼️ 共 0 张照片", fontSize = 14.sp, color = Color(0xFF64748B))
        }
    }
}

// --- 21. 预览 ---
@Preview(showBackground = true)
@Composable
fun PreviewMainScreen() {
    MaterialTheme {
        MainScreen(
            homeState = HomeScreenState(),
            analysisState = AnalysisScreenState(),
            recordState = RecordScreenState(),
            cameraState = CameraScreenState()
        )
    }
}
