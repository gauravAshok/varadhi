# Message

```
struct Message {
    String group_id,
    byte[] payload
}

Hasher(Message) -> int
```

# CompositeTopic

Composite Topic is a sequence of 'n' kafka/pulsar topics (called storage topics) with many partitions.
Topic is always created with partitions having partitions 1, 2, 3 or any multiple 4 or multiple of 6. This will allow us to shard it uniformly across various
shard configuration.

```

struct CompositeTopic {
        name: string
        List<StorageTopics> storage_topics = ordered sequence[T_0, T_1, T_2,..., T_n],
        int produce_pointer
}

// where StorageTopic is defined as
struct StorageTopic {
        topic_name: "ST",
        partitions: [ST-p_0, ST-p_1, ST-p_2, ..., ST-p_P(ST)-1],
        int K
}

// K is the factor by which this storage topic has already been scaled up by. For eg: If the first storage_topic had 2 partitions, and then the next storage_topic was added containing 10 partitions, then K is 5.
// If we add a new storage topic where the new partition count is not a multiple of the original topic anymore, then we set K = 1.
// K allows us to scale and remain consistent with any shards its subscriptions may have.

// where partition is storage defined as
struct Partition {
        int offset;
}

// offset is a monotonically increasing integer pointer.

Router(int) -> partition_id
// Router allows us to choose a partition given a int hash of the message.

```

Each storage topic has P(T_i) partitions.
We will denote a specific partition as T_i-p_j where j is between 0 and P(T_i)-1.
produce_pointer is the index of the storage topic to which the next message will be produced. It is initialized to 0 and
is between 0 and n-1.

## Operation on CompositeTopic:

### Produce(m)

Produce a message m to the CompositeTopic. The message will be produced to the storage topic T_i, where i = produce_pointer. produce_pointer is not mutated in
this
operation. A partition j within T_i is chosen where j = Router(hash(m)). Effectively m is produced to T_i-p_j. Offset of T_i-p_j is incremented by 1.
Router needs to follow the formula:
Router(h = hash(m)) -> K * h % P(T_i) + (h / P(T_i)) % K. We can call this routing to be shard consistent.

### Consume(r, p', read_offset), where r (aka read_pointer) is between 0 and n-1, p' is between 0 and P(T_r-p_p') - 1 and read_offset < offset(T_r-p_p')

Read a message from partition present at offset(T_r-p_p'). produce_pointer is not
mutated.

### AddStorageTopic(#partitions)

Append a new storage topic in the end of storage topics list with the provided partition count. produce_pointer is not mutated.
This just simply creates a new topic and does not affect the produce operations.
There are 2 cases here; assuming ST is the new storage topic to be added at index n':

- If the P(ST) is a multiple of the P(T-{n'-1})/K, where, then ST.K = new_partition_count / (P(T-{n'-1})/K).
- Otherwise, ST.K = 1. This case may impact the shards of its subscriptions and they may need to be re-sharded for uniform load distribution. To be covered
  later.

### SwitchProduce()

Switch the produce_pointer to the next storage topic. produce_pointer is incremented by 1. If the produce_pointer points
to the last storage_topic, then it stays at the last index and does not reset to 0. This is the only operation which mutates produce_pointer.

# Subscription

Subscription is an entity which is collection of internal topics (namely RQ/DLQ) and their consumers. It subscribes to a
single CompositeTopic.
Its main aim is to consume messages from this CompositeTopic and deliver them to an endpoint. It uses its internal topic to retry
failed messages and park hard failures in DLQ.

```
struct Subscription {
        main_topic: CompositeTopic, // aka MQ
        rq_topic: CompositeTopic, // aka RQ
        dlq_topic: CompositeTopic, // aka DLQ
        shard_count: int
        shards: List<SubscriptionShard>
}

struct SubscriptionShard {
    subscription: &Subscription // reference of the subscription to which this shard belongs to
    shard_id: int,
    assignment: List<ShardAssignment>
}

struct ShardAssignment {
    topic_name: String // name of a CompositeTopic.
    storage_topic_idx: int // index of the storage topic in the CompositeTopic.
    assigned_partitions: List<int> // list of partitions of the above storage topic assigned to this shard.
}
```

assigned_partitions are computed using "some" strategy. The default approach would be:
Partitions assigned for shard with id 'sid' = [sid * P(T_i) / shard_count, (sid + 1) * P(T_i) / shard_count - 1]. Both bounds inclusive.

shard_count should be chosen such that shard_count divides P(T_i).
rq_topic is a topic of type CompositeTopic. Same for dlq_topic. On creation of Subscription, the rq_topic & dlq_topic are created with 1 storage topic having
'r', 'd' partitions respectively.
r and d are multiples of shard_count.

shard_id is between 0 and shard_count - 1.

GroupState is defined as follows:

## GroupState

```
struct MessageState {
    main_topic_idx: int,
    main_topic_offset: Offset,
    internal_topic_idx: int,
    internal_topic_offset: Offset
}

struct GroupState {
    p: MessageState, // write offset of the latest message produced of this group in this queue
    c: MessageState  // read offset of the latest message consumed of this group in this queue
}

struct SubscriptionLevelGroupsState, aka SLGS, {
    rq: Map<group_id, GroupState>,
    dlq: Map<group_id, GroupState>
}
```

Here, for the group_ids in rq/dlq, the main_topic_idx is the index of the storage topic in the subscription's main_topic and the main_topic_offset is the offset
of the message from where it was read / written to. partition id does not matter and we will not store that in GroupState.

