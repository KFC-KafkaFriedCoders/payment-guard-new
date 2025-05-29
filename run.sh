#!/bin/bash

# Java 모듈 접근 제한 우회를 위한 옵션
JAVA_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED \
           --add-opens=java.base/java.lang=ALL-UNNAMED \
           --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
           --add-opens=java.base/java.text=ALL-UNNAMED \
           --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
           --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
           --add-opens=java.base/java.nio=ALL-UNNAMED \
           --add-opens=java.base/java.util.stream=ALL-UNNAMED \
           --add-opens=java.base/java.util.function=ALL-UNNAMED \
           --add-opens=java.base/java.time=ALL-UNNAMED \
           -Djdk.reflect.useDirectMethodHandle=false \
           -Dorg.apache.flink.shaded.jackson.dataformat.yaml.YAMLMapper.enforceDefaultFormat=false"

# 토픽 생성 확인
./create-topics.sh

# 애플리케이션 실행
echo "Store Inactivity Detector 애플리케이션을 시작합니다..."
echo "5초 동안 활동이 없는 상점(store)을 감지합니다."
echo "----------------------------------------"
echo "- 플리크 에 의해 상태가 관리되며 비활성 상태가 감지되면 알림을 생성합니다."
echo "- 생성된 알림은 3_non_response 토픽으로 전송됩니다."
echo "- http://localhost:9021 에서 토픽 데이터를 확인할 수 있습니다."
echo "----------------------------------------"

# 애플리케이션 실행 (Java 옵션 적용)
JAVA_TOOL_OPTIONS="$JAVA_OPTS" ./gradlew run
