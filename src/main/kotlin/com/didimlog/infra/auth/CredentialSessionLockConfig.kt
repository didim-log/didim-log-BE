package com.didimlog.infra.auth

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

const val CREDENTIAL_SESSION_RENEWAL_EXECUTOR = "credentialSessionRenewalExecutor"

@Configuration
class CredentialSessionLockConfig {

    @Bean(name = [CREDENTIAL_SESSION_RENEWAL_EXECUTOR], destroyMethod = "shutdown")
    fun credentialSessionRenewalExecutor(): ScheduledExecutorService {
        return Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "credential-session-renewal").apply {
                isDaemon = true
            }
        }
    }
}
