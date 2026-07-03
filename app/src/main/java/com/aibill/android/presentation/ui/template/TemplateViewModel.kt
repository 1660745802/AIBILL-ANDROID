package com.aibill.android.presentation.ui.template

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.data.local.dao.TemplateDao
import com.aibill.android.data.local.entity.TemplateEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TemplateViewModel @Inject constructor(
    private val templateDao: TemplateDao,
) : ViewModel() {

    val templates: StateFlow<List<TemplateEntity>> = templateDao.getAllTemplates()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    data class DialogState(
        val isVisible: Boolean = false,
        val name: String = "",
        val type: String = "expense",
        val amount: String = "",
        val description: String = "",
    )

    sealed class UiEvent {
        data class ShowToast(val message: String) : UiEvent()
        data class NavigateToRecord(val template: TemplateEntity) : UiEvent()
    }

    private val _dialogState = MutableStateFlow(DialogState())
    val dialogState: StateFlow<DialogState> = _dialogState.asStateFlow()

    private val _uiEvent = Channel<UiEvent>(Channel.BUFFERED)
    val uiEvent = _uiEvent.receiveAsFlow()

    fun showAddDialog() {
        _dialogState.update { DialogState(isVisible = true) }
    }

    fun dismissDialog() {
        _dialogState.update { DialogState() }
    }

    fun onDialogNameChanged(name: String) {
        _dialogState.update { it.copy(name = name) }
    }

    fun onDialogTypeChanged(type: String) {
        _dialogState.update { it.copy(type = type) }
    }

    fun onDialogAmountChanged(amount: String) {
        _dialogState.update { it.copy(amount = amount) }
    }

    fun onDialogDescriptionChanged(desc: String) {
        _dialogState.update { it.copy(description = desc) }
    }

    fun onSaveTemplate() {
        val state = _dialogState.value
        if (state.name.isBlank()) return

        viewModelScope.launch {
            val amountCents = state.amount.toDoubleOrNull()?.let { (it * 100).toInt() }
            val entity = TemplateEntity(
                name = state.name.trim(),
                type = state.type,
                amount = amountCents,
                categoryId = null,
                accountId = null,
                description = state.description.ifBlank { null },
            )
            templateDao.insertTemplate(entity)
            _dialogState.update { DialogState() }
            _uiEvent.send(UiEvent.ShowToast("模板已保存 ✅"))
        }
    }

    fun onTemplateClick(template: TemplateEntity) {
        viewModelScope.launch {
            _uiEvent.send(UiEvent.NavigateToRecord(template))
        }
    }

    fun onDeleteTemplate(template: TemplateEntity) {
        viewModelScope.launch {
            templateDao.deleteTemplate(template.id)
            _uiEvent.send(UiEvent.ShowToast("已删除「${template.name}」"))
        }
    }
}
