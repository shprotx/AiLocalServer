package kz.shprot

import kz.shprot.models.*
import kotlinx.serialization.json.*

/**
 * Сервис для автоматизированного code review PR через MCP и LLM
 *
 * Использует:
 * - MCP GitHub для получения diff и файлов из PR
 * - RAG для контекста из документации проекта
 * - LLM для анализа кода и генерации ревью
 */
class CodeReviewService(
    private val mcpManager: SimpleMcpManager,
    private val llmClient: YandexLLMClient,
    private val ragManager: RAGManager
) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Выполняет code review для указанного PR
     */
    suspend fun reviewPullRequest(
        owner: String,
        repo: String,
        pullNumber: Int,
        useRAG: Boolean = true,
        temperature: Double = 0.3
    ): CodeReviewResult {
        val startTime = System.currentTimeMillis()
        println("🔍 Начинаю code review для $owner/$repo PR #$pullNumber")

        // 1. Получаем информацию о PR через MCP
        val prInfo = getPullRequestInfo(owner, repo, pullNumber)
        println("📋 PR: ${prInfo.title} by ${prInfo.author}")

        // 2. Получаем diff PR через MCP
        val diff = getPullRequestDiff(owner, repo, pullNumber)
        println("📝 Получен diff: ${diff.length} символов")

        // 3. Получаем список измененных файлов
        val changedFiles = getPullRequestFiles(owner, repo, pullNumber)
        println("📁 Изменено файлов: ${changedFiles.size}")

        // 4. Опционально: получаем контекст из RAG (документация проекта)
        var ragContext: String? = null
        var ragSources: List<String>? = null

        if (useRAG) {
            val ragQuery = buildRAGQuery(prInfo, changedFiles)
            val ragResult = ragManager.augmentPromptWithKnowledgeDetailed(
                userQuery = ragQuery,
                originalMessages = emptyList(),
                config = RAGManager.RAGConfig(useReranking = true)
            )
            if (ragResult.ragUsed) {
                ragContext = ragResult.ragContext
                ragSources = ragResult.sources.map { it.filename }
                println("📚 RAG контекст: ${ragContext?.length ?: 0} символов из ${ragSources?.size ?: 0} источников")
            }
        }

        // 5. Формируем промпт для LLM
        val reviewPrompt = buildReviewPrompt(prInfo, diff, changedFiles, ragContext)

        // 6. Отправляем на анализ в LLM
        println("🤖 Отправляю на анализ в LLM...")
        val messages = listOf(
            Message("system", CODE_REVIEW_SYSTEM_PROMPT),
            Message("user", reviewPrompt)
        )

        val llmResponse = llmClient.sendMessageWithHistoryAndUsage(
            messages = messages,
            temperature = temperature
        )

        // 7. Парсим структурированный ответ
        val reviewResult = parseReviewResponse(
            response = llmResponse.response.message,
            owner = owner,
            repo = repo,
            pullNumber = pullNumber,
            prInfo = prInfo,
            ragUsed = ragContext != null,
            ragSources = ragSources,
            reviewTime = System.currentTimeMillis() - startTime
        )

        println("✅ Code review завершен за ${reviewResult.reviewTime}ms")
        println("   Оценка: ${reviewResult.overallScore}/10")
        println("   Найдено проблем: ${reviewResult.issues.size}")
        println("   Рекомендация: ${reviewResult.recommendation}")

        return reviewResult
    }

    /**
     * Получает информацию о PR через MCP GitHub
     */
    private suspend fun getPullRequestInfo(owner: String, repo: String, pullNumber: Int): PRInfoInternal {
        val result = mcpManager.callTool(
            toolName = "pull_request_read",
            arguments = mapOf(
                "method" to "get",
                "owner" to owner,
                "repo" to repo,
                "pullNumber" to pullNumber
            )
        )

        // Парсим JSON ответ от GitHub API
        val jsonResponse = json.parseToJsonElement(result).jsonObject

        return PRInfoInternal(
            number = pullNumber,
            title = jsonResponse["title"]?.jsonPrimitive?.content ?: "Unknown",
            author = jsonResponse["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "Unknown",
            state = jsonResponse["state"]?.jsonPrimitive?.content ?: "unknown",
            additions = jsonResponse["additions"]?.jsonPrimitive?.intOrNull ?: 0,
            deletions = jsonResponse["deletions"]?.jsonPrimitive?.intOrNull ?: 0,
            changedFiles = jsonResponse["changed_files"]?.jsonPrimitive?.intOrNull ?: 0,
            baseRef = jsonResponse["base"]?.jsonObject?.get("ref")?.jsonPrimitive?.content ?: "main",
            headRef = jsonResponse["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.content ?: "unknown",
            body = jsonResponse["body"]?.jsonPrimitive?.content ?: ""
        )
    }

    /**
     * Получает diff PR через MCP GitHub
     */
    private suspend fun getPullRequestDiff(owner: String, repo: String, pullNumber: Int): String {
        return mcpManager.callTool(
            toolName = "pull_request_read",
            arguments = mapOf(
                "method" to "get_diff",
                "owner" to owner,
                "repo" to repo,
                "pullNumber" to pullNumber
            )
        )
    }

    /**
     * Получает список измененных файлов через MCP GitHub
     */
    private suspend fun getPullRequestFiles(owner: String, repo: String, pullNumber: Int): List<ChangedFile> {
        val result = mcpManager.callTool(
            toolName = "pull_request_read",
            arguments = mapOf(
                "method" to "get_files",
                "owner" to owner,
                "repo" to repo,
                "pullNumber" to pullNumber
            )
        )

        return runCatching {
            val jsonArray = json.parseToJsonElement(result).jsonArray
            jsonArray.map { file ->
                val fileObj = file.jsonObject
                ChangedFile(
                    filename = fileObj["filename"]?.jsonPrimitive?.content ?: "",
                    status = fileObj["status"]?.jsonPrimitive?.content ?: "",
                    additions = fileObj["additions"]?.jsonPrimitive?.intOrNull ?: 0,
                    deletions = fileObj["deletions"]?.jsonPrimitive?.intOrNull ?: 0,
                    patch = fileObj["patch"]?.jsonPrimitive?.content
                )
            }
        }.getOrElse { emptyList() }
    }

    /**
     * Формирует запрос для RAG на основе информации о PR
     */
    private fun buildRAGQuery(prInfo: PRInfoInternal, changedFiles: List<ChangedFile>): String {
        val fileTypes = changedFiles.map { it.filename.substringAfterLast('.') }.distinct()
        val directories = changedFiles.map { it.filename.substringBeforeLast('/') }.distinct().take(5)

        return """
            Code review для PR: ${prInfo.title}
            Изменены файлы типов: ${fileTypes.joinToString(", ")}
            В директориях: ${directories.joinToString(", ")}
            Описание: ${prInfo.body.take(200)}
        """.trimIndent()
    }

    /**
     * Формирует промпт для LLM на основе всей информации
     */
    private fun buildReviewPrompt(
        prInfo: PRInfoInternal,
        diff: String,
        changedFiles: List<ChangedFile>,
        ragContext: String?
    ): String {
        val filesInfo = changedFiles.joinToString("\n") { file ->
            "- ${file.filename} (${file.status}): +${file.additions}/-${file.deletions}"
        }

        val contextSection = if (ragContext != null) {
            """

            === КОНТЕКСТ ИЗ ДОКУМЕНТАЦИИ ПРОЕКТА ===
            $ragContext
            === КОНЕЦ КОНТЕКСТА ===
            """.trimIndent()
        } else ""

        // Ограничиваем diff чтобы не превысить контекстное окно
        val truncatedDiff = if (diff.length > 15000) {
            diff.take(15000) + "\n\n... (diff сокращен, показано первые 15000 символов)"
        } else diff

        return """
            # Pull Request для Review

            **PR #${prInfo.number}**: ${prInfo.title}
            **Автор**: ${prInfo.author}
            **Ветка**: ${prInfo.headRef} → ${prInfo.baseRef}
            **Изменения**: +${prInfo.additions}/-${prInfo.deletions} в ${prInfo.changedFiles} файлах

            ## Описание PR
            ${prInfo.body.ifEmpty { "(описание отсутствует)" }}

            ## Измененные файлы
            $filesInfo
            $contextSection

            ## Diff
            ```diff
            $truncatedDiff
            ```

            Выполни детальный code review этого PR. Проанализируй код на:
            1. Потенциальные баги и логические ошибки
            2. Проблемы безопасности
            3. Проблемы производительности
            4. Нарушения code style и best practices
            5. Возможности для улучшения

            Ответь СТРОГО в JSON формате согласно системному промпту.
        """.trimIndent()
    }

    /**
     * Парсит ответ LLM и формирует структурированный результат
     */
    private fun parseReviewResponse(
        response: String,
        owner: String,
        repo: String,
        pullNumber: Int,
        prInfo: PRInfoInternal,
        ragUsed: Boolean,
        ragSources: List<String>?,
        reviewTime: Long
    ): CodeReviewResult {
        return runCatching {
            // Пробуем распарсить как JSON
            val cleanedResponse = cleanJsonResponse(response)
            val jsonResponse = json.parseToJsonElement(cleanedResponse).jsonObject

            val issues = jsonResponse["issues"]?.jsonArray?.map { issue ->
                val issueObj = issue.jsonObject
                CodeIssue(
                    severity = issueObj["severity"]?.jsonPrimitive?.content ?: "info",
                    category = issueObj["category"]?.jsonPrimitive?.content ?: "other",
                    file = issueObj["file"]?.jsonPrimitive?.content ?: "",
                    line = issueObj["line"]?.jsonPrimitive?.intOrNull,
                    endLine = issueObj["endLine"]?.jsonPrimitive?.intOrNull,
                    title = issueObj["title"]?.jsonPrimitive?.content ?: "",
                    description = issueObj["description"]?.jsonPrimitive?.content ?: "",
                    suggestion = issueObj["suggestion"]?.jsonPrimitive?.content,
                    codeSnippet = issueObj["codeSnippet"]?.jsonPrimitive?.content
                )
            } ?: emptyList()

            val positives = jsonResponse["positives"]?.jsonArray?.map {
                it.jsonPrimitive.content
            } ?: emptyList()

            CodeReviewResult(
                owner = owner,
                repo = repo,
                pullNumber = pullNumber,
                prTitle = prInfo.title,
                prAuthor = prInfo.author,
                filesChanged = prInfo.changedFiles,
                additions = prInfo.additions,
                deletions = prInfo.deletions,
                summary = jsonResponse["summary"]?.jsonPrimitive?.content ?: "Ревью выполнено",
                issues = issues,
                positives = positives,
                overallScore = jsonResponse["overallScore"]?.jsonPrimitive?.intOrNull ?: 5,
                recommendation = jsonResponse["recommendation"]?.jsonPrimitive?.content ?: "comment",
                ragUsed = ragUsed,
                ragSources = ragSources,
                reviewTime = reviewTime
            )
        }.getOrElse {
            // Fallback - создаем базовый результат из текстового ответа
            println("⚠️ Не удалось распарсить JSON, используем fallback: ${it.message}")

            CodeReviewResult(
                owner = owner,
                repo = repo,
                pullNumber = pullNumber,
                prTitle = prInfo.title,
                prAuthor = prInfo.author,
                filesChanged = prInfo.changedFiles,
                additions = prInfo.additions,
                deletions = prInfo.deletions,
                summary = response.take(500),
                issues = emptyList(),
                positives = emptyList(),
                overallScore = 5,
                recommendation = "comment",
                ragUsed = ragUsed,
                ragSources = ragSources,
                reviewTime = reviewTime
            )
        }
    }

    /**
     * Очищает JSON от markdown-обёрток
     */
    private fun cleanJsonResponse(response: String): String {
        var cleaned = response.trim()

        // Удаляем markdown блоки
        if (cleaned.startsWith("```json")) {
            cleaned = cleaned.removePrefix("```json")
        }
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.removePrefix("```")
        }
        if (cleaned.endsWith("```")) {
            cleaned = cleaned.removeSuffix("```")
        }

        return cleaned.trim()
    }

    /**
     * Постит результат ревью как комментарий к PR
     */
    suspend fun postReviewComment(
        owner: String,
        repo: String,
        pullNumber: Int,
        review: CodeReviewResult
    ): Long? {
        val commentBody = formatReviewAsMarkdown(review)

        return runCatching {
            val result = mcpManager.callTool(
                toolName = "add_issue_comment",
                arguments = mapOf(
                    "owner" to owner,
                    "repo" to repo,
                    "issue_number" to pullNumber,
                    "body" to commentBody
                )
            )

            // Парсим ответ для получения ID комментария
            val jsonResponse = json.parseToJsonElement(result).jsonObject
            jsonResponse["id"]?.jsonPrimitive?.longOrNull
        }.getOrNull()
    }

    /**
     * Постит line comments к конкретным строкам кода
     */
    suspend fun postLineComments(
        owner: String,
        repo: String,
        pullNumber: Int,
        review: CodeReviewResult
    ) {
        // Создаем pending review
        runCatching {
            mcpManager.callTool(
                toolName = "pull_request_review_write",
                arguments = mapOf(
                    "method" to "create",
                    "owner" to owner,
                    "repo" to repo,
                    "pullNumber" to pullNumber
                )
            )
        }

        // Добавляем комментарии к конкретным строкам
        review.issues.filter { it.line != null && it.file.isNotEmpty() }.forEach { issue ->
            runCatching {
                mcpManager.callTool(
                    toolName = "add_comment_to_pending_review",
                    arguments = mapOf(
                        "owner" to owner,
                        "repo" to repo,
                        "pullNumber" to pullNumber,
                        "path" to issue.file,
                        "line" to issue.line!!,
                        "body" to formatIssueAsComment(issue),
                        "side" to "RIGHT",
                        "subjectType" to "LINE"
                    )
                )
                println("💬 Добавлен комментарий к ${issue.file}:${issue.line}")
            }.onFailure {
                println("⚠️ Не удалось добавить комментарий к ${issue.file}:${issue.line}: ${it.message}")
            }
        }

        // Отправляем review
        val event = when (review.recommendation) {
            "approve" -> "APPROVE"
            "request_changes" -> "REQUEST_CHANGES"
            else -> "COMMENT"
        }

        runCatching {
            mcpManager.callTool(
                toolName = "pull_request_review_write",
                arguments = mapOf(
                    "method" to "submit_pending",
                    "owner" to owner,
                    "repo" to repo,
                    "pullNumber" to pullNumber,
                    "event" to event,
                    "body" to "🤖 Автоматический code review\n\nОценка: ${review.overallScore}/10"
                )
            )
        }
    }

    /**
     * Форматирует результат ревью как Markdown комментарий
     */
    private fun formatReviewAsMarkdown(review: CodeReviewResult): String {
        val issuesByPriority = review.issues.groupBy { it.severity }

        val criticalSection = formatIssuesSection("🔴 Критические проблемы", issuesByPriority["critical"])
        val warningSection = formatIssuesSection("🟠 Предупреждения", issuesByPriority["warning"])
        val suggestionSection = formatIssuesSection("🟡 Предложения", issuesByPriority["suggestion"])
        val infoSection = formatIssuesSection("🔵 Информация", issuesByPriority["info"])

        val positivesSection = if (review.positives.isNotEmpty()) {
            """

            ## ✅ Положительные аспекты
            ${review.positives.joinToString("\n") { "- $it" }}
            """.trimIndent()
        } else ""

        val ragSection = if (review.ragUsed && !review.ragSources.isNullOrEmpty()) {
            """

            ---
            <details>
            <summary>📚 Использованные источники из документации</summary>

            ${review.ragSources.joinToString("\n") { "- `$it`" }}
            </details>
            """.trimIndent()
        } else ""

        return """
            # 🤖 Автоматический Code Review

            ## 📊 Сводка

            | Параметр | Значение |
            |----------|----------|
            | **Оценка** | ${review.overallScore}/10 ${getScoreEmoji(review.overallScore)} |
            | **Рекомендация** | ${formatRecommendation(review.recommendation)} |
            | **Файлов изменено** | ${review.filesChanged} |
            | **Изменений** | +${review.additions}/-${review.deletions} |
            | **Найдено проблем** | ${review.issues.size} |

            ${review.summary}
            $criticalSection
            $warningSection
            $suggestionSection
            $infoSection
            $positivesSection
            $ragSection

            ---
            *Ревью выполнено за ${review.reviewTime}ms с помощью [AiLocalServer](https://github.com/arturshprot/AiLocalServer)*
        """.trimIndent()
    }

    private fun formatIssuesSection(title: String, issues: List<CodeIssue>?): String {
        if (issues.isNullOrEmpty()) return ""

        val issuesText = issues.joinToString("\n\n") { issue ->
            val location = if (issue.line != null) {
                "`${issue.file}:${issue.line}${if (issue.endLine != null) "-${issue.endLine}" else ""}`"
            } else {
                "`${issue.file}`"
            }

            val snippetBlock = if (!issue.codeSnippet.isNullOrEmpty()) {
                "\n```\n${issue.codeSnippet}\n```"
            } else ""

            val suggestionBlock = if (!issue.suggestion.isNullOrEmpty()) {
                "\n\n💡 **Предложение**: ${issue.suggestion}"
            } else ""

            """
            ### ${issue.title}
            📍 $location | 🏷️ ${issue.category}

            ${issue.description}$snippetBlock$suggestionBlock
            """.trimIndent()
        }

        return """

            ## $title

            $issuesText
        """.trimIndent()
    }

    private fun formatIssueAsComment(issue: CodeIssue): String {
        val severityEmoji = when (issue.severity) {
            "critical" -> "🔴"
            "warning" -> "🟠"
            "suggestion" -> "🟡"
            else -> "🔵"
        }

        val suggestion = if (!issue.suggestion.isNullOrEmpty()) {
            "\n\n💡 **Предложение**: ${issue.suggestion}"
        } else ""

        return """
            $severityEmoji **${issue.title}** | `${issue.category}`

            ${issue.description}$suggestion
        """.trimIndent()
    }

    private fun getScoreEmoji(score: Int): String = when {
        score >= 9 -> "🌟"
        score >= 7 -> "✅"
        score >= 5 -> "⚠️"
        score >= 3 -> "🟠"
        else -> "🔴"
    }

    private fun formatRecommendation(recommendation: String): String = when (recommendation) {
        "approve" -> "✅ Approve"
        "request_changes" -> "🔄 Request Changes"
        else -> "💬 Comment"
    }

    /**
     * Получает список открытых PR для репозитория
     */
    suspend fun listPullRequests(owner: String, repo: String, state: String = "open"): List<PRInfo> {
        val result = mcpManager.callTool(
            toolName = "list_pull_requests",
            arguments = mapOf(
                "owner" to owner,
                "repo" to repo,
                "state" to state
            )
        )

        return runCatching {
            val jsonArray = json.parseToJsonElement(result).jsonArray
            jsonArray.map { pr ->
                val prObj = pr.jsonObject
                PRInfo(
                    number = prObj["number"]?.jsonPrimitive?.intOrNull ?: 0,
                    title = prObj["title"]?.jsonPrimitive?.content ?: "",
                    author = prObj["user"]?.jsonObject?.get("login")?.jsonPrimitive?.content ?: "",
                    state = prObj["state"]?.jsonPrimitive?.content ?: "",
                    createdAt = prObj["created_at"]?.jsonPrimitive?.content ?: "",
                    updatedAt = prObj["updated_at"]?.jsonPrimitive?.content ?: "",
                    filesChanged = prObj["changed_files"]?.jsonPrimitive?.intOrNull ?: 0,
                    additions = prObj["additions"]?.jsonPrimitive?.intOrNull ?: 0,
                    deletions = prObj["deletions"]?.jsonPrimitive?.intOrNull ?: 0,
                    baseRef = prObj["base"]?.jsonObject?.get("ref")?.jsonPrimitive?.content ?: "",
                    headRef = prObj["head"]?.jsonObject?.get("ref")?.jsonPrimitive?.content ?: "",
                    url = prObj["html_url"]?.jsonPrimitive?.content ?: ""
                )
            }
        }.getOrElse { emptyList() }
    }

    // Внутренние модели данных
    private data class PRInfoInternal(
        val number: Int,
        val title: String,
        val author: String,
        val state: String,
        val additions: Int,
        val deletions: Int,
        val changedFiles: Int,
        val baseRef: String,
        val headRef: String,
        val body: String
    )

    private data class ChangedFile(
        val filename: String,
        val status: String,
        val additions: Int,
        val deletions: Int,
        val patch: String?
    )

    companion object {
        /**
         * System prompt для LLM при выполнении code review
         */
        private val CODE_REVIEW_SYSTEM_PROMPT = """
            Ты - опытный senior разработчик, выполняющий code review.
            Твоя задача - внимательно проанализировать diff и найти:

            1. **Баги и логические ошибки** - некорректная логика, edge cases, race conditions
            2. **Проблемы безопасности** - SQL injection, XSS, CSRF, утечки данных, небезопасные операции
            3. **Проблемы производительности** - N+1 запросы, утечки памяти, неэффективные алгоритмы
            4. **Code style** - нарушения конвенций, нечитаемый код, отсутствие документации
            5. **Best practices** - анти-паттерны, нарушения SOLID, дублирование кода

            ВАЖНО:
            - Анализируй ТОЛЬКО измененный код (строки с + в diff)
            - Указывай точные номера строк где возможно
            - Предлагай конкретные исправления
            - Отмечай также положительные аспекты кода

            ОТВЕТ СТРОГО В JSON ФОРМАТЕ:
            {
                "summary": "Краткое резюме ревью в 2-3 предложениях",
                "issues": [
                    {
                        "severity": "critical|warning|suggestion|info",
                        "category": "bug|security|performance|style|logic|best-practice",
                        "file": "путь/к/файлу.kt",
                        "line": 42,
                        "endLine": 45,
                        "title": "Краткое название проблемы",
                        "description": "Подробное описание проблемы и почему это важно",
                        "suggestion": "Конкретное предложение по исправлению",
                        "codeSnippet": "проблемный код если нужно показать"
                    }
                ],
                "positives": [
                    "Положительный аспект 1",
                    "Положительный аспект 2"
                ],
                "overallScore": 7,
                "recommendation": "approve|request_changes|comment"
            }

            Правила оценки:
            - 9-10: Отличный код, готов к мержу
            - 7-8: Хороший код, minor issues
            - 5-6: Нормальный код, требует доработки
            - 3-4: Проблемный код, серьезные issues
            - 1-2: Критические проблемы, требует переработки

            Рекомендации:
            - approve: оценка >= 8 и нет critical/warning
            - request_changes: есть critical issues или оценка < 5
            - comment: во всех остальных случаях
        """.trimIndent()
    }
}
