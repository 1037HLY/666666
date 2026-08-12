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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.geosurvey.toolbox.R
import com.geosurvey.toolbox.domain.model.Constellation
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
    var isPreview1Expanded by mutableStateOf(false)
    var isPreview2Expanded by mutableStateOf(false)
    var isPreview3Expanded by mutableStateOf(false)
}

class AnalysisScreenState {
    var isPreview1Expanded by mutableStateOf(false)
    var isPreview2Expanded by mutableStateOf(false)
    var isPreview3Expanded by mutableStateOf(false)
}

class RecordScreenState {
    var isPreview1Expanded by mutableStateOf(false)
    var isPreview2Expanded by mutableStateOf(false)
}

class CameraScreenState {
    var isPreview1Expanded by mutableStateOf(false)
    var isPreview2Expanded by mutableStateOf(false)
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
                    HomeScreen(state = homeState)
                }
                composable(Screen.Analysis.route) {
                    AnalysisScreen(state = analysisState)
                }
                composable(Screen.Record.route) {
                    RecordScreen(state = recordState)
                }
                composable(Screen.Camera.route) {
                    CameraScreen(state = cameraState)
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

// --- 7. 预览窗口（点击切换小/大窗口） ---
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PreviewWindow(
    title: String,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    smallContent: @Composable () -> Unit,   // 小窗口显示的内容
    expandedContent: @Composable () -> Unit // 大窗口显示的内容
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onToggle() }
            .shadow(
                elevation = if (isExpanded) 16.dp else 8.dp,
                shape = RoundedCornerShape(if (isExpanded) 16.dp else 20.dp),
                ambientColor = Color.Black.copy(alpha = 0.15f),
                spotColor = Color.Black.copy(alpha = 0.1f)
            ),
        shape = RoundedCornerShape(if (isExpanded) 16.dp else 20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = if (isExpanded) 0.98f else 0.85f)
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
            // 顶部高光条
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (isExpanded) 4.dp else 3.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0f),
                                Color.White.copy(alpha = if (isExpanded) 0.98f else 0.9f),
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
                // 标题行
                Text(
                    text = title,
                    fontSize = if (isExpanded) 20.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1E293B),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                // 内容切换
                AnimatedContent(
                    targetState = isExpanded,
                    transitionSpec = {
                        fadeIn() + slideInVertically() with fadeOut() + slideOutVertically()
                    }
                ) { expanded ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = if (expanded) 250.dp else 80.dp)
                    ) {
                        if (expanded) {
                            expandedContent()
                        } else {
                            smallContent()
                        }
                    }
                }
            }
        }
    }
}

