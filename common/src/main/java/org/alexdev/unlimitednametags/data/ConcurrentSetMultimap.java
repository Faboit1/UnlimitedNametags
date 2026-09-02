package org.alexdev.unlimitednametags.data;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class ConcurrentSetMultimap<K, V> {

    // Hash-set buckets: put/remove/containsEntry are O(1) instead of the O(n) scans a queue needs.
    //
    // A bucket is "published" while map.get(key) returns it. remove() drops a bucket from the map once it
    // empties, so a bucket reference obtained before acquiring its lock may already be detached by the time
    // the lock is granted; mutating it then would be invisible to everyone else. Every mutator therefore
    // re-checks publication while holding the bucket lock and retries against a fresh bucket if it lost the
    // race. Because detach only ever happens under that same lock, a bucket seen as published while holding
    // it stays published for the duration of the critical section.
    private final ConcurrentHashMap<K, Set<V>> map = new ConcurrentHashMap<>();

    /**
     * Returns the bucket currently published for {@code key}, creating one if needed. The caller must hold no
     * bucket lock; the returned bucket may be detached before the caller locks it, which is why every mutator
     * re-checks {@link #isPublished(Object, Set)} inside its critical section.
     */
    private Set<V> bucketFor(K key) {
        return map.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet());
    }

    /** Must be called while holding {@code bucket}'s monitor. */
    private boolean isPublished(K key, Set<V> bucket) {
        return map.get(key) == bucket;
    }

    /**
     * Adds the value to the specified key.
     * If the value is already present for the key, it will not be added.
     *
     * @param key   the key
     * @param value the value to add
     * @return true if the value was added, false otherwise
     */
    public boolean put(K key, V value) {
        while (true) {
            // Create a new bucket if one does not already exist for the key
            Set<V> bucket = bucketFor(key);
            // Ensure uniqueness by synchronizing on the bucket for this key
            synchronized (bucket) {
                if (isPublished(key, bucket)) {
                    return bucket.add(value);
                }
            }
            // A concurrent remove emptied and dropped this bucket before we locked it; adding to it now
            // would silently lose the value. Retry against whatever bucket is published next.
        }
    }

    /**
     * Adds all values from the provided collection to the specified key.
     * Only adds values that are not already associated with the key.
     *
     * @param key    the key
     * @param values the collection of values to add
     * @return true if at least one value was added, false otherwise
     */
    public boolean putAll(K key, Collection<? extends V> values) {
        while (true) {
            // Create a new bucket if one does not already exist for the key
            Set<V> bucket = bucketFor(key);
            synchronized (bucket) {
                if (isPublished(key, bucket)) {
                    return bucket.addAll(values);
                }
            }
            // Same detach race as put(K, V); retry against the bucket that is published now.
        }
    }

    /**
     * Returns a set of values associated with the specified key.
     *
     * @param key the key
     * @return a Set containing the associated values, or an empty Set if the key does not exist
     */
    public Set<V> get(K key) {
        Set<V> bucket = map.get(key);
        if (bucket == null) {
            return new HashSet<>();
        }
        // Create a copy of the values; synchronize to avoid concurrent modification issues
        synchronized (bucket) {
            return new HashSet<>(bucket);
        }
    }

    /**
     * Returns an unmodifiable, weakly consistent <em>view</em> of the values associated with the key.
     * Unlike {@link #get(K)} this copies nothing, so it suits hot read paths that only iterate the values;
     * the view reflects later changes to the bucket and never throws {@link java.util.ConcurrentModificationException}.
     * Callers that need a stable snapshot must use {@link #get(K)}.
     *
     * @param key the key
     * @return a live unmodifiable view of the associated values, or an empty set if the key does not exist
     */
    public Set<V> view(K key) {
        Set<V> bucket = map.get(key);
        return bucket == null ? Set.of() : Collections.unmodifiableSet(bucket);
    }

    /**
     * Removes the specified value associated with the key.
     * If the bucket becomes empty after removal, the key is removed from the map.
     *
     * @param key   the key
     * @param value the value to remove
     * @return true if the value was removed, false otherwise
     */
    public boolean remove(K key, V value) {
        while (true) {
            Set<V> bucket = map.get(key);
            if (bucket == null) {
                return false;
            }
            synchronized (bucket) {
                if (isPublished(key, bucket)) {
                    boolean removed = bucket.remove(value);
                    if (bucket.isEmpty()) {
                        map.remove(key, bucket);
                    }
                    return removed;
                }
            }
            // This bucket was dropped before we locked it, so the value may live in its replacement.
            // Retry rather than reporting "not removed" against a bucket nobody can see.
        }
    }

    /**
     * Removes all values associated with the specified key.
     *
     * @param key the key
     * @return a Set containing the removed values, or an empty Set if the key did not exist
     */
    public Set<V> removeAll(K key) {
        Set<V> bucket = map.remove(key);
        if (bucket == null) {
            return new HashSet<>();
        }
        synchronized (bucket) {
            return new HashSet<>(bucket);
        }
    }

    /**
     * Removes all key-value associations in the multimap.
     */
    public void clear() {
        map.clear();
    }

    /**
     * Returns a collection view of all values present in the multimap.
     * Note: This is a snapshot of the values at the time of invocation.
     *
     * @return a Collection containing all values from all keys
     */
    public Collection<V> values() {
        Collection<V> allValues = new ArrayList<>();
        for (Set<V> bucket : map.values()) {
            synchronized (bucket) {
                allValues.addAll(bucket);
            }
        }
        return allValues;
    }

    /**
     * Returns the set of keys present in the multimap.
     *
     * @return a Set containing all keys
     */
    public Set<K> keySet() {
        return map.keySet();
    }

    /**
     * Returns a set of key-value pairs in the multimap.
     * Each entry consists of a key and the corresponding set of values.
     *
     * @return a Set of Map.Entry containing keys and their associated value sets
     */
    public Set<java.util.Map.Entry<K, Set<V>>> entrySet() {
        Set<java.util.Map.Entry<K, Set<V>>> entries = new HashSet<>();
        for (K key : map.keySet()) {
            entries.add(new java.util.AbstractMap.SimpleEntry<>(key, get(key)));
        }
        return entries;
    }

    //forEach <key, Set<value>>
    public void forEach(java.util.function.BiConsumer<K, Set<V>> action) {
        for (K key : map.keySet()) {
            action.accept(key, get(key));
        }
    }

    public boolean containsEntry(K key, V value) {
        Set<V> bucket = map.get(key);
        return bucket != null && bucket.contains(value);
    }
}
