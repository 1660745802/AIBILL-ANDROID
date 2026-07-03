package com.aibill.android.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import com.aibill.android.presentation.widget.MonthlySummaryWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

private val Context.widgetDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "widget_data")

/**
 * Widget 数据更新器
 * 通过 DataStore 缓存月度收支数据，供 MonthlySummaryWidget 读取
 */
object WidgetDataUpdater {

    object Keys {
        val MONTHLY_EXPENSE = intPreferencesKey("monthly_expense")
        val MONTHLY_INCOME = intPreferencesKey("monthly_income")
        val UPDATED_AT = longPreferencesKey("widget_updated_at")
    }

    /**
     * 更新月度数据并刷新 Widget
     * 由 HomeViewModel.refresh() 或 SyncWorker 成功后调用
     */
    suspend fun updateMonthlySummary(
        context: Context,
        expenseCents: Int,
        incomeCents: Int
    ) {
        try {
            context.widgetDataStore.edit { prefs ->
                prefs[Keys.MONTHLY_EXPENSE] = expenseCents
                prefs[Keys.MONTHLY_INCOME] = incomeCents
                prefs[Keys.UPDATED_AT] = System.currentTimeMillis()
            }
            // 通知 Widget 刷新
            MonthlySummaryWidget().updateAll(context)
            Timber.d("Widget 数据已更新: expense=$expenseCents, income=$incomeCents")
        } catch (e: Exception) {
            Timber.e(e, "Widget 数据更新失败")
        }
    }

    /**
     * 当有新交易入库时触发 Widget 刷新（不修改缓存金额，仅提示有变化）
     * 下次打开 App 或 SyncWorker 完成后会更新精确数据
     */
    fun notifyTransactionAdded(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                MonthlySummaryWidget().updateAll(context)
            } catch (e: Exception) {
                Timber.e(e, "Widget 刷新通知失败")
            }
        }
    }

    /**
     * 读取缓存的月度支出（分）
     */
    suspend fun getMonthlyExpense(context: Context): Int {
        return context.widgetDataStore.data
            .map { it[Keys.MONTHLY_EXPENSE] ?: 0 }
            .first()
    }

    /**
     * 读取缓存的月度收入（分）
     */
    suspend fun getMonthlyIncome(context: Context): Int {
        return context.widgetDataStore.data
            .map { it[Keys.MONTHLY_INCOME] ?: 0 }
            .first()
    }
}
