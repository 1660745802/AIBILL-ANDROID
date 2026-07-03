package com.aibill.android.presentation.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.remote.api.CategoryApi
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class CategoryManageViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
    private val categoryApi: CategoryApi,
) : ViewModel() {

    val expenseCategories: StateFlow<List<Category>> =
        categoryRepository.observeCategories("expense")
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val incomeCategories: StateFlow<List<Category>> =
        categoryRepository.observeCategories("income")
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    fun createCategory(name: String, type: String, icon: String, sortOrder: Int) {
        viewModelScope.launch {
            try {
                val request = mapOf<String, Any>(
                    "name" to name,
                    "type" to type,
                    "icon" to icon,
                    "sort_order" to sortOrder
                )
                val response = categoryApi.createCategory(request)
                if (response.code == 0) {
                    _toastMessage.value = "创建成功"
                    categoryRepository.syncCategories()
                } else {
                    _toastMessage.value = "创建失败: ${response.message}"
                }
            } catch (e: Exception) {
                Timber.e(e, "创建分类失败")
                _toastMessage.value = "创建失败: ${e.message}"
            }
        }
    }

    fun updateCategory(id: Int, name: String, icon: String, sortOrder: Int) {
        viewModelScope.launch {
            try {
                val request = mapOf<String, Any>(
                    "name" to name,
                    "icon" to icon,
                    "sort_order" to sortOrder
                )
                val response = categoryApi.updateCategory(id, request)
                if (response.code == 0) {
                    _toastMessage.value = "更新成功"
                    categoryRepository.syncCategories()
                } else {
                    _toastMessage.value = "更新失败: ${response.message}"
                }
            } catch (e: Exception) {
                Timber.e(e, "更新分类失败")
                _toastMessage.value = "更新失败: ${e.message}"
            }
        }
    }

    fun deleteCategory(id: Int) {
        viewModelScope.launch {
            try {
                val response = categoryApi.deleteCategory(id)
                if (response.code == 0) {
                    _toastMessage.value = "已停用"
                    categoryRepository.syncCategories()
                } else {
                    _toastMessage.value = "停用失败: ${response.message}"
                }
            } catch (e: Exception) {
                Timber.e(e, "停用分类失败")
                _toastMessage.value = "停用失败: ${e.message}"
            }
        }
    }

    fun clearToast() {
        _toastMessage.update { null }
    }
}
