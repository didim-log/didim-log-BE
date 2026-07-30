package com.didimlog.application.auth

class ImmediateCredentialSessionCoordinator : CredentialSessionCoordinator {
    override fun <T> execute(studentId: String, action: () -> T): T = action()

    override fun <T> executeWithCompletionCheck(studentId: String, action: () -> T): T = action()
}
