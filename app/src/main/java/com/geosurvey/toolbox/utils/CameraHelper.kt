package com.geosurvey.toolbox.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class CameraHelper(private val context: Context) {

    companion object {
        private const val AUTHORITY_SUFFIX = ".fileprovider"
        private const val PHOTO_DIRECTORY = "CameraPhotos"
    }

    /**
     * 检查相机权限
     */
    fun hasCameraPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    /**
     * 创建图片文件
     */
    fun createImageFile(): File? {
        return try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            storageDir?.let {
                if (!it.exists()) {
                    it.mkdirs()
                }
                File(it, "IMG_${timeStamp}.jpg")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 获取图片的Uri
     */
    fun getImageUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}${AUTHORITY_SUFFIX}",
            file
        )
    }

    /**
     * 获取图片文件列表
     */
    fun getImageFiles(): List<File> {
        val files = mutableListOf<File>()
        try {
            val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            storageDir?.let {
                val fileList = it.listFiles { file ->
                    file.isFile && file.name.endsWith(".jpg") || file.name.endsWith(".jpeg") || file.name.endsWith(".png")
                }
                fileList?.let {
                    files.addAll(it.sortedByDescending { it.lastModified() })
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return files
    }

    /**
     * 解码图片（限制大小）
     */
    fun decodeSampledBitmapFromFile(filePath: String, reqWidth: Int, reqHeight: Int): Bitmap? {
        return try {
            // 首先获取图片尺寸
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(filePath, options)

            // 计算采样率
            var inSampleSize = 1
            if (options.outHeight > reqHeight || options.outWidth > reqWidth) {
                val halfHeight = options.outHeight / 2
                val halfWidth = options.outWidth / 2
                while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                    inSampleSize *= 2
                }
            }

            // 解码图片
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = inSampleSize
                inJustDecodeBounds = false
            }
            BitmapFactory.decodeFile(filePath, decodeOptions)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * 删除图片文件
     */
    fun deleteImageFile(file: File): Boolean {
        return try {
            file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * 获取文件大小（格式化）
     */
    fun getFileSize(file: File): String {
        val size = file.length()
        return when {
            size < 1024 -> "${size}B"
            size < 1024 * 1024 -> "${String.format("%.1f", size / 1024.0)}KB"
            else -> "${String.format("%.1f", size / (1024.0 * 1024.0))}MB"
        }
    }
}
