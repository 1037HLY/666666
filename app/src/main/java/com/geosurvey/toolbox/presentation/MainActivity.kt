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
import com.geosurvey.toolbox.domain.model.Constellation
import com.geosurvey.toolbox.domain.model.LocationQuality
import com.geosurvey.toolbox.presentation.viewmodel.LocationViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
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
            ) {
                IconButton(
                    onClick = { fullscreenContent = null },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.1f), RoundedCornerShape(50))
                ) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Color(0xFF1E293B))
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 64.dp, bottom = 16.dp, start = 16.dp, end = 16.dp)
                ) {
                    content()
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

// --- 6. 卫星极坐标图组件 ---
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
@OptIn(ExperimentalAnimationApi::class)
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
        // ===== 窗口1：GPS定位 =====
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
                        fineLocationPermissionState = fineLocationPermissionState
                    )
                }
            }
        ) {
            if (location != null) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    Text(
                        text = locationName,
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

        // ===== 窗口2：卫星 =====
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

        // ===== 窗口3：轨迹与导航 =====
        SmallWindowCard(
            title = "轨迹与导航",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    TrackFullscreenContent(
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
                Text("📈 实时海拔", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Color(0xFF64748B))
                Text(
                    if (tracks.isNotEmpty()) "当前: ${String.format("%.1f", tracks.last().altitude)}m" else "等待数据...",
                    fontSize = 12.sp,
                    color = if (tracks.isNotEmpty()) Color(0xFF0EA5E9) else Color(0xFF94A3B8)
                )
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
@Composable
fun GPSFullscreenContent(
    viewModel: LocationViewModel,
    uiState: LocationViewModel.LocationUiState,
    location: com.geosurvey.toolbox.domain.model.LocationData?,
    isTracking: Boolean,
    satellites: List<com.geosurvey.toolbox.domain.model.SatelliteInfo>,
    locationName: String,
    detailedAddress: com.geosurvey.toolbox.data.repository.LocationRepository.DetailedAddress?,
    isGpsEnabled: Boolean,
    isNetworkEnabled: Boolean,
    fineLocationPermissionState: com.google.accompanist.permissions.ExperimentalPermissionsApi.PermissionState
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!fineLocationPermissionState.status.isGranted) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize()
            ) {
                Text("⚠️ 缺少定位权限", fontSize = 20.sp, color = Color.Red)
                Button(onClick = { fineLocationPermissionState.launchPermissionRequest() }) {
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
            val addr = detailedAddress
            Text(
                text = "📍 当前位置",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF0EA5E9)
            )
            Text(
                text = locationName,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
            )
            
            if (addr != null && addr.fullAddress.isNotEmpty() && addr.fullAddress != locationName) {
                Text(
                    text = addr.fullAddress,
                    fontSize = 14.sp,
                    color = Color(0xFF475569)
                )
            }
            
            Divider(modifier = Modifier.padding(vertical = 8.dp))
            
            Text("📌 坐标信息", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0EA5E9))
            Text("纬度: ${String.format("%.6f", location.latitude)}°", fontSize = 16.sp)
            Text("经度: ${String.format("%.6f", location.longitude)}°", fontSize = 16.sp)
            Text("海拔: ${String.format("%.1f", location.altitude)} m", fontSize = 16.sp)
            
            Text("📊 精度与速度", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF10B981))
            Text("水平精度: ${String.format("%.1f", location.accuracy)} m", fontSize = 16.sp)
            Text("速度: ${String.format("%.1f", location.speed)} m/s", fontSize = 16.sp)
            Text("方向: ${String.format("%.1f", location.bearing)}°", fontSize = 16.sp)
            Text("定位源: ${location.provider}", fontSize = 16.sp)
            
            Text("🛰️ 卫星信息", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF8B5CF6))
            Text("可见卫星: ${satellites.size} 颗", fontSize = 16.sp)
            Text("HDOP: ${String.format("%.1f", uiState.hdop)}", fontSize = 16.sp)
            Text(
                text = "质量: ${uiState.qualityText}",
                fontSize = 16.sp,
                color = when (uiState.quality) {
                    LocationQuality.EXCELLENT -> Color(0xFF4CAF50)
                    LocationQuality.GOOD -> Color(0xFF8BC34A)
                    LocationQuality.FAIR -> Color(0xFFFFC107)
                    LocationQuality.POOR -> Color(0xFFFF9800)
                    LocationQuality.BAD -> Color(0xFFF44336)
                    else -> Color.Gray
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
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
    uiState: LocationViewModel.LocationUiState
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 上半屏：卫星极坐标图
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
        
        // 下半屏：卫星详细信息
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
    tracks: List<com.geosurvey.toolbox.data.database.TrackEntity>,
    isTracking: Boolean,
    uiState: LocationViewModel.LocationUiState
) {
    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // 上半屏：实时行进轨迹
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🗺️ 实时行进轨迹", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                if (tracks.isNotEmpty()) {
                    Text("轨迹点数: ${tracks.size}", fontSize = 15.sp, color = Color(0xFF64748B))
                    Text("起点: ${String.format("%.4f", tracks.first().latitude)}, ${String.format("%.4f", tracks.first().longitude)}", fontSize = 14.sp)
                    Text("终点: ${String.format("%.4f", tracks.last().latitude)}, ${String.format("%.4f", tracks.last().longitude)}", fontSize = 14.sp)
                    Text("记录中: ${if (isTracking) "✅" else "⏸️"}", fontSize = 14.sp)
                    val elevs = tracks.map { it.altitude }
                    Text("最高: ${String.format("%.1f", elevs.maxOrNull() ?: 0.0)}m, 最低: ${String.format("%.1f", elevs.minOrNull() ?: 0.0)}m", fontSize = 14.sp)
                    Text("总里程: ${String.format("%.2f", calculateDistance(tracks))}m", fontSize = 14.sp, color = Color(0xFF0EA5E9))
                } else {
                    Text("暂无轨迹数据", fontSize = 16.sp, color = Color.Gray)
                    Text("点击「开始记录」开始收集", fontSize = 14.sp, color = Color.Gray)
                }
                if (tracks.size > 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(100.dp)
                    ) {
                        val points = tracks.takeLast(30)
                        val padding = 20f
                        val step = (size.width - padding * 2) / (points.size - 1).coerceAtLeast(1)
                        points.forEachIndexed { index, point ->
                            val x = padding + step * index
                            val y = size.height / 2 + (point.altitude / 10).toFloat()
                            drawCircle(
                                color = Color(0xFF0EA5E9),
                                radius = 5f,
                                center = Offset(x, y.coerceIn(padding, size.height - padding))
                            )
                        }
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        // 下半屏：轨迹导航
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(0.8f)
                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🧭 轨迹导航", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                Text("选择历史轨迹进行导航", fontSize = 15.sp, color = Color.Gray)
                if (tracks.isNotEmpty()) {
                    Text("当前轨迹: ${tracks.size}个点", fontSize = 14.sp, color = Color(0xFF64748B))
                }
                Text("功能开发中...", fontSize = 14.sp, color = Color(0xFF94A3B8))
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { /* 选择轨迹 */ },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0EA5E9))
                    ) {
                        Text("选择轨迹", fontSize = 15.sp)
                    }
                    Button(
                        onClick = { /* 开始导航 */ },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981))
                    ) {
                        Text("开始导航", fontSize = 15.sp)
                    }
                }
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
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("📐 产状测量", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
                        Text("倾向: 0°", fontSize = 18.sp)
                        Text("倾角: 0°", fontSize = 18.sp)
                        Text("走向: 0°", fontSize = 18.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(onClick = {}) { Text("📝 记录产状", fontSize = 16.sp) }
                    }
                }
            }
        ) {
            Text("倾向: 0° 倾角: 0°", fontSize = 14.sp, color = Color(0xFF64748B))
        }
        
        SmallWindowCard(
            title = "赤平投影 & 玫瑰花图",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    Column {
                        Text("📊 赤平投影 & 玫瑰花图", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("🔴 赤平投影", fontSize = 18.sp) }
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) { Text("🌹 玫瑰花图", fontSize = 18.sp) }
                    }
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

// --- 14. RecordScreen ---
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

// --- 15. CameraScreen ---
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

// --- 16. 预览 ---
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
