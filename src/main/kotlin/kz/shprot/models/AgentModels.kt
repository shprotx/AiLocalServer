package kz.shprot.models

import kotlinx.serialization.Serializable

/**
 * Агент-специалист для обработки сложных вопросов
 */
@Serializable
data class Agent(
    val id: String,
    val role: String,
    val systemPrompt: String,
    val temperature: Double = 0.6,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Информация о специалисте, который должен быть создан
 */
@Serializable
data class SpecialistInfo(
    val role: String,
    val specialization: String,
    val reason: String
)

/**
 * Результат анализа вопроса координатором
 */
@Serializable
data class AgentAnalysis(
    val needsSpecialists: Boolean,
    val complexity: String, // "simple", "medium", "complex"
    val specialists: List<SpecialistInfo>,
    val reasoning: String
)

/**
 * Ответ от одного агента-специалиста
 */
@Serializable
data class AgentResponse(
    val agentId: String,
    val agentRole: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Финальный ответ от multi-agent системы
 */
@Serializable
data class MultiAgentResponse(
    val isMultiAgent: Boolean,
    val agentResponses: List<AgentResponse>,
    val synthesis: String,
    val title: String
)
