package com.geosurvey.toolbox.presentation

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.geosurvey.toolbox.R
import com.geosurvey.toolbox.data.repository.AttitudeData
import com.geosurvey.toolbox.data.repository.DrillSampleData
import com.geosurvey.toolbox.data.repository.SampleData
import com.geosurvey.toolbox.domain.model.Constellation
import com.geosurvey.toolbox.domain.model.LocationQuality
import com.geosurvey.toolbox.presentation.viewmodel.LocationUiState
import com.geosurvey.toolbox.presentation.viewmodel.LocationViewModel
import com.geosurvey.toolbox.utils.CameraHelper
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionState
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
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
    // ===== 水印开关（新增）=====
    var showLocation by mutableStateOf(true)
    var showAttitude by mutableStateOf(true)
    var showTime by mutableStateOf(true)
    var showNote by mutableStateOf(false)
    var watermarkNote by mutableStateOf("")
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
                    DrillingFullscreenContent(
                        viewModel = viewModel
                    )
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
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var noteText by remember { mutableStateOf("") }
    var exportFileName by remember { mutableStateOf("产状记录") }
    var calibrationOffset by remember { mutableStateOf(viewModel.getCalibrationOffset()) }
    var showCalibration by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("📐 产状测量", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
        Text("将手机背面贴合岩层面", fontSize = 14.sp, color = Color(0xFF64748B))
        Text("⚡ 等待数据稳定后点击记录", fontSize = 12.sp, color = Color(0xFF94A3B8))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { showCalibration = !showCalibration },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFF59E0B)
                )
            ) {
                Text(if (showCalibration) "隐藏校正" else "🔧 手动校正", fontSize = 13.sp)
            }
            Text(
                "偏移: ${String.format("%.0f", calibrationOffset)}°",
                fontSize = 13.sp,
                color = Color(0xFF64748B),
                modifier = Modifier
                    .weight(1f)
                    .padding(8.dp)
                    .background(Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                    .wrapContentSize(Alignment.Center)
            )
        }

        if (showCalibration) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFEF3C7)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("🔧 手动校正", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("将手机贴合已知方向的岩面，调整偏移量", fontSize = 12.sp, color = Color(0xFF64748B))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { 
                                calibrationOffset = (calibrationOffset - 1 + 360) % 360
                                viewModel.setCalibrationOffset(calibrationOffset)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444)
                            )
                        ) {
                            Text("-1°")
                        }
                        Button(
                            onClick = { 
                                calibrationOffset = (calibrationOffset - 10 + 360) % 360
                                viewModel.setCalibrationOffset(calibrationOffset)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFEF4444)
                            )
                        ) {
                            Text("-10°")
                        }
                        Text(
                            "${String.format("%.0f", calibrationOffset)}°",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).wrapContentSize(Alignment.Center)
                        )
                        Button(
                            onClick = { 
                                calibrationOffset = (calibrationOffset + 10) % 360
                                viewModel.setCalibrationOffset(calibrationOffset)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981)
                            )
                        ) {
                            Text("+10°")
                        }
                        Button(
                            onClick = { 
                                calibrationOffset = (calibrationOffset + 1) % 360
                                viewModel.setCalibrationOffset(calibrationOffset)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF10B981)
                            )
                        ) {
                            Text("+1°")
                        }
                    }
                    Button(
                        onClick = {
                            viewModel.setCalibrationOffset(calibrationOffset)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF0EA5E9)
                        )
                    ) {
                        Text("应用校正", fontSize = 13.sp)
                    }
                }
            }
        }

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
                    
                    AttitudeCompass(
                        dipDirection = currentAttitude.dipDirection,
                        dip = currentAttitude.dip
                    )
                    
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
                    Text("请将手机背面贴合岩面", fontSize = 12.sp, color = Color.Gray)
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

        Text(
            "📋 历史记录 (${attitudeHistory.size}组)",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1E293B),
            modifier = Modifier.padding(top = 8.dp)
        )

        if (attitudeHistory.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                shape = RoundedCornerShape(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                ) {
                    items(attitudeHistory.reversed()) { attitude ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (attitude.note.isNotEmpty()) Color(0xFFE8F5E9) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "倾向:${String.format("%.0f", attitude.dipDirection)}° 倾角:${String.format("%.0f", attitude.dip)}° 走向:${String.format("%.0f", attitude.strike)}°",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
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
                            }
                            Text(
                                "📍 ${String.format("%.4f", attitude.latitude)}",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Divider()
                    }
                }
            }
        } else {
            Text("暂无记录", fontSize = 13.sp, color = Color(0xFF94A3B8))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = exportFileName,
                onValueChange = { exportFileName = it },
                label = { Text("文件名") },
                modifier = Modifier.weight(2f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF0EA5E9)
                )
            )
            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val csvData = viewModel.exportAttitudeHistory()
                            if (csvData.isNotEmpty()) {
                                val fileName = if (exportFileName.isNotBlank()) exportFileName else "产状记录"
                                shareCSV(context, csvData, fileName)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = attitudeHistory.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF10B981)
                )
            ) {
                Text("📤 导出CSV", fontSize = 14.sp)
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

// --- 导出CSV并分享 ---
private fun shareCSV(context: Context, csvData: String, fileName: String) {
    try {
        val file = File(context.cacheDir, "$fileName.csv")
        FileOutputStream(file).use { outputStream ->
            outputStream.write(csvData.toByteArray())
        }
        
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        
        context.startActivity(Intent.createChooser(shareIntent, "分享产状数据"))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

// --- 15. 简化版指南针 ---
@Composable
fun AttitudeCompass(dipDirection: Float, dip: Float) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFF1A2332),
                        Color(0xFF0D1117)
                    ),
                    radius = 1f
                ),
                shape = RoundedCornerShape(60)
            )
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(60),
                ambientColor = Color(0xFF0EA5E9).copy(alpha = 0.2f)
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .size(110.dp)
        ) {
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val radius = size.width / 2f - 4f
            
            drawCircle(
                color = Color(0xFF0EA5E9).copy(alpha = 0.1f),
                radius = radius + 4f,
                center = Offset(centerX, centerY)
            )
            
            drawCircle(
                color = Color(0xFF3B82F6).copy(alpha = 0.3f),
                radius = radius,
                center = Offset(centerX, centerY),
                style = Stroke(width = 2f)
            )
            
            drawCircle(
                color = Color(0xFF64748B).copy(alpha = 0.2f),
                radius = radius * 0.7f,
                center = Offset(centerX, centerY),
                style = Stroke(width = 1f)
            )
            
            for (i in 0..3) {
                val angle = i * PI / 2
                val endX = centerX + radius * 0.7f * cos(angle).toFloat()
                val endY = centerY + radius * 0.7f * sin(angle).toFloat()
                drawLine(
                    color = Color(0xFF64748B).copy(alpha = 0.15f),
                    start = Offset(centerX, centerY),
                    end = Offset(endX, endY),
                    strokeWidth = 1f
                )
            }
            
            val directions = listOf("N", "E", "S", "W")
            for (i in 0..3) {
                val angle = i * PI / 2 - PI / 2
                val textRadius = radius - 8f
                val x = centerX + textRadius * cos(angle).toFloat()
                val y = centerY + textRadius * sin(angle).toFloat()
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor(
                            when (i) {
                                0 -> "#4CAF50"
                                1 -> "#64748B"
                                2 -> "#EF4444"
                                else -> "#64748B"
                            }
                        )
                        textSize = if (i == 0) 14f else 11f
                        textAlign = android.graphics.Paint.Align.CENTER
                        typeface = if (i == 0) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
                    }
                    drawText(directions[i], x, y + 4, paint)
                }
            }
            
            for (i in 0..11) {
                val angle = i * PI / 6 - PI / 2
                val innerRadius = if (i % 3 == 0) radius - 10f else radius - 5f
                val outerRadius = radius - 2f
                val startX = centerX + innerRadius * cos(angle).toFloat()
                val startY = centerY + innerRadius * sin(angle).toFloat()
                val endX = centerX + outerRadius * cos(angle).toFloat()
                val endY = centerY + outerRadius * sin(angle).toFloat()
                drawLine(
                    color = if (i % 3 == 0) Color.White else Color(0xFF64748B).copy(alpha = 0.4f),
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = if (i % 3 == 0) 2f else 1f
                )
            }
            
            val angleRad = Math.toRadians(dipDirection.toDouble()) - PI / 2
            val pointerLength = radius * 0.6f
            val endX = centerX + pointerLength * cos(angleRad).toFloat()
            val endY = centerY + pointerLength * sin(angleRad).toFloat()
            
            drawLine(
                color = Color(0xFFEF4444).copy(alpha = 0.2f),
                start = Offset(centerX, centerY),
                end = Offset(endX, endY),
                strokeWidth = 14f
            )
            
            drawLine(
                color = Color(0xFFEF4444),
                start = Offset(centerX, centerY),
                end = Offset(endX, endY),
                strokeWidth = 3f
            )
            
            val headAngle = angleRad
            val headLength = 8f
            val headX = endX - headLength * cos(headAngle).toFloat()
            val headY = endY - headLength * sin(headAngle).toFloat()
            
            val path = Path()
            path.moveTo(endX, endY)
            path.lineTo(headX - 4 * sin(headAngle).toFloat(), headY + 4 * cos(headAngle).toFloat())
            path.lineTo(headX + 4 * sin(headAngle).toFloat(), headY - 4 * cos(headAngle).toFloat())
            path.close()
            
            drawPath(
                path = path,
                color = Color(0xFFEF4444)
            )
            
            drawCircle(
                color = Color(0xFF0EA5E9),
                radius = 6f,
                center = Offset(centerX, centerY)
            )
            drawCircle(
                color = Color(0xFFFFFFFF),
                radius = 2f,
                center = Offset(centerX, centerY)
            )
        }
        
        Text(
            "${String.format("%.0f", dipDirection)}°",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFFFFFF),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// --- 16. 钻孔计算与绘制 ---
