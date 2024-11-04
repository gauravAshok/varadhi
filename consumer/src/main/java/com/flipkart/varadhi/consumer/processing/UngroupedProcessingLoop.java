package com.flipkart.varadhi.consumer.processing;

import com.flipkart.varadhi.consumer.*;
import com.flipkart.varadhi.consumer.concurrent.Context;
import com.flipkart.varadhi.consumer.delivery.DeliveryResponse;
import com.flipkart.varadhi.consumer.delivery.MessageDelivery;
import com.flipkart.varadhi.entities.InternalQueueType;
import com.flipkart.varadhi.entities.Offset;

import java.util.*;
import java.util.concurrent.CompletableFuture;

public class UngroupedProcessingLoop extends ProcessingLoop {

    private final Map<InternalQueueType, FailedMsgProducer> internalProducers;
    private final ConsumptionFailurePolicy failurePolicy;

    public UngroupedProcessingLoop(
            Context context,
            MessageSrcSelector msgSrcSelector, ConcurrencyControl<DeliveryResult> concurrencyControl,
            Throttler<DeliveryResponse> throttler, MessageDelivery deliveryClient,
            Map<InternalQueueType, FailedMsgProducer> internalProducers,
            ConsumptionFailurePolicy failurePolicy, int maxInFlightMessages
    ) {
        super(context, msgSrcSelector, concurrencyControl, throttler, deliveryClient, maxInFlightMessages);
        this.internalProducers = internalProducers;
        this.failurePolicy = failurePolicy;
    }

    @Override
    protected void onMessagesPolled(MessageSrcSelector.PolledMessageTrackers polled) {
        super.onMessagesPolled(polled);

        if (polled.getSize() > 0) {
            Collection<CompletableFuture<DeliveryResult>> asyncResponses =
                    deliverMessages(polled.getInternalQueueType(), Arrays.asList(polled.getMessages()));
            // Some of the push will have succeeded, for which we can begin the post processing.
            // For others we start the failure management.
            asyncResponses.forEach(fut -> fut.whenComplete((response, ex) -> {
                if (response.response().success()) {
                    onComplete(response.message(), MessageConsumptionStatus.SENT);
                } else {
                    onPushFailure(polled.getInternalQueueType(), response.message());
                }
            }));
        }
    }

    void onFailure(
            InternalQueueType type, InternalQueueType failedMsgInQueue, MessageTracker message,
            MessageConsumptionStatus status
    ) {
        // failed msgs are present in failedMsgInQueue, so produce this msg there
        CompletableFuture<Offset> asyncProduce =
                internalProducers.get(failedMsgInQueue).produceAsync(message.getMessage());
        asyncProduce.whenComplete((offset, e) -> onComplete(message, status));
    }

    void onPushFailure(InternalQueueType type, MessageTracker message) {
        InternalQueueType nextQueue = nextInternalQueue(type);
        onFailure(type, nextQueue, message, MessageConsumptionStatus.FAILED);
    }

    InternalQueueType nextInternalQueue(InternalQueueType type) {
        if (type instanceof InternalQueueType.Main) {
            return InternalQueueType.retryType(1);
        } else if (type instanceof InternalQueueType.Retry retryType) {
            if (retryType.getRetryCount() < failurePolicy.getRetryPolicy().getRetryAttempts()) {
                return InternalQueueType.retryType(retryType.getRetryCount() + 1);
            } else {
                return InternalQueueType.deadLetterType();
            }
        } else {
            throw new IllegalStateException("Invalid type: " + type);
        }
    }
}