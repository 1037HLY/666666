package com.geosurvey.toolbox.presentation

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
import androidx.compose.ui.draw.*
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.geosurvey.toolbox.R

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
    // 定义导航项：使用 Triple 存储 (Screen, Icon, Label)
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

// --- 6. 液态玻璃卡片 ---
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .clickable { onClick() }
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(24.dp),
                ambientColor = Color.Black.copy(alpha = 0.12f),
                spotColor = Color.Black.copy(alpha = 0.08f)
            )
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.75f),
                        Color.White.copy(alpha = 0.5f)
                    )
                ),
                shape = RoundedCornerShape(24.dp)
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

        // 内容
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            content()
        }
    }
}

// --- 7. 预览窗口 ---
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
    GlassCard(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(
                animationSpec = spring(
                    dampingRatio = 0.7f,
                    stiffness = Spring.StiffnessMedium
                )
            ),
        onClick = onToggle
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // 标题行
            Row(
                modifier = Modifier.fillMaxWidth(),
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

// --- 8. HomeScreen ---
@Composable
fun HomeScreen(state: HomeScreenState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        PreviewWindow(
            title = "GPS 定位",
            subtitle = "GPS窗口，功能后续开发",
            isExpanded = state.isPreview1Expanded,
            onToggle = { state.isPreview1Expanded = !state.isPreview1Expanded },
            modifier = Modifier.weight(1f),
            expandedContent = {
                Column {
                    Text("纬度: 0.000000°", fontSize = 16.sp)
                    Text("经度: 0.000000°", fontSize = 16.sp)
                    Text("海拔: 0.0 m", fontSize = 16.sp)
                    Text("卫星数: 0", fontSize = 16.sp)
                }
            }
        )

        PreviewWindow(
            title = "卫星与轨迹",
            subtitle = "上:卫星极坐标图，下:卫星信息窗口",
            isExpanded = state.isPreview2Expanded,
            onToggle = { state.isPreview2Expanded = !state.isPreview2Expanded },
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
                        Text("📡 卫星极坐标图", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🛰️ 卫星信息窗口", color = Color.Gray)
                    }
                }
            }
        )

        PreviewWindow(
            title = "轨迹与导航",
            subtitle = "上:实时轨迹，下:轨迹导航",
            isExpanded = state.isPreview3Expanded,
            onToggle = { state.isPreview3Expanded = !state.isPreview3Expanded },
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
                        Text("🗺️ 实时轨迹", color = Color.Gray)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFFE2E8F0), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("🧭 轨迹导航", color = Color.Gray)
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
