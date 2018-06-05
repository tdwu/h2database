/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.mvstore.tx;

import org.h2.mvstore.Cursor;
import org.h2.mvstore.DataUtils;
import org.h2.mvstore.MVMap;
import org.h2.mvstore.Page;
import org.h2.mvstore.type.DataType;

import java.util.AbstractMap;
import java.util.BitSet;
import java.util.Iterator;
import java.util.Map;

/**
 * A map that supports transactions.
 *
 * @param <K> the key type
 * @param <V> the value type
 */
public class TransactionMap<K, V> {

    /**
     * The map used for writing (the latest version).
     * <p>
     * Key: key the key of the data.
     * Value: { transactionId, oldVersion, value }
     */
    public final MVMap<K, VersionedValue> map;

    /**
     * The transaction which is used for this map.
     */
    final Transaction transaction;

    TransactionMap(Transaction transaction, MVMap<K, VersionedValue> map) {
        this.transaction = transaction;
        this.map = map;
    }

    /**
     * Get a clone of this map for the given transaction.
     *
     * @param transaction the transaction
     * @return the map
     */
    public TransactionMap<K, V> getInstance(Transaction transaction) {
        return new TransactionMap<>(transaction, map);
    }

    /**
     * Get the size of the raw map. This includes uncommitted entries, and
     * transiently removed entries, so it is the maximum number of entries.
     *
     * @return the maximum size
     */
    public long sizeAsLongMax() {
        return map.sizeAsLong();
    }

    /**
     * Get the size of the map as seen by this transaction.
     *
     * @return the size
     */
    public long sizeAsLong() {
        TransactionStore store = transaction.store;

        BitSet committingTransactions;
        MVMap.RootReference mapRootReference;
        MVMap.RootReference undoLogRootReference;
        do {
            committingTransactions = store.committingTransactions.get();
            mapRootReference = map.getRoot();
            undoLogRootReference = store.undoLog.getRoot();
        } while(committingTransactions != store.committingTransactions.get() ||
                mapRootReference != map.getRoot());

        Page undoRootPage = undoLogRootReference.root;
        long undoLogSize = undoRootPage.getTotalCount();
        Page mapRootPage = mapRootReference.root;
        long size = mapRootPage.getTotalCount();
        if (undoLogSize == 0) {
            return size;
        }
        if (undoLogSize > size) {
            // the undo log is larger than the map -
            // count the entries of the map
            size = 0;
            Cursor<K, VersionedValue> cursor = new Cursor<>(mapRootPage, null);
            while (cursor.hasNext()) {
                K key = cursor.next();
                VersionedValue data = cursor.getValue();
                data = getValue(data, committingTransactions);
                if (data != null && data.value != null) {
                    size++;
                }
            }
            return size;
        }
        // the undo log is smaller than the map -
        // scan the undo log and subtract invisible entries
        MVMap<Object, Integer> temp = store.createTempMap();
        try {
            Cursor<Long, Object[]> cursor = new Cursor<>(undoRootPage, null);
            while (cursor.hasNext()) {
                cursor.next();
                Object[] op = cursor.getValue();
                int m = (int) op[0];
                if (m != map.getId()) {
                    // a different map - ignore
                    continue;
                }
                @SuppressWarnings("unchecked")
                K key = (K) op[1];
                VersionedValue data = map.get(mapRootPage, key);
                data = getValue(data, committingTransactions);
                if (data == null || data.value == null) {
                    Integer old = temp.put(key, 1);
                    // count each key only once (there might be
                    // multiple
                    // changes for the same key)
                    if (old == null) {
                        size--;
                    }
                }
            }
        } finally {
            transaction.store.store.removeMap(temp);
        }
        return size;
    }

    /**
     * Remove an entry.
     * <p>
     * If the row is locked, this method will retry until the row could be
     * updated or until a lock timeout.
     *
     * @param key the key
     * @throws IllegalStateException if a lock timeout occurs
     */
    public V remove(K key) {
        return set(key, (V)null);
    }

    /**
     * Update the value for the given key.
     * <p>
     * If the row is locked, this method will retry until the row could be
     * updated or until a lock timeout.
     *
     * @param key the key
     * @param value the new value (not null)
     * @return the old value
     * @throws IllegalStateException if a lock timeout occurs
     */
    public V put(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        return set(key, value);
    }

    /**
     * Put the value for the given key if entry for this key does not exist.
     * It is atomic equivalent of the following expression:
     * contains(key) ? get(k) : put(key, value);
     *
     * @param key the key
     * @param value the new value (not null)
     * @return the old value
     */
    public V putIfAbsent(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        TxDecisionMaker decisionMaker = new TxDecisionMaker.PutIfAbsentDecisionMaker(map.getId(), key, value, transaction);
        return set(key, decisionMaker);
    }

