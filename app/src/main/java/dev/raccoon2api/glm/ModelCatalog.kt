package dev.raccoon2api.glm

/** 对外暴露的模型条目。 */
data class GlmModel(
    val id: String,
    val name: String,
    val description: String,
    val ownedBy: String = "zhipu"
)

/**
 * 模型目录。
 *
 * 智谱清言网页版通过 assistant_id 区分智能体，这里把常用能力映射成
 * OpenAI 风格的模型名，方便客户端下拉选择；选中后按 assistant_id 解析。
 */
object ModelCatalog {

    /** 网页版默认「GLM」通用助手。 */
    const val DEFAULT_ASSISTANT_ID = GlmClient.DEFAULT_ASSISTANT_ID

    private val models = listOf(
        GlmModel("GLM-4", "GLM-4", "智谱清言通用助手（默认）"),
        GlmModel("GLM-4-Think", "GLM-4 沉思", "带思考过程的推理模式"),
        GlmModel("GLM-4-Zero", "GLM-4 Zero", "Zero 推理模式"),
        GlmModel("GLM-4-DeepResearch", "GLM-4 深度研究", "深度检索与长报告"),
        GlmModel("GLM-4-AllTools", "GLM-4 全工具", "启用联网与工具能力"),
        GlmModel("glm-4-flash", "GLM-4-Flash", "高速轻量"),
        GlmModel("glm-4-plus", "GLM-4-Plus", "增强版"),
        GlmModel("glm-4-air", "GLM-4-Air", "均衡版"),
        GlmModel("glm-4-long", "GLM-4-Long", "长文本"),
        GlmModel("glm-4v", "GLM-4V", "多模态视觉")
    )

    fun list(): List<GlmModel> = models

    fun find(id: String): GlmModel? = models.firstOrNull { it.id == id }
}
