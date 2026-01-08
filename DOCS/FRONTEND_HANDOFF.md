# 프론트엔드 연동 가이드 - BOJ 크롤링 API

이 문서는 BOJ 크롤링 관련 API의 변경사항과 프론트엔드에서 고려해야 할 사항을 정리한 가이드입니다.

## 주요 변경사항

### 1. Resumable Crawling (이어하기 기능)

크롤링 작업이 중단되어도 마지막 처리 지점부터 이어서 진행할 수 있는 기능이 추가되었습니다.

- **Checkpoint 자동 저장**: 크롤링 중 10개마다 자동으로 checkpoint가 저장됩니다.
- **자동 재개**: 같은 API를 다시 호출하면 checkpoint부터 자동으로 이어서 진행합니다.
- **완료 시 자동 삭제**: 작업이 완료되면 checkpoint가 자동으로 삭제됩니다.

### 2. API 변경사항

#### 메타데이터 수집 API (`POST /api/v1/admin/problems/collect-metadata`)

**변경 전:**
- 항상 `start` 파라미터부터 시작

**변경 후:**
- checkpoint가 있으면 checkpoint 다음 ID부터 자동으로 시작
- `start` 파라미터는 checkpoint가 없을 때만 사용됨

**예시:**
```typescript
// 첫 번째 호출: 1000~2000 범위
POST /api/v1/admin/problems/collect-metadata?start=1000&end=2000
// → 1000번부터 시작

// 중단 후 재시작: 같은 범위로 호출
POST /api/v1/admin/problems/collect-metadata?start=1000&end=2000
// → checkpoint(예: 1500) 다음부터 자동으로 시작 (1501~2000)
```

#### 상세 정보 수집 API (`POST /api/v1/admin/problems/collect-details`)

**변경 전:**
- 항상 모든 `descriptionHtml`이 null인 문제부터 시작

**변경 후:**
- checkpoint가 있으면 checkpoint 다음 문제 ID부터 시작
- checkpoint가 없으면 모든 null 문제부터 시작

**예시:**
```typescript
// 첫 번째 호출
POST /api/v1/admin/problems/collect-details
// → 모든 descriptionHtml이 null인 문제 처리

// 중단 후 재시작
POST /api/v1/admin/problems/collect-details
// → checkpoint 다음 문제부터 자동으로 이어서 진행
```

#### 언어 정보 업데이트 API (`POST /api/v1/admin/problems/update-language`)

**변경 전:**
- 항상 모든 언어가 null이거나 "other"인 문제부터 시작

**변경 후:**
- checkpoint가 있으면 checkpoint 다음 문제 ID부터 시작
- checkpoint가 없으면 모든 대상 문제부터 시작

## 프론트엔드 구현 가이드

### 1. 크롤링 작업 시작

기존과 동일하게 API를 호출하면 됩니다. checkpoint는 백엔드에서 자동으로 처리됩니다.

```typescript
// 메타데이터 수집 시작
const response = await fetch('/api/v1/admin/problems/collect-metadata?start=1000&end=2000', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`
  }
});

const { jobId } = await response.json();
```

### 2. 작업 상태 조회

기존과 동일하게 상태 조회 API를 사용합니다.

```typescript
// 작업 상태 조회
const statusResponse = await fetch(`/api/v1/admin/problems/collect-metadata/status/${jobId}`, {
  headers: {
    'Authorization': `Bearer ${token}`
  }
});

const status = await statusResponse.json();
// status.processedCount: 처리된 개수
// status.totalCount: 전체 개수
// status.progressPercentage: 진행률 (0~100)
```

### 3. 중단 후 재시작

**중요:** 작업이 중단되어도 checkpoint가 저장되어 있으므로, **같은 API를 다시 호출**하면 자동으로 checkpoint부터 이어서 진행됩니다.

```typescript
// 중단된 작업 재시작 (같은 파라미터로 호출)
const restartResponse = await fetch('/api/v1/admin/problems/collect-metadata?start=1000&end=2000', {
  method: 'POST',
  headers: {
    'Authorization': `Bearer ${token}`
  }
});

const { jobId: newJobId } = await restartResponse.json();
// 새로운 jobId가 발급되지만, checkpoint부터 이어서 진행됨
```

### 4. 예외 처리

#### Timeout 처리

크롤링 작업은 오래 걸릴 수 있으므로, 프론트엔드에서 타임아웃을 설정하지 마세요. 대신 주기적으로 상태를 조회하여 진행 상황을 표시하세요.

```typescript
// ❌ 잘못된 예: 타임아웃 설정
const controller = new AbortController();
setTimeout(() => controller.abort(), 30000); // 30초 타임아웃

