package com.bettercloud.kadmin.kafka;

import com.bettercloud.messaging.kafka.consume.MessageHandler;
import com.google.common.collect.Lists;
import lombok.Builder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Created by davidesposito on 7/1/16.
 */
public class QueuedKafkaMessageHandler implements MessageHandler<String, Object> {

    private static final Logger logger = LoggerFactory.getLogger(QueuedKafkaMessageHandler.class);

    private final FixedSizeList<MessageContainer> messageQueue;
    private final AtomicLong total = new AtomicLong(0L);

    public QueuedKafkaMessageHandler(int maxSize) {
        messageQueue = new FixedSizeList<>(maxSize);
    }

    @Override
    public void handleMessage(String s, Object o) {
        logger.info("receiving => {}, queued => {}", total.get() + 1, messageQueue.spine.size());
        total.incrementAndGet();
        this.messageQueue.add(MessageContainer.builder()
                .key(s)
                .message(o)
                .writeTime(System.currentTimeMillis())
                .build());
    }

    @Override
    public void onError(Throwable cause) throws Throwable {
        logger.error(cause.getMessage(), cause);
    }

    public List<Object> get(Long since) {
        return messageQueue.stream()
                .filter(c -> isValidDate(since, c.getWriteTime()))
                .collect(Collectors.toList());
    }

    public int count(Long since) {
        return (int)messageQueue.stream()
                .filter(c -> isValidDate(since, c.getWriteTime()))
                .count();
    }

    public long total() {
        return total.get();
    }

    public void clear() {
        total.set(0L);
        messageQueue.clear();
    }

    protected boolean isValidDate(Long since, Long writeTime) {
        return since < 0 || writeTime > since;
    }

    @Data
    @Builder
    public static class MessageContainer {

        private final long writeTime;
        private final String key;
        private final Object message;
    }

    protected static class FixedSizeList<E> {

        private final LinkedList<E> spine;
        private final int maxSize;

        public FixedSizeList(int maxSize) {
            spine = Lists.newLinkedList();
            this.maxSize = Math.min(Math.max(maxSize, 1), 2000);
        }

        public synchronized void add(E ele) {
            if (spine.size() >= maxSize) {
                spine.removeFirst();
            }
            spine.add(ele);
        }

        public void clear() {
            spine.clear();
        }

        public synchronized Stream<E> stream() {
            return spine.stream();
        }
    }
}
