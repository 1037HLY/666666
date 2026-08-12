package com.geosurvey.toolbox.presentation

import android.Manifest
import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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

// --- 6. 预览窗口 ---
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun PreviewWindow(
    title: String,
    subtitle: String = "功能待开发",
    isExpanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    expandedContent: @Composable (() -> Unit)? = null
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { 
                onToggle() 
            }
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.75f)
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
                // 标题行
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onToggle() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF1E293B)
                    )
                    Icon(
                        imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (isExpanded) "折叠" else "展开",
                        tint = Color(0xFF64748B)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 内容区域
                if (isExpanded && expandedContent != null) {
                    AnimatedContent(
                        targetState = true,
                        transitionSpec = {
                            fadeIn() + slideInVertically() with fadeOut() + slideOutVertically()
                        }
                    ) {
                        Box(modifier = Modifier.fillMaxWidth()) {
                            expandedContent()
                        }
                    }
                } else {
                    Text(
                        text = subtitle,
                        fontSize = 14.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }
        }
    }
}

// --- 7. HomeScreen（带定位功能） ---
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(state: HomeScreenState) {
    val viewModel: LocationViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsState()
    val location by viewModel.currentLocation.collectAsState()
    val isTracking by viewModel.isTracking.collectAsState()
    val satellites by viewModel.satellites.collectAsState()
    val context = LocalContext.current

    // 权限状态
    val fineLocationPermissionState = rememberPermissionState(
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    // 检查GPS是否开启
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    // 请求权限
    LaunchedEffect(Unit) {
        if (!fineLocationPermissionState.status.isGranted) {
            fineLocationPermissionState.launchPermissionRequest()
        }
    }

    // 当权限授予后重新开始定位
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
            subtitle = if (location != null) "📍 定位已获取" else "⏳ 正在获取定位...",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                if (!fineLocationPermissionState.status.isGranted) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Text("⚠️ 缺少定位权限", fontSize = 16.sp, color = Color.Red)
                        Button(
                            onClick = { 
                                fineLocationPermissionState.launchPermissionRequest()
                            },
                            modifier = Modifier.padding(top = 8.dp)
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
                            onClick = { viewModel.restartLocation() },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("重新尝试定位")
                        }
                    }
                } else if (location != null) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "📍 位置信息",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0EA5E9)
                        )
                        Text("纬度: ${String.format("%.6f", location!!.latitude)}°", fontSize = 15.sp)
                        Text("经度: ${String.format("%.6f", location!!.longitude)}°", fontSize = 15.sp)
                        Text("海拔: ${String.format("%.1f", location!!.altitude)} m", fontSize = 15.sp)
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "📊 精度与速度",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF10B981)
                        )
                        Text("精度: ${String.format("%.1f", location!!.accuracy)} m", fontSize = 15.sp)
                        Text("速度: ${String.format("%.1f", location!!.speed)} m/s", fontSize = 15.sp)
                        Text("方向: ${String.format("%.1f", location!!.bearing)}°", fontSize = 15.sp)
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = "🛰️ 卫星与质量",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF8B5CF6)
                        )
                        Text("卫星数: ${uiState.satelliteCount}", fontSize = 15.sp)
                        Text("HDOP: ${String.format("%.1f", uiState.hdop)}", fontSize = 15.sp)
                        Text(
                            text = "质量: ${uiState.qualityText}",
                            fontSize = 15.sp,
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
                                onClick = { viewModel.loadTracks() },
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF10B981)
                                )
                            ) {
                                Text("📂 加载轨迹")
                            }
                        }
                        Text(
                            text = "轨迹点: ${uiState.tracks.size} 个",
                            fontSize = 13.sp,
                            color = Color.Gray
                        )
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
                        Text(
                            text = "正在搜索GPS信号...",
                            fontSize = 16.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = "请在室外开阔地带等待",
                            fontSize = 14.sp,
                            color = Color.Gray
                        )
                        Text(
                            text = "GPS状态: ${if (isGpsEnabled) "已开启 ✅" else "未开启 ❌"}",
                            fontSize = 14.sp,
                            color = if (isGpsEnabled) Color(0xFF4CAF50) else Color.Red
                        )
                        Text(
                            text = "网络定位: ${if (isNetworkEnabled) "已开启 ✅" else "未开启 ❌"}",
                            fontSize = 14.sp,
                            color = if (isNetworkEnabled) Color(0xFF4CAF50) else Color.Red
                        )
                        Text(
                            text = "权限: ${if (fineLocationPermissionState.status.isGranted) "已授予 ✅" else "未授予 ❌"}",
                            fontSize = 14.sp,
                            color = if (fineLocationPermissionState.status.isGranted) Color(0xFF4CAF50) else Color.Red
                        )
                        Button(
                            onClick = { viewModel.restartLocation() },
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("重新尝试定位")
                        }
                    }
                }
            }
        )

        // 窗口2：卫星信息
        PreviewWindow(
            title = "卫星与轨迹",
            subtitle = "🛰️ 卫星: ${satellites.size}颗 | 轨迹: ${uiState.tracks.size}个点",
            isExpanded = state.isPreview2Expanded,
            onToggle = { 
                state.isPreview2Expanded = !state.isPreview2Expanded
            },
            modifier = Modifier.weight(1f),
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
                            Text("📡 卫星极坐标图", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("可见卫星: ${satellites.size} 颗", fontSize = 13.sp)
                            if (satellites.isNotEmpty()) {
                                val gpsCount = satellites.count { it.constellation == Constellation.GPS }
                                val glonassCount = satellites.count { it.constellation == Constellation.GLONASS }
                                val galileoCount = satellites.count { it.constellation == Constellation.GALILEO }
                                val beidouCount = satellites.count { it.constellation == Constellation.BEIDOU }
                                Text("GPS: $gpsCount  GLONASS: $glonassCount", fontSize = 12.sp)
                                Text("Galileo: $galileoCount  北斗: $beidouCount", fontSize = 12.sp)
                            } else {
                                Text("等待卫星信号...", fontSize = 12.sp, color = Color.Gray)
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
                            Text("🛰️ 卫星信息窗口", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            if (satellites.isNotEmpty()) {
                                satellites.take(5).forEach { satellite ->
                                    Text(
                                        "${satellite.constellation.name} #${satellite.prn} | " +
                                        "SNR: ${String.format("%.1f", satellite.snr)} dB",
                                        fontSize = 11.sp,
                                        color = if (satellite.usedInFix) Color(0xFF4CAF50) else Color.Gray
                                    )
                                }
                                if (satellites.size > 5) {
                                    Text("... 还有 ${satellites.size - 5} 颗卫星", fontSize = 11.sp, color = Color.Gray)
                                }
                            } else {
                                Text("等待卫星信号...", fontSize = 13.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        )

        // 窗口3：轨迹显示
        PreviewWindow(
            title = "轨迹与导航",
            subtitle = "🗺️ 实时轨迹: ${uiState.tracks.size}个点 | 导航: 待开发",
            isExpanded = state.isPreview3Expanded,
            onToggle = { 
                state.isPreview3Expanded = !state.isPreview3Expanded
            },
            modifier = Modifier.weight(1f),
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
                            Text("🗺️ 实时轨迹", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            if (uiState.tracks.isNotEmpty()) {
                                val first = uiState.tracks.first()
                                val last = uiState.tracks.last()
                                Text("起点: ${String.format("%.4f", first.latitude)}, ${String.format("%.4f", first.longitude)}", fontSize = 11.sp)
                                Text("终点: ${String.format("%.4f", last.latitude)}, ${String.format("%.4f", last.longitude)}", fontSize = 11.sp)
                                Text("总点数: ${uiState.tracks.size}", fontSize = 12.sp)
                                Text("记录中: ${if (isTracking) "✅" else "⏸️"}", fontSize = 12.sp)
                            } else {
                                Text("暂无轨迹数据", fontSize = 13.sp, color = Color.Gray)
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
                            Text("🧭 轨迹导航", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Text("选择历史轨迹进行导航", fontSize = 13.sp, color = Color.Gray)
                            Text("功能开发中...", fontSize = 12.sp, color = Color.Gray)
                        }
                    }
                }
            }
        )
    }
}

// --- 8. AnalysisScreen ---
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
            subtitle = "产状测量，功能后续开发",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                Text("倾向: 0°, 倾角: 0°")
            }
        )
        PreviewWindow(
            title = "赤平投影 & 玫瑰花图",
            subtitle = "上:赤平投影，下:玫瑰花图",
            isExpanded = state.isPreview2Expanded,
            onToggle = { state.isPreview2Expanded = !state.isPreview2Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🔴 赤平投影")
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🌹 玫瑰花图")
                    }
                }
            }
        )
        PreviewWindow(
            title = "钻孔计算 & 绘制",
            subtitle = "上:钻孔计算，下:钻孔绘制",
            isExpanded = state.isPreview3Expanded,
            onToggle = { state.isPreview3Expanded = !state.isPreview3Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📐 钻孔计算")
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("📊 钻孔绘制")
                    }
                }
            }
        )
    }
}

// --- 9. RecordScreen ---
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
            subtitle = "普通样本，功能后续开发",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                Text("📋 普通样本列表")
            }
        )
        PreviewWindow(
            title = "钻孔样本",
            subtitle = "钻孔样本，功能后续开发",
            isExpanded = state.isPreview2Expanded,
            onToggle = { state.isPreview2Expanded = !state.isPreview2Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                Text("🕳️ 钻孔样本列表")
            }
        )
    }
}

// --- 10. CameraScreen ---
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
            subtitle = "水印相机，功能后续开发",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                Text("📷 相机预览 + 水印设置")
            }
        )
        PreviewWindow(
            title = "相册",
            subtitle = "相册，功能后续开发",
            isExpanded = state.isPreview2Expanded,
            onToggle = { state.isPreview2Expanded = !state.isPreview2Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                Text("🖼️ 图片列表")
            }
        )
    }
}

// --- 11. 预览 ---
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
