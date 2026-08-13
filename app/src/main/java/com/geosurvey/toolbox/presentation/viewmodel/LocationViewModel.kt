// --- 19. RecordScreen - 完善样本记录 ---
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
        // 普通样本
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
        
        // 钻孔样本
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

// --- 20. 普通样本全屏内容 ---
@Composable
fun SampleFullscreenContent(
    viewModel: LocationViewModel,
    sampleType: String
) {
    var sampleId by remember { mutableStateOf("") }
    var depth by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val currentLocation by viewModel.currentLocation.collectAsState()
    val samples by viewModel.samples.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("📋 普通样本", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
        Text("记录野外样本信息", fontSize = 14.sp, color = Color(0xFF64748B))

        // 位置信息
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

        // 输入表单
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
                    value = depth,
                    onValueChange = { depth = it },
                    label = { Text("深度 (m)") },
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

        // 保存按钮
        Button(
            onClick = {
                if (sampleId.isNotBlank() && currentLocation != null) {
                    val sample = SampleData(
                        sampleId = sampleId,
                        type = sampleType,
                        latitude = currentLocation!!.latitude,
                        longitude = currentLocation!!.longitude,
                        altitude = currentLocation!!.altitude,
                        depth = depth.toDoubleOrNull() ?: 0.0,
                        description = description,
                        time = System.currentTimeMillis(),
                        note = note
                    )
                    viewModel.saveSample(sample)
                    sampleId = ""
                    depth = ""
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

        // 历史记录
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
                                    "${android.text.format.DateFormat.format("HH:mm:ss", sample.time)}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${String.format("%.1f", sample.depth)}m",
                                    fontSize = 12.sp,
                                    color = Color(0xFF0EA5E9)
                                )
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

// --- 21. 钻孔样本全屏内容 ---
@Composable
fun DrillSampleFullscreenContent(
    viewModel: LocationViewModel
) {
    var holeId by remember { mutableStateOf("") }
    var sampleId by remember { mutableStateOf("") }
    var depthFrom by remember { mutableStateOf("") }
    var depthTo by remember { mutableStateOf("") }
    var rockType by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    val currentLocation by viewModel.currentLocation.collectAsState()
    val drillSamples by viewModel.drillSamples.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("🕳️ 钻孔样本", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0EA5E9))
        Text("记录钻孔岩心样本信息", fontSize = 14.sp, color = Color(0xFF64748B))

        // 位置信息
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

        // 输入表单
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
                    value = holeId,
                    onValueChange = { holeId = it },
                    label = { Text("钻孔编号") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFF0EA5E9)
                    )
                )
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

        // 保存按钮
        Button(
            onClick = {
                if (holeId.isNotBlank() && sampleId.isNotBlank() && currentLocation != null) {
                    val sample = DrillSampleData(
                        holeId = holeId,
                        sampleId = sampleId,
                        depthFrom = depthFrom.toDoubleOrNull() ?: 0.0,
                        depthTo = depthTo.toDoubleOrNull() ?: 0.0,
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

        // 历史记录
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
                                if (sample.rockType.isNotEmpty()) {
                                    Text(
                                        "🪨 ${sample.rockType}",
                                        fontSize = 11.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                Text(
                                    "${String.format("%.1f", sample.depthFrom)}-${String.format("%.1f", sample.depthTo)}m",
                                    fontSize = 11.sp,
                                    color = Color(0xFF0EA5E9)
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${android.text.format.DateFormat.format("HH:mm:ss", sample.time)}",
                                    fontSize = 10.sp,
                                    color = Color(0xFF94A3B8)
                                )
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
