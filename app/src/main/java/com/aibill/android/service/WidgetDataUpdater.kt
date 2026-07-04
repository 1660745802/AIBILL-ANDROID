package com.aibill.android.service

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.updateAll
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.presentation.widget.MonthlySummaryWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.time.LocalDate

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
        /** 缓存对应的月份（YYYY-MM），跨月时需要整体重算 */
        val MONTH_TAG = stringPreferencesKey("widget_month_tag")
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
            val monthTag = currentMonthTag()
            context.widgetDataStore.edit { prefs ->
                prefs[Keys.MONTHLY_EXPENSE] = expenseCents
                prefs[Keys.MONTHLY_INCOME] = incomeCents
                prefs[Keys.MONTH_TAG] = monthTag
                prefs[Keys.UPDATED_AT] = System.currentTimeMillis()
            }
            // 通知 Widget 刷新
            MonthlySummaryWidget().updateAll(context)
            Timber.d("Widget 数据已更新: month=$monthTag expense=$expenseCents, income=$incomeCents")
        } catch (e: Exception) {
            Timber.e(e, "Widget 数据更新失败")
        }
    }

    /**
     * 当有新交易入库时，原子更新月度缓存并刷新 Widget。
     * 跨月时把缓存重置为本笔（避免把上月数据累加到本月）。
     */
    fun notifyTransactionAdded(
        context: Context,
        type: TransactionType,
        amountCents: Int,
        date: String? = null,
    ) {
        // PR M2：先快照跨月判断所需的月份标签，
        // 避免日期在「早 return 校验」与「实际 IO 写入」之间跨月导致竞态。
        val transactionMonthTag = date?.take(7)
        // 仅累加本月数据；如果传入 date 跨月则跳过（由下次 updateMonthlySummary 刷新）
        if (date != null && transactionMonthTag != currentMonthTag()) return
        // TRANSFER 不计入月度收支，提前 return 避免无谓的 IO + DataStore 写入。
        if (type == TransactionType.TRANSFER) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val monthTag = currentMonthTag()
                context.widgetDataStore.edit { prefs ->
                    val cachedTag = prefs[Keys.MONTH_TAG]
                    if (cachedTag != monthTag) {
                        // 跨月：重置缓存
                        prefs[Keys.MONTHLY_EXPENSE] = 0
                        prefs[Keys.MONTHLY_INCOME] = 0
                        prefs[Keys.MONTH_TAG] = monthTag
                    }
                    when (type) {
                        TransactionType.EXPENSE -> {
                            prefs[Keys.MONTHLY_EXPENSE] =
                                (prefs[Keys.MONTHLY_EXPENSE] ?: 0) + amountCents
                        }
                        TransactionType.INCOME -> {
                            prefs[Keys.MONTHLY_INCOME] =
                                (prefs[Keys.MONTHLY_INCOME] ?: 0) + amountCents
                        }
                        TransactionType.TRANSFER -> Unit // 已提前 return，这里兜底
                    }
                    prefs[Keys.UPDATED_AT] = System.currentTimeMillis()
                }
                MonthlySummaryWidget().updateAll(context)
            } catch (e: Exception) {
                Timber.e(e, "Widget 刷新通知失败")
            }
        }
    }

    private fun currentMonthTag(): String = LocalDate.now().toString().take(7)

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
