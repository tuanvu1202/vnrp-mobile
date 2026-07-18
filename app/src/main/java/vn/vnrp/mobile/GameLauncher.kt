package vn.vnrp.mobile

import android.content.Context
import android.widget.Toast

object GameLauncher {
    fun launch(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(LauncherConfig.gamePackage)
        if (intent == null) {
            Toast.makeText(
                context,
                "Chưa tìm thấy game package: ${LauncherConfig.gamePackage}",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        context.startActivity(intent)
    }
}
