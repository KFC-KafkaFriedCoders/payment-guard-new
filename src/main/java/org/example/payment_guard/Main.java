package org.example.payment_guard;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MemorySize;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import com.example.test1.entity.ReceiptData;
import org.apache.flink.formats.avro.registry.confluent.ConfluentRegistryAvroDeserializationSchema;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public class Main extends KeyedProcessFunction<String, ReceiptData, JsonNode> {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    // (더 이상 사용하지 않음) private transient MapState<String, Long> storeLastActivityState;
    
    // 스토어 상태를 추적하기 위한 내부 클래스 정의
    static class StoreState {
        String brand;
        String name;
        long lastActivityTime;
        String receiptTime;

        StoreState(String brand, String name, String receiptTime) {
            this.brand = brand;
            this.name = name;
            this.receiptTime = receiptTime;
            this.lastActivityTime = System.currentTimeMillis();
        }
    }
    
    // 현재 활성화된 상점 상태 추적 (franchiseId-storeId 조합 키 사용)
    private Map<String, StoreState> activeStores = new ConcurrentHashMap<>();
    
    // 상점의 샘플 데이터 시간 정보 저장 (franchiseId-storeId 조합 키 사용)
    private Map<String, String> storeReceiptTimes = new ConcurrentHashMap<>();
    
    private final Set<String> seenStores = ConcurrentHashMap.newKeySet();
    
    // 알림 간격 (밀리초)
    private static final long INACTIVITY_THRESHOLD_MS = 10000;

    @Override
    public void processElement(ReceiptData value, Context ctx, Collector<JsonNode> out) throws Exception {
        if (value == null) {
            System.out.println("수신된 ReceiptData가 null입니다. 처리를 건너뜁니다.");
            return;
        }
        
        // 프랜차이즈+상점 고유 키 생성
        String compositeKey = value.getFranchiseId() + "-" + value.getStoreId();
        
        // 중복 없이 상점 ID 저장
        if (seenStores.add(compositeKey)) {
            System.out.println("\n[신규 상점 감지]");
            System.out.println("Franchise-Store Key: " + compositeKey);
            System.out.println("현재 전체 상점 리스트:");
            seenStores.forEach(System.out::println);
            System.out.println("---------------------------\n");
        }
        
        String brand = value.getStoreBrand();
        String name = value.getStoreName();
        String time = value.getTime(); // 원본 데이터의 시간 정보
        
        // 시간 정보가 있는 경우 compositeKey로 저장
        if (time != null && !time.isEmpty()) {
            storeReceiptTimes.put(compositeKey, time);
        }
        
        // null 체크
        if (brand == null || brand.isEmpty()) {
            brand = "알 수 없는 브랜드";
        }
        
        if (name == null || name.isEmpty()) {
            name = "알 수 없는 상점";
        }

        // 활성 상점 상태 업데이트 (compositeKey 사용) - receiptTime 추가
        activeStores.put(compositeKey, new StoreState(brand, name, time));
        
        // 일정 시간 후에 타이머 설정
        if (ctx != null) {
            ctx.timerService().registerProcessingTimeTimer(
                ctx.timerService().currentProcessingTime() + INACTIVITY_THRESHOLD_MS
            );
        }
    }

    @Override
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<JsonNode> out) throws Exception {
        long now = System.currentTimeMillis();
        
        // 각 활성 상점의 비활성 상태 확인
        for (Map.Entry<String, StoreState> entry : activeStores.entrySet()) {
            String compositeKey = entry.getKey();
            String key_franchise_id = compositeKey.split("-")[0];
            String key_store_id = compositeKey.split("-")[1];
            StoreState state = entry.getValue();

            // 비활성 상태 확인
            long inactiveMillis = now - state.lastActivityTime;
            if (inactiveMillis > INACTIVITY_THRESHOLD_MS) {
                long inactiveSeconds = inactiveMillis / 1000;

                // 상점 정보
                String brand = state.brand;
                String name = state.name;

                // receiptTime(원본 time) 사용
                String lastActivityTimeStr = state.receiptTime;

                // JSON 알림 생성
                ObjectNode node = objectMapper.createObjectNode();
                node.put("franchise_id", key_franchise_id);
                node.put("store_id", key_store_id);
                node.put("store_brand", state.brand);
                node.put("store_name", state.name);
                node.put("last_activity", state.receiptTime);
                node.put("inactive_time", inactiveSeconds);

                out.collect(node);


                // 터미널에 비활성 상점 정보 출력
                System.out.println("*****************************************");
                System.out.println("비활성 상점 감지: ");
                System.out.println("franchise_id: " + key_franchise_id);
                System.out.println("store_id: " + key_store_id);
                System.out.println("store_brand: " + brand);
                System.out.println("store_name: " + name);
                System.out.println("last_activity: " + lastActivityTimeStr);
                System.out.println("inactive_time: " + (inactiveMillis / 1000) + "초");
                System.out.println("*****************************************\n");

                out.collect(node);

                // 알림 생성 후 상태에서 제거 (다시 활동이 감지될 때까지)
                activeStores.remove(compositeKey);
            }
        }
        
        // 다음 타이머 설정
        ctx.timerService().registerProcessingTimeTimer(
            ctx.timerService().currentProcessingTime() + INACTIVITY_THRESHOLD_MS
        );
    }

    public static class JsonSerializationSchema implements SerializationSchema<JsonNode> {
        @Override
        public byte[] serialize(JsonNode element) {
            try {
                // UTF-8 인코딩 명시적 지정
                return objectMapper.writeValueAsString(element).getBytes("UTF-8");
            } catch (Exception e) {
                throw new RuntimeException("JSON serialization error", e);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        // 설정 파일 로드
        Properties appProps = new Properties();
        try (InputStream input = Main.class.getClassLoader().getResourceAsStream("application.properties")) {
            if (input != null) {
                appProps.load(input);
                System.out.println("application.properties 로드 완료");
            } else {
                System.out.println("application.properties 파일을 찾을 수 없습니다. 기본 설정을 사용합니다.");
            }
        } catch (Exception e) {
            System.err.println("application.properties 로드 중 오류: " + e.getMessage());
        }
        
        // Flink 설정 최적화
        Configuration config = new Configuration();
        // TaskManager 메모리 설정
        config.set(TaskManagerOptions.TASK_HEAP_MEMORY, MemorySize.ofMebiBytes(384));
        config.set(TaskManagerOptions.MANAGED_MEMORY_SIZE, MemorySize.ofMebiBytes(128));
        config.setString("parallelism.default", "1");
        config.setString("state.backend", "hashmap");
        config.setString("state.checkpoint-storage", "jobmanager");
        
        // 최적화된 설정으로 Flink 환경 생성
        final StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(config);
        env.setParallelism(1);
        env.getConfig().setAutoWatermarkInterval(0); // Watermark 간격 비활성화
        
        // 메모리 사용량 최적화를 위한 체크포인팅 사용
        env.disableOperatorChaining(); // 연산자 체이닝 비활성화로 메모리 압력 감소
        
        // application.properties에서 Kafka 설정 가져오기
        String bootstrapServers = appProps.getProperty("bootstrap.servers", "13.209.157.53:9092,15.164.111.153:9092,3.34.32.69:9092");
        String groupId = appProps.getProperty("group.id", "store_inactivity_detector");
        String inputTopic = appProps.getProperty("input.topic", "test-topic"); // 입력 토픽 설정 추가
        String outputTopic = appProps.getProperty("output.topic", "3_non_response");
        String schemaRegistryUrl = appProps.getProperty("schema.registry.url", "http://43.201.175.172:8081,http://43.202.127.159:8081");
        
        System.out.println("\n***** Kafka 연결 정보 *****");
        System.out.println("Kafka 브로커 주소: " + bootstrapServers);
        System.out.println("Consumer 그룹 ID: " + groupId);
        System.out.println("입력 토픽: " + inputTopic);
        System.out.println("출력 토픽: " + outputTopic);
        
        // Kafka 클라이언트 추가 설정
        Properties kafkaProps = new Properties();
        kafkaProps.put("bootstrap.servers", bootstrapServers);
        // 연결 타임아웃 증가 (30초)
        kafkaProps.put("request.timeout.ms", "30000");
        // 재연결 시도 횟수 증가
        kafkaProps.put("reconnect.backoff.max.ms", "10000");
        kafkaProps.put("reconnect.backoff.ms", "1000");
        // 여러 서버 연결 시 추가 설정
        kafkaProps.put("retry.backoff.ms", "1000");
        
        System.out.println("Kafka 연결 설정 준비 완료");
        System.out.println("***********************\n");
        
        // 소스 및 싱크 구성
        Properties sourceProps = new Properties();
        sourceProps.put("request.timeout.ms", "30000");
        sourceProps.put("reconnect.backoff.max.ms", "10000");
        sourceProps.put("session.timeout.ms", "60000"); // 세션 타임아웃 증가
        sourceProps.put("heartbeat.interval.ms", "10000"); // 하트비트 간격 증가
        sourceProps.put("max.poll.interval.ms", "5000000"); // 폴링 간격 증가
        sourceProps.put("schema.registry.url", schemaRegistryUrl);
        
        KafkaSource<ReceiptData> source = KafkaSource.<ReceiptData>builder()
            .setBootstrapServers(bootstrapServers)
            .setTopics(inputTopic)
            .setGroupId(groupId)
            .setValueOnlyDeserializer(
                ConfluentRegistryAvroDeserializationSchema.forSpecific(
                    ReceiptData.class,
                    schemaRegistryUrl
                )
            )
            .setStartingOffsets(OffsetsInitializer.latest()) // 연결 시 최신 오프셋부터 데이터 소비
            .setProperty("client.id", "payment-guard-source")
            .setProperties(sourceProps)
            .build();
        
        System.out.println("Kafka Source 설정 완료");

        Properties sinkProps = new Properties();
        sinkProps.put("request.timeout.ms", "30000");
        sinkProps.put("reconnect.backoff.max.ms", "10000");
        sinkProps.put("transaction.timeout.ms", "900000"); // 트랜잭션 타임아웃 증가
        
        KafkaSink<JsonNode> sink = KafkaSink.<JsonNode>builder()
            .setBootstrapServers(bootstrapServers)
            .setRecordSerializer(
                KafkaRecordSerializationSchema.builder()
                    .setTopic(outputTopic)
                    .setValueSerializationSchema(new JsonSerializationSchema())
                    .build()
            )
            .setKafkaProducerConfig(sinkProps)
            .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
            .build();
            
        System.out.println("Kafka Sink 설정 완료");

        // 데이터 스트림 설정
        DataStream<ReceiptData> sourceStream = env.fromSource(source, WatermarkStrategy.noWatermarks(), "Kafka Source");

        sourceStream
            .keyBy(data -> data.getFranchiseId() + "-" + data.getStoreId())
            .process(new Main())
            .setParallelism(1)
            .sinkTo(sink)
            .setParallelism(1);
        
        System.out.println("Store Inactivity Detector 실행 준비 완료");
        env.execute("Store Inactivity Detector");
    }
}