# 프론트엔드 구현 가이드 (Frontend Implementation Guide)

## 개요

이 문서는 "회고 작성 & AI 리뷰" 기능의 프론트엔드 구현 가이드를 제공합니다.
AI 한 줄 리뷰는 **상단 인사이트 카드**로, 정적 템플릿은 **에디터 내용**으로 분리되어 제공됩니다.

---

## 1. 컴포넌트 구조

### `<AiReviewCard />` 컴포넌트

**역할:** 로그의 코드에 대한 AI 한 줄 리뷰를 상단 카드로 표시

**API 호출:**
```typescript
POST /api/v1/logs/{logId}/ai-review
```

**응답:**
```typescript
{
  review: string;      // AI 한 줄 리뷰 또는 안내 메시지
  cached: boolean;     // 캐시 히트 여부
}
```

**구현 예시:**
```tsx
import { useState, useEffect } from 'react';
import { Alert, AlertTitle, Skeleton } from '@mui/material'; // 또는 사용하는 UI 라이브러리

interface AiReviewCardProps {
  logId: string;
}

export const AiReviewCard: React.FC<AiReviewCardProps> = ({ logId }) => {
  const [review, setReview] = useState<string | null>(null);
  const [cached, setCached] = useState<boolean>(false);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchAiReview = async () => {
      try {
        setLoading(true);
        const response = await fetch(`/api/v1/logs/${logId}/ai-review`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
        });

        if (!response.ok) {
          throw new Error(`AI 리뷰 조회 실패: ${response.status}`);
        }

        const data = await response.json();
        setReview(data.review);
        setCached(data.cached);
      } catch (err) {
        setError(err instanceof Error ? err.message : 'AI 리뷰를 불러올 수 없습니다.');
      } finally {
        setLoading(false);
      }
    };

    if (logId) {
      fetchAiReview();
    }
  }, [logId]);

  if (loading) {
    return (
      <Skeleton variant="rectangular" height={100} sx={{ mb: 2, borderRadius: 1 }} />
    );
  }

  if (error) {
    return (
      <Alert severity="error" sx={{ mb: 2 }}>
        <AlertTitle>오류</AlertTitle>
        {error}
      </Alert>
    );
  }

  return (
    <Alert 
      severity="info" 
      icon={<span>🤖</span>}
      sx={{ mb: 2, borderRadius: 1 }}
    >
      <AlertTitle>AI Insight</AlertTitle>
      {review}
      {cached && (
        <span style={{ fontSize: '0.75rem', color: '#666', marginLeft: '8px' }}>
          (캐시됨)
        </span>
      )}
    </Alert>
  );
};
```

---

### `<RetrospectiveEditor />` 컴포넌트

**역할:** 정적 마크다운 템플릿을 에디터에 로드하여 사용자가 회고를 작성

**API 호출:**
```typescript
POST /api/v1/retrospectives/template/static
```

**요청:**
```typescript
{
  code: string;           // 사용자 코드
  problemId: string;     // 문제 ID
  isSuccess: boolean;     // 풀이 성공 여부
  errorMessage?: string; // 에러 메시지 (실패 시)
}
```

**응답:**
```typescript
{
  template: string; // 마크다운 형식의 템플릿 (footer 포함)
}
```

**구현 예시:**
```tsx
import { useState, useEffect } from 'react';
import { Editor } from '@monaco-editor/react'; // 또는 사용하는 마크다운 에디터

interface RetrospectiveEditorProps {
  code: string;
  problemId: string;
  isSuccess: boolean;
  errorMessage?: string;
}

export const RetrospectiveEditor: React.FC<RetrospectiveEditorProps> = ({
  code,
  problemId,
  isSuccess,
  errorMessage,
}) => {
  const [template, setTemplate] = useState<string>('');
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const fetchTemplate = async () => {
      try {
        setLoading(true);
        const response = await fetch('/api/v1/retrospectives/template/static', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
          },
          body: JSON.stringify({
            code,
            problemId,
            isSuccess,
            errorMessage: errorMessage || undefined,
          }),
        });

        if (!response.ok) {
          throw new Error(`템플릿 생성 실패: ${response.status}`);
        }

        const data = await response.json();
        setTemplate(data.template);
      } catch (err) {
        setError(err instanceof Error ? err.message : '템플릿을 불러올 수 없습니다.');
      } finally {
        setLoading(false);
      }
    };

    if (code && problemId) {
      fetchTemplate();
    }
  }, [code, problemId, isSuccess, errorMessage]);

  if (loading) {
    return <div>템플릿을 불러오는 중...</div>;
  }

  if (error) {
    return <div>오류: {error}</div>;
  }

  return (
    <Editor
      height="600px"
      defaultLanguage="markdown"
      value={template}
      onChange={(value) => setTemplate(value || '')}
      options={{
        minimap: { enabled: false },
        wordWrap: 'on',
      }}
    />
  );
};
```

