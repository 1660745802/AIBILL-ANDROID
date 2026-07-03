package com.aibill.android.util

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.math.BigDecimal

data class CsvTransaction(
    val date: String,
    val time: String,
    val type: String,
    val amount: Long,
    val description: String,
    val source: String
)

enum class CsvSource { WECHAT, ALIPAY, UNKNOWN }

object CsvParser {

    fun detectAndParse(inputStream: InputStream): List<CsvTransaction> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val lines = reader.readLines()
        reader.close()

        val source = detectSource(lines)
        return when (source) {
            CsvSource.WECHAT -> parseWechatLines(lines)
            CsvSource.ALIPAY -> parseAlipayLines(lines)
            CsvSource.UNKNOWN -> emptyList()
        }
    }

    fun parseWechatCsv(inputStream: InputStream): List<CsvTransaction> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val lines = reader.readLines()
        reader.close()
        return parseWechatLines(lines)
    }

    fun parseAlipayCsv(inputStream: InputStream): List<CsvTransaction> {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val lines = reader.readLines()
        reader.close()
        return parseAlipayLines(lines)
    }

    private fun detectSource(lines: List<String>): CsvSource {
        val header = lines.firstOrNull().orEmpty()
        return when {
            header.contains("微信") || lines.any { it.contains("微信支付") } -> CsvSource.WECHAT
            header.contains("支付宝") || lines.any { it.contains("支付宝") } -> CsvSource.ALIPAY
            else -> CsvSource.UNKNOWN
        }
    }

    private fun parseWechatLines(lines: List<String>): List<CsvTransaction> {
        val dataLines = findDataLines(lines)
        return dataLines.mapNotNull { line ->
            runCatching { parseWechatLine(line) }.getOrNull()
        }
    }

    private fun parseAlipayLines(lines: List<String>): List<CsvTransaction> {
        val dataLines = findDataLines(lines)
        return dataLines.mapNotNull { line ->
            runCatching { parseAlipayLine(line) }.getOrNull()
        }
    }

    private fun findDataLines(lines: List<String>): List<String> {
        val headerIndex = lines.indexOfFirst { it.contains("交易时间") || it.contains("付款时间") }
        if (headerIndex < 0) return emptyList()
        return lines.drop(headerIndex + 1).filter { it.isNotBlank() }
    }

    private fun parseWechatLine(line: String): CsvTransaction {
        val cols = splitCsvLine(line)
        val dateTime = cols.getOrElse(0) { "" }.trim()
        val dateParts = dateTime.split(" ")
        val date = dateParts.getOrElse(0) { "" }
        val time = dateParts.getOrElse(1) { "" }
        val type = cols.getOrElse(1) { "" }.trim()
        val description = cols.getOrElse(3) { "" }.trim()
        val amountStr = cols.getOrElse(5) { "0" }.trim().replace("¥", "").replace(",", "")
        val amount = yuanToCents(amountStr)
        return CsvTransaction(date, time, type, amount, description, "微信")
    }

    private fun parseAlipayLine(line: String): CsvTransaction {
        val cols = splitCsvLine(line)
        val dateTime = cols.getOrElse(2) { "" }.trim()
        val dateParts = dateTime.split(" ")
        val date = dateParts.getOrElse(0) { "" }
        val time = dateParts.getOrElse(1) { "" }
        val type = cols.getOrElse(10) { "" }.trim()
        val description = cols.getOrElse(8) { "" }.trim()
        val amountStr = cols.getOrElse(9) { "0" }.trim().replace(",", "")
        val amount = yuanToCents(amountStr)
        return CsvTransaction(date, time, type, amount, description, "支付宝")
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        for (char in line) {
            when {
                char == '"' -> inQuotes = !inQuotes
                char == ',' && !inQuotes -> {
                    result.add(current.toString())
                    current = StringBuilder()
                }
                else -> current.append(char)
            }
        }
        result.add(current.toString())
        return result
    }

    private fun yuanToCents(yuan: String): Long {
        return runCatching {
            BigDecimal(yuan).multiply(BigDecimal(100)).toLong()
        }.getOrDefault(0L)
    }
}
