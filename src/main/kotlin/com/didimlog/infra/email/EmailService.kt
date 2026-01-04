package com.didimlog.infra.email

import jakarta.mail.internet.MimeMessage
import org.springframework.mail.SimpleMailMessage
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Service
import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine

/**
 * 이메일 발송 서비스
 * JavaMailSender와 Thymeleaf를 사용하여 텍스트 및 HTML 이메일을 발송한다.
 */
@Service
class EmailService(
    private val mailSender: JavaMailSender,
    private val templateEngine: SpringTemplateEngine
) {

    /**
     * 텍스트 이메일을 발송한다.
     *
     * @param to 수신자 이메일 주소
     * @param subject 이메일 제목
     * @param text 이메일 본문
     */
    fun sendEmail(to: String, subject: String, text: String) {
        val message = SimpleMailMessage()
        message.setTo(to)
        message.setSubject(subject)
        message.setText(text)
        mailSender.send(message)
    }

    /**
     * Thymeleaf 템플릿을 사용하여 HTML 이메일을 발송한다.
     *
     * @param to 수신자 이메일 주소
     * @param subject 이메일 제목
     * @param templateName 템플릿 파일명 (예: "mail/find-id")
     * @param variables 템플릿에 전달할 변수 맵
     */
    fun sendTemplateEmail(
        to: String,
        subject: String,
        templateName: String,
        variables: Map<String, Any>
    ) {
        val message: MimeMessage = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(message, true, "UTF-8")

        val context = Context().apply {
            variables.forEach { (key, value) ->
                setVariable(key, value)
            }
        }

        val htmlContent = templateEngine.process(templateName, context)

        helper.setTo(to)
        helper.setSubject(subject)
        helper.setText(htmlContent, true)

        mailSender.send(message)
    }
}