---

## 2. 통합 예시

### `<RetrospectivePage />` 통합 컴포넌트

```tsx
import { AiReviewCard } from './AiReviewCard';
import { RetrospectiveEditor } from './RetrospectiveEditor';

interface RetrospectivePageProps {
  logId: string;
  code: string;
  problemId: string;
  isSuccess: boolean;
  errorMessage?: string;
}

export const RetrospectivePage: React.FC<RetrospectivePageProps> = ({
  logId,
  code,
  problemId,
  isSuccess,
  errorMessage,
}) => {
  return (
    <div style={{ padding: '24px' }}>
      {/* AI 인사이트 카드 (상단) */}
      <AiReviewCard logId={logId} />
      
      {/* 시각적 구분선 */}
      <hr style={{ margin: '24px 0', border: 'none', borderTop: '2px solid #e0e0e0' }} />
      
      {/* 회고 작성 에디터 */}
      <div>
        <h2>📝 회고 작성 (Markdown Editor)</h2>
        <RetrospectiveEditor
          code={code}
          problemId={problemId}
          isSuccess={isSuccess}
          errorMessage={errorMessage}
        />
      </div>
    </div>
  );
};
```

---

## 3. API 응답 예시

### AI 리뷰 응답 (캐시됨)
```json
{
  "review": "Java의 Stream API를 사용하셨네요! filter 로직을 개선하면 O(N) 시간을 더 단축할 수 있습니다.",
  "cached": true
}
```

### AI 리뷰 응답 (새로 생성)
```json
{
  "review": "코드 구조가 깔끔하지만, 시간 복잡도를 O(N²)에서 O(N log N)으로 개선할 수 있습니다.",
  "cached": false
}
```

### AI 리뷰 응답 (생성 중)
```json
{
  "review": "AI review is being generated. Please retry shortly.",
  "cached": false
}
```

### 정적 템플릿 응답 (성공)
```json
{
  "template": "# 🏆 [백준/BOJ] 1000번 A+B (JAVA) 해결 회고\n\n## 🔑 학습 키워드\n\n- 구현\n- BRONZE 3\n\n## 1. 접근 방법 (Approach)\n\n- 문제를 해결하기 위해 어떤 알고리즘이나 자료구조를 선택했나요?\n- 풀이의 핵심 로직을 한 줄로 요약해 보세요.\n\n...\n\n## 제출한 코드\n\n```java\npublic class Solution { ... }\n```\n\n---\nGenerated by DidimLog"
}
```

---

## 4. 에러 처리

### AI 리뷰 생성 실패 (503)
```json
{
  "status": 503,
  "error": "Service Unavailable",
  "code": "AI_GENERATION_FAILED",
  "message": "AI 리뷰 생성에 실패했습니다. 잠시 후 다시 시도해주세요."
}
```

**프론트엔드 처리:**
- 사용자에게 재시도 안내 메시지 표시
- 자동 재시도 로직 구현 (선택사항)

---

## 5. 스타일링 가이드

### AI 인사이트 카드 스타일
- **배경색:** 정보성 색상 (예: #E3F2FD)
- **아이콘:** 🤖 이모지 또는 AI 아이콘
- **제목:** "AI Insight" 또는 "🤖 AI 인사이트"
- **텍스트:** 한 줄 리뷰 내용
- **캐시 표시:** 작은 회색 텍스트로 "(캐시됨)" 표시

### 에디터 스타일
- **폰트:** Monospace 또는 마크다운 에디터 기본 폰트
- **줄 번호:** 표시 (선택사항)
- **미니맵:** 비활성화 권장 (작은 화면 대응)
- **워드 랩:** 활성화 (긴 줄 처리)

---

## 6. 주의사항

1. **AI 리뷰는 비동기로 로드**되므로, 로딩 상태를 명확히 표시해야 합니다.
2. **템플릿은 한 번만 로드**하고, 사용자가 수정할 수 있도록 에디터에 바인딩합니다.
3. **footer (`--- Generated by DidimLog`)는 템플릿에 포함**되어 있으므로, 별도로 추가할 필요가 없습니다.
4. **에러 발생 시 사용자 친화적인 메시지**를 표시하고, 재시도 옵션을 제공합니다.

