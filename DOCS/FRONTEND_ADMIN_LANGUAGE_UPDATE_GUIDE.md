# 프론트엔드 관리자 페이지 언어 최신화 기능 추가 가이드

## 📋 개요

백엔드에서 문제 언어 정보 최신화 API가 추가되었습니다. 관리자 페이지에 이 기능을 추가하여 기존 문제들의 언어 정보를 일괄 업데이트할 수 있도록 해야 합니다.

## 🎯 추가해야 할 기능

### 1. 관리자 페이지에 "문제 언어 정보 최신화" 버튼 추가

**위치:** 관리자 페이지의 문제 관리 섹션 (Problem Management)

**UI 구성:**
- 버튼: "문제 언어 정보 최신화" 또는 "언어 정보 일괄 업데이트"
- 설명 텍스트: "DB에 저장된 모든 문제의 언어 정보를 재판별하여 업데이트합니다. (소요 시간: 문제 수에 따라 수 분 ~ 수십 분)"
- 로딩 상태 표시
- 진행 상황 표시 (선택사항)

## 📡 API 연동

### 1. 언어 정보 최신화 시작 API

**엔드포인트:**
```typescript
POST /api/v1/admin/problems/update-language
Authorization: Bearer {ADMIN_TOKEN}
```

**요청 예시:**
```typescript
// TypeScript/React 예시
const startLanguageUpdate = async () => {
  try {
    const response = await fetch('/api/v1/admin/problems/update-language', {
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${adminToken}`,
        'Content-Type': 'application/json'
      }
    });

    if (!response.ok) {
      throw new Error('언어 정보 최신화 시작 실패');
    }

    const data = await response.json();
    console.log(`작업 시작: jobId=${data.jobId}`);
    return data;
  } catch (error) {
    console.error('언어 정보 최신화 시작 중 오류:', error);
    throw error;
  }
};
```

**응답 구조:**
```typescript
interface UpdateLanguageStartResponse {
  message: string;  // "문제 언어 정보 최신화 작업이 시작되었습니다."
  jobId: string;    // 작업 ID (작업 상태 조회에 사용)
}
```

### 2. 작업 상태 조회 API

**엔드포인트:**
```typescript
GET /api/v1/admin/problems/update-language/status/{jobId}
Authorization: Bearer {ADMIN_TOKEN}
```

**요청 예시:**
```typescript
const getLanguageUpdateStatus = async (jobId: string) => {
  try {
    const response = await fetch(`/api/v1/admin/problems/update-language/status/${jobId}`, {
      method: 'GET',
      headers: {
        'Authorization': `Bearer ${adminToken}`,
        'Content-Type': 'application/json'
      }
    });

    if (response.status === 404) {
      return null; // 작업을 찾을 수 없음
    }

    if (!response.ok) {
      throw new Error('작업 상태 조회 실패');
    }

    const data = await response.json();
    return data;
  } catch (error) {
    console.error('작업 상태 조회 중 오류:', error);
    throw error;
  }
};
```

**응답 구조:**
```typescript
interface LanguageUpdateStatusResponse {
  jobId: string;                    // 작업 ID
  status: string;                   // "PENDING" | "RUNNING" | "COMPLETED" | "FAILED"
  totalCount: number;               // 전체 문제 수
  processedCount: number;           // 처리된 문제 수
  successCount: number;             // 성공한 문제 수
  failCount: number;                // 실패한 문제 수
  progressPercentage: number;       // 진행률 (0~100)
  estimatedRemainingSeconds: number | null;  // 예상 남은 시간 (초)
  startedAt: number;                // 작업 시작 시간 (Unix timestamp)
  completedAt: number | null;       // 작업 완료 시간 (Unix timestamp)
  errorMessage: string | null;      // 에러 메시지 (실패 시)
}
```

## 🎨 UI/UX 권장사항

### 1. 버튼 디자인

```tsx
// React 예시
<Button
  variant="outlined"
  color="primary"
  onClick={handleUpdateLanguages}
  disabled={isUpdating}
  startIcon={isUpdating ? <CircularProgress size={20} /> : <UpdateIcon />}
