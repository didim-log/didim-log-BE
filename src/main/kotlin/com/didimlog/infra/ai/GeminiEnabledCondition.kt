package com.didimlog.infra.ai

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

class GeminiEnabledCondition : Condition {

    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val apiKey = context.environment.getProperty("ai.gemini.api-key").orEmpty()
        val url = context.environment.getProperty("ai.gemini.url").orEmpty()
        return apiKey.isNotBlank() && url.isNotBlank()
    }
}

