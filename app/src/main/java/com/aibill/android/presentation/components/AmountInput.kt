package com.aibill.android.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * - 元 ↔ 分双向转换（外部只关心"分"）
 * - 类型着色（expense/income/transfer）
 *
 * 用法：
 * ```
 * var amountFen by remember { mutableStateOf(0) }
 * AmountInput(
 *     amountFen = amountFen,
 *     onAmountChange = { amountFen = it },
 *     type = "expense",  // "expense" / "income" / "transfer"
 * )
 * ```
 *
 * @param amountFen 当前金额（分）。0 显示 placeholder
 * @param onAmountChange 金额变化回调（分）
 * @param type 业务类型（决定着色）
 */
@Composable
fun AmountInput(
    amountFen: Int,
    onAmountChange: (Int) -> Unit,
    type: String,
    modifier: Modifier = Modifier,
    placeholder: String = "0.00",
) {
    val accent = when (type) {
        "income" -> IncomeColor
        "transfer" -> TransferColor
        else -> ExpenseColor
    }
    var text by remember(amountFen) {
        mutableStateOf(
            if (amountFen > 0) "%.2f".format(amountFen / 100.0) else ""
        )
    }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = Tokens.Spacing.xl, vertical = Tokens.Spacing.md)) {
        OutlinedTextField(
            value = text,
            onValueChange = { newVal ->
                if (newVal.isEmpty() || newVal.matches(Regex("""^\d*\.?\d{0,2}$"""))) {
                    text = newVal
                    val fen = if (newVal.isEmpty()) 0
                    else Math.round((newVal.toDoubleOrNull() ?: 0.0) * 100).toInt()
                    onAmountChange(fen)
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