    /**
     * Lock row for the given key.
     * <p>
     * If the row is locked, this method will retry until the row could be
     * updated or until a lock timeout.
     *
     * @param key the key
     * @return the locked value
     * @throws IllegalStateException if a lock timeout occurs
     */
    public V lock(K key) {
        TxDecisionMaker decisionMaker = new TxDecisionMaker.LockDecisionMaker(map.getId(), key, transaction);
        return set(key, decisionMaker);
    }

    /**
     * Update the value for the given key, without adding an undo log entry.
     *
     * @param key the key
     * @param value the value
     * @return the old value
     */
    public V putCommitted(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        VersionedValue newValue = VersionedValue.getInstance(value);
        VersionedValue oldValue = map.put(key, newValue);
        @SuppressWarnings("unchecked")
        V result = (V) (oldValue == null ? null : oldValue.value);
        return result;
    }

    private V set(K key, V value) {
        TxDecisionMaker decisionMaker = new TxDecisionMaker.PutDecisionMaker(map.getId(), key, value, transaction);
        return set(key, decisionMaker);
    }

    private V set(K key, TxDecisionMaker decisionMaker) {
        TransactionStore store = transaction.store;
        Transaction blockingTransaction;
        long sequenceNumWhenStarted;
        VersionedValue result;
        do {
            sequenceNumWhenStarted = store.openTransactions.get().getVersion();
            assert transaction.getBlockerId() == 0;
            // although second parameter (value) is not really used,
            // since TxDecisionMaker has it embedded,
            // MVRTreeMap has weird traversal logic based on it,
            // and any non-null value will do
            result = map.put(key, VersionedValue.DUMMY, decisionMaker);

            MVMap.Decision decision = decisionMaker.getDecision();
            assert decision != null;
            assert decision != MVMap.Decision.REPEAT;
            blockingTransaction = decisionMaker.getBlockingTransaction();
            if (decision != MVMap.Decision.ABORT || blockingTransaction == null) {
                transaction.blockingMap = null;
                transaction.blockingKey = null;
                @SuppressWarnings("unchecked")
                V res = result == null ? null : (V) result.value;
                return res;
            }
            decisionMaker.reset();
            transaction.blockingMap = map;
            transaction.blockingKey = key;
        } while (blockingTransaction.sequenceNum > sequenceNumWhenStarted || transaction.waitFor(blockingTransaction));

        throw DataUtils.newIllegalStateException(DataUtils.ERROR_TRANSACTION_LOCKED,
                "Map entry <{0}> with key <{1}> and value {2} is locked by tx {3} and can not be updated by tx {4} within allocated time interval {5} ms.",
                map.getName(), key, result, blockingTransaction.transactionId, transaction.transactionId, transaction.timeoutMillis);
    }

    /**
     * Try to remove the value for the given key.
     * <p>
     * This will fail if the row is locked by another transaction (that
     * means, if another open transaction changed the row).
     *
     * @param key the key
     * @return whether the entry could be removed
     */
    public boolean tryRemove(K key) {
        return trySet(key, null);
    }

    /**
     * Try to update the value for the given key.
     * <p>
     * This will fail if the row is locked by another transaction (that
     * means, if another open transaction changed the row).
     *
     * @param key the key
     * @param value the new value
     * @return whether the entry could be updated
     */
    public boolean tryPut(K key, V value) {
        DataUtils.checkArgument(value != null, "The value may not be null");
        return trySet(key, value);
    }

