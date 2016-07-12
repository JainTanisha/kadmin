package com.bettercloud.kadmin.kafka;

import com.bettercloud.kadmin.api.kafka.KafkaProviderService;
import com.bettercloud.logger.services.LogLevel;
import com.bettercloud.logger.services.Logger;
import com.bettercloud.logger.services.LoggerFactory;
import com.bettercloud.messaging.kafka.consume.ConsumerGroup;
import com.bettercloud.messaging.kafka.consume.MessageHandler;
import com.bettercloud.messaging.kafka.produce.ProducerService;
import com.google.common.base.Joiner;
import com.google.common.collect.Maps;
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.management.InstanceAlreadyExistsException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

/**
 * Created by davidesposito on 6/28/16.
 */
@Service
public class DefaultKafkaProviderService implements KafkaProviderService {

    private static final Logger logger = LoggerFactory.getLogger(DefaultKafkaProviderService.class);
    private static final long IDLE_THRESHOLD = 15L * 60 * 1000; // 15 minutes

    private final Joiner keyJoiner = Joiner.on("<:=:>");

    @Value("${kafka.host:http://localhost:8081}")
    private String schemaRegistryUrl;
    @Value("${schema.registry.url:localhost:9092}")
    private String bootstrapServers;

    private final Map<String, ProducerService<String, Object>> producerMap = Maps.newHashMap();
    private final Map<String, ConsumerGroup<String, Object>> consumerMap = Maps.newHashMap();

    protected Properties producerProperties(String schemaRegistryUrl, String kafkaUrl) {
        final Properties properties = new Properties();
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaUrl);
        properties.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        return properties;
    }

    private String getProducerKey(String schemaRegistryUrl, String kafkaUrl) {
        return keyJoiner.join(schemaRegistryUrl, kafkaUrl);
    }

    @Override
    public ProducerService<String, Object> producerService(String schemaRegistryUrl, String kafkaUrl) {
        if (kafkaUrl == null) {
            kafkaUrl = bootstrapServers;
        }
        if (schemaRegistryUrl == null) {
            schemaRegistryUrl = this.schemaRegistryUrl;
        }
        String key = getProducerKey(schemaRegistryUrl, kafkaUrl);
        if (!producerMap.containsKey(key)) {
            Properties props = producerProperties(schemaRegistryUrl, kafkaUrl);
            producerMap.put(key, producerService(props));
        }
        return producerMap.get(key);
    }

    @Override
    public ProducerService<String, Object> lookupProducer(String key) {
        return producerMap.get(key);
    }

    protected ProducerService<String, Object> producerService(Properties producerProperties) {
        return ProducerService.<String, Object>create()
                .withProducerProperties(producerProperties)
                .withKeySerializer(new StringSerializer())
                .withMessageSerializer(new KafkaAvroSerializer())
                .build();
    }

    /*
    ========================================================
                        CONSUMER
    ========================================================
     */

    private String getConsumerKey(String kafkaUrl, String schemaRegistryUrl, String topic) {
        return keyJoiner.join(kafkaUrl, schemaRegistryUrl, topic);
    }

    protected Properties kafkaConsumerProperties(String kafkaHost, String schemaRegistryUrl) throws UnknownHostException {
        final Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaHost);
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, UUID.randomUUID().toString());
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, InetAddress.getLocalHost().getHostName());
        properties.put(AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        properties.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 1000);
        properties.put(ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG, 32 * 1024);
        return properties;

    }

    @Override
    public ConsumerGroup<String, Object> consumerService(MessageHandler<String, Object> handler, String topic,
                                                         String kafkaUrl, String schemaRegistryUrl) {
        if (kafkaUrl == null) {
            kafkaUrl = bootstrapServers;
        }
        if (schemaRegistryUrl == null) {
            schemaRegistryUrl = this.schemaRegistryUrl;
        }
        String key = getConsumerKey(kafkaUrl, schemaRegistryUrl, topic);
        logger.log(LogLevel.INFO, "Consumer Key: {}", key);
        if (!consumerMap.containsKey(key)) {
            Properties props = null;
            try {
                props = kafkaConsumerProperties(kafkaUrl, schemaRegistryUrl);
                consumerMap.put(key, consumerService(props, topic, handler));
            } catch (UnknownHostException e) {
                e.printStackTrace();
            } catch (InstanceAlreadyExistsException e) {
                /* ignore metrics registration exception */
            }
        }
        return consumerMap.get(key);
    }

    @Override
    public ConsumerGroup<String, Object> lookupConsumer(String key) {
        return consumerMap.get(key);
    }

    protected ConsumerGroup<String, Object> consumerService(Properties kafkaConsumerProperties, String topic,
                                                            MessageHandler<String, Object> handler) throws InstanceAlreadyExistsException {
        return ConsumerGroup.<String, Object>create()
                .withConsumerProperties(kafkaConsumerProperties)
                .withMessageHandler(handler)
                .withKeyDeserializer(new StringDeserializer())
                .withMessageDeserializer(new KafkaAvroDeserializer())
                .forTopic(topic)
                .withPollTimeout(2000)
                .withThreads(1)
                .build();
    }
}
