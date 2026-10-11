package app.grapheneos.camera

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.File

/**
 * Runs [AppleLook.grade] on a file, without UI, so the on-device shader can be checked against
 * tools/look_sim.py. Paths are relative to the app's external files dir.
 *
 *   am broadcast -n com.sturq.orchardcam/app.grapheneos.camera.GradeTestReceiver --es in a.jpg --es out b.jpg
 *
 * Result goes to logcat tag "AppleLook".
 */
class GradeTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val dir = context.getExternalFilesDir(null) ?: return
        val src = File(dir, intent.getStringExtra("in") ?: return)
        val dst = File(dir, intent.getStringExtra("out") ?: return)
        val pending = goAsync()
        Thread {
            try {
                val t0 = SystemClock.elapsedRealtime()
                val out = AppleLook.grade(context, src.readBytes(), 95)
                dst.writeBytes(out)
                val ms = SystemClock.elapsedRealtime() - t0
                val gm = if (Build.VERSION.SDK_INT >= 34) {
                    BitmapFactory.decodeByteArray(out, 0, out.size)?.hasGainmap()
                } else null
                Log.i("AppleLook", "test grade ${src.name} -> ${dst.name}: ${out.size} bytes, ${ms} ms, gainmap=$gm")
            } catch (e: Throwable) {
                Log.e("AppleLook", "test grade failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }
}
