package com.aibill.android.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.aibill.android.presentation.theme.ExpenseColor
import com.aibill.android.presentation.theme.IncomeColor
import com.aibill.android.presentation.theme.Tokens
import com.aibill.android.presentation.theme.TransferColor

/**
 * 受控金额输入框。统一处理：
 * - 数字+小数点验证（正则 `^\\d*\\.?\\d{0,2}$`）
 * - 类型着色（expense/income/transfer）
 *
 * 用法：
 * ```
 * var amountText by remember { mutableStateOf("") }
 * AmountInput(
 *     value = amountText,
 *     onValueChange = { amountText = it },
 *     type = "expense",  // "expense" / "income" / "transfer"
 * )
 * ```
 *
 * **设计要点（PR 修复）**：
 * 之前用 `remember(amountFen) { mutableStateOf("%.2f".format(...)) }` 把 amountFen
 * 转成 "5.00" 显示，结果用户输入 "5" → amountFen=500 → text 被 reformat 回 "5.00"，
 * 光标跳到末尾，无法继续输入（"小数位自动填充00导致输入异常"）。
 *
 * 修法：直接用 String 作受控值，**完全去掉金额单位换算**。金额（分）的解析由调用方
 * 在 ViewModel 里负责，UI 只展示用户键入的字符。这样键入 "5" 就一直是 "5"，键入
 * "5.5" 就一直是 "5.5"，外部分类预填 / AI 解析时仍可一次性写入完整字符串。
 *
 * @param value 当前输入字符串（与用户键入一致，不强制格式化）。
 * @param onValueChange 字符串变化回调；非数字/超过 2 位小数会被本地丢弃。
 * @param type 业务类型（决定着色）。
 */
@Composable
fun AmountInput(
    value: String,
    onValueChange: (String) -> Unit,
    type: String,
    modifier: Modifier = Modifier,
    placeholder: String = "0.00",
) {
    val accent = when (type) {
        "income" -> IncomeColor
        "transfer" -> TransferColor
        else -> ExpenseColor
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.md)) {
        OutlinedTextField(
            value = value,
            onValueChange = { newVal ->
                if (newVal.isEmpty() || newVal.matches(Regex("""^\d*\.?\d{0,2}$"""))) {
                    onValueChange(newVal)
                }
            },
            placeholder = {
                Text(
                    placeholder,
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent.copy(alpha = 0.4f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            textStyle = TextStyle(
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = accent,
                textAlign = TextAlign.Center,
            ),
            prefix = {
                Text("¥", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = accent)
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = androidx.compose.foundation.shape.RoundedCornerShape(Tokens.Radius.lg),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = accent,
                unfocusedBorderColor = accent.copy(alpha = 0.3f),
            ),
        )
    }
}
