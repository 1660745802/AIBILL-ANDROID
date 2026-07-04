package com.aibill.android.presentation.ui.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Category
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CategoryManageViewModel @Inject constructor(
    // PR #61：删除 CategoryApi 直接依赖，全部走 CategoryRepository
    private val categoryRepository: CategoryRepository,
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
            when (val result = categoryRepository.createCategory(
                name = name, type = type, icon = icon, sortOrder = sortOrder,
            )) {
                is Result.Success -> _toastMessage.value = "创建成功"
                is Result.Error -> _toastMessage.value = "创建失败: ${result.message}"
                is Result.Loading -> Unit
            }
        }
    }

    fun updateCategory(id: Int, name: String, icon: String, sortOrder: Int) {
        viewModelScope.launch {
            when (val result = categoryRepository.updateCategory(
                id = id, name = name, icon = icon, sortOrder = sortOrder,
            )) {
                is Result.Success -> _toastMessage.value = "更新成功"
                is Result.Error -> _toastMessage.value = "更新失败: ${result.message}"
                is Result.Loading -> Unit
            }
        }
    }

    fun deleteCategory(id: Int) {
        viewModelScope.launch {
            when (val result = categoryRepository.deleteCategory(id)) {
                is Result.Success -> _toastMessage.value = "已删除"
                is Result.Error -> _toastMessage.value = "删除失败: ${result.message}"
                is Result.Loading -> Unit
            }
        }
    }

    fun clearToast() {
        _toastMessage.update { null }
    }
}