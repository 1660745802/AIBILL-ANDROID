package com.aibill.android.presentation.ui.import_

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aibill.android.domain.model.Result
import com.aibill.android.domain.model.Transaction
import com.aibill.android.domain.model.TransactionSource
import com.aibill.android.domain.model.TransactionType
import com.aibill.android.domain.repository.TransactionRepository
import com.aibill.android.util.CsvParser
import com.aibill.android.util.CsvTransaction
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

enum class ImportStep { SELECT, PREVIEW, IMPORTING, DONE }

data class CsvImportUiState(
    val step: ImportStep = ImportStep.SELECT,
    val previewItems: List<CsvTransaction> = emptyList(),
    val allItems: List<CsvTransaction> = emptyList(),
    val progress: Float = 0f,
    val totalCount: Int = 0,
    val errorMessage: String? = null
)

sealed class CsvImportEvent {
    data class ShowToast(val message: String) : CsvImportEvent()
    data object ImportSuccess : CsvImportEvent()
}

@HiltViewModel
class CsvImportViewModel @Inject constructor(
    application: Application,
    private val transactionRepository: TransactionRepository,
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(CsvImportUiState())
    val uiState: StateFlow<CsvImportUiState> = _uiState.asStateFlow()

    private val _events = Channel<CsvImportEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var selectedUri: Uri? = null

    fun onFileSelected(uri: Uri) {
        selectedUri = uri
        parseFile(uri)
    }

    private fun parseFile(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val context = getApplication<Application>()
                val inputStream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取文件")
                inputStream.use { stream ->
                    CsvParser.detectAndParse(stream)
                }
            }.onSuccess { transactions ->
                _uiState.update { state ->
                    state.copy(
                        step = ImportStep.PREVIEW,
                        allItems = transactions,
                        previewItems = transactions.take(5),
                        totalCount = transactions.size,
                        errorMessage = null
                    )
                }
            }.onFailure { e ->
                _uiState.update { state ->
                    state.copy(errorMessage = "解析失败: ${e.message}")
                }
            }
        }
    }

    fun onConfirmImport() {
        viewModelScope.launch(Dispatchers.IO) {
            val items = _uiState.value.allItems
            if (items.isEmpty()) return@launch

            _uiState.update { it.copy(step = ImportStep.IMPORTING, progress = 0f, errorMessage = null) }

            val transactions = items.map { it.toTransaction() }
            val batchSize = 50
            val batches = transactions.chunked(batchSize)

            for ((index, batch) in batches.withIndex()) {
                val result = transactionRepository.createTransactions(batch)
                when (result) {
                    is Result.Success -> {
                        val progress = (index + 1).toFloat() / batches.size
                        _uiState.update { it.copy(progress = progress) }
                    }
                    is Result.Error -> {
                        _uiState.update {
                            it.copy(
                                step = ImportStep.PREVIEW,
                                progress = 0f,
                                errorMessage = "导入失败: ${result.message}"
                            )
                        }
                        _events.send(CsvImportEvent.ShowToast("导入失败: ${result.message}"))
                        return@launch
                    }
                    is Result.Loading -> Unit
                }
            }

            _uiState.update { it.copy(step = ImportStep.DONE, progress = 1f) }
            _events.send(CsvImportEvent.ImportSuccess)
        }
    }

    fun resetState() {
        _uiState.update { CsvImportUiState() }
        selectedUri = null
    }

    private fun CsvTransaction.toTransaction(): Transaction {
        return Transaction(
            clientId = UUID.randomUUID().toString(),
            type = mapTransactionType(type),
            amount = amount.toInt(),
            categoryId = null,
            description = description.ifBlank { null },
            date = date,
            time = time.ifBlank { null },
            source = mapSource(source),
        )
    }

    private fun mapTransactionType(raw: String): TransactionType {
        return when {
            raw.contains("支出") || raw.contains("expense", ignoreCase = true) -> TransactionType.EXPENSE
            raw.contains("收入") || raw.contains("income", ignoreCase = true) -> TransactionType.INCOME
            else -> TransactionType.EXPENSE
        }
    }

    private fun mapSource(raw: String): TransactionSource {
        // CSV 导入的数据标记为手动来源（用户主动导入）
        return TransactionSource.MANUAL
    }
}
