package tv.own.owntv.player

import android.content.Context
import android.content.Intent

class ScreenOff(private val context: Context) {
    fun isAllowed(): Boolean = true
    fun revoke() {}
    fun requestIntent(): Intent = Intent()
}
