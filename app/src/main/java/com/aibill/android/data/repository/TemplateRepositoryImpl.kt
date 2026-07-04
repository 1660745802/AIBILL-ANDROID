package com.aibill.android.data.repository

import com.aibill.android.data.local.dao.TemplateDao
import com.aibill.android.data.local.entity.TemplateEntity
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Template
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.TemplateRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TemplateRepositoryImpl @Inject constructor(
    private val templateDao: TemplateDao,
) : TemplateRepository {

    override fun observeAll(): Flow<List<Template>> =
        templateDao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun syncFromServer(): Result<Unit> = Result.Success(Unit)

    override suspend fun addLocal(template: Template): Result<Unit> {
        templateDao.insert(template.toEntity())
        return Result.Success(Unit)
    }

    override suspend fun deleteLocal(id: Long): Result<Unit> {
        templateDao.deleteById(id)
        return Result.Success(Unit)
    }

    override suspend fun findById(id: Long): Template? =
        templateDao.findById(id)?.toDomain()

    private fun TemplateEntity.toDomain(): Template = Template(
        id = id,
        name = name,
        type = TransactionType.fromValue(type) ?: TransactionType.EXPENSE,
        amount = amount ?: 0,
        categoryId = categoryId,
        accountId = accountId,
        description = description,
        sortOrder = 0,
    )

    private fun Template.toEntity(): TemplateEntity = TemplateEntity(
        id = id,
        name = name,
        type = type.value,
        amount = amount,
        categoryId = categoryId,
        accountId = accountId,
        description = description,
    )
}