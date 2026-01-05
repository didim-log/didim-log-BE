# 회고(Retrospective) 데이터 보관 기간 정책

## 📋 개요

회고 데이터는 작성일(`createdAt`)로부터 **180일(6개월)**이 지나면 자동으로 삭제됩니다.

## 🔧 구현 방식

### 스케줄러 기반 자동 삭제

MongoDB를 사용하고 있으며, `LocalDateTime` 타입을 사용하므로 **스케줄러 방식**을 사용합니다.

- **서비스**: `RetrospectiveCleanupService`
- **실행 시간**: 매일 새벽 3시 (`@Scheduled(cron = "0 0 3 * * *")`)
- **삭제 기준**: `createdAt`이 현재 시각에서 보관 기간(기본 180일) 이전인 회고

### MongoDB TTL 인덱스 (참고)

MongoDB TTL 인덱스는 `Date` 타입만 지원하므로, 현재 `LocalDateTime`을 사용하는 구조에서는 적용할 수 없습니다.

만약 TTL 인덱스를 사용하려면:
1. `Retrospective` 엔티티에 `Date` 타입의 필드 추가
2. `@Indexed(expireAfterSeconds = 15552000)` 어노테이션 적용
   - 180일 = 180 * 24 * 60 * 60 = 15,552,000초

## ⚙️ 설정

### application.yaml

```yaml
app:
  retrospective:
    retention-days: ${RETROSPECTIVE_RETENTION_DAYS:180}  # 기본값: 180일
```

### 환경 변수

환경 변수 `RETROSPECTIVE_RETENTION_DAYS`를 설정하여 보관 기간을 변경할 수 있습니다.

```bash
export RETROSPECTIVE_RETENTION_DAYS=90  # 90일로 변경
```

## 🔍 검증 방법

### 1. 스케줄러 로그 확인

애플리케이션 로그에서 다음 메시지를 확인할 수 있습니다:

```
자동 회고 정리 완료: 180일 이상 된 회고 5개 삭제 (보관 기간: 180일)
```

### 2. 수동 정리 테스트

관리자 API 또는 서비스 메서드를 통해 수동으로 정리할 수 있습니다:

```kotlin
val deletedCount = retrospectiveCleanupService.cleanupRetrospectives(olderThanDays = 180)
```

### 3. MongoDB 쿼리로 확인

MongoDB에서 직접 확인:

```javascript
// 180일 이전에 생성된 회고 개수 확인
db.retrospectives.count({
  createdAt: { $lt: ISODate(new Date(Date.now() - 180 * 24 * 60 * 60 * 1000)) }
})

// 180일 이전에 생성된 회고 조회
db.retrospectives.find({
  createdAt: { $lt: ISODate(new Date(Date.now() - 180 * 24 * 60 * 60 * 1000)) }
})
```

### 4. 테스트 코드 실행

```bash
./gradlew test --tests "*RetrospectiveCleanupServiceTest*"
```

## 📝 관련 파일

- **서비스**: `src/main/kotlin/com/didimlog/application/retrospective/RetrospectiveCleanupService.kt`
- **Repository**: `src/main/kotlin/com/didimlog/domain/repository/RetrospectiveRepository.kt`
- **설정**: `src/main/resources/application.yaml`
- **테스트**: `src/test/kotlin/com/didimlog/application/retrospective/RetrospectiveCleanupServiceTest.kt`

## ⚠️ 주의사항

1. **데이터 복구 불가**: 삭제된 회고는 복구할 수 없습니다.
2. **스케줄러 실행 시간**: 매일 새벽 3시에 실행되므로, 정확히 180일 후에 삭제되는 것이 아니라 다음 스케줄러 실행 시점에 삭제됩니다.
3. **보관 기간 변경**: 보관 기간을 변경하면 다음 스케줄러 실행 시점부터 새로운 정책이 적용됩니다.

