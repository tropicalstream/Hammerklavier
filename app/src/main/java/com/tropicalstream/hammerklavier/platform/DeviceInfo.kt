package com.tropicalstream.hammerklavier.platform

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.StatFs
import android.provider.Settings

/**
 * Device identity and resources (PLAN §2.2): RayNeo by manufacturer/brand/product, free space,
 * `ActivityManager.MemoryInfo`, `Settings.Global.BOOT_COUNT` (a reboot during a soak shows as a
 * new count). Any thread; every read inside `runCatching`.
 */
object DeviceInfo {
    val isRayNeo: Boolean get() = listOf(Build.MANUFACTURER, Build.BRAND, Build.PRODUCT, Build.MODEL)
        .any { (it ?: "").contains("rayneo", ignoreCase = true) || (it ?: "").contains("mercury", ignoreCase = true) }

    fun freeBytes(ctx: Context): Long = runCatching { StatFs((ctx.getExternalFilesDir(null) ?: ctx.filesDir).path).availableBytes }.getOrDefault(-1L)

    fun memory(ctx: Context): ActivityManager.MemoryInfo? = runCatching {
        ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
    }.getOrNull()

    fun bootCount(ctx: Context): Int = runCatching { Settings.Global.getInt(ctx.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /** One line for the self-test and the soak log. */
    fun summary(ctx: Context): String {
        val m = memory(ctx)
        return "${Build.MANUFACTURER}/${Build.BRAND}/${Build.PRODUCT}/${Build.MODEL} sdk=${Build.VERSION.SDK_INT} rayneo=$isRayNeo " +
            "free=${freeBytes(ctx) shr 20}MiB mem=${m?.availMem?.shr(20)}/${m?.totalMem?.shr(20)}MiB low=${m?.lowMemory} boots=${bootCount(ctx)}"
    }
}