>
  {isUpdating ? '업데이트 중...' : '문제 언어 정보 최신화'}
</Button>
```

### 2. 확인 다이얼로그

**중요:** 이 작업은 시간이 오래 걸릴 수 있으므로 확인 다이얼로그를 표시하는 것을 권장합니다.

```tsx
const handleUpdateLanguages = () => {
  const confirmed = window.confirm(
    '모든 문제의 언어 정보를 업데이트하시겠습니까?\n\n' +
    '이 작업은 시간이 오래 걸릴 수 있습니다.\n' +
    '(문제 수에 따라 수 분 ~ 수십 분 소요)'
  );

  if (confirmed) {
    startUpdate();
  }
};
```

### 3. 로딩 상태 및 진행 상황 관리

```tsx
const [isUpdating, setIsUpdating] = useState(false);
const [jobId, setJobId] = useState<string | null>(null);
const [updateStatus, setUpdateStatus] = useState<LanguageUpdateStatusResponse | null>(null);
const [pollingInterval, setPollingInterval] = useState<NodeJS.Timeout | null>(null);

const startUpdate = async () => {
  setIsUpdating(true);
  setUpdateStatus(null);

  try {
    // 작업 시작
    const result = await startLanguageUpdate();
    setJobId(result.jobId);
    
    // 상태 폴링 시작 (5초마다)
    const interval = setInterval(async () => {
      const status = await getLanguageUpdateStatus(result.jobId);
      if (status) {
        setUpdateStatus(status);
        
        // 완료 또는 실패 시 폴링 중지
        if (status.status === 'COMPLETED' || status.status === 'FAILED') {
          clearInterval(interval);
          setIsUpdating(false);
          
          if (status.status === 'COMPLETED') {
            showSuccessMessage(
              `언어 정보가 성공적으로 업데이트되었습니다. (${status.successCount}개 성공, ${status.failCount}개 실패)`
            );
          } else {
            showErrorMessage(`언어 정보 업데이트에 실패했습니다: ${status.errorMessage}`);
          }
        }
      }
    }, 5000);
    
    setPollingInterval(interval);
    
    // 초기 상태 조회
    const initialStatus = await getLanguageUpdateStatus(result.jobId);
    if (initialStatus) {
      setUpdateStatus(initialStatus);
    }
  } catch (error) {
    setIsUpdating(false);
    showErrorMessage('언어 정보 업데이트 시작에 실패했습니다.');
  }
};

// 컴포넌트 언마운트 시 폴링 중지
useEffect(() => {
  return () => {
    if (pollingInterval) {
      clearInterval(pollingInterval);
    }
  };
}, [pollingInterval]);
```

### 4. 진행 상황 표시

**비동기 처리:** 작업을 백그라운드에서 실행하므로, 진행 상황을 실시간으로 조회할 수 있습니다.

```tsx
{isUpdating && updateStatus && (
  <Box sx={{ mt: 2 }}>
    <LinearProgress 
      variant="determinate" 
      value={updateStatus.progressPercentage} 
    />
    <Typography variant="body2" color="text.secondary" sx={{ mt: 1 }}>
      진행률: {updateStatus.progressPercentage}% ({updateStatus.processedCount}/{updateStatus.totalCount})
    </Typography>
    <Typography variant="body2" color="text.secondary">
      성공: {updateStatus.successCount}개 | 실패: {updateStatus.failCount}개
    </Typography>
    {updateStatus.estimatedRemainingSeconds && (
      <Typography variant="caption" color="text.secondary">
        예상 남은 시간: 약 {Math.floor(updateStatus.estimatedRemainingSeconds / 60)}분
      </Typography>
    )}
    <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
      이 작업은 시간이 오래 걸릴 수 있습니다. 페이지를 닫지 마세요.
    </Typography>
  </Box>
)}

{updateStatus?.status === 'COMPLETED' && (
  <Alert severity="success" sx={{ mt: 2 }}>
    업데이트 완료: {updateStatus.successCount}개 성공, {updateStatus.failCount}개 실패
  </Alert>
)}

