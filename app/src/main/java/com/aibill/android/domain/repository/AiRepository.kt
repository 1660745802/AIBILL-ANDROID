package com.aibill.android.domain.repository

import com.aibill.android.domain.model.AiParseResult
import com.aibill.android.domain.model.Result

interface AiRepository {

    suspend fun parseInput(input: String): Result<List<AiParseResult>>
}