For the group_ids in rq, the internal_topic_idx is the index of the storage topic in the subscription's rq_topic.
For the group_ids in dlq, the internal_topic_idx is the index of the storage topic in the subscription's dlq_topic.

For group_ids in rq/dlq, the internal_topic_offset is the offset of the message from where it was read / written to. partition id does not matter and we will
not store that in GroupState.

## Operation on Subscription

### ChangeShardCount(nsc = aka new shard count); when the main topic's partition has not changed.

Change the shard_count of the subscription to nsc. What it means that if currently the subscription is running at "x" (the old shard count) places, then now
stop it and run it at "nsc" places. To be able to distribute the load evenly across these "nsc" shards,

- "nsc" needs to still divide the P(T_i) evenly and we should be able to increase "nsc" upto P(T_i) or decrease "nsc" to 1. Increasing "nsc" more than P(
  T_i) should not be required. But increasing the topic partitions by another factor, can be a viable approach to support higher shard count. More shard
  count without topic partition increase can be explored if the messaging stack can support per partition shared consumers, but not in scope for now.
- "nsc" still needs to divide P(RQ_i) & P(DLQ_i). Otherwise, we need to add new storage topics in RQ and DLQ with appropriate partitions. In such a case the
  new failures can directly go to these new storage topics. The lag from previous RQ/DLQ storage topics needs to be cleared by a feeding mechanism which we
  will cover later

Once we have chosen the new nsc, there are 3 steps to follow, to be able to conclude shard count change:

1. If RQ / DLQ needs new storage topics, due to the new shard count, then create them.
2. Switch failed messages production to these new RQ/DLQ storage topics.
3. Re-shard the groupState according to new shards.
4. Resume consumption by running
   a. new shards. run RQ/DLQ as well if no storage topics were added, or if the new storage topics were added, then wait for previous storage topics' lag to be
   zero.
   b. old shards for any RQ/DLQ lag. The old shards will run as feeders to the new shards.

The above strategy can be used for increasing/decreasing shards.

### ChangeShardCount(nsc = aka new shard count); when the main topic's partition has changed and old shard count can't support new topic partitions.

This can happen lets say, when the main_topic had 7 partitions and its subscription also had 7 shards. Now the main_topic has been scaled up to 10 partitions,
and we want to
change the shard count to 10 as well.
This scenario can also be handled in the same way, except RQ/DLQ will need a new storage topics with 10 partitions or any multiple of it.

### Subscription's Offset Reset

RQ/DLQ dont support offset rewind. Their offsets can be fast-forwarded.
MQ offset can be rewinded / fast-forwarded.

The MQ might have many storage topics.

T_0, T_1, T_2, T_3 .... T_n.

A offset rewind from T_n to T_k, where k <= n, can be supported without any issue as long as all topics between them and themselves can be sharded uniformly
across the current shard
configuration. This is possible due to shard consistent routing done by the producers.

But if by chance there is a storage topic where the partitions cannot be spread uniformly, then we will need to treat this again as a partition change scenario.
and follow the same procedure. This may incur shard count change, because of incompatible partition count.

## Message Consumption

Message consumption is a composition of 2 processes.

- Delivering messages
- Handle failure by pushing failed messages to appropriate internal topics, and update GroupState.

The current shards will always be responsible for the above. The old shards will only be responsible for "feeding" the old failed messages to the new shards,
based on the same Routing strategy used by the producers. These fed messages can be processed by the shards as if they were coming from pulsar directly. So
effectively, the old shards will never be responsible for accessing / mutating GroupState, making http call.

TODO: Extend the data model to support tracking "old shards" and "new shards" separately and their lifecycle. Not in scope for MVP.

## Considerations

### Why does shard count need to change if the previous shard count does not divide the new partition count?

The primary consideration is that if they dont divide, then there may be some shards with more partitions than others. This will lead to uneven load
distribution in terms of failure generation which means more capacity requirements on RQ/DLQ's shard specific partitions. Pulsar does not support per partition
capacity.
So to handle this we can change shard count to match the new partitions, Or change the capacity to accommodate higher load on some partitions.
This discrepancy can be higher in case of lower shard count. Think of scenario, where shard_0 is assigned load of 2 partitions and shard_1 is assigned load of
only 1. That is 2x load difference.
This capacity issue can accumulate if this happens to be the case over larger number of subscriptions. This is likely if we dont put restrictions over shard
count
and topic's partitions count.

Now, what to support and what not to support?
Ideally, uneven load distribution should be feasible if allowed. the design should not restrict that. The code / data model should not assume even distribution.
The
management plan, can try to always suggest shards, partitions so that even distribution is achieved. But exceptions should be possible.

### Tradeoffs when we follow sharding model.

The most common state of a subscription is of message processing. This state will be most efficient. If this state changes, then the system follows certain
rules to again arrive at this most efficient state eventually. The tradeoff is that the transition is now more restrictive and can be slightly complex and
inefficient.

## Varadhi Consumer as a library

It should not suffer from the restrictions of the design choices made for platformisation.
Few things that are undesirable for a library:

* Sharding Concept.
* Complex horizontal scaling model. Horizontal scaling should be possible by a manner of simply running the same consumer in multiple places.
* The Consumption model should be largely independent of the topic's partition count.

The above is achievable largely if we have:

* Distributed rate-limiter of error throttling. If usecase requires it.
* Distributed concurrency control. If usecase requires it.
* Global GroupState
* Ordering key abstraction. (group-id is not special, it can be any key).
* Filter computation externalized.
* Consumer Group Management. Each instance should be able to "Reach" others so that they together perform the overall subscription's job. But for this we might
  need some way to register and discover each other. This needs to be hooked in as well.
