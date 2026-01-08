# 프론트엔드 가이드: 문제 메타데이터 수집 API

## 📋 개요

문제 메타데이터 수집 API가 비동기 처리로 변경되었습니다. 대량 문제 처리 시 발생하는 HTTP 타임아웃 문제를 해결하고, 작업 진행 상황을 실시간으로 확인할 수 있습니다.

**참고:** 메타데이터 수집은 지정된 범위의 모든 문제를 처리합니다 (Upsert 방식). 상세 정보 크롤링과 언어 정보 업데이트는 업데이트 안된 문제만 처리합니다.

## 🎯 주요 변경사항

### Before (동기 처리)
- API 호출 후 모든 작업이 완료될 때까지 대기
- 1000개 문제 기준 약 8분 소요 → HTTP 타임아웃 발생 가능
- 작업 진행 상황 확인 불가

### After (비동기 처리)
- API 호출 즉시 `jobId` 반환
- 작업은 백그라운드에서 실행
- 작업 상태 조회 API로 진행 상황 실시간 확인 가능
- 타임아웃 걱정 없이 대량 문제 처리 가능

## 📡 API 연동

### 1. 메타데이터 수집 시작 API

**엔드포인트:**
```typescript
POST /api/v1/admin/problems/collect-metadata?start={start}&end={end}
Authorization: Bearer {ADMIN_TOKEN}
```

**요청 예시:**
```typescript
const startMetadataCollect = async (start: number, end: number) => {
  try {
    const response = await fetch(
      `/api/v1/admin/problems/collect-metadata?start=${start}&end=${end}`,
      {
        method: 'POST',
        headers: {
          'Authorization': `Bearer ${adminToken}`,
          'Content-Type': 'application/json'
        }
      }
    );

    if (!response.ok) {
      throw new Error('메타데이터 수집 시작 실패');
    }

    const data = await response.json();
    console.log(`작업 시작: jobId=${data.jobId}`);
    return data;
  } catch (error) {
    console.error('메타데이터 수집 시작 중 오류:', error);
    throw error;
  }
};
```

**응답 구조:**
```typescript
interface MetadataCollectStartResponse {
  message: string;  // "문제 메타데이터 수집 작업이 시작되었습니다."
  jobId: string;    // 작업 ID (작업 상태 조회에 사용)
  range: string;    // "start-end" 형식의 범위 문자열
}
```

### 2. 작업 상태 조회 API

**엔드포인트:**
```typescript
GET /api/v1/admin/problems/collect-metadata/status/{jobId}
Authorization: Bearer {ADMIN_TOKEN}
```

**요청 예시:**
```typescript
const getMetadataCollectStatus = async (jobId: string) => {
  try {
    const response = await fetch(
      `/api/v1/admin/problems/collect-metadata/status/${jobId}`,
      {
        method: 'GET',
        headers: {
          'Authorization': `Bearer ${adminToken}`,
          'Content-Type': 'application/json'
        }
      }
    );

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
interface MetadataCollectStatusResponse {
  jobId: string;                    // 작업 ID
  status: string;                   // "PENDING" | "RUNNING" | "COMPLETED" | "FAILED"
  totalCount: number;               // 전체 문제 수
  processedCount: number;           // 처리된 문제 수
  successCount: number;             // 성공한 문제 수
  failCount: number;                // 실패한 문제 수
  startProblemId: number;          // 시작 문제 ID
  endProblemId: number;            // 종료 문제 ID
  progressPercentage: number;      // 진행률 (0~100)
  estimatedRemainingSeconds: number | null;  // 예상 남은 시간 (초)
  startedAt: number;               // 작업 시작 시간 (Unix timestamp)
  completedAt: number | null;       // 작업 완료 시간 (Unix timestamp)
  errorMessage: string | null;      // 에러 메시지 (실패 시)
}
```

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
  LinearProgress,
  TextField
} from '@mui/material';
import CloudDownloadIcon from '@mui/icons-material/CloudDownload';

interface MetadataCollectStartResponse {
  message: string;
  jobId: string;
  range: string;
}

interface MetadataCollectStatusResponse {
  jobId: string;
  status: string;
  totalCount: number;
  processedCount: number;
  successCount: number;
  failCount: number;
  startProblemId: number;
  endProblemId: number;
  progressPercentage: number;
  estimatedRemainingSeconds: number | null;
  startedAt: number;
  completedAt: number | null;
  errorMessage: string | null;
}

