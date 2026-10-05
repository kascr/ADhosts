package com.kascr.adhosts.utils

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowBitmapFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppBackgroundManagerTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun cleanUp() {
        context.getSharedPreferences("app_wallpaper_preferences", 0).edit().clear().commit()
        File(context.filesDir, "custom_background_image").delete()
    }

    @Test fun ordinaryPhoneWallpaperKeepsItsOriginalResolution() {
        assertEquals(1, AppBackgroundManager.calculateInSampleSize(1216, 2688, 1216, 2688))
    }

    @Test fun extremeAspectRatiosAndHugeBoundsStayWithinAllocationLimits() {
        for ((width, height) in listOf(50000 to 2000, 2000 to 50000, 50000 to 50000,
            Int.MAX_VALUE to Int.MAX_VALUE, Int.MAX_VALUE to 1)) {
            val sample = AppBackgroundManager.calculateInSampleSize(width, height, 1216, 2688)
            val decodedWidth = (width.toLong() + sample - 1) / sample
            val decodedHeight = (height.toLong() + sample - 1) / sample
            assertTrue("$width x $height", decodedWidth <= AppBackgroundManager.MAX_DECODED_DIMENSION)
            assertTrue("$width x $height", decodedHeight <= AppBackgroundManager.MAX_DECODED_DIMENSION)
            assertTrue("$width x $height", decodedWidth * decodedHeight <= AppBackgroundManager.MAX_DECODED_PIXELS)
        }
    }

    @Test
    @Config(shadows = [FailingFileDecoder::class])
    fun decoderFailureSwitchesToDefaultAndPreservesTheOriginalFile() {
        val file = File(context.filesDir, "custom_background_image").apply { writeText("original") }
        context.getSharedPreferences("app_wallpaper_preferences", 0).edit()
            .putString("wallpaper_mode", "custom").commit()

        AppBackgroundManager.decodeBackgroundBitmap(context, 1216, 2688)?.recycle()

        assertEquals(AppBackgroundManager.WallpaperMode.NIGHT_SKY, AppBackgroundManager.getWallpaperMode(context))
        assertEquals("original", file.readText())
        // The next launch selects the bundled image and does not revisit the failing file decoder.
        AppBackgroundManager.decodeBackgroundBitmap(context, 1216, 2688)?.recycle()
        assertEquals(1, FailingFileDecoder.calls)
    }

    @Implements(BitmapFactory::class)
    class FailingFileDecoder : ShadowBitmapFactory() {
        companion object {
            var calls = 0
            @JvmStatic @Implementation(methodName = "decodeFile")
            fun failFileDecode(path: String?, options: BitmapFactory.Options?): Bitmap? {
                ++calls
                throw OutOfMemoryError("Simulated image decoder allocation failure")
            }
        }
    }
}
