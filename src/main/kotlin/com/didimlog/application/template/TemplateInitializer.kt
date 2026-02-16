package com.didimlog.application.template

import com.didimlog.domain.enums.TemplateOwnershipType
import com.didimlog.domain.repository.TemplateRepository
import com.didimlog.domain.template.Template
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * 시스템 템플릿 초기화 컴포넌트
 * 애플리케이션 시작 시 시스템 템플릿을 생성한다.
 * Simple(요약)과 Detail(상세) 템플릿을 자동으로 초기화하여 신규 사용자도 바로 사용할 수 있도록 한다.
 */
@Component
class TemplateInitializer(
    private val templateRepository: TemplateRepository
) : ApplicationRunner {

    override fun run(args: ApplicationArguments?) {
        initializeSimpleTemplate()
        initializeDetailTemplate()
    }

    /**
     * 요약(Simple) 시스템 템플릿을 초기화한다.
     */
    private fun initializeSimpleTemplate() {
        val systemTemplates = templateRepository.findByType(TemplateOwnershipType.SYSTEM)
        val existingTemplate = systemTemplates
            .firstOrNull { it.isDefaultSuccess }
            ?: systemTemplates.firstOrNull { it.title == "Simple(요약)" }
        
        if (existingTemplate != null) {
            return
        }

        val simpleContent = """
# 🏆 [백준/BOJ] {{problemId}}번 {{problemTitle}} ({{language}}) {{result}} 회고

## 💡 핵심 로직

## 📝 오늘의 배움

        """.trimIndent()

        val simpleTemplate = Template(
            studentId = null,
            title = "Simple(요약)",
            content = simpleContent,
            type = TemplateOwnershipType.SYSTEM,
            isDefaultSuccess = true,
            isDefaultFail = false
        )

        templateRepository.save(simpleTemplate)
    }

    /**
     * 상세(Detail) 시스템 템플릿을 초기화한다.
     */
    private fun initializeDetailTemplate() {
        val systemTemplates = templateRepository.findByType(TemplateOwnershipType.SYSTEM)
        val existingTemplate = systemTemplates
            .firstOrNull { it.isDefaultFail }
            ?: systemTemplates.firstOrNull { it.title == "Detail(상세)" }
        
        if (existingTemplate != null) {
            return
        }

        val detailContent = """
# 🏆 [백준/BOJ] {{problemId}}번 {{problemTitle}} ({{language}}) {{result}} 회고

## 1. 접근 방법

## 2. 시간/공간 복잡도

## 3. 실패 원인 / 어려웠던 점

## 4. 개선할 점

        """.trimIndent()

        val detailTemplate = Template(
            studentId = null,
            title = "Detail(상세)",
            content = detailContent,
            type = TemplateOwnershipType.SYSTEM,
            isDefaultSuccess = false,
            isDefaultFail = true
        )

        templateRepository.save(detailTemplate)
    }
}
