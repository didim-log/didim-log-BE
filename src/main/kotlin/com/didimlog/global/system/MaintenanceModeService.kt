package com.didimlog.global.system

import org.springframework.stereotype.Service
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 유지보수 모드 서비스
 * 서버를 끄지 않고 일반 사용자의 접근만 차단하는 기능을 제공한다.
 */
@Service
class MaintenanceModeService {
    private val isMaintenanceMode = AtomicBoolean(false)

    /**
     * 유지보수 모드를 활성화/비활성화한다.
     *
     * @param enabled 활성화 여부
     */
    fun setMaintenanceMode(enabled: Boolean) {
        isMaintenanceMode.set(enabled)
    }

    /**
     * 유지보수 모드 활성화 여부를 확인한다.
     *
     * @return 활성화되어 있으면 true
     */
    fun isMaintenanceMode(): Boolean {
        return isMaintenanceMode.get()
    }
}


