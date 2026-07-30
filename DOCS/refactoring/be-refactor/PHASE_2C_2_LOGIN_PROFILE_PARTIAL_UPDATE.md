# Phase 2C-2 — 로그인 프로필 부분 갱신

## 문제

로그인은 비밀번호를 확인한 뒤 solved.ac에서 최신 rating을 조회하고, 처음 조회한
`Student`를 복사해 전체 문서를 다시 저장했다.

```text
Student 조회·비밀번호 확인
→ solved.ac 응답 대기
→ 다른 요청이 비밀번호 재설정
→ 로그인 요청이 이전 Student 전체 저장
→ 새 비밀번호가 이전 값으로 되돌아감
```

로그인 외에도 BOJ 프로필 수동 동기화와 문제 서비스의 티어 동기화가 같은 방식으로
전체 문서를 저장했다. 프로필 동기화에 필요하지 않은 비밀번호, 닉네임, 풀이 기록까지
이전 조회 값으로 덮어쓸 수 있었다.

## 변경

프로필 갱신은 학생 `_id`를 조건으로 다음 세 필드만 한 번에 `$set`한다.

| 필드 | 값 |
| --- | --- |
| `rating` | solved.ac 최신 rating |
| `solvedAcTierLevel` | rating에서 계산한 solved.ac 단계 |
| `currentTier` | rating에서 계산한 서비스 티어 |

MongoDB `findAndModify`에 `returnNew=true`, `upsert=false`를 적용했다. 성공 시 갱신 뒤
문서를 반환하므로 API 응답도 저장된 프로필을 기준으로 만든다. 갱신 도중 학생이
삭제되면 새 문서를 만들지 않는다. 로그인과 수동 동기화는 `STUDENT_NOT_FOUND`로
중단하며, 반환값이 없는 문제 서비스의 동기화는 기존 계약대로 조용히 종료한다.

solved.ac 호출 장애는 계정 삭제와 구분한다. 외부 API만 실패한 경우에는 기존 프로필로
로그인을 계속하지만, MongoDB에서 계정 부재가 확인된 경우에는 Access Token과 Refresh
Token을 발급하지 않는다.

로그인, BOJ 프로필 수동 동기화, 문제 서비스 티어 동기화가 같은 저장 메서드를
사용한다. rating이 같더라도 두 파생 티어 필드가 맞지 않으면 세 필드를 함께 바로잡는다.

비밀번호 재설정은 이미 password 필드만 부분 갱신한다. 반대 방향의 변경과 실패 경계는
[비밀번호 재설정 코드 원자적 소비](./PHASE_2B_2_PASSWORD_RESET_ATOMIC_CONSUME.md)에
정리했다.

## 경합 검증

실제 MongoDB에서 다음 순서를 래치로 고정했다.

```text
로그인 요청이 기존 비밀번호 확인
→ solved.ac 응답 직전에 대기
→ 비밀번호 재설정 완료
→ 로그인 요청의 프로필 갱신 재개
```

수정 전에는 마지막 전체 저장이 재설정된 비밀번호를 되돌려 테스트가 실패했다. 수정
후에는 새 비밀번호가 유지되고 rating과 두 티어 필드만 최신 값으로 바뀐다. 닉네임과
학생 문서 수는 변하지 않는다.

없는 학생 ID에 부분 갱신을 요청했을 때 반환값이 `null`이고 새 문서가 만들어지지 않는
것도 별도로 확인했다.

## 검증

```bash
./gradlew test \
  --tests 'com.didimlog.application.auth.AuthServiceTest' \
  --tests 'com.didimlog.application.auth.AuthServiceLoginSecurityTest' \
  --tests 'com.didimlog.application.student.StudentServiceTest' \
  --tests 'com.didimlog.application.problem.ProblemServiceTest' \
  --no-daemon

./gradlew integrationTest \
  --tests 'com.didimlog.application.auth.PasswordResetConsistencyIntegrationTest' \
  --no-daemon
```

| 항목 | 결과 |
| --- | --- |
| 관련 단위 테스트 | 40개 통과 |
| 비밀번호 재설정 정합성 통합 테스트 | 8개 통과 |
| 전체 단위 테스트 | 525개 통과 |
| 전체 통합 테스트 | 88개 중 81개 통과, 조건부 테스트 7개 제외 |
| JaCoCo gate | core-v1, full-v1 모두 통과 |
| 수정 전 경합 재현 | 새 비밀번호 유지 검증 실패 |
| 수정 후 최종 상태 | 새 비밀번호와 최신 rating·tier 동시 유지 |
| 없는 ID 갱신 | `null` 반환, 문서 생성 없음 |

이 단계는 처리량을 높인 작업이 아니라 동시 실행 뒤 최종 필드 상태를 바로잡은 작업이다.
따라서 응답 시간이나 성능 향상률은 기록하지 않는다.

## 남은 범위

- 다른 `Student` 전체 저장 경로의 경합 여부 점검
- 동시에 도착한 여러 solved.ac 응답의 저장 순서 정책
- 완료: [Phase 2E — 비밀번호 변경과 토큰 소유자 고정](./PHASE_2E_PASSWORD_CHANGE_SESSION_REVOCATION.md)
