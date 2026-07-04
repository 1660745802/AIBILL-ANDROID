package com.aibill.android.presentation.ui.import_

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.aibill.android.presentation.theme.PrimaryButton
import com.aibill.android.util.CsvTransaction

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CsvImportScreen(
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit = {},
    viewModel: CsvImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.onFileSelected(it) }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("账单导入") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            StepIndicator(currentStep = uiState.step)
            Spacer(modifier = Modifier.height(16.dp))

            when (uiState.step) {
                ImportStep.SELECT -> SelectFileContent(
                    errorMessage = uiState.errorMessage,
                    onSelectFile = { filePicker.launch("text/*") }
                )
                ImportStep.PREVIEW -> PreviewContent(
                    previewItems = uiState.previewItems,
                    totalCount = uiState.totalCount,
                    onConfirm = viewModel::onConfirmImport
                )
                ImportStep.IMPORTING -> ImportingContent(progress = uiState.progress)
                ImportStep.DONE -> DoneContent(
                    totalCount = uiState.totalCount,
                    onFinish = onNavigateBack
                )
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: ImportStep, modifier: Modifier = Modifier) {
    val steps = listOf("选择文件", "预览数据", "确认导入")
    val currentIndex = currentStep.ordinal.coerceAtMost(steps.lastIndex)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        steps.forEachIndexed { index, label ->
            val color = if (index <= currentIndex) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
            Text(text = label, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun SelectFileContent(
    errorMessage: String?,
    onSelectFile: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("支持微信、支付宝导出的 CSV 文件", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(text = "选择 CSV 文件", onClick = onSelectFile)
        errorMessage?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PreviewContent(
    previewItems: List<CsvTransaction>,
    totalCount: Int,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxSize()) {
        Text("共解析 $totalCount 条记录，预览前 ${previewItems.size} 条：")
        Spacer(modifier = Modifier.height(8.dp))
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(previewItems) { item -> TransactionPreviewCard(transaction = item) }
        }
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(
            text = "确认导入 $totalCount 条记录",
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TransactionPreviewCard(
    transaction: CsvTransaction,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(transaction.description, style = MaterialTheme.typography.bodyMedium)
                val yuan = transaction.amount / 100
                val fen = transaction.amount % 100
                Text("¥$yuan.${"%02d".format(fen)}", style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                "${transaction.date} ${transaction.time} · ${transaction.source}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun ImportingContent(progress: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("正在导入...", style = MaterialTheme.typography.titleMedium)
        Spacer(modifier = Modifier.height(16.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
        Spacer(modifier = Modifier.height(8.dp))
        Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DoneContent(
    totalCount: Int,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("导入完成", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(8.dp))
        Text("成功导入 $totalCount 条账单记录")
        Spacer(modifier = Modifier.height(16.dp))
        PrimaryButton(text = "完成", onClick = onFinish)
    }
}
