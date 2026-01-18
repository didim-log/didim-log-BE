package com.didimlog.domain.template

import com.didimlog.domain.enums.TemplateOwnershipType
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.mongodb.core.mapping.Document
import java.time.LocalDateTime

/**
 * 회고 템플릿 도메인 객체
 * 사용자가 회고 작성을 위해 사용할 수 있는 템플릿을 표현한다.
 *
 * @property studentId 템플릿 소유자 ID (SYSTEM 템플릿의 경우 null)
 * @property title 템플릿 이름
 * @property content 템플릿 내용 (마크다운, 매크로 포함)
 * @property type 템플릿 소유권 타입 (SYSTEM, CUSTOM)
 * @property isDefaultSuccess 성공용 기본 템플릿 여부 (Deprecated: Use Student entity's defaultSuccessTemplateId instead)
 * @property isDefaultFail 실패용 기본 템플릿 여부 (Deprecated: Use Student entity's defaultFailTemplateId instead)
 */
@Document(collection = "templates")
data class Template(
    @Id
    val id: String? = null,
    val studentId: String? = null,
    val title: String,
    val content: String,
    val type: TemplateOwnershipType,
    @Deprecated("Use Student entity's defaultSuccessTemplateId instead. TODO: Drop this column in future migration.")
    val isDefaultSuccess: Boolean = false,
    @Deprecated("Use Student entity's defaultFailTemplateId instead. TODO: Drop this column in future migration.")
    val isDefaultFail: Boolean = false,
    @CreatedDate
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @LastModifiedDate
    val updatedAt: LocalDateTime = LocalDateTime.now()
) {
    init {
        require(title.isNotBlank()) { "템플릿 제목은 필수입니다." }
        require(title.length <= 100) { "템플릿 제목은 100자 이하여야 합니다." }
        require(content.isNotBlank()) { "템플릿 내용은 필수입니다." }
        require(content.length <= 10000) { "템플릿 내용은 10000자 이하여야 합니다." }
        if (type == TemplateOwnershipType.SYSTEM) {
            require(studentId == null) { "시스템 템플릿은 소유자를 가질 수 없습니다." }
        }
        if (type == TemplateOwnershipType.CUSTOM) {
            require(studentId != null) { "커스텀 템플릿은 소유자가 필요합니다." }
        }
    }

    /**
     * 템플릿 내용을 업데이트한다.
     * 시스템 템플릿은 수정할 수 없다.
     *
     * @param newTitle 새로운 제목
     * @param newContent 새로운 내용
     * @return 업데이트된 템플릿
     * @throws IllegalArgumentException 시스템 템플릿인 경우
     */
    fun update(newTitle: String, newContent: String): Template {
        if (type == TemplateOwnershipType.SYSTEM) {
            throw IllegalArgumentException("시스템 템플릿은 수정할 수 없습니다.")
        }
        require(newTitle.isNotBlank()) { "템플릿 제목은 필수입니다." }
        require(newTitle.length <= 100) { "템플릿 제목은 100자 이하여야 합니다." }
        require(newContent.isNotBlank()) { "템플릿 내용은 필수입니다." }
        require(newContent.length <= 10000) { "템플릿 내용은 10000자 이하여야 합니다." }
        
        return copy(
            title = newTitle,
            content = newContent,
            updatedAt = LocalDateTime.now()
        )
    }

    /**
     * 템플릿을 성공용 기본값으로 설정한다.
     * 
     * @deprecated 이제 기본 템플릿은 Student 엔티티가 관리합니다. TemplateService.setDefaultTemplate을 사용하세요.
     * @return 성공용 기본값으로 설정된 템플릿
     */
    @Deprecated("Use Student entity's defaultSuccessTemplateId instead. This method is no longer used in business logic.")
    fun setAsDefaultSuccess(): Template {
        if (type == TemplateOwnershipType.SYSTEM) {
            throw IllegalArgumentException("시스템 템플릿은 기본 템플릿으로 설정할 수 없습니다.")
        }
        return copy(isDefaultSuccess = true, updatedAt = LocalDateTime.now())
    }

    /**
     * 템플릿의 성공용 기본값을 해제한다.
     * 
     * @deprecated 이제 기본 템플릿은 Student 엔티티가 관리합니다. 이 메서드는 더 이상 사용되지 않습니다.
     * @return 성공용 기본값 해제된 템플릿
     */
    @Deprecated("Use Student entity's defaultSuccessTemplateId instead. This method is no longer used in business logic.")
    fun unsetDefaultSuccess(): Template {
        return copy(isDefaultSuccess = false, updatedAt = LocalDateTime.now())
    }

    /**
     * 템플릿을 실패용 기본값으로 설정한다.
     * 
     * @deprecated 이제 기본 템플릿은 Student 엔티티가 관리합니다. TemplateService.setDefaultTemplate을 사용하세요.
     * @return 실패용 기본값으로 설정된 템플릿
     */
    @Deprecated("Use Student entity's defaultFailTemplateId instead. This method is no longer used in business logic.")
    fun setAsDefaultFail(): Template {
        if (type == TemplateOwnershipType.SYSTEM) {
            throw IllegalArgumentException("시스템 템플릿은 기본 템플릿으로 설정할 수 없습니다.")
        }
        return copy(isDefaultFail = true, updatedAt = LocalDateTime.now())
    }

    /**
     * 템플릿의 실패용 기본값을 해제한다.
     * 
     * @deprecated 이제 기본 템플릿은 Student 엔티티가 관리합니다. 이 메서드는 더 이상 사용되지 않습니다.
     * @return 실패용 기본값 해제된 템플릿
     */
    @Deprecated("Use Student entity's defaultFailTemplateId instead. This method is no longer used in business logic.")
    fun unsetDefaultFail(): Template {
        return copy(isDefaultFail = false, updatedAt = LocalDateTime.now())
    }

    /**
     * 템플릿의 소유자인지 확인한다.
     *
     * @param studentId 확인할 학생 ID
     * @return 소유자이면 true, 그렇지 않으면 false
     */
    fun isOwner(studentId: String): Boolean {
        if (type == TemplateOwnershipType.SYSTEM) {
            return false
        }
        return this.studentId == studentId
    }

    /**
     * 템플릿의 소유자인지 검증한다.
     * 소유자가 아니면 예외를 발생시킨다.
     *
     * @param studentId 확인할 학생 ID
     * @throws IllegalArgumentException 소유자가 아닌 경우
     */
    fun validateOwner(studentId: String) {
        if (!isOwner(studentId)) {
            throw IllegalArgumentException("템플릿 소유자가 아닙니다. studentId=$studentId")
        }
    }
}