@Composable
fun DrillingFullscreenContent(
    viewModel: LocationViewModel
) {
    var holeX by remember { mutableStateOf("") }
    var holeY by remember { mutableStateOf("") }
    var holeZ by remember { mutableStateOf("") }
    var azimuth by remember { mutableStateOf("") }
    var dipAngle by remember { mutableStateOf("") }
    var holeDepth by remember { mutableStateOf("") }
    
    var resultX by remember { mutableStateOf("") }
    var resultY by remember { mutableStateOf("") }
    var resultZ by remember { mutableStateOf("") }
    var horizontalDistance by remember { mutableStateOf("") }
    var verticalDistance by remember { mutableStateOf("") }
    var totalDistance by remember { mutableStateOf("") }
    var showResult by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🕳️ 钻孔计算 & 绘制", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
        Text("输入钻孔参数进行计算", fontSize = 14.sp, color = Color(0xFF64748B))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("📝 输入参数", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = holeX,
                        onValueChange = { holeX = it },
                        label = { Text("孔口X (m)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                    OutlinedTextField(
                        value = holeY,
                        onValueChange = { holeY = it },
                        label = { Text("孔口Y (m)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = holeZ,
                        onValueChange = { holeZ = it },
                        label = { Text("孔口Z (m)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                    OutlinedTextField(
                        value = azimuth,
                        onValueChange = { azimuth = it },
                        label = { Text("方位角 (°)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = dipAngle,
                        onValueChange = { dipAngle = it },
                        label = { Text("倾角 (°)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                    OutlinedTextField(
                        value = holeDepth,
                        onValueChange = { holeDepth = it },
                        label = { Text("孔深 (m)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                }
            }
        }

        Button(
            onClick = {
                try {
                    val x = holeX.toDoubleOrNull() ?: 0.0
                    val y = holeY.toDoubleOrNull() ?: 0.0
                    val z = holeZ.toDoubleOrNull() ?: 0.0
                    val az = Math.toRadians(azimuth.toDoubleOrNull() ?: 0.0)
                    val dip = Math.toRadians(dipAngle.toDoubleOrNull() ?: 0.0)
                    val depth = holeDepth.toDoubleOrNull() ?: 0.0
                    
                    val horizontal = depth * cos(dip)
                    val vertical = depth * sin(dip)
                    
                    val endX = x + horizontal * sin(az)
                    val endY = y + horizontal * cos(az)
                    val endZ = z - vertical
                    
                    resultX = String.format("%.2f", endX)
                    resultY = String.format("%.2f", endY)
                    resultZ = String.format("%.2f", endZ)
                    horizontalDistance = String.format("%.2f", horizontal)
                    verticalDistance = String.format("%.2f", vertical)
                    totalDistance = String.format("%.2f", depth)
                    showResult = true
                } catch (e: Exception) {
                    showResult = false
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0EA5E9)
            )
        ) {
            Text("📐 计算", fontSize = 16.sp)
        }

        if (showResult) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("📊 计算结果", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1E293B))
                    Text("孔底X: $resultX m", fontSize = 14.sp)
                    Text("孔底Y: $resultY m", fontSize = 14.sp)
                    Text("孔底Z: $resultZ m", fontSize = 14.sp)
                    Text("水平位移: $horizontalDistance m", fontSize = 14.sp)
                    Text("垂直位移: $verticalDistance m", fontSize = 14.sp)
                    Text("总孔深: $totalDistance m", fontSize = 14.sp)
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Canvas(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(Color(0xFFF8FAFC), RoundedCornerShape(8.dp))
                    ) {
                        val padding = 20f
                        val chartWidth = size.width - padding * 2
                        val chartHeight = size.height - padding * 2
                        
                        drawLine(
                            color = Color(0xFF1E293B),
                            start = Offset(padding, padding + chartHeight),
                            end = Offset(padding + chartWidth, padding + chartHeight),
                            strokeWidth = 2f
                        )
                        drawLine(
                            color = Color(0xFF1E293B),
                            start = Offset(padding, padding + chartHeight),
                            end = Offset(padding, padding),
                            strokeWidth = 2f
                        )
                        
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#64748B")
                                textSize = 12f
                            }
                            drawText("水平", padding + chartWidth - 20, padding + chartHeight + 20, paint)
                            drawText("垂直", 4f, padding + 20, paint)
                        }
                        
                        val horiz = horizontalDistance.toDoubleOrNull() ?: 1.0
                        val vert = verticalDistance.toDoubleOrNull() ?: 1.0
                        val maxVal = max(horiz, vert)
                        val scale = if (maxVal > 0) min(chartWidth, chartHeight) / maxVal * 0.8 else 1.0
                        
                        val startX = padding + 20
                        val startY = padding + chartHeight - 20
                        val endX = startX + (horiz * scale).toFloat()
                        val endY = startY - (vert * scale).toFloat()
                        
                        drawLine(
                            color = Color(0xFF0EA5E9),
                            start = Offset(startX, startY),
                            end = Offset(endX.toFloat(), endY.toFloat()),
                            strokeWidth = 4f
                        )
                        
                        drawCircle(
                            color = Color(0xFF10B981),
                            radius = 6f,
                            center = Offset(startX, startY)
                        )
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#10B981")
                                textSize = 11f
                            }
                            drawText("起点", startX - 12, startY + 20, paint)
                        }
                        
                        drawCircle(
                            color = Color(0xFFEF4444),
                            radius = 6f,
                            center = Offset(endX.toFloat(), endY.toFloat())
                        )
                        drawContext.canvas.nativeCanvas.apply {
                            val paint = android.graphics.Paint().apply {
                                color = android.graphics.Color.parseColor("#EF4444")
                                textSize = 11f
                            }
                            drawText("终点", endX - 10, endY - 12, paint)
                        }
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

// --- 17. 赤平投影和玫瑰花图全屏内容 ---
@Composable
fun ProjectionFullscreenContent(
    viewModel: LocationViewModel,
    attitudeHistory: List<AttitudeData>
) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("赤平投影", "玫瑰花图")
    var selectedIndices by remember { mutableStateOf<Set<Int>>(setOf()) }

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
            Text(
                "选择数据 (${selectedIndices.size}/${attitudeHistory.size})",
                fontSize = 13.sp,
                color = Color(0xFF64748B)
            )
            
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 120.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                shape = RoundedCornerShape(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                ) {
                    items(attitudeHistory.indices.toList()) { index ->
                        val attitude = attitudeHistory[index]
                        val isSelected = selectedIndices.contains(index)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isSelected) Color(0xFFE8F5E9) else Color.Transparent,
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(6.dp)
                                .clickable {
                                    selectedIndices = if (isSelected) {
                                        selectedIndices - index
                                    } else {
                                        selectedIndices + index
                                    }
                                },
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = {
                                        selectedIndices = if (isSelected) {
                                            selectedIndices - index
                                        } else {
                                            selectedIndices + index
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(
                                        checkedColor = Color(0xFF0EA5E9)
                                    )
                                )
                                Column {
                                    Text(
                                        "#${index + 1}: ${String.format("%.0f", attitude.dipDirection)}°/${String.format("%.0f", attitude.dip)}° 走向:${String.format("%.0f", attitude.strike)}°",
                                        fontSize = 12.sp
                                    )
                                    Text(
                                        "${android.text.format.DateFormat.format("HH:mm", attitude.time)}",
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                            Text(
                                "📍 ${String.format("%.4f", attitude.latitude)}",
                                fontSize = 10.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Divider()
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        selectedIndices = attitudeHistory.indices.toSet()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF94A3B8)
                    )
                ) {
                    Text("全选", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        selectedIndices = emptySet()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF94A3B8)
                    )
                ) {
                    Text("取消全选", fontSize = 12.sp)
                }
                Button(
                    onClick = {
                        // 更新图表
                    },
                    modifier = Modifier.weight(2f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (selectedIndices.isNotEmpty()) Color(0xFF0EA5E9) else Color(0xFF94A3B8)
                    ),
                    enabled = selectedIndices.isNotEmpty()
                ) {
                    Text("🔄 更新图表", fontSize = 14.sp)
                }
            }

            val selectedData = selectedIndices.map { attitudeHistory[it] }
            when (selectedTab) {
                0 -> StereographicProjectionChart(attitudeHistory = selectedData)
                1 -> RoseDiagramChart(attitudeHistory = selectedData)
            }
            
            Text(
                "当前图表使用 ${selectedData.size} 组数据",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
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

// --- 18. 赤平投影图 ---
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
            
            if (attitudeHistory.isNotEmpty()) {
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
            } else {
                drawContext.canvas.nativeCanvas.apply {
                    val paint = android.graphics.Paint().apply {
                        color = android.graphics.Color.parseColor("#94A3B8")
                        textSize = 20f
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    drawText("无数据", centerX, centerY + 6, paint)
                }
            }
        }
    }
}

// --- 19. 玫瑰花图 ---
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
            
            if (attitudeHistory.isNotEmpty()) {
                attitudeHistory.forEach { attitude ->
                    var strike = attitude.strike
                    if (strike > 180) strike -= 180
                    val binIndex = (strike / binSize).toInt().coerceIn(0, bins - 1)
                    counts[binIndex]++
                }
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
                    color = roseColor.copy(alpha = if (count > 0) alpha else 0.1f)
                )
                
                if (count > 0) {
                    drawPath(
                        path = path,
                        color = roseColor.copy(alpha = 0.8f),
                        style = Stroke(width = 1f)
                    )
                }
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
                if (attitudeHistory.isEmpty()) {
                    paint.color = android.graphics.Color.parseColor("#94A3B8")
                    paint.textSize = 20f
                    drawText("无数据", centerX, centerY + 6, paint)
                }
            }
        }
    }
}

// --- 20. RecordScreen ---
@Composable
fun RecordScreen(
    state: RecordScreenState,
    showFullscreen: (@Composable () -> Unit) -> Unit
) {
    val viewModel: LocationViewModel = viewModel()

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
                    SampleFullscreenContent(
                        viewModel = viewModel,
                        sampleType = "普通"
                    )
                }
            }
        ) {
            Column {
                Text("📋 共 ${viewModel.samples.value.size} 个样本", fontSize = 14.sp, color = Color(0xFF64748B))
                if (viewModel.samples.value.isNotEmpty()) {
                    val last = viewModel.samples.value.last()
                    Text("最新: ${last.sampleId}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        }
        
        SmallWindowCard(
            title = "钻孔样本",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    DrillSampleFullscreenContent(
                        viewModel = viewModel
                    )
                }
            }
        ) {
            Column {
                Text("🕳️ 共 ${viewModel.drillSamples.value.size} 个钻孔样本", fontSize = 14.sp, color = Color(0xFF64748B))
                if (viewModel.drillSamples.value.isNotEmpty()) {
                    val last = viewModel.drillSamples.value.last()
                    Text("最新: ${last.sampleId}", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

// --- 21. 普通样本全屏内容 ---
@Composable
fun SampleFullscreenContent(
    viewModel: LocationViewModel,
    sampleType: String
) {
    var sampleId by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var exportFileName by remember { mutableStateOf("普通样本记录") }
    val currentLocation by viewModel.currentLocation.collectAsState()
    val samples by viewModel.samples.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("📋 普通样本", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
        Text("记录野外样本信息", fontSize = 14.sp, color = Color(0xFF64748B))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("📍 当前位置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (currentLocation != null) {
                    Text("纬度: ${String.format("%.6f", currentLocation!!.latitude)}°", fontSize = 13.sp)
                    Text("经度: ${String.format("%.6f", currentLocation!!.longitude)}°", fontSize = 13.sp)
                    Text("海拔: ${String.format("%.1f", currentLocation!!.altitude)}m", fontSize = 13.sp)
                } else {
                    Text("等待GPS定位...", fontSize = 13.sp, color = Color.Gray)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = sampleId,
                    onValueChange = { sampleId = it },
                    label = { Text("样本编号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
                OutlinedTextField(
                    value = weight,
                    onValueChange = { weight = it },
                    label = { Text("重量 (kg)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("岩性描述") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
            }
        }

        Button(
            onClick = {
                if (sampleId.isNotBlank() && currentLocation != null) {
                    val sample = SampleData(
                        sampleId = sampleId,
                        type = sampleType,
                        latitude = currentLocation!!.latitude,
                        longitude = currentLocation!!.longitude,
                        altitude = currentLocation!!.altitude,
                        weight = weight.toDoubleOrNull() ?: 0.0,
                        description = description,
                        time = System.currentTimeMillis(),
                        note = note
                    )
                    viewModel.saveSample(sample)
                    sampleId = ""
                    weight = ""
                    description = ""
                    note = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0EA5E9)
            ),
            enabled = sampleId.isNotBlank() && currentLocation != null
        ) {
            Text("💾 保存样本", fontSize = 16.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = exportFileName,
                onValueChange = { exportFileName = it },
                label = { Text("文件名") },
                modifier = Modifier.weight(2f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF0EA5E9)
                )
            )
            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val csvData = viewModel.exportSamples()
                            if (csvData.isNotEmpty()) {
                                val fileName = if (exportFileName.isNotBlank()) exportFileName else "普通样本记录"
                                shareCSV(context, csvData, fileName)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = samples.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF10B981)
                )
            ) {
                Text("📤 导出CSV", fontSize = 14.sp)
            }
        }

        Text(
            "📋 已保存样本 (${samples.size}组)",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1E293B),
            modifier = Modifier.padding(top = 4.dp)
        )

        if (samples.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                shape = RoundedCornerShape(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                ) {
                    items(samples.reversed()) { sample ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "📌 ${sample.sampleId}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                if (sample.description.isNotEmpty()) {
                                    Text(
                                        sample.description,
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                Text(
                                    "重量: ${String.format("%.2f", sample.weight)}kg",
                                    fontSize = 11.sp,
                                    color = Color(0xFF0EA5E9)
                                )
                                Text(
                                    "${android.text.format.DateFormat.format("HH:mm:ss", sample.time)}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (sample.note.isNotEmpty()) {
                                    Text(
                                        "📝 ${sample.note}",
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                        Divider()
                    }
                }
            }
        } else {
            Text("暂无样本记录", fontSize = 13.sp, color = Color(0xFF94A3B8))
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

// --- 22. 钻孔样本全屏内容 ---
@Composable
fun DrillSampleFullscreenContent(
    viewModel: LocationViewModel
) {
    var holeId by remember { mutableStateOf("") }
    var sampleId by remember { mutableStateOf("") }
    var depthFrom by remember { mutableStateOf("") }
    var depthTo by remember { mutableStateOf("") }
    var coreLength by remember { mutableStateOf("") }
    var recoveryRate by remember { mutableStateOf("") }
    var sampleLength by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var coreDiameter by remember { mutableStateOf("") }
    var rockType by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var exportFileName by remember { mutableStateOf("钻孔样本记录") }
    val currentLocation by viewModel.currentLocation.collectAsState()
    val drillSamples by viewModel.drillSamples.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🕳️ 钻孔样本", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
        Text("记录钻孔岩心样本信息", fontSize = 14.sp, color = Color(0xFF64748B))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("📍 当前位置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                if (currentLocation != null) {
                    Text("纬度: ${String.format("%.6f", currentLocation!!.latitude)}°", fontSize = 13.sp)
                    Text("经度: ${String.format("%.6f", currentLocation!!.longitude)}°", fontSize = 13.sp)
                    Text("海拔: ${String.format("%.1f", currentLocation!!.altitude)}m", fontSize = 13.sp)
                } else {
                    Text("等待GPS定位...", fontSize = 13.sp, color = Color.Gray)
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = holeId,
                        onValueChange = { holeId = it },
                        label = { Text("钻孔编号") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                    OutlinedTextField(
                        value = sampleId,
                        onValueChange = { sampleId = it },
                        label = { Text("样本编号") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = depthFrom,
                        onValueChange = { depthFrom = it },
                        label = { Text("深度从 (m)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                    OutlinedTextField(
                        value = depthTo,
                        onValueChange = { depthTo = it },
                        label = { Text("深度到 (m)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = coreLength,
                        onValueChange = { coreLength = it },
                        label = { Text("岩心长 (m)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                    OutlinedTextField(
                        value = recoveryRate,
                        onValueChange = { recoveryRate = it },
                        label = { Text("采取率 (%)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                }
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = sampleLength,
                        onValueChange = { sampleLength = it },
                        label = { Text("样长 (m)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = it },
                        label = { Text("重量 (kg)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                }
                
                OutlinedTextField(
                    value = coreDiameter,
                    onValueChange = { coreDiameter = it },
                    label = { Text("岩心直径 (mm)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
                
                OutlinedTextField(
                    value = rockType,
                    onValueChange = { rockType = it },
                    label = { Text("岩性") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
                
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("描述") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
                
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("备注") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
            }
        }

        Button(
            onClick = {
                if (holeId.isNotBlank() && sampleId.isNotBlank() && currentLocation != null) {
                    val sample = DrillSampleData(
                        holeId = holeId,
                        sampleId = sampleId,
                        depthFrom = depthFrom.toDoubleOrNull() ?: 0.0,
                        depthTo = depthTo.toDoubleOrNull() ?: 0.0,
                        coreLength = coreLength.toDoubleOrNull() ?: 0.0,
                        recoveryRate = recoveryRate.toDoubleOrNull() ?: 0.0,
                        sampleLength = sampleLength.toDoubleOrNull() ?: 0.0,
                        weight = weight.toDoubleOrNull() ?: 0.0,
                        coreDiameter = coreDiameter.toDoubleOrNull() ?: 0.0,
                        latitude = currentLocation!!.latitude,
                        longitude = currentLocation!!.longitude,
                        altitude = currentLocation!!.altitude,
                        rockType = rockType,
                        description = description,
                        time = System.currentTimeMillis(),
                        note = note
                    )
                    viewModel.saveDrillSample(sample)
                    holeId = ""
                    sampleId = ""
                    depthFrom = ""
                    depthTo = ""
                    coreLength = ""
                    recoveryRate = ""
                    sampleLength = ""
                    weight = ""
                    coreDiameter = ""
                    rockType = ""
                    description = ""
                    note = ""
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF0EA5E9)
            ),
            enabled = holeId.isNotBlank() && sampleId.isNotBlank() && currentLocation != null
        ) {
            Text("💾 保存钻孔样本", fontSize = 16.sp)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = exportFileName,
                onValueChange = { exportFileName = it },
                label = { Text("文件名") },
                modifier = Modifier.weight(2f),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFF0EA5E9)
                )
            )
            Button(
                onClick = {
                    coroutineScope.launch {
                        try {
                            val csvData = viewModel.exportDrillSamples()
                            if (csvData.isNotEmpty()) {
                                val fileName = if (exportFileName.isNotBlank()) exportFileName else "钻孔样本记录"
                                shareCSV(context, csvData, fileName)
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
                enabled = drillSamples.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF10B981)
                )
            ) {
                Text("📤 导出CSV", fontSize = 14.sp)
            }
        }

        Text(
            "📋 已保存钻孔样本 (${drillSamples.size}组)",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF1E293B),
            modifier = Modifier.padding(top = 4.dp)
        )

        if (drillSamples.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                shape = RoundedCornerShape(8.dp)
            ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                ) {
                    items(drillSamples.reversed()) { sample ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    "🕳️ ${sample.holeId} - ${sample.sampleId}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    "深度: ${String.format("%.1f", sample.depthFrom)}-${String.format("%.1f", sample.depthTo)}m",
                                    fontSize = 11.sp,
                                    color = Color(0xFF0EA5E9)
                                )
                                if (sample.rockType.isNotEmpty()) {
                                    Text(
                                        "🪨 ${sample.rockType}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                Text(
                                    "岩心长:${String.format("%.2f", sample.coreLength)}m 采取率:${String.format("%.1f", sample.recoveryRate)}%",
                                    fontSize = 10.sp,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    "${android.text.format.DateFormat.format("HH:mm:ss", sample.time)}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                if (sample.note.isNotEmpty()) {
                                    Text(
                                        "📝 ${sample.note}",
                                        fontSize = 10.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                        Divider()
                    }
                }
            }
        } else {
            Text("暂无钻孔样本记录", fontSize = 13.sp, color = Color(0xFF94A3B8))
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

// ============================================================
// === 23. CameraScreen (修改后 - 带实时预览和相册缩略图) ===
// ============================================================
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    state: CameraScreenState,
    showFullscreen: (@Composable () -> Unit) -> Unit
) {
    val viewModel: LocationViewModel = viewModel()
    val context = LocalContext.current
    val cameraHelper = remember { CameraHelper(context) }
    
    val cameraPermissionState = rememberPermissionState(
        Manifest.permission.CAMERA
    )
    val storagePermissionState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        rememberPermissionState(Manifest.permission.READ_MEDIA_IMAGES)
    } else {
        rememberPermissionState(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    // 获取最新照片
    val latestPhoto = remember { mutableStateOf<File?>(null) }
    LaunchedEffect(Unit) {
        val photos = cameraHelper.getImageFiles()
        latestPhoto.value = photos.firstOrNull()
    }

    LaunchedEffect(Unit) {
        if (!cameraPermissionState.status.isGranted) {
            cameraPermissionState.launchPermissionRequest()
        }
        if (!storagePermissionState.status.isGranted) {
            storagePermissionState.launchPermissionRequest()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 窗口1：水印相机 - 带实时预览
        SmallWindowCard(
            title = "水印相机",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    WatermarkCameraFullscreenContent(
                        viewModel = viewModel,
                        cameraHelper = cameraHelper,
                        state = state,
                        cameraPermissionState = cameraPermissionState
                    )
                }
            }
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📷 实时预览", fontSize = 14.sp, color = Color(0xFF64748B))
                val imageCount = cameraHelper.getImageFiles().size
                Text("🖼️ ${imageCount}张", fontSize = 14.sp, color = Color(0xFF0EA5E9))
            }
        }
        
        // 窗口2：相册 - 显示最新一张照片
        SmallWindowCard(
            title = "相册",
            modifier = Modifier.weight(1f),
            onClick = {
                showFullscreen {
                    GalleryFullscreenContent(
                        viewModel = viewModel,
                        cameraHelper = cameraHelper
                    )
                }
            }
        ) {
            val photos = cameraHelper.getImageFiles()
            if (photos.isNotEmpty()) {
                val latestBitmap = remember(photos.firstOrNull()) {
                    photos.firstOrNull()?.let {
                        cameraHelper.decodeSampledBitmapFromFile(it.absolutePath, 200, 200)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 显示最新照片缩略图
                    if (latestBitmap != null) {
                        Image(
                            bitmap = latestBitmap.asImageBitmap(),
                            contentDescription = "最新照片",
                            modifier = Modifier
                                .size(70.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(70.dp)
                                .background(Color(0xFFE2E8F0), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("📷", fontSize = 24.sp)
                        }
                    }
                    
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            "共 ${photos.size} 张照片",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E293B)
                        )
                        if (photos.isNotEmpty()) {
                            val lastModified = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(photos.first().lastModified()))
                            Text(
                                "最新: $lastModified",
                                fontSize = 12.sp,
                                color = Color(0xFF94A3B8)
                            )
                        }
                        Text(
                            "点击查看全部 →",
                            fontSize = 12.sp,
                            color = Color(0xFF0EA5E9)
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("📭", fontSize = 32.sp)
                    Text("暂无照片", fontSize = 14.sp, color = Color(0xFF94A3B8))
                    Text("使用水印相机拍照", fontSize = 12.sp, color = Color(0xFF94A3B8))
                }
            }
        }
    }
}

// --- 24. 水印相机全屏内容 (使用 CameraX 实时预览) ---
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun WatermarkCameraFullscreenContent(
    viewModel: LocationViewModel,
    cameraHelper: CameraHelper,
    state: CameraScreenState,
    cameraPermissionState: PermissionState
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    val currentLocation by viewModel.currentLocation.collectAsState()
    val currentAttitude by viewModel.currentAttitude.collectAsState()
    val locationName by viewModel.locationName.collectAsState()
    
    // CameraX 相关
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var isCapturing by remember { mutableStateOf(false) }
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var showCapturedPreview by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("📷 水印相机", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
        Text("拍照自动添加水印", fontSize = 14.sp, color = Color(0xFF64748B))

        // ===== 水印设置 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF1F5F9)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("⚙️ 水印设置", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = state.showLocation,
                        onClick = { state.showLocation = !state.showLocation },
                        label = { Text("📍 位置", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = state.showAttitude,
                        onClick = { state.showAttitude = !state.showAttitude },
                        label = { Text("📐 产状", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = state.showTime,
                        onClick = { state.showTime = !state.showTime },
                        label = { Text("🕐 时间", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = state.showNote,
                        onClick = { state.showNote = !state.showNote },
                        label = { Text("📝 备注", fontSize = 11.sp) },
                        modifier = Modifier.weight(1f)
                    )
                }
                AnimatedVisibility(visible = state.showNote) {
                    OutlinedTextField(
                        value = state.watermarkNote,
                        onValueChange = { state.watermarkNote = it },
                        label = { Text("备注内容") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFF0EA5E9)
                        )
                    )
                }
            }
        }

        // ===== 当前位置信息 =====
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("📍 当前位置", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                if (currentLocation != null) {
                    Text("${String.format("%.6f", currentLocation!!.latitude)}°, ${String.format("%.6f", currentLocation!!.longitude)}°", fontSize = 12.sp)
                    Text("海拔: ${String.format("%.1f", currentLocation!!.altitude)}m", fontSize = 12.sp)
                    if (locationName.isNotEmpty() && locationName != "正在获取地址..." && locationName != "地址获取失败") {
                        Text(locationName, fontSize = 12.sp, color = Color(0xFF64748B))
                    }
                } else {
                    Text("等待GPS定位...", fontSize = 12.sp, color = Color.Gray)
                }
                if (currentAttitude != null) {
                    Text("倾向: ${String.format("%.0f", currentAttitude!!.dipDirection)}° 倾角: ${String.format("%.0f", currentAttitude!!.dip)}°", fontSize = 12.sp)
                }
            }
        }

        // ===== 相机预览 (CameraX) =====
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black),
            shape = RoundedCornerShape(12.dp)
        ) {
            if (cameraPermissionState.status.isGranted) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // CameraX 预览视图
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                                cameraProviderFuture.addListener({
                                    val cameraProvider = cameraProviderFuture.get()
                                    val preview = Preview.Builder().build().also {
                                        it.setSurfaceProvider(surfaceProvider)
                                    }

                                    imageCapture = ImageCapture.Builder()
                                        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                        .build()

                                    val cameraSelector = androidx.camera.core.CameraSelector.DEFAULT_BACK_CAMERA

                                    try {
                                        cameraProvider.unbindAll()
                                        cameraProvider.bindToLifecycle(
                                            lifecycleOwner,
                                            cameraSelector,
                                            preview,
                                            imageCapture
                                        )
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }, ContextCompat.getMainExecutor(ctx))
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // 拍照按钮
                    if (!showCapturedPreview) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable(
                                    enabled = !isCapturing
                                ) {
                                    if (!isCapturing) {
                                        imageCapture?.takePicture(
                                            ContextCompat.getMainExecutor(context),
                                            object : ImageCapture.OnImageCapturedCallback() {
                                                override fun onCaptureSuccess(image: ImageProxy) {
                                                    val bitmap = image.toBitmap()
                                                    image.close()
                                                    
                                                    // 添加水印
                                                    val watermarked = addWatermarkToBitmap(
                                                        context = context,
                                                        originalBitmap = bitmap,
                                                        location = currentLocation,
                                                        attitude = currentAttitude,
                                                        locationName = locationName,
                                                        note = if (state.showNote) state.watermarkNote else "",
                                                        showLocation = state.showLocation,
                                                        showAttitude = state.showAttitude,
                                                        showTime = state.showTime
                                                    )
                                                    
                                                    capturedBitmap = watermarked
                                                    showCapturedPreview = true
                                                    isCapturing = false
                                                    
                                                    // 保存到相册（使用MediaStore，避免重复）
                                                    saveImageToGallery(context, watermarked)
                                                    Toast.makeText(context, "✅ 照片已保存", Toast.LENGTH_SHORT).show()
                                                }

                                                override fun onError(exception: ImageCaptureException) {
                                                    exception.printStackTrace()
                                                    Toast.makeText(context, "拍照失败: ${exception.message}", Toast.LENGTH_SHORT).show()
                                                    isCapturing = false
                                                }
                                            }
                                        )
                                        isCapturing = true
                                    }
                                },
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            // 圆形拍照按钮
                            Box(
                                modifier = Modifier
                                    .padding(bottom = 24.dp)
                                    .size(72.dp)
                                    .background(
                                        Color.White.copy(alpha = 0.9f),
                                        RoundedCornerShape(36.dp)
                                    )
                                    .border(
                                        width = 4.dp,
                                        color = Color.White,
                                        shape = RoundedCornerShape(36.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCapturing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(32.dp),
                                        color = Color(0xFF0EA5E9),
                                        strokeWidth = 3.dp
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .background(Color(0xFF0EA5E9), RoundedCornerShape(24.dp))
                                    )
                                }
                            }
                        }
                    }

                    // 拍照预览（水印效果预览）
                    if (showCapturedPreview && capturedBitmap != null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f))
                        ) {
                            Image(
                                bitmap = capturedBitmap!!.asImageBitmap(),
                                contentDescription = "预览",
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(280.dp)
                                    .padding(8.dp),
                                contentScale = ContentScale.Fit
                            )
                            
                            // 预览控制按钮
                            Row(
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                Button(
                                    onClick = {
                                        showCapturedPreview = false
                                        capturedBitmap = null
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFEF4444)
                                    )
                                ) {
                                    Text("重拍")
                                }
                                Button(
                                    onClick = {
                                        showCapturedPreview = false
                                        capturedBitmap = null
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF10B981)
                                    )
                                ) {
                                    Text("✅ 完成")
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📷", fontSize = 48.sp)
                        Text("需要相机权限", fontSize = 16.sp, color = Color.White)
                        Button(
                            onClick = { cameraPermissionState.launchPermissionRequest() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0EA5E9)
                            )
                        ) {
                            Text("授予权限")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "点击外部关闭 | 点击圆形按钮拍照",
            fontSize = 11.sp,
            color = Color(0xFF94A3B8),
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

// --- 25. 相册全屏内容 (修改后) ---
@Composable
fun GalleryFullscreenContent(
    viewModel: LocationViewModel,
    cameraHelper: CameraHelper
) {
    val context = LocalContext.current
    var selectedImage by remember { mutableStateOf<File?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    var refreshTrigger by remember { mutableStateOf(0) }
    val currentImages = remember(refreshTrigger) { cameraHelper.getImageFiles() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("🖼️ 相册", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
            Text("共 ${currentImages.size} 张照片", fontSize = 14.sp, color = Color(0xFF64748B))
        }

        if (currentImages.isEmpty()) {
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
                    Text("暂无照片", fontSize = 16.sp, color = Color.Gray)
                    Text("使用水印相机拍照", fontSize = 13.sp, color = Color(0xFF94A3B8))
                }
            }
        } else {
            // 网格显示所有照片
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 500.dp)
            ) {
                items(currentImages) { file ->
                    val bitmap = remember(file) {
                        cameraHelper.decodeSampledBitmapFromFile(file.absolutePath, 200, 200)
                    }
                    Card(
                        modifier = Modifier
                            .aspectRatio(1f)
                            .clickable {
                                selectedImage = file
                                showDetail = true
                            },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "照片",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("📷", fontSize = 24.sp)
                            }
                        }
                    }
                }
            }
            
            Text(
                "点击照片查看详情",
                fontSize = 11.sp,
                color = Color(0xFF94A3B8),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }

        // 照片详情对话框
        if (showDetail && selectedImage != null) {
            Dialog(
                onDismissRequest = { showDetail = false },
                properties = DialogProperties(
                    usePlatformDefaultWidth = false,
                    decorFitsSystemWindows = false
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.8f))
                        .clickable { showDetail = false }
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                            .clickable { /* 阻止穿透 */ },
                        contentAlignment = Alignment.Center
                    ) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 500.dp),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState())
                            ) {
                                val fullBitmap = remember(selectedImage) {
                                    selectedImage?.let {
                                        BitmapFactory.decodeFile(it.absolutePath)
                                    }
                                }
                                if (fullBitmap != null) {
                                    Image(
                                        bitmap = fullBitmap.asImageBitmap(),
                                        contentDescription = "照片",
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(300.dp),
                                        contentScale = ContentScale.Fit
                                    )
                                }
                                
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        "📷 ${selectedImage?.name ?: "未知"}",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        "📏 ${cameraHelper.getFileSize(selectedImage!!)}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF64748B)
                                    )
                                    Text(
                                        "🕐 ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(selectedImage!!.lastModified()))}",
                                        fontSize = 12.sp,
                                        color = Color(0xFF64748B)
                                    )
                                    
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Button(
                                            onClick = { showDeleteDialog = true },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFFEF4444)
                                            )
                                        ) {
                                            Text("🗑️ 删除", fontSize = 13.sp)
                                        }
                                        Button(
                                            onClick = {
                                                val uri = FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    selectedImage!!
                                                )
                                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                                    type = "image/jpeg"
                                                    putExtra(Intent.EXTRA_STREAM, uri)
                                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                                }
                                                context.startActivity(Intent.createChooser(shareIntent, "分享照片"))
                                            },
                                            modifier = Modifier.weight(1f),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = Color(0xFF10B981)
                                            )
                                        ) {
                                            Text("📤 分享", fontSize = 13.sp)
                                        }
                                    }
                                    Button(
                                        onClick = { showDetail = false },
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = Color(0xFF64748B)
                                        )
                                    ) {
                                        Text("关闭", fontSize = 13.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 删除确认对话框
        if (showDeleteDialog && selectedImage != null) {
            AlertDialog(
                onDismissRequest = { showDeleteDialog = false },
                title = { Text("确认删除") },
                text = { Text("确定要删除这张照片吗？") },
                confirmButton = {
                    Button(
                        onClick = {
                            selectedImage?.let {
                                cameraHelper.deleteImageFile(it)
                                showDeleteDialog = false
                                showDetail = false
                                selectedImage = null
                                refreshTrigger++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFEF4444)
                        )
                    ) {
                        Text("删除")
                    }
                },
                dismissButton = {
                    Button(
                        onClick = { showDeleteDialog = false }
                    ) {
                        Text("取消")
                    }
                }
            )
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

// ============================================================
// === 水印工具函数 ===
// ============================================================

/**
 * 添加水印到Bitmap
 */
fun addWatermarkToBitmap(
    context: Context,
    originalBitmap: Bitmap,
    location: com.geosurvey.toolbox.domain.model.LocationData?,
    attitude: AttitudeData?,
    locationName: String,
    note: String = "",
    showLocation: Boolean = true,
    showAttitude: Boolean = true,
    showTime: Boolean = true
): Bitmap {
    val mutableBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
    val canvas = Canvas(mutableBitmap)
    
    // 水印样式 - 使用Android原生Canvas
    val titlePaint = Paint().apply {
        color = android.graphics.Color.parseColor("#0EA5E9")
        textSize = 42f
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 2f, 2f, android.graphics.Color.BLACK)
    }
    
    val textPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        textSize = 32f
        typeface = Typeface.DEFAULT
        setShadowLayer(4f, 2f, 2f, android.graphics.Color.BLACK)
    }
    
    // 构建水印内容
    val lines = mutableListOf<String>()
    lines.add("📍 地质调查")
    
    if (showLocation) {
        if (locationName.isNotEmpty() && locationName != "正在获取地址..." && locationName != "地址获取失败") {
            lines.add("📍 $locationName")
        }
        location?.let {
            lines.add("📍 ${String.format("%.6f", it.latitude)}°, ${String.format("%.6f", it.longitude)}°")
            lines.add("📏 海拔: ${String.format("%.1f", it.altitude)}m")
        }
    }
    
    if (showAttitude && attitude != null) {
        lines.add("📐 倾向: ${String.format("%.0f", attitude.dipDirection)}° 倾角: ${String.format("%.0f", attitude.dip)}°")
        lines.add("📐 走向: ${String.format("%.0f", attitude.strike)}°")
    }
    
    if (showTime) {
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        lines.add("🕐 $timeStr")
    }
    
    if (note.isNotEmpty()) {
        lines.add("📝 $note")
    }
    
    // 计算水印区域尺寸
    var maxWidth = 0f
    var totalHeight = 0f
    val lineHeights = mutableListOf<Float>()
    
    lines.forEachIndexed { index, text ->
        val paint = if (index == 0) titlePaint else textPaint
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textWidth = bounds.width().toFloat()
        if (textWidth > maxWidth) maxWidth = textWidth
        val lineHeight = bounds.height().toFloat() + 12f
        lineHeights.add(lineHeight)
        totalHeight += lineHeight
    }
    
    val padding = 30f
    val bgLeft = canvas.width - maxWidth - padding * 3 - 20f
    val bgTop = canvas.height - totalHeight - padding * 2 - 10f
    val bgRight = canvas.width - padding - 10f
    val bgBottom = canvas.height - padding - 10f
    
    // 绘制半透明背景
    val bgPaint = Paint().apply {
        color = android.graphics.Color.BLACK
        alpha = 160
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(
        bgLeft, bgTop, bgRight, bgBottom,
        20f, 20f, bgPaint
    )
    
    // 绘制高光条（顶部）
    val highlightPaint = Paint().apply {
        color = android.graphics.Color.WHITE
        alpha = 60
    }
    canvas.drawRoundRect(
        bgLeft + 8f, bgTop + 4f, bgRight - 8f, bgTop + 6f,
        4f, 4f, highlightPaint
    )
    
    // 绘制水印文本
    var y = bgTop + padding + 10f
    lines.forEachIndexed { index, text ->
        val paint = if (index == 0) titlePaint else textPaint
        canvas.drawText(text, bgLeft + padding, y, paint)
        y += lineHeights[index]
    }
    
    return mutableBitmap
}

/**
 * 保存图片到相册（使用MediaStore，避免重复）
 */
fun saveImageToGallery(context: Context, bitmap: Bitmap): Uri? {
    return try {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val displayName = "IMG_${timeStamp}.jpg"
        
        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES)
            }
        }
        
        val uri = context.contentResolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        )
        
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, outputStream)
            }
        }
        
        uri
    } catch (e: Exception) {
        e.printStackTrace()
        null
    }
}

// --- 26. 预览 ---
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
