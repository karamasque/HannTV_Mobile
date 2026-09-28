package tv.own.owntv.core.brand

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import tv.own.owntv.core.settings.SettingsRepository

object AppIconSwitcher {
    var mainActivityClass: String = ""
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun component(context: Context, icon: AppIcon): ComponentName {
        val cls = mainActivityClass.ifEmpty { "${context.packageName}.MainActivity" }
        val name = if (icon.activitySuffix.isEmpty()) cls else "$cls${icon.activitySuffix}"
        return ComponentName(context, name)
    }

    fun applied(context: Context): AppIcon {
        val pm = context.packageManager
        return AppIcon.entries.firstOrNull { icon ->
            val state = pm.getComponentEnabledSetting(component(context, icon))
            state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                (icon == AppIcon.DEFAULT && (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED))
        } ?: AppIcon.DEFAULT
    }

    fun launchComponent(context: Context): ComponentName {
        return component(context, applied(context))
    }

    fun apply(context: Context, icon: AppIcon) {
        if (applied(context) == icon) return
        val pm = context.packageManager
        val target = component(context, icon)
        AppIcon.entries.forEach { item ->
            val comp = component(context, item)
            val state = if (comp == target) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (pm.getComponentEnabledSetting(comp) != state) {
                pm.setComponentEnabledSetting(comp, state, PackageManager.DONT_KILL_APP)
            }
        }
    }

    fun restartWith(activity: Activity, icon: AppIcon) {
        apply(activity, icon)
        val intent = Intent().setComponent(component(activity, icon))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    fun isRestartProcess(context: Context): Boolean = false

    fun start(app: Application, settings: SettingsRepository) {
        scope.launch {
            settings.appIcon.collect { icon ->
                if (applied(app) != icon) {
                    apply(app, icon)
                }
            }
        }
    }
}