// --- 8. HomeScreen ---
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(state: HomeScreenState) {
    val viewModel: LocationViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val location by viewModel.currentLocation.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val satellites by viewModel.satellites.collectAsState()
    val locationName by viewModel.locationName.collectAsState()
    val detailedAddress by viewModel.detailedAddress.collectAsState()
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
        // 窗口1：GPS定位信息
        PreviewWindow(
            title = "GPS 定位",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                // 小窗口：只显示坐标和地名
                if (location != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = locationName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF1E293B),
                            maxLines = 1
                        )
                        Text(
                            text = "${String.format("%.4f", location.latitude)}°, ${String.format("%.4f", location.longitude)}°",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )
                        Text(
                            text = "海拔: ${String.format("%.1f", location.altitude)}m | 精度: ${String.format("%.1f", location.accuracy)}m",
                            fontSize = 11.sp,
                            color = Color(0xFF94A3B8)
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
                        Text(
                            text = "搜索GPS信号...",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
                    }
                }
            },
            expandedContent = {
                // 大窗口：显示完整信息
                if (!fineLocationPermissionState.status.isGranted) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("⚠️ 缺少定位权限", fontSize = 16.sp, color = Color.Red)
                        Button(
                            onClick = { fineLocationPermissionState.launchPermissionRequest() }
                        ) {
                            Text("授予权限")
                        }
                    }
                } else if (!isGpsEnabled && !isNetworkEnabled) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("⚠️ 定位服务未开启", fontSize = 16.sp, color = Color.Red)
                        Text("请打开GPS或网络定位", fontSize = 14.sp, color = Color.Gray)
                        Button(
                            onClick = { viewModel.restartLocation() }
                        ) {
                            Text("重新尝试定位")
                        }
                    }
                } else if (location != null) {
                    val addr = detailedAddress
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            text = "📍 当前位置",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0EA5E9)
                        )
                        Text(
                            text = locationName,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1E293B)
                        )
                        
                        if (addr != null && addr.fullAddress.isNotEmpty() && addr.fullAddress != locationName) {
                            Text(
                                text = addr.fullAddress,
                                fontSize = 13.sp,
                                color = Color(0xFF475569)
                            )
                        }
                        
                        Divider(modifier = Modifier.padding(vertical = 4.dp))
                        
                        Text(
                            text = "📌 坐标信息",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0EA5E9)
                        )
                        Text("纬度: ${String.format("%.6f", location.latitude)}°", fontSize = 14.sp)
                        Text("经度: ${String.format("%.6f", location.longitude)}°", fontSize = 14.sp)
                        Text("海拔: ${String.format("%.1f", location.altitude)} m", fontSize = 14.sp)
                        
                        Text(
                            text = "📊 精度与速度",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF10B981)
                        )
                        Text("水平精度: ${String.format("%.1f", location.accuracy)} m", fontSize = 14.sp)
                        Text("速度: ${String.format("%.1f", location.speed)} m/s", fontSize = 14.sp)
                        Text("方向: ${String.format("%.1f", location.bearing)}°", fontSize = 14.sp)
                        Text("定位源: ${location.provider}", fontSize = 14.sp)
                        
                        Text(
                            text = "🛰️ 卫星信息",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF8B5CF6)
                        )
                        Text("可见卫星: ${satellites.size} 颗", fontSize = 14.sp)
                        Text("HDOP: ${String.format("%.1f", uiState.hdop)}", fontSize = 14.sp)
                        Text(
                            text = "质量: ${uiState.qualityText}",
                            fontSize = 14.sp,
                            color = when (uiState.quality) {
                                com.geosurvey.toolbox.domain.model.LocationQuality.EXCELLENT -> Color(0xFF4CAF50)
                                com.geosurvey.toolbox.domain.model.LocationQuality.GOOD -> Color(0xFF8BC34A)
                                com.geosurvey.toolbox.domain.model.LocationQuality.FAIR -> Color(0xFFFFC107)
                                com.geosurvey.toolbox.domain.model.LocationQuality.POOR -> Color(0xFFFF9800)
                                com.geosurvey.toolbox.domain.model.LocationQuality.BAD -> Color(0xFFF44336)
                                else -> Color.Gray
                            }
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (isTracking) {
                                        viewModel.stopTracking()
                                    } else {
                                        viewModel.startTracking()
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isTracking) Color(0xFFEF4444) else Color(0xFF0EA5E9)
                                )
                            ) {
                                Text(if (isTracking) "⏹ 停止记录" else "▶ 开始记录")
                            }
                            Button(
                                onClick = { viewModel.restartLocation() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF59E0B)
                                )
                            ) {
                                Text("🔄 刷新定位")
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
                                Text("📂 加载轨迹")
                            }
                            Text(
                                text = "轨迹点: ${uiState.tracks.size}",
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(8.dp)
                                    .background(Color(0xFFE2E8F0), RoundedCornerShape(8.dp))
                                    .wrapContentSize(Alignment.Center),
                                fontSize = 13.sp,
                                color = Color(0xFF475569)
                            )
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = Color(0xFF0EA5E9)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("正在搜索GPS信号...", fontSize = 16.sp, color = Color.Gray)
                        Text("请在室外开阔地带等待", fontSize = 14.sp, color = Color.Gray)
                        Button(
                            onClick = { viewModel.restartLocation() }
                        ) {
                            Text("重新尝试定位")
                        }
                    }
                }
            }
        )

        // 窗口2：卫星极坐标图
        PreviewWindow(
            title = "卫星与轨迹",
            isExpanded = state.isPreview2Expanded,
            onToggle = { state.isPreview2Expanded = !state.isPreview2Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                // 小窗口：只显示卫星数量和轨迹点数
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("🛰️ ${satellites.size}颗", fontSize = 14.sp, color = Color(0xFF475569))
                    Text("📍 ${uiState.tracks.size}个点", fontSize = 14.sp, color = Color(0xFF475569))
                    if (isTracking) {
                        Text("🔴 记录中", fontSize = 14.sp, color = Color(0xFFEF4444))
                    }
                }
            },
            expandedContent = {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // 卫星极坐标图
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
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text("可见: ${satellites.size} 颗", fontSize = 14.sp, color = Color.White)
                                    val usedCount = satellites.count { it.usedInFix }
                                    Text("锁定: $usedCount 颗", fontSize = 14.sp, color = Color(0xFF4CAF50))
                                }
                            }
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(12.dp)
                                    .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                Column {
                                    Text("● GPS", fontSize = 11.sp, color = Color(0xFF4CAF50))
                                    Text("● GLONASS", fontSize = 11.sp, color = Color(0xFF2196F3))
                                    Text("● Galileo", fontSize = 11.sp, color = Color(0xFFFF9800))
                                    Text("● 北斗", fontSize = 11.sp, color = Color(0xFFE91E63))
                                }
                            }
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("📡", fontSize = 48.sp)
                                Text("等待卫星信号...", fontSize = 14.sp, color = Color.Gray)
                            }
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 卫星详细信息
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(0.6f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp))
                            .padding(12.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text("🛰️ 卫星详细信息", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                            if (satellites.isNotEmpty()) {
                                val gpsList = satellites.filter { it.constellation == Constellation.GPS }
                                val glonassList = satellites.filter { it.constellation == Constellation.GLONASS }
                                val galileoList = satellites.filter { it.constellation == Constellation.GALILEO }
                                val beidouList = satellites.filter { it.constellation == Constellation.BEIDOU }
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    if (gpsList.isNotEmpty()) Text("GPS: ${gpsList.size}", fontSize = 12.sp, color = Color(0xFF4CAF50))
                                    if (glonassList.isNotEmpty()) Text("GLONASS: ${glonassList.size}", fontSize = 12.sp, color = Color(0xFF2196F3))
                                    if (galileoList.isNotEmpty()) Text("Galileo: ${galileoList.size}", fontSize = 12.sp, color = Color(0xFFFF9800))
                                    if (beidouList.isNotEmpty()) Text("北斗: ${beidouList.size}", fontSize = 12.sp, color = Color(0xFFE91E63))
                                }
                                
                                satellites.take(5).forEach { satellite ->
                                    Text(
                                        "${satellite.constellation.name} #${satellite.prn} | SNR: ${String.format("%.1f", satellite.snr)}dB | 仰角: ${String.format("%.1f", satellite.elevation)}°" +
                                        if (satellite.usedInFix) " ✅" else "",
                                        fontSize = 11.sp,
                                        color = if (satellite.usedInFix) Color(0xFF1E293B) else Color(0xFF94A3B8)
                                    )
                                }
                                if (satellites.size > 5) {
                                    Text("... 还有 ${satellites.size - 5} 颗", fontSize = 11.sp, color = Color(0xFF94A3B8))
                                }
                            } else {
                                Text("等待卫星信号...", fontSize = 13.sp, color = Color.Gray, modifier = Modifier.fillMaxSize().wrapContentSize(Alignment.Center))
                            }
                        }
                    }
                }
            }
        )

        // 窗口3：轨迹显示
        PreviewWindow(
            title = "轨迹与导航",
            isExpanded = state.isPreview3Expanded,
            onToggle = { state.isPreview3Expanded = !state.isPreview3Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                // 小窗口：只显示轨迹点数
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("📍 ${uiState.tracks.size}个点", fontSize = 14.sp, color = Color(0xFF475569))
                    if (isTracking) {
                        Text("🔴 记录中", fontSize = 14.sp, color = Color(0xFFEF4444))
                    } else {
                        Text("⏸️ 未记录", fontSize = 14.sp, color = Color(0xFF94A3B8))
                    }
                }
            },
            expandedContent = {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🗺️ 实时轨迹", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            if (uiState.tracks.isNotEmpty()) {
                                val first = uiState.tracks.first()
                                val last = uiState.tracks.last()
                                Text("起点: ${String.format("%.4f", first.latitude)}, ${String.format("%.4f", first.longitude)}", fontSize = 12.sp)
                                Text("终点: ${String.format("%.4f", last.latitude)}, ${String.format("%.4f", last.longitude)}", fontSize = 12.sp)
                                Text("总点数: ${uiState.tracks.size}", fontSize = 12.sp)
                                Text("记录中: ${if (isTracking) "✅" else "⏸️"}", fontSize = 12.sp)
                            } else {
                                Text("暂无轨迹数据", fontSize = 14.sp, color = Color.Gray)
                                Text("点击「开始记录」开始收集", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("🧭 轨迹导航", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("选择历史轨迹进行导航", fontSize = 13.sp, color = Color.Gray)
                            Text("功能开发中...", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        )
    }
}

// --- 9. AnalysisScreen ---
@Composable
fun AnalysisScreen(state: AnalysisScreenState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PreviewWindow(
            title = "产状测量",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                Text("倾向: 0° 倾角: 0°", fontSize = 14.sp, color = Color(0xFF64748B))
            },
            expandedContent = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("倾向: 0°", fontSize = 16.sp)
                    Text("倾角: 0°", fontSize = 16.sp)
                    Text("走向: 0°", fontSize = 16.sp)
                    Text("点击「记录」保存产状数据", fontSize = 13.sp, color = Color.Gray)
                }
            }
        )
        PreviewWindow(
            title = "赤平投影 & 玫瑰花图",
            isExpanded = state.isPreview2Expanded,
            onToggle = { state.isPreview2Expanded = !state.isPreview2Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("🔴 赤平投影", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text("🌹 玫瑰花图", fontSize = 13.sp, color = Color(0xFF64748B))
                }
            },
            expandedContent = {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔴 赤平投影", fontSize = 16.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🌹 玫瑰花图", fontSize = 16.sp)
                    }
                }
            }
        )
        PreviewWindow(
            title = "钻孔计算 & 绘制",
            isExpanded = state.isPreview3Expanded,
            onToggle = { state.isPreview3Expanded = !state.isPreview3Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Text("📐 钻孔计算", fontSize = 13.sp, color = Color(0xFF64748B))
                    Text("📊 钻孔绘制", fontSize = 13.sp, color = Color(0xFF64748B))
                }
            },
            expandedContent = {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📐 钻孔计算", fontSize = 16.sp)
                        Text("输入钻孔参数进行计算", fontSize = 12.sp, color = Color.Gray)
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📊 钻孔绘制", fontSize = 16.sp)
                        Text("显示钻孔柱状图", fontSize = 12.sp, color = Color.Gray)
                    }
                }
            }
        )
    }
}