{updateStatus?.status === 'FAILED' && (
  <Alert severity="error" sx={{ mt: 2 }}>
    업데이트 실패: {updateStatus.errorMessage}
  </Alert>
)}
```

## 📍 배치 위치 권장사항

### 관리자 페이지 구조 예시

```
관리자 대시보드
├── 회원 관리
├── 문제 관리
│   ├── 문제 메타데이터 수집
│   ├── 문제 상세 정보 크롤링
│   ├── 문제 통계 조회
│   └── 문제 언어 정보 최신화  ← 여기에 추가
├── 공지사항 관리
└── ...
```

### 문제 관리 섹션 예시

```tsx
<Card>
  <CardHeader title="문제 데이터 관리" />
  <CardContent>
    <Grid container spacing={2}>
      {/* 기존 기능들 */}
      <Grid item xs={12} md={6}>
        <Button onClick={handleCollectMetadata}>
          메타데이터 수집
        </Button>
      </Grid>
      <Grid item xs={12} md={6}>
        <Button onClick={handleCollectDetails}>
          상세 정보 크롤링
        </Button>
      </Grid>
      
      {/* 새로 추가할 기능 */}
      <Grid item xs={12} md={6}>
        <Button 
          onClick={handleUpdateLanguages}
          disabled={isUpdating}
          variant="outlined"
          color="primary"
        >
          {isUpdating ? '업데이트 중...' : '언어 정보 최신화'}
        </Button>
        {updateProgress && (
          <Typography variant="caption" color="text.secondary">
            {updateProgress}
          </Typography>
        )}
      </Grid>
    </Grid>
  </CardContent>
</Card>
```

## ⚠️ 주의사항

### 1. 작업 시간

- **소요 시간:** 문제 수에 따라 수 분 ~ 수십 분 소요
- **Rate Limiting:** 각 요청 사이에 2~4초 간격이 있으므로, 3400개 문제 기준 약 1.9~3.8시간 소요
- **비동기 처리:** 작업은 백그라운드에서 실행되므로, HTTP 타임아웃 걱정 없이 사용 가능
- **권장사항:** 작업 시작 전 사용자에게 소요 시간을 안내하고, 진행 상황을 실시간으로 표시

### 2. 에러 처리

```tsx
try {
  const result = await startLanguageUpdate();
  // 작업 시작 성공, jobId 저장 후 상태 폴링 시작
} catch (error) {
  if (error.response?.status === 401) {
    // 인증 오류
    showError('인증이 필요합니다. 다시 로그인해주세요.');
  } else if (error.response?.status === 403) {
    // 권한 오류
    showError('관리자 권한이 필요합니다.');
  } else if (error.response?.status === 500) {
    // 서버 오류
    showError('서버 오류가 발생했습니다. 잠시 후 다시 시도해주세요.');
  } else {
    // 기타 오류
    showError('언어 정보 업데이트 시작에 실패했습니다.');
  }
}
```

### 3. 작업 상태 조회 에러 처리

```tsx
const status = await getLanguageUpdateStatus(jobId);
if (status === null) {
  // 작업을 찾을 수 없음 (404)
  showWarning('작업을 찾을 수 없습니다. 작업이 만료되었거나 잘못된 jobId입니다.');
} else if (status.status === 'FAILED') {
  // 작업 실패
  showError(`작업이 실패했습니다: ${status.errorMessage}`);
}
```

### 4. 사용자 경험

- **비동기 처리:** 작업을 백그라운드에서 실행하므로 즉시 응답 반환 (타임아웃 없음)
- **진행 상황 표시:** 5초마다 상태를 조회하여 실시간 진행률 표시
- **예상 시간 표시:** 남은 문제 수와 평균 처리 시간을 기반으로 예상 남은 시간 계산
- **취소 불가:** 현재 API는 배치 작업이므로 중간에 취소할 수 없음 (향후 개선 가능)
- **결과 표시:** 업데이트 완료 후 성공/실패 수를 명확히 표시

## 🧪 테스트 체크리스트

- [ ] 관리자 권한으로 로그인했을 때 버튼이 표시되는지 확인
- [ ] 일반 사용자로 로그인했을 때 버튼이 표시되지 않는지 확인
- [ ] 버튼 클릭 시 확인 다이얼로그가 표시되는지 확인
- [ ] 작업 시작 API 호출 시 jobId가 반환되는지 확인
- [ ] 작업 상태 조회 API가 정상 동작하는지 확인
- [ ] 진행 상황이 실시간으로 업데이트되는지 확인 (5초마다 폴링)
- [ ] 진행률 바가 올바르게 표시되는지 확인
- [ ] 예상 남은 시간이 표시되는지 확인
- [ ] 작업 완료 시 성공/실패 수가 표시되는지 확인
- [ ] 작업 실패 시 에러 메시지가 표시되는지 확인
- [ ] 네트워크 오류 시 적절한 에러 처리가 되는지 확인
- [ ] 페이지를 닫아도 작업이 계속 진행되는지 확인 (백그라운드 작업)

## 📝 구현 예시 코드

### 전체 컴포넌트 예시 (React + TypeScript)

```tsx
import React, { useState, useEffect } from 'react';
import {
  Button,
  Card,
  CardContent,
  CardHeader,
  CircularProgress,
  Typography,
  Box,
  Alert,
  LinearProgress
} from '@mui/material';
import UpdateIcon from '@mui/icons-material/Update';

