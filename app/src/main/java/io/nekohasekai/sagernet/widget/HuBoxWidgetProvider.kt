package io.nekohasekai.sagernet.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent

class HuBoxWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.HuBox.app.widget.ACTION_TOGGLE"

        fun updateWidgets(context: Context) {
            HuBoxWidgetHelper.updateAllWidgets(context)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_TOGGLE) {
            HuBoxWidgetHelper.handleToggle(context)
            return
        }
        super.onReceive(context, intent)
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        HuBoxWidgetHelper.updateAllWidgets(context)
    }
}
