# Store Inactivity Detector

이 프로젝트는 Kafka 토픽에서 입력된 데이터를 기반으로 일정 시간동안 활동이 없는 상점(store)을 감지하는 애플리케이션입니다.

## 기능

- `test-topic`에서 메시지 소비
- 각 store_brand + store_id 조합의 마지막 활동 시간 추적
- 30초 동안 활동이 없는 상점 감지
- 비활성 상태로 감지된 상점 정보를 `3_non_response` 토픽으로 전송

## 사전 요구사항

- Java 17 이상
- Gradle
- Docker 및 Docker Compose
- purchase-main 프로젝트가 실행 중이고 `test-topic`에 데이터를 전송 중이어야 함

## 실행 방법

1. 권한 부여
   ```bash
   sh chmod-scripts.sh
   ```

2. 토픽 생성 및 애플리케이션 실행
   ```bash
   ./run.sh
   ```

## 알림 형식

비활성 상태로 감지된 상점에 대한 알림은 다음과 같은 JSON 형식으로 전송됩니다:

```json
{
  "alert_type": "inactivity",
  "store_brand": "[store_brand 값]",
  "store_id": [store_id 값],
  "last_activity_time": [timestamp],
  "current_time": [timestamp],
  "inactive_seconds": [seconds]
}
```

## 토픽 확인 방법

Kafka Control Center UI에서 토픽 데이터를 확인할 수 있습니다.
- 접속 URL: http://localhost:9021
- Topics 메뉴에서 `3_non_response` 토픽 선택
- Messages 탭에서 메시지 확인

## 아키텍처

```
[purchase-main]      [payment_guard-main]
Excel 데이터 ---> test-topic ---(가공)--> 3_non_response
                          |
                   상태 관리 및 비활성 감지
```

## 관련 프로젝트

- `purchase-main`: Excel 데이터를 `test-topic`으로 보내는 애플리케이션
- `payment_guard-main`: `test-topic`에서 데이터를 소비하고 비활성 상태를 감지하여 `3_non_response`로 알림을 보내는 애플리케이션