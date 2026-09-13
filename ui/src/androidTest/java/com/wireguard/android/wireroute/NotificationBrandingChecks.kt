/* SPDX-License-Identifier: Apache-2.0 */
package com.wireguard.android.wireroute

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.wireguard.android.QuickTileService
import com.wireguard.android.R
import com.wireguard.android.widget.SlashDrawable
import java.io.File

/** Tests the real notification builder without starting automation or changing VPN state. */
internal fun checkNotificationBranding(context: Context) {
    val service = WireRouteOnDemandService()
    ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
        isAccessible = true
        invoke(service, context)
    }
    val notification = WireRouteOnDemandService::class.java.getDeclaredMethod("notification", String::class.java).run {
        isAccessible = true
        invoke(service, "Branding check") as Notification
    }
    check(notification.smallIcon.resId == R.drawable.ic_wireroute_status)
    check(notification.contentIntent != null)
    check(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    check(notification.extras.getString(Notification.EXTRA_TITLE) == "WireRoute On-Demand")
    @Suppress("DEPRECATION")
    val tile = context.packageManager.getServiceInfo(ComponentName(context, QuickTileService::class.java), 0)
    check(tile.icon == R.drawable.ic_wireroute_status)

    val preview = Bitmap.createBitmap(480, 240, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(preview)
    canvas.drawColor(Color.rgb(17, 27, 42))
    canvas.save()
    canvas.clipRect(0, 120, 480, 240)
    canvas.drawColor(Color.rgb(244, 247, 251))
    canvas.restore()
    for ((row, tint) in listOf(Color.WHITE, Color.rgb(17, 27, 42)).withIndex()) {
        for ((column, size) in listOf(24, 48, 96).withIndex()) {
            val drawable = requireNotNull(context.getDrawable(R.drawable.ic_wireroute_status)).mutate()
            val pixels = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(pixels))
            check(Color.alpha(pixels.getPixel(0, 0)) == 0)
            check(Color.alpha(pixels.getPixel(size - 1, size - 1)) == 0)
            val data = IntArray(size * size)
            pixels.getPixels(data, 0, size, 0, 0, size, size)
            val visible = data.filter { Color.alpha(it) > 0 }
            check(visible.isNotEmpty() && visible.size < size * size / 2)
            check(visible.all { Color.red(it) == 255 && Color.green(it) == 255 && Color.blue(it) == 255 })
            drawable.setTint(tint)
            val x = column * 120 + (120 - size) / 2
            val y = row * 120 + (120 - size) / 2
            drawable.setBounds(x, y, x + size, y + size)
            drawable.draw(canvas)
        }
        val slashed = SlashDrawable(requireNotNull(context.getDrawable(R.drawable.ic_wireroute_status)).mutate())
        slashed.setAnimationEnabled(false)
        slashed.setSlashed(true)
        slashed.setTint(tint)
        // QuickTileService renders this legacy drawable into a zero-origin bitmap.
        slashed.setBounds(0, 0, 72, 72)
        canvas.save()
        canvas.translate(384f, row * 120f + 24f)
        slashed.draw(canvas)
        canvas.restore()
    }
    File(context.cacheDir, "notification-branding.png").outputStream().use {
        check(preview.compress(Bitmap.CompressFormat.PNG, 100, it))
    }
}
