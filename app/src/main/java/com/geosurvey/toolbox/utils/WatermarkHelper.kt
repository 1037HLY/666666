package com.geosurvey.toolbox.utils

import android.content.Context
import android.graphics.*
import android.media.ExifInterface
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class WatermarkHelper(private val context: Context) {

    companion object {
        private const val WATERMARK_TEXT_SIZE = 28f
        private const val WATERMARK_TITLE_SIZE = 36f
        private const val WATERMARK_PADDING = 30f
        private const val WATERMARK_LINE_SPACING = 8f
    }

    /**
     * 添加水印到图片
     */
    fun addWatermark(
        imagePath: String,
        locationName: String = "",
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        altitude: Double = 0.0,
        dipDirection: Float = 0f,
        dip: Float = 0f,
        strike: Float = 0f,
        note: String = ""
    ): String? {
        return try {
            // 加载原始图片
            val originalBitmap = BitmapFactory.decodeFile(imagePath)
            if (originalBitmap == null) {
                return null
            }

            // 创建可编辑的Bitmap副本
            val resultBitmap = originalBitmap.copy(Bitmap.Config.ARGB_8888, true)
            val canvas = Canvas(resultBitmap)

            // 获取图片尺寸
            val width = resultBitmap.width
            val height = resultBitmap.height

            // 计算水印位置（右下角）
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            // 构建水印文本
            val watermarkTexts = buildWatermarkTexts(
                locationName, latitude, longitude, altitude,
                dipDirection, dip, strike, note
            )

            // 计算水印背景尺寸
            val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = WATERMARK_TEXT_SIZE
                typeface = Typeface.DEFAULT_BOLD
            }

            var maxWidth = 0f
            var totalHeight = 0f
            val lineHeights = mutableListOf<Float>()

            watermarkTexts.forEach { text ->
                val bounds = Rect()
                textPaint.getTextBounds(text, 0, text.length, bounds)
                val textWidth = bounds.width().toFloat()
                if (textWidth > maxWidth) maxWidth = textWidth
                val lineHeight = bounds.height().toFloat() + WATERMARK_LINE_SPACING
                lineHeights.add(lineHeight)
                totalHeight += lineHeight
            }

            // 加上标题的高度
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = WATERMARK_TITLE_SIZE
                typeface = Typeface.DEFAULT_BOLD
            }
            val titleBounds = Rect()
            titlePaint.getTextBounds("地质调查", 0, 4, titleBounds)
            val titleHeight = titleBounds.height().toFloat() + WATERMARK_LINE_SPACING
            totalHeight += titleHeight

            // 水印背景
            val padding = WATERMARK_PADDING
            val bgLeft = width - maxWidth - padding * 3
            val bgTop = height - totalHeight - padding * 2
            val bgRight = width - padding
            val bgBottom = height - padding

            // 绘制半透明背景
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                alpha = 180
                style = Paint.Style.FILL
            }
            canvas.drawRoundRect(
                bgLeft, bgTop, bgRight, bgBottom,
                16f, 16f, bgPaint
            )

            // 绘制水印文本
            var y = bgTop + padding + titleHeight / 2

            // 绘制标题
            val titleColorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#0EA5E9")
                textSize = WATERMARK_TITLE_SIZE
                typeface = Typeface.DEFAULT_BOLD
            }
            canvas.drawText("📍 地质调查", bgLeft + padding, y, titleColorPaint)
            y += titleHeight

            // 绘制内容
            watermarkTexts.forEach { text ->
                val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.WHITE
                    textSize = WATERMARK_TEXT_SIZE
                    typeface = Typeface.DEFAULT
                }
                canvas.drawText(text, bgLeft + padding, y, textPaint)
                y += lineHeights[watermarkTexts.indexOf(text)]
            }

            // 保存带水印的图片
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "watermarked_${timeStamp}.jpg"
            val outputFile = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), fileName)

            FileOutputStream(outputFile).use { out ->
                resultBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            // 回收Bitmap
            originalBitmap.recycle()
            resultBitmap.recycle()

            outputFile.absolutePath

        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 构建水印文本列表
     */
    private fun buildWatermarkTexts(
        locationName: String,
        latitude: Double,
        longitude: Double,
        altitude: Double,
        dipDirection: Float,
        dip: Float,
        strike: Float,
        note: String
    ): List<String> {
        val texts = mutableListOf<String>()

        // 地点
        if (locationName.isNotEmpty() && locationName != "正在获取地址..." && locationName != "地址获取失败") {
            texts.add("📍 ${locationName}")
        }

        // 坐标
        texts.add("📍 ${String.format("%.6f", latitude)}°, ${String.format("%.6f", longitude)}°")

        // 海拔
        texts.add("📏 海拔: ${String.format("%.1f", altitude)}m")

        // 产状（如果有）
        if (dipDirection > 0 || dip > 0) {
            texts.add("📐 倾向: ${String.format("%.0f", dipDirection)}° 倾角: ${String.format("%.0f", dip)}°")
        }
        if (strike > 0) {
            texts.add("📐 走向: ${String.format("%.0f", strike)}°")
        }

        // 备注
        if (note.isNotEmpty()) {
            texts.add("📝 ${note}")
        }

        // 时间
        val timeStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        texts.add("🕐 ${timeStr}")

        return texts
    }

    /**
     * 获取图片的Exif信息
     */
    fun getExifData(imagePath: String): Map<String, String> {
        val exifData = mutableMapOf<String, String>()
        try {
            val exif = ExifInterface(imagePath)
            val attributes = arrayOf(
                ExifInterface.TAG_DATETIME,
                ExifInterface.TAG_GPS_LATITUDE,
                ExifInterface.TAG_GPS_LONGITUDE,
                ExifInterface.TAG_MAKE,
                ExifInterface.TAG_MODEL,
                ExifInterface.TAG_IMAGE_WIDTH,
                ExifInterface.TAG_IMAGE_LENGTH
            )
            attributes.forEach { tag ->
                val value = exif.getAttribute(tag)
                if (!value.isNullOrEmpty()) {
                    exifData[tag] = value
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return exifData
    }
}