// --- 10. RecordScreen ---
@Composable
fun RecordScreen(state: RecordScreenState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PreviewWindow(
            title = "普通样本",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                Text("📋 共 0 个样本", fontSize = 14.sp, color = Color(0xFF64748B))
            },
            expandedContent = {
                Text("📋 普通样本列表", fontSize = 16.sp)
                Text("暂无样本数据", fontSize = 14.sp, color = Color.Gray)
            }
        )
        PreviewWindow(
            title = "钻孔样本",
            isExpanded = state.isPreview2Expanded,
            onToggle = { state.isPreview2Expanded = !state.isPreview2Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                Text("🕳️ 共 0 个钻孔", fontSize = 14.sp, color = Color(0xFF64748B))
            },
            expandedContent = {
                Text("🕳️ 钻孔样本列表", fontSize = 16.sp)
                Text("暂无钻孔数据", fontSize = 14.sp, color = Color.Gray)
            }
        )
    }
}

// --- 11. CameraScreen ---
@Composable
fun CameraScreen(state: CameraScreenState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PreviewWindow(
            title = "水印相机",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                Text("📷 点击打开相机", fontSize = 14.sp, color = Color(0xFF64748B))
            },
            expandedContent = {
                Text("📷 相机预览", fontSize = 16.sp)
                Text("水印设置: 坐标 | 时间 | 地点", fontSize = 13.sp, color = Color.Gray)
            }
        )
        PreviewWindow(
            title = "相册",
            isExpanded = state.isPreview2Expanded,
            onToggle = { state.isPreview2Expanded = !state.isPreview2Expanded },
            modifier = Modifier.weight(1f),
            smallContent = {
                Text("🖼️ 共 0 张照片", fontSize = 14.sp, color = Color(0xFF64748B))
            },
            expandedContent = {
                Text("🖼️ 照片列表", fontSize = 16.sp)
                Text("暂无照片", fontSize = 14.sp, color = Color.Gray)
            }
        )
    }
}

// --- 12. 预览 ---
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
