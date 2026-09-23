package com.kascr.adhosts.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.kascr.adhosts.R
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AppBackgroundManager {

    private const val BACKGROUND_FILE_NAME = "custom_background_image"
    private const val PREFERENCES_NAME = "app_wallpaper_preferences"
    private const val KEY_WALLPAPER_MODE = "wallpaper_mode"
    private const val LEGACY_DEFAULT_BACKGROUND_FILE_NAME = "default_background_image"
    private const val MAX_CUSTOM_BACKGROUND_BYTES = 25L * 1024 * 1024
    private val nightSkyBackgroundResId = R.drawable.default_app_background
    private val mikuBackgroundResId = R.drawable.miku_wallpaper

    fun getWallpaperMode(context: Context): WallpaperMode {
        val storedValue = preferences(context).getString(
            KEY_WALLPAPER_MODE,
            WallpaperMode.NIGHT_SKY.storageValue
        )
        val mode = WallpaperMode.fromStorageValue(storedValue)
        if (mode == WallpaperMode.CUSTOM && !backgroundFile(context).exists()) {
            setWallpaperMode(context, WallpaperMode.NIGHT_SKY)
            return WallpaperMode.NIGHT_SKY
        }
        return mode
    }

    fun saveCustomBackground(context: Context, uri: Uri) {
        val target = backgroundFile(context)
        val temp = File(target.parentFile, "$BACKGROUND_FILE_NAME.tmp")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IOException("Unable to open selected image")
            input.use {
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var totalBytes = 0L
                    while (true) {
                        val read = it.read(buffer)
                        if (read == -1) break
                        totalBytes += read
                        if (totalBytes > MAX_CUSTOM_BACKGROUND_BYTES) {
                            throw IOException(
                                context.getString(
                                    R.string.settings_background_too_large,
                                    MAX_CUSTOM_BACKGROUND_BYTES / (1024 * 1024)
                                )
                            )
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw IOException("Invalid image file")
            }

            moveReplacing(temp, target)
            setWallpaperMode(context, WallpaperMode.CUSTOM)
            clearLegacyDefaultBackground(context)
        } catch (error: Exception) {
            temp.delete()
            throw error
        }
    }

    fun selectBundledWallpaper(context: Context, mode: WallpaperMode) {
        require(mode != WallpaperMode.CUSTOM) { "Custom wallpaper must be selected from user storage." }
        setWallpaperMode(context, mode)
        clearLegacyDefaultBackground(context)
    }

    fun decodeBackgroundBitmap(context: Context, reqWidth: Int, reqHeight: Int): Bitmap? {
        val safeWidth = reqWidth.coerceAtLeast(1)
        val safeHeight = reqHeight.coerceAtLeast(1)
        return when (getWallpaperMode(context)) {
            WallpaperMode.CUSTOM -> {
                val file = backgroundFile(context)
                if (file.exists()) {
                    decodeBitmapFromFile(file, safeWidth, safeHeight)
                } else {
                    setWallpaperMode(context, WallpaperMode.NIGHT_SKY)
                    decodeBitmapFromResource(context, nightSkyBackgroundResId, safeWidth, safeHeight)
                }
            }

            WallpaperMode.NIGHT_SKY -> {
                decodeBitmapFromResource(context, nightSkyBackgroundResId, safeWidth, safeHeight)
            }

            WallpaperMode.MIKU -> {
                decodeBitmapFromResource(context, mikuBackgroundResId, safeWidth, safeHeight)
            }
        }
    }

    private fun decodeBitmapFromFile(file: File, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun decodeBitmapFromResource(
        context: Context,
        resId: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(context.resources, resId, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeResource(context.resources, resId, options)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        reqWidth: Int,
        reqHeight: Int
    ): Int {
        var inSampleSize = 1
        var currentWidth = width
        var currentHeight = height

        while (currentWidth / 2 >= reqWidth && currentHeight / 2 >= reqHeight) {
            currentWidth /= 2
            currentHeight /= 2
            inSampleSize *= 2
        }
        return inSampleSize.coerceAtLeast(1)
    }

    private fun backgroundFile(context: Context): File =
        File(context.filesDir, BACKGROUND_FILE_NAME)

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private fun legacyDefaultBackgroundFile(context: Context): File =
        File(context.filesDir, LEGACY_DEFAULT_BACKGROUND_FILE_NAME)

    private fun clearLegacyDefaultBackground(context: Context) {
        val legacy = legacyDefaultBackgroundFile(context)
        if (legacy.exists()) {
            legacy.delete()
        }
    }

    private fun setWallpaperMode(context: Context, mode: WallpaperMode) {
        preferences(context).edit()
            .putString(KEY_WALLPAPER_MODE, mode.storageValue)
            .apply()
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    enum class WallpaperMode(val storageValue: String) {
        CUSTOM("custom"),
        NIGHT_SKY("night_sky"),
        MIKU("miku");

        companion object {
            fun fromStorageValue(value: String?): WallpaperMode {
                return entries.firstOrNull { it.storageValue == value } ?: NIGHT_SKY
            }
        }
    }
}