const ProblemMetadataCollect: React.FC = () => {
  const [startId, setStartId] = useState<number>(1000);
  const [endId, setEndId] = useState<number>(1100);
  const [isCollecting, setIsCollecting] = useState(false);
  const [jobId, setJobId] = useState<string | null>(null);
  const [collectStatus, setCollectStatus] = useState<MetadataCollectStatusResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [pollingInterval, setPollingInterval] = useState<NodeJS.Timeout | null>(null);

  const getMetadataCollectStatus = async (jobId: string): Promise<MetadataCollectStatusResponse | null> => {
    try {
      const token = localStorage.getItem('adminToken');
      const response = await fetch(`/api/v1/admin/problems/collect-metadata/status/${jobId}`, {
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

  const handleCollect = async () => {
    const confirmed = window.confirm(
      `문제 ID ${startId}부터 ${endId}까지의 메타데이터를 수집하시겠습니까?\n\n` +
      `총 ${endId - startId + 1}개 문제를 수집합니다.\n` +
      `이 작업은 시간이 오래 걸릴 수 있습니다.`
    );

    if (!confirmed) return;

    setIsCollecting(true);
    setError(null);
    setCollectStatus(null);

    try {
      const token = localStorage.getItem('adminToken');
      const response = await fetch(
        `/api/v1/admin/problems/collect-metadata?start=${startId}&end=${endId}`,
        {
          method: 'POST',
          headers: {
            'Authorization': `Bearer ${token}`,
            'Content-Type': 'application/json'
          }
        }
      );

      if (!response.ok) {
        if (response.status === 401) {
          throw new Error('인증이 필요합니다.');
        } else if (response.status === 403) {
          throw new Error('관리자 권한이 필요합니다.');
        } else {
          throw new Error('메타데이터 수집 시작에 실패했습니다.');
        }
      }

      const data: MetadataCollectStartResponse = await response.json();
      setJobId(data.jobId);

      // 상태 폴링 시작 (5초마다)
      const interval = setInterval(async () => {
        const status = await getMetadataCollectStatus(data.jobId);
        if (status) {
          setCollectStatus(status);

          // 완료 또는 실패 시 폴링 중지
          if (status.status === 'COMPLETED' || status.status === 'FAILED') {
            clearInterval(interval);
            setIsCollecting(false);
            setPollingInterval(null);
          }
        }
      }, 5000);

      setPollingInterval(interval);

      // 초기 상태 조회
      const initialStatus = await getMetadataCollectStatus(data.jobId);
      if (initialStatus) {
        setCollectStatus(initialStatus);
      }
    } catch (err) {
      setIsCollecting(false);
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
      <CardHeader title="문제 메타데이터 수집" />
      <CardContent>
        <Box sx={{ mb: 2 }}>
          <Typography variant="body2" color="text.secondary" sx={{ mb: 2 }}>
            Solved.ac API를 통해 지정된 범위의 문제 메타데이터를 수집하여 DB에 저장합니다.
            작업은 백그라운드에서 실행되며, 진행 상황을 실시간으로 확인할 수 있습니다.
          </Typography>

          <Box sx={{ display: 'flex', gap: 2, mb: 2 }}>
            <TextField
              label="시작 문제 ID"
              type="number"
              value={startId}
              onChange={(e) => setStartId(Number(e.target.value))}
              disabled={isCollecting}
              inputProps={{ min: 1 }}
            />
            <TextField
              label="종료 문제 ID"
              type="number"
              value={endId}
              onChange={(e) => setEndId(Number(e.target.value))}
              disabled={isCollecting}
              inputProps={{ min: 1 }}
            />
          </Box>

          <Button
            variant="outlined"
            color="primary"
            onClick={handleCollect}
            disabled={isCollecting || startId < 1 || endId < startId}
            startIcon={isCollecting ? <CircularProgress size={20} /> : <CloudDownloadIcon />}
          >
            {isCollecting ? '수집 중...' : '메타데이터 수집 시작'}
          </Button>
        </Box>

        {isCollecting && collectStatus && (
          <Box sx={{ mt: 2 }}>
            <LinearProgress 
              variant="determinate" 
              value={collectStatus.progressPercentage} 
              sx={{ mb: 1 }}
            />
            <Typography variant="body2" color="text.secondary">
              진행률: {collectStatus.progressPercentage}% ({collectStatus.processedCount}/{collectStatus.totalCount})
            </Typography>
            <Typography variant="body2" color="text.secondary">
              범위: {collectStatus.startProblemId} ~ {collectStatus.endProblemId}
            </Typography>
            <Typography variant="body2" color="text.secondary">
              성공: {collectStatus.successCount}개 | 실패: {collectStatus.failCount}개
            </Typography>
            {collectStatus.estimatedRemainingSeconds && (
              <Typography variant="caption" color="text.secondary">
                예상 남은 시간: 약 {Math.floor(collectStatus.estimatedRemainingSeconds / 60)}분
              </Typography>
            )}
            <Typography variant="caption" color="text.secondary" sx={{ display: 'block', mt: 1 }}>
              이 작업은 시간이 오래 걸릴 수 있습니다. 페이지를 닫지 마세요.
            </Typography>
          </Box>
        )}

        {collectStatus?.status === 'COMPLETED' && (
          <Alert severity="success" sx={{ mt: 2 }}>
            수집 완료: {collectStatus.successCount}개 성공, {collectStatus.failCount}개 실패
          </Alert>
        )}

        {collectStatus?.status === 'FAILED' && (
          <Alert severity="error" sx={{ mt: 2 }}>
            수집 실패: {collectStatus.errorMessage}
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

export default ProblemMetadataCollect;
```

## ⚠️ 주의사항

### 1. 작업 시간

- **소요 시간:** 문제 수에 따라 수 분 ~ 수십 분 소요
- **Rate Limiting:** 각 요청 사이에 0.5초 간격이 있으므로, 1000개 문제 기준 약 8분 소요
- **비동기 처리:** 작업은 백그라운드에서 실행되므로, HTTP 타임아웃 걱정 없이 사용 가능
- **권장사항:** 작업 시작 전 사용자에게 소요 시간을 안내하고, 진행 상황을 실시간으로 표시

### 2. 에러 처리

```tsx
try {
  const result = await startMetadataCollect(start, end);
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
    showError('메타데이터 수집 시작에 실패했습니다.');
  }
}
```

### 3. 작업 상태 조회 에러 처리

```tsx
const status = await getMetadataCollectStatus(jobId);
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
- **결과 표시:** 수집 완료 후 성공/실패 수를 명확히 표시

## 🧪 테스트 체크리스트

- [ ] 관리자 권한으로 로그인했을 때 버튼이 표시되는지 확인
- [ ] 일반 사용자로 로그인했을 때 버튼이 표시되지 않는지 확인
- [ ] 시작/종료 문제 ID 입력 필드가 올바르게 동작하는지 확인
- [ ] 작업 시작 API 호출 시 jobId가 반환되는지 확인
- [ ] 작업 상태 조회 API가 정상 동작하는지 확인
- [ ] 진행 상황이 실시간으로 업데이트되는지 확인 (5초마다 폴링)
- [ ] 진행률 바가 올바르게 표시되는지 확인
- [ ] 예상 남은 시간이 표시되는지 확인
- [ ] 작업 완료 시 성공/실패 수가 표시되는지 확인
- [ ] 작업 실패 시 에러 메시지가 표시되는지 확인
- [ ] 네트워크 오류 시 적절한 에러 처리가 되는지 확인
- [ ] 페이지를 닫아도 작업이 계속 진행되는지 확인 (백그라운드 작업)

## 🚀 배포 전 확인 사항

1. **백엔드 API 확인:**
   - `POST /api/v1/admin/problems/collect-metadata` 요청이 즉시 jobId를 반환하는지 확인
   - `GET /api/v1/admin/problems/collect-metadata/status/{jobId}` 요청이 올바르게 동작하는지 확인
   - 작업 상태가 실시간으로 업데이트되는지 확인

2. **프론트엔드 UI 확인:**
   - 작업 시작 후 즉시 응답이 반환되는지 확인 (타임아웃 없음)
   - 진행 상황이 실시간으로 업데이트되는지 확인
   - 작업 완료 후 결과가 올바르게 표시되는지 확인

3. **에러 처리:**
   - 작업 시작 실패 시 적절한 에러 메시지가 표시되는지 확인
   - 작업 상태 조회 실패 시 (404) 적절한 메시지가 표시되는지 확인
   - 네트워크 오류 시 적절한 에러 처리가 되는지 확인

## 📚 관련 문서

- [API 명세서](./API_SPECIFICATION.md) - 전체 API 문서
- [언어 정보 업데이트 가이드](./FRONTEND_ADMIN_LANGUAGE_UPDATE_GUIDE.md) - 언어 정보 업데이트 API 가이드