    /**
     * Try to set or remove the value. When updating only unchanged entries,
     * then the value is only changed if it was not changed after opening
     * the map.
     *
     * @param key the key
     * @param value the new value (null to remove the value)
     * @return true if the value was set, false if there was a concurrent
     *         update
     */
    public boolean trySet(K key, V value) {
        try {
            // TODO: effective transaction.timeoutMillis should be set to 0 here and restored before return
            // TODO: eliminate exception usage as part of normal control flaw
            set(key, value);
            return true;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Get the effective value for the given key.
     *
     * @param key the key
     * @return the value or null
     */
    @SuppressWarnings("unchecked")
    public V get(K key) {
        VersionedValue data = map.get(key);
        if (data == null) {
            // doesn't exist or deleted by a committed transaction
            return null;
        }
        long id = data.getOperationId();
        if (id == 0) {
            // it is committed
            return (V)data.value;
        }
        int tx = TransactionStore.getTransactionId(id);
        if (tx == transaction.transactionId || transaction.store.committingTransactions.get().get(tx)) {
            // added by this transaction or another transaction which is committed by now
            return (V)data.value;
        } else {
            return (V) data.getCommittedValue();
        }
    }

    /**
     * Whether the map contains the key.
     *
     * @param key the key
     * @return true if the map contains an entry for this key
     */
    public boolean containsKey(K key) {
        return get(key) != null;
    }

    /**
     * Whether the entry for this key was added or removed from this
     * session.
     *
     * @param key the key
     * @return true if yes
     */
    public boolean isSameTransaction(K key) {
        VersionedValue data = map.get(key);
        if (data == null) {
            // doesn't exist or deleted by a committed transaction
            return false;
        }
        int tx = TransactionStore.getTransactionId(data.getOperationId());
        return tx == transaction.transactionId;
    }

    /**
     * Get the versioned value from the raw versioned value (possibly uncommitted),
     * as visible by the current transaction.
     *
     * @param data the value stored in the main map
     * @param committingTransactions set of transactions being committed
     *                               at the time when snapshot was taken
     * @return the value
     */
    VersionedValue getValue(VersionedValue data, BitSet committingTransactions) {
        long id;
        int tx;
        // If value doesn't exist or it was deleted by a committed transaction,
        // or if value is a committed one, just return it.
        if (data != null &&
                (id = data.getOperationId()) != 0 &&
                ((tx = TransactionStore.getTransactionId(id)) != transaction.transactionId &&
                    !committingTransactions.get(tx))) {
            // current value comes from another uncommitted transaction
            // take committed value instead
            Object committedValue = data.getCommittedValue();
            data = committedValue == null ? null : VersionedValue.getInstance(committedValue);
        }
        return data;
    }

    /**
     * Check whether this map is closed.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return map.isClosed();
    }

    /**
     * Clear the map.
     */
    public void clear() {
        // TODO truncate transactionally?
        map.clear();
    }

    /**
     * Get the first key.
     *
     * @return the first key, or null if empty
     */
    public K firstKey() {
        Iterator<K> it = keyIterator(null);
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Get the last key.
     *
     * @return the last key, or null if empty
     */
    public K lastKey() {
        K k = map.lastKey();
        while (k != null && get(k) == null) {
            k = map.lowerKey(k);
        }
        return k;
    }

    /**
     * Get the smallest key that is larger than the given key, or null if no
     * such key exists.
     *
     * @param key the key (may not be null)
     * @return the result
     */
    public K higherKey(K key) {
        do {
            key = map.higherKey(key);
        } while (key != null && get(key) == null);
        return key;
    }

    /**
     * Get the smallest key that is larger than or equal to this key,
     * or null if no such key exists.
     *
     * @param key the key (may not be null)
     * @return the result
     */
    public K ceilingKey(K key) {
        Iterator<K> it = keyIterator(key);
        return it.hasNext() ? it.next() : null;
    }

    /**
     * Get one of the previous or next keys. There might be no value
     * available for the returned key.
     *
     * @param key the key (may not be null)
     * @param offset how many keys to skip (-1 for previous, 1 for next)
     * @return the key
     */
    public K relativeKey(K key, long offset) {
        K k = offset > 0 ? map.ceilingKey(key) : map.floorKey(key);
        if (k == null) {
            return k;
        }
        long index = map.getKeyIndex(k);
        return map.getKey(index + offset);
    }

    /**
     * Get the largest key that is smaller than or equal to this key,
     * or null if no such key exists.
     *
     * @param key the key (may not be null)
     * @return the result
     */
    public K floorKey(K key) {
        key = map.floorKey(key);
        while (key != null && get(key) == null) {
            // Use lowerKey() for the next attempts, otherwise we'll get an infinite loop
            key = map.lowerKey(key);
        }
        return key;
    }

    /**
     * Get the largest key that is smaller than the given key, or null if no
     * such key exists.
     *
     * @param key the key (may not be null)
     * @return the result
     */
    public K lowerKey(K key) {
        do {
            key = map.lowerKey(key);
        } while (key != null && get(key) == null);
        return key;
    }

    /**
     * Iterate over keys.
     *
     * @param from the first key to return
     * @return the iterator
     */
    public Iterator<K> keyIterator(K from) {
        return keyIterator(from, null, false);
    }

    /**
     * Iterate over keys.
     *
     * @param from the first key to return
     * @param to the last key to return or null if there is no limit
     * @param includeUncommitted whether uncommitted entries should be
     *            included
     * @return the iterator
     */
    public Iterator<K> keyIterator(K from, K to, boolean includeUncommitted) {
        return new KeyIterator<>(this, from, to, includeUncommitted);
    }

    /**
     * Iterate over entries.
     *
     * @param from the first key to return
     * @param to the last key to return
     * @return the iterator
     */
    public Iterator<Map.Entry<K, V>> entryIterator(final K from, final K to) {
        return new EntryIterator<>(this, from, to);
    }

    /**
     * Iterate over keys.
     *
     * @param iterator the iterator to wrap
     * @param includeUncommitted whether uncommitted entries should be
     *            included
     * @return the iterator
     */
    public Iterator<K> wrapIterator(final Iterator<K> iterator,
            final boolean includeUncommitted) {
        // TODO duplicate code for wrapIterator and entryIterator
        return new Iterator<K>() {
            private K current;

            {
                fetchNext();
            }

            private void fetchNext() {
                while (iterator.hasNext()) {
                    current = iterator.next();
                    if (includeUncommitted) {
                        return;
                    }
                    if (containsKey(current)) {
                        return;
                    }
                }
                current = null;
            }

            @Override
            public boolean hasNext() {
                return current != null;
            }

            @Override
            public K next() {
                K result = current;
                fetchNext();
                return result;
            }

            @Override
            public void remove() {
                throw DataUtils.newUnsupportedOperationException(
                        "Removal is not supported");
            }
        };
    }

    public Transaction getTransaction() {
        return transaction;
    }

    public DataType getKeyType() {
        return map.getKeyType();
    }


    private static final class KeyIterator<K> extends TMIterator<K,K> {

        public KeyIterator(TransactionMap<K, ?> transactionMap, K from, K to, boolean includeUncommitted) {
            super(transactionMap, from, to, includeUncommitted);
        }

        @Override
        protected K registerCurrent(K key, VersionedValue data) {
            return key;
        }
    }

    private static final class EntryIterator<K,V> extends TMIterator<K,Map.Entry<K,V>> {

        public EntryIterator(TransactionMap<K, ?> transactionMap, K from, K to) {
            super(transactionMap, from, to, false);
        }

        @Override
        @SuppressWarnings("unchecked")
        protected Map.Entry<K, V> registerCurrent(K key, VersionedValue data) {
            return new AbstractMap.SimpleImmutableEntry<>(key, (V) data.value);
        }
    }

    private abstract static class TMIterator<K,X> implements Iterator<X> {
        private final TransactionMap<K,?> transactionMap;
        private final BitSet committingTransactions;
        private final Cursor<K,VersionedValue> cursor;
        private final boolean includeAllUncommitted;
        private X current;

        protected TMIterator(TransactionMap<K,?> transactionMap, K from, K to, boolean includeAllUncommitted) {
            this.transactionMap = transactionMap;
            TransactionStore store = transactionMap.transaction.store;
            MVMap<K, VersionedValue> map = transactionMap.map;
            // The purpose of the following loop is to get a coherent picture
            // of a state of two independent volatile / atomic variables
            // which they had at some recent moment in time.
            // In order to get such a "snapshot", we wait for a moment of silence
            // when neither of the variables concurrently chenges it's value.
            BitSet committingTransactions;
            MVMap.RootReference mapRootReference;
            do {
                committingTransactions = store.committingTransactions.get();
                mapRootReference = map.getRoot();
            } while (committingTransactions != store.committingTransactions.get());
            // Now we have a snapshot, where mapRootReference points to state of the map
            // and committingTransactions mask tells us which of seemingly uncommitted changes
            // should be considered as committed.
            // Subsequent map traversal uses this snapshot info only.
            this.cursor = new Cursor<>(mapRootReference.root, from, to);
            this.committingTransactions = committingTransactions;

            this.includeAllUncommitted = includeAllUncommitted;
        }

        protected abstract X registerCurrent(K key, VersionedValue data);

        private void fetchNext() {
            while (cursor.hasNext()) {
                K key = cursor.next();
                VersionedValue data = cursor.getValue();
                if (!includeAllUncommitted) {
                    data = transactionMap.getValue(data, committingTransactions);
                }
                if (data != null && (data.value != null ||
                        includeAllUncommitted && transactionMap.transaction.transactionId !=
                                                    TransactionStore.getTransactionId(data.getOperationId()))) {
                    current = registerCurrent(key, data);
                    return;
                }
            }
            current = null;
        }

        @Override
        public final boolean hasNext() {
            if(current == null) {
                fetchNext();
            }
            return current != null;
        }

        @Override
        public final X next() {
            if(!hasNext()) {
                return null;
            }
            X result = current;
            current = null;
            return result;
        }

        @Override
        public final void remove() {
            throw DataUtils.newUnsupportedOperationException(
                    "Removal is not supported");
        }
    }
}
