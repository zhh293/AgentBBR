package io.loglens.web;

import io.loglens.api.ProcessedRecord;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Subscription and broadcast share one lock so the watermark is an exact stream boundary. */
public final class EventHub implements AutoCloseable {
    public final class Subscription implements AutoCloseable {
        private final ArrayBlockingQueue<ProcessedRecord> queue;
        private final long watermark;
        private volatile boolean gap;
        private volatile boolean closed;
        private Subscription(long watermark) { this.watermark = watermark; queue = new ArrayBlockingQueue<>(capacity); }
        public long watermark() { return watermark; }
        public boolean gap() { return gap; }
        public boolean closed() { return closed; }
        public ProcessedRecord poll(long timeoutMillis) throws InterruptedException { return queue.poll(timeoutMillis, TimeUnit.MILLISECONDS); }
        @Override public void close() { synchronized (EventHub.this) { closed = true; subscribers.remove(this); queue.clear(); } }
    }
    private final int capacity;
    private final int maxClients;
    private final Set<Subscription> subscribers = new HashSet<>();
    private long watermark;
    private boolean closed;
    public EventHub(int capacity, int maxClients, long initialWatermark) {
        if (capacity < 1 || maxClients < 1 || initialWatermark < 0) throw new IllegalArgumentException("invalid hub limits");
        this.capacity = capacity; this.maxClients = maxClients; watermark = initialWatermark;
    }
    public synchronized Subscription subscribe() {
        if (closed) throw new IllegalStateException("event hub closed");
        if (subscribers.size() == maxClients) throw new IllegalStateException("SSE client limit reached");
        Subscription subscription = new Subscription(watermark); subscribers.add(subscription); return subscription;
    }
    public synchronized void publish(ProcessedRecord record) {
        if (closed) return;
        if (record.recordSequence() <= watermark) throw new IllegalArgumentException("record sequence must increase");
        watermark = record.recordSequence();
        var iterator = subscribers.iterator();
        while (iterator.hasNext()) {
            Subscription subscription = iterator.next();
            if (!subscription.queue.offer(record)) {
                subscription.gap = true; subscription.closed = true; subscription.queue.clear(); iterator.remove();
            }
        }
    }
    public synchronized int clients() { return subscribers.size(); }
    @Override public synchronized void close() {
        closed = true;
        for (Subscription subscription : subscribers) { subscription.closed = true; subscription.queue.clear(); }
        subscribers.clear();
    }
}