// ✅ 올바른 예: 주기적 상태 조회
const pollStatus = async (jobId: string) => {
  const interval = setInterval(async () => {
    const status = await fetchStatus(jobId);
    
    if (status.status === 'COMPLETED' || status.status === 'FAILED') {
      clearInterval(interval);
      // 완료 처리
    }
    
    // 진행률 업데이트
    updateProgress(status.progressPercentage);
  }, 2000); // 2초마다 조회
};
```

#### 에러 응답 처리

작업이 실패하면 `status.status === 'FAILED'`가 되고 `errorMessage`에 에러 메시지가 포함됩니다.

```typescript
const status = await fetchStatus(jobId);

if (status.status === 'FAILED') {
  console.error('작업 실패:', status.errorMessage);
  // 사용자에게 알림 표시
  showError(`크롤링 작업이 실패했습니다: ${status.errorMessage}`);
}
```

#### 404 에러 (작업을 찾을 수 없음)

작업 상태는 24시간 후 자동으로 삭제됩니다. 24시간이 지난 작업 ID로 조회하면 404가 반환됩니다.

```typescript
try {
  const status = await fetchStatus(jobId);
} catch (error) {
  if (error.status === 404) {
    // 작업이 만료되었거나 존재하지 않음
    showError('작업을 찾을 수 없습니다. 새로 시작해주세요.');
  }
}
```

### 5. UI/UX 권장사항

#### 진행률 표시

작업 상태 조회 API의 `progressPercentage`를 사용하여 진행률을 표시하세요.

```typescript
const status = await fetchStatus(jobId);

// 진행률 표시
<ProgressBar value={status.progressPercentage} />

// 통계 표시
<div>
  처리됨: {status.processedCount} / {status.totalCount}
  성공: {status.successCount}
  실패: {status.failCount}
</div>

// 예상 남은 시간 표시
{status.estimatedRemainingSeconds && (
  <div>예상 남은 시간: {formatSeconds(status.estimatedRemainingSeconds)}</div>
)}
```

#### 재시작 안내

작업이 중단되었을 때 사용자에게 재시작 가능하다는 것을 알려주세요.

```typescript
if (status.status === 'FAILED') {
  return (
    <div>
      <p>작업이 중단되었습니다.</p>
      <p>같은 범위로 다시 호출하면 중단 지점부터 이어서 진행됩니다.</p>
      <button onClick={handleRestart}>재시작</button>
    </div>
  );
}
```

## 주의사항

1. **Checkpoint는 자동 관리됨**: 프론트엔드에서 checkpoint를 직접 관리할 필요가 없습니다.
2. **작업 ID는 새로 발급됨**: 재시작 시에도 새로운 `jobId`가 발급되지만, checkpoint는 자동으로 적용됩니다.
3. **완료 시 checkpoint 삭제**: 작업이 완료되면 checkpoint가 자동으로 삭제되므로, 완료된 작업을 재시작하면 처음부터 다시 시작됩니다.
4. **상태 조회 주기**: 너무 자주 조회하지 마세요. 권장 주기는 2~5초입니다.

## 예제 코드

전체 예제는 아래와 같습니다:

```typescript
class CrawlingService {
  async startMetadataCollection(start: number, end: number): Promise<string> {
    const response = await fetch(
      `/api/v1/admin/problems/collect-metadata?start=${start}&end=${end}`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${this.token}`
        }
      }
    );
    
    if (!response.ok) {
      throw new Error('작업 시작 실패');
    }
    
    const data = await response.json();
    return data.jobId;
  }

  async getStatus(jobId: string, type: 'metadata' | 'details' | 'language') {
    const endpoint = {
      metadata: `/api/v1/admin/problems/collect-metadata/status/${jobId}`,
      details: `/api/v1/admin/problems/collect-details/status/${jobId}`,
      language: `/api/v1/admin/problems/update-language/status/${jobId}`
    }[type];

    const response = await fetch(endpoint, {
      headers: {
        'Authorization': `Bearer ${this.token}`
      }
    });

    if (response.status === 404) {
      return null; // 작업을 찾을 수 없음
    }

    if (!response.ok) {
      throw new Error('상태 조회 실패');
    }

    return await response.json();
  }

  async pollStatus(
    jobId: string,
    type: 'metadata' | 'details' | 'language',
    onUpdate: (status: any) => void
  ) {
    const interval = setInterval(async () => {
      const status = await this.getStatus(jobId, type);
      
      if (!status) {
        clearInterval(interval);
        onUpdate({ error: '작업을 찾을 수 없습니다.' });
        return;
      }

      onUpdate(status);

      if (status.status === 'COMPLETED' || status.status === 'FAILED') {
        clearInterval(interval);
      }
    }, 2000);

    return () => clearInterval(interval);
  }
}
```

## 문의

추가 질문이나 문제가 있으면 백엔드 팀에 문의해주세요.

