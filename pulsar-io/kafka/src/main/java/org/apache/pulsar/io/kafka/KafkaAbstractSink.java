/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pulsar.io.kafka;

import com.google.common.base.Strings;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.connect.data.Schema;
import org.apache.pulsar.functions.api.Record;
import org.apache.pulsar.io.core.Sink;
import org.apache.pulsar.io.core.SinkContext;
import org.apache.pulsar.io.kafka.connect.KafkaSinkWrappingProducer;
import org.apache.pulsar.io.kafka.connect.PulsarKafkaWorkerConfig;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/**
 * A Simple abstract class for Kafka sink
 * Users need to implement extractKeyValue function to use this sink
 */
@Slf4j
public abstract class KafkaAbstractSink<K, V> implements Sink<V> {

    private Producer<K, V> producer;
    private Properties props = new Properties();
    private KafkaSinkConfig kafkaSinkConfig;

    protected String topicName;

    @Override
    public void write(Record<V> sourceRecord) {
        ProducerRecord<K, V> record = toProducerRecord(sourceRecord);

        if (log.isDebugEnabled()) {
            log.debug("Record sending to kafka, record={}.", record);
        }

        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                sourceRecord.ack();
            } else {
                sourceRecord.fail();
            }
        });
    }

    @Override
    public void close() throws IOException {
        if (producer != null) {
            producer.close();
            log.info("Kafka sink stopped.");
        }
    }

    protected Properties beforeCreateProducer(Properties props) {
        return props;
    }

    @Override
    public void open(Map<String, Object> config, SinkContext sinkContext) throws Exception {
        kafkaSinkConfig = KafkaSinkConfig.load(config);
        Objects.requireNonNull(kafkaSinkConfig.getTopic(), "Kafka topic is not set");
        topicName = kafkaSinkConfig.getTopic();

        String kafkaConnectorName = kafkaSinkConfig.getKafkaConnectorSinkClass();
        if (Strings.isNullOrEmpty(kafkaConnectorName)) {
            Objects.requireNonNull(kafkaSinkConfig.getBootstrapServers(), "Kafka bootstrapServers is not set");
            Objects.requireNonNull(kafkaSinkConfig.getAcks(), "Kafka acks mode is not set");
            if (kafkaSinkConfig.getBatchSize() <= 0) {
                throw new IllegalArgumentException("Invalid Kafka Producer batchSize : "
                        + kafkaSinkConfig.getBatchSize());
            }
            if (kafkaSinkConfig.getMaxRequestSize() <= 0) {
                throw new IllegalArgumentException("Invalid Kafka Producer maxRequestSize : "
                        + kafkaSinkConfig.getMaxRequestSize());
            }
            if (kafkaSinkConfig.getProducerConfigProperties() != null) {
                props.putAll(kafkaSinkConfig.getProducerConfigProperties());
            }

            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaSinkConfig.getBootstrapServers());
            props.put(ProducerConfig.ACKS_CONFIG, kafkaSinkConfig.getAcks());
            props.put(ProducerConfig.BATCH_SIZE_CONFIG, String.valueOf(kafkaSinkConfig.getBatchSize()));
            props.put(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, String.valueOf(kafkaSinkConfig.getMaxRequestSize()));
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, kafkaSinkConfig.getKeySerializerClass());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, kafkaSinkConfig.getValueSerializerClass());

            producer = new KafkaProducer<>(beforeCreateProducer(props));
        } else {
            kafkaSinkConfig.getKafkaConnectorConfigProperties().entrySet()
                    .forEach(kv -> props.put(kv.getKey(), kv.getValue()));

            Schema keySchema = (Schema)Schema.class
                    .getField(kafkaSinkConfig.getDefaultKeySchema()).get(null);
            Schema valueSchema = (Schema)Schema.class
                    .getField(kafkaSinkConfig.getDefaultValueSchema()).get(null);

            props.put(PulsarKafkaWorkerConfig.OFFSET_STORAGE_TOPIC_CONFIG, kafkaSinkConfig.getOffsetStorageTopic());
            props.put(PulsarKafkaWorkerConfig.PULSAR_SERVICE_URL_CONFIG, kafkaSinkConfig.getPulsarServiceUrl());

            producer = KafkaSinkWrappingProducer.create(kafkaConnectorName,
                            props,
                            keySchema,
                            valueSchema);
        }
        log.info("Kafka sink started : {}.", props);
    }

    public abstract ProducerRecord<K, V> toProducerRecord(Record<V> record);
}