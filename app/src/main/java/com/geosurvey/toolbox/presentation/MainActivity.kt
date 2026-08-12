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
        val centerX = size.width / 2
        val centerY = size.height / 2
        val radius = min(size.width, size.height) / 2 - 20

        // 绘制外圈
        drawCircle(
            color = Color(0xFF64748B).copy(alpha = 0.3f),
            radius = radius,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1f)
        )

        // 绘制内圈（50%半径）
        drawCircle(
            color = Color(0xFF64748B).copy(alpha = 0.2f),
            radius = radius * 0.5f,
            center = Offset(centerX, centerY),
            style = Stroke(width = 1f)
        )

        // 绘制十字线
        for (i in 0..3) {
            val angle = i * PI / 2
            val endX = centerX + radius * cos(angle).toFloat()
            val endY = centerY + radius * sin(angle).toFloat()
            drawLine(
                color = Color(0xFF64748B).copy(alpha = 0.2f),
                start = Offset(centerX, centerY),
                end = Offset(endX, endY),
                strokeWidth = 1f
            )
        }

        // 绘制方向标签
        val labels = listOf("N", "E", "S", "W")
        for (i in labels.indices) {
            val angle = i * PI / 2 - PI / 2
            val labelRadius = radius + 16
            val labelX = centerX + labelRadius * cos(angle).toFloat()
            val labelY = centerY + labelRadius * sin(angle).toFloat()
            // 使用drawContext.canvas.nativeCanvas绘制文字
        }

        // 绘制卫星点
        satellites.forEach { satellite ->
            val elevation = satellite.elevation
            val azimuth = satellite.azimuth

            // 将仰角映射到半径 (0度在中心，90度在边缘)
            val elevationRad = (elevation / 90.0).coerceIn(0.0, 1.0)
            val r = radius * elevationRad

            // 方位角 (0度 = 北)
            val angleRad = Math.toRadians(azimuth.toDouble()) - PI / 2

            val x = centerX + r * cos(angleRad).toFloat()
            val y = centerY + r * sin(angleRad).toFloat()

            // 根据星座选择颜色
            val color = when (satellite.constellation) {
                Constellation.GPS -> Color(0xFF4CAF50)
                Constellation.GLONASS -> Color(0xFF2196F3)
                Constellation.GALILEO -> Color(0xFFFF9800)
                Constellation.BEIDOU -> Color(0xFFE91E63)
                else -> Color(0xFF9E9E9E)
            }

            // 根据信噪比决定大小
            val size = (8 + satellite.snr / 6).coerceIn(4f, 16f)

            // 绘制卫星点
            drawCircle(
                color = color.copy(alpha = if (satellite.usedInFix) 1f else 0.4f),
                radius = size,
                center = Offset(x, y)
            )

            // 绘制外发光
            drawCircle(
                color = color.copy(alpha = 0.2f),
                radius = size * 1.8f,
                center = Offset(x, y)
            )
        }

        // 绘制中心点
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

// --- 7. 预览窗口（点击整个窗口展开） ---
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
                elevation = if (isExpanded) 12.dp else 8.dp,
                shape = RoundedCornerShape(if (isExpanded) 12.dp else 24.dp),
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            ),
        shape = RoundedCornerShape(if (isExpanded) 12.dp else 24.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = if (isExpanded) 0.95f else 0.75f)
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
                   
