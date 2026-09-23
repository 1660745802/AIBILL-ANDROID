package com.aibill.android.di

import com.aibill.android.data.repository.AccountRepositoryImpl
import com.aibill.android.data.repository.AiRepositoryImpl
import com.aibill.android.data.repository.AuthRepositoryImpl
import com.aibill.android.data.repository.BudgetRepositoryImpl
import com.aibill.android.data.repository.CategoryRepositoryImpl
import com.aibill.android.data.repository.CategoryRuleRepositoryImpl
import com.aibill.android.data.repository.NotificationRecordRepositoryImpl
import com.aibill.android.data.repository.PendingTransactionRepositoryImpl
import com.aibill.android.data.repository.StatsRepositoryImpl
import com.aibill.android.data.repository.StreakRepositoryImpl
import com.aibill.android.data.repository.TemplateRepositoryImpl
import com.aibill.android.data.repository.TransactionRepositoryImpl
import com.aibill.android.domain.repository.AccountRepository
import com.aibill.android.domain.repository.AiRepository
import com.aibill.android.domain.repository.AuthRepository
import com.aibill.android.domain.repository.BudgetRepository
import com.aibill.android.domain.repository.CategoryRepository
import com.aibill.android.domain.repository.CategoryRuleRepository
import com.aibill.android.domain.repository.NotificationRecordRepository
import com.aibill.android.domain.repository.PendingTransactionRepository
import com.aibill.android.domain.repository.StatsRepository
import com.aibill.android.domain.repository.StreakRepository
import com.aibill.android.domain.repository.TemplateRepository
import com.aibill.android.domain.repository.TransactionRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    @Singleton
    abstract fun bindTransactionRepository(impl: TransactionRepositoryImpl): TransactionRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds
    @Singleton
    abstract fun bindAiRepository(impl: AiRepositoryImpl): AiRepository

    @Binds
    @Singleton
    abstract fun bindAccountRepository(impl: AccountRepositoryImpl): AccountRepository

    @Binds
    @Singleton
    abstract fun bindTemplateRepository(impl: TemplateRepositoryImpl): TemplateRepository

    @Binds
    @Singleton
    abstract fun bindBudgetRepository(impl: BudgetRepositoryImpl): BudgetRepository

    // PR M9：补 StatsRepository bind，PR #802bc0f 漏了
    @Binds
    @Singleton
    abstract fun bindStatsRepository(impl: StatsRepositoryImpl): StatsRepository

    // PR 重构：CategoryLearningEngine 不再直接持有 DAO
    @Binds
    @Singleton
    abstract fun bindCategoryRuleRepository(impl: CategoryRuleRepositoryImpl): CategoryRuleRepository

    // PR 重构：StreakTracker 不再持有 Context/DataStore
    @Binds
    @Singleton
    abstract fun bindStreakRepository(impl: StreakRepositoryImpl): StreakRepository

    @Binds
    @Singleton
    abstract fun bindNotificationRecordRepository(impl: NotificationRecordRepositoryImpl): NotificationRecordRepository

    @Binds
    @Singleton
    abstract fun bindPendingTransactionRepository(impl: PendingTransactionRepositoryImpl): PendingTransactionRepository
}
