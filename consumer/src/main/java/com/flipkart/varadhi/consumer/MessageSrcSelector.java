package com.flipkart.varadhi.consumer;

import com.flipkart.varadhi.consumer.concurrent.Context;
import com.flipkart.varadhi.entities.InternalQueueType;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public class MessageSrcSelector {

    private final Context context;
    private final Holder[] messageSrcs;
    private final AtomicReference<CompletableFuture<PolledMessageTrackers>> pendingRequest = new AtomicReference<>();

    /**
     * Using LinkedHashMap to receive the order of the message sources.
     *
     * @param msgSrcs
     */
    public MessageSrcSelector(Context context, LinkedHashMap<InternalQueueType, MessageSrc> msgSrcs, int batchSize) {
        this.context = context;
        this.messageSrcs = new Holder[msgSrcs.size()];
        int i = 0;
        for (var entries : msgSrcs.entrySet()) {
            messageSrcs[i] = new Holder(entries.getKey(), entries.getValue(), new MessageTracker[batchSize]);
            ++i;
        }
    }

    public int getBatchSize() {
        return messageSrcs[0].messages.length;
    }

    public CompletableFuture<PolledMessageTrackers> nextMessages() {
        assert context.isInContext();
        CompletableFuture<PolledMessageTrackers> promise = new CompletableFuture<>();
        if (!pendingRequest.compareAndSet(null, promise)) {
            promise.completeExceptionally(new IllegalStateException("Only one request is allowed at a time"));
            return promise;
        }

        for (Holder holder : messageSrcs) {
            if (holder.fetcher.get() == null) {
                // possibility of having msgs. return it.
                promise = tryCompleteRequest(holder);
                if(promise != null) {
                    return promise;
                }
            }
            // else, fetcher is non-null, so we don't have any msgs from this source. thus ignore.
        }

        return promise;
    }

    private CompletableFuture<PolledMessageTrackers> tryCompleteRequest(Holder holder) {
        CompletableFuture<PolledMessageTrackers> promise = pendingRequest.getAndSet(null);
        if(promise != null) {
            promise.complete(new PolledMessageTrackers(holder));
            return promise;
        }
        return null;
    }

    @RequiredArgsConstructor
    private static final class Holder {
        private final InternalQueueType internalQueueType;
        private final MessageSrc msgSrc;
        private final MessageTracker[] messages;
        private int size = 0;
        private final AtomicReference<Future<Integer>> fetcher = new AtomicReference<>();
    }

    @RequiredArgsConstructor
    public final class PolledMessageTrackers {
        private final Holder holder;

        public InternalQueueType getInternalQueueType() {
            return holder.internalQueueType;
        }

        public MessageTracker[] getMessages() {
            return holder.messages;
        }

        public int getSize() {
            return holder.size;
        }

        public void release() {
            assert context.isInContext();

            int size = holder.size;
            holder.size = 0;
            Arrays.fill(holder.messages, 0, size, null);
            holder.fetcher.set(holder.msgSrc.nextMessages(holder.messages).thenApply(count -> {
                holder.size = count;
                holder.fetcher.set(null);
                tryCompleteRequest(holder);
                return null;
            }));
        }
    }
}