interface UpdateLanguageStartResponse {
  message: string;
  jobId: string;
}

interface LanguageUpdateStatusResponse {
  jobId: string;
  status: string;
  totalCount: number;
  processedCount: number;
  successCount: number;
  failCount: number;
  progressPercentage: number;
  estimatedRemainingSeconds: number | null;
  startedAt: number;
  completedAt: number | null;
  errorMessage: string | null;
}

const ProblemLanguageUpdate: React.FC = () => {
  const [isUpdating, setIsUpdating] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [updateStatus, setUpdateStatus] = useState<LanguageUpdateStatusResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pollingInterval, setPollingInterval] = useState<NodeJS.Timeout | null>(null);

  const getLanguageUpdateStatus = async (jobId: string): Promise<LanguageUpdateStatusResponse | null> => {
    try {
      const token = localStorage.getItem('adminToken');
      const response = await fetch(`/api/v1/admin/problems/update-language/status/${jobId}`, {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        }
      });

      if (response.status === 404) {
        return null;
      }

      if (!response.ok) {
        throw new Error('작업 상태 조회 실패');
      }

      return await response.json();
    } catch (err) {
      console.error('작업 상태 조회 중 오류:', err);
      return null;
    }
  };

  const handleUpdate = async () => {
    const confirmed = window.confirm(
      '모든 문제의 언어 정보를 업데이트하시겠습니까?\n\n' +
      '이 작업은 시간이 오래 걸릴 수 있습니다.\n' +
      '(문제 수에 따라 수 분 ~ 수십 분 소요)'
    );

    if (!confirmed) return;

    setIsUpdating(true);
    setError(null);
    setUpdateStatus(null);

    try {
      const token = localStorage.getItem('adminToken');
      const response = await fetch('/api/v1/admin/problems/update-language', {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${token}`,
          'Content-Type': 'application/json'
        }
      });

      if (!response.ok) {
        if (response.status === 401) {
          throw new Error('인증이 필요합니다.');
        } else if (response.status === 403) {
          throw new Error('관리자 권한이 필요합니다.');
        } else {
          throw new Error('언어 정보 업데이트 시작에 실패했습니다.');
        }
      }

      const data: UpdateLanguageStartResponse = await response.json();
      setJobId(data.jobId);

      // 상태 폴링 시작 (5초마다)
      const interval = setInterval(async () => {
        const status = await getLanguageUpdateStatus(data.jobId);
        if (status) {
          setUpdateStatus(status);

          // 완료 또는 실패 시 폴링 중지
          if (status.status === 'COMPLETED' || status.status === 'FAILED') {
            clearInterval(interval);
            setIsUpdating(false);
            setPollingInterval(null);
          }
        }
      }, 5000);

      setPollingInterval(interval);

      // 초기 상태 조회
      const initialStatus = await getLanguageUpdateStatus(data.jobId);
      if (initialStatus) {
        setUpdateStatus(initialStatus);
      }
    } catch (err) {
      setIsUpdating(false);
      setError(err instanceof Error ? err.message : '알 수 없는 오류가 발생했습니다.');
    }
  };

  // 컴포넌트 언마운트 시 폴링 중지
  useEffect(() => {
    return () => {
      if (pollingInterval) {
        clearInterval(pollingInterval);
      }
    };
  }, [pollingInterval]);

  return (
    <Card>
      <CardHeader title="문제 언어 정보 관리" />
      <CardContent>
        <Box sx={{ mb: 2 }}>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            DB에 저장된 모든 문제의 언어 정보를 재판별하여 업데이트합니다.
            기존 크롤링 데이터는 유지하고 language 필드만 업데이트합니다.
            작업은 백그라운드에서 실행되며, 진행 상황을 실시간으로 확인할 수 있습니다.
          </Typography>
          
          <Button
            variant="outlined"
            color="primary"
            onClick={handleUpdate}
            disabled={isUpdating}
            startIcon={isUpdating ? <CircularProgress size={20} /> : <UpdateIcon />}
          >
            {isUpdating ? '업데이트 중...' : '문제 언어 정보 최신화'}
          </Button>
        </Box>

        {isUpdating && updateStatus && (
          <Box sx={{ mt: 2 }}>
            <LinearProgress 
              variant="determinate" 
              value={updateStatus.progressPercentage} 
              sx={{ mb: 1 }}
            />
            <Typography variant="body2" color="text.secondary">
              진행률: {updateStatus.progressPercentage}% ({updateStatus.processedCount}/{updateStatus.totalCount})
            </Typography>
            <Typography variant="body2" color="text.secondary">
              성공: {updateStatus.successCount}개 | 실패: {updateStatus.failCount}개
            </Typography>
            {updateStatus.estimatedRemainingSeconds && (
              <Typography variant="caption" color="text.secondary">
                예상 남은 시간: 약 {Math.floor(updateStatus.estimatedRemainingSeconds / 60)}분
              </Typography>
            )}
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              이 작업은 시간이 오래 걸릴 수 있습니다. 페이지를 닫지 마세요.
            </Typography>
          </Box>
        )}

        {updateStatus?.status === 'COMPLETED' && (
          <Alert severity="success" sx={{ mt: 2 }}>
            업데이트 완료: {updateStatus.successCount}개 성공, {updateStatus.failCount}개 실패
          </Alert>
        )}

        {updateStatus?.status === 'FAILED' && (
          <Alert severity="error" sx={{ mt: 2 }}>
            업데이트 실패: {updateStatus.errorMessage}
          </Alert>
        )}

        {error && (
          <Alert severity="error" sx={{ mt: 2 }}>
            {error}
          </Alert>
        )}
      </CardContent>
    </Card>
  );
};

export default ProblemLanguageUpdate;
```

## 🔗 관련 API 문서

- **API 명세서:** `DOCS/API_SPECIFICATION.md`의 `ProblemCollectorController` 섹션 참조
- **엔드포인트:** `POST /api/v1/admin/problems/update-language`
- **권한:** ADMIN 권한 필요

## 📌 추가 고려사항

### 향후 개선 가능한 기능

1. **진행 상황 표시:** WebSocket이나 Server-Sent Events를 사용하여 실시간 진행 상황 표시
2. **부분 업데이트:** 특정 문제 ID 범위만 업데이트하는 기능
3. **예약 실행:** 특정 시간에 자동으로 실행하는 기능
4. **업데이트 이력:** 언제 마지막으로 업데이트되었는지 표시

### 현재 제한사항

- 배치 작업이 완료될 때까지 응답이 없음 (장시간 대기)
- 중간에 취소할 수 없음
- 진행 상황을 실시간으로 확인할 수 없음

이러한 제한사항은 향후 백엔드 개선을 통해 해결할 수 있습니다.

