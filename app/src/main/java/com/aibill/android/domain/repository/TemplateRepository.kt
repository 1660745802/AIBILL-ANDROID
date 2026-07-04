package com.aibill.android.domain.repository

import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Template
import kotlinx.coroutines.flow.Flow

interface TemplateRepository {
    fun observeAll(): Flow<List<Template>>
    suspend fun syncFromServer(): Result<Unit>
    suspend fun addLocal(template: Template): Result<Unit>
    suspend fun deleteLocal(id: Long): Result<Unit>
    suspend fun findById(id: Long): Template?
}