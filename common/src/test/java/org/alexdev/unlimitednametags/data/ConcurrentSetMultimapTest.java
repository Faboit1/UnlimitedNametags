package org.alexdev.unlimitednametags.data;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConcurrentSetMultimapTest {

    @Test
    void putReportsWhetherTheValueWasNew() {
        final ConcurrentSetMultimap<String, String> multimap = new ConcurrentSetMultimap<>();

        assertTrue(multimap.put("k", "a"));
        assertFalse(multimap.put("k", "a"));
        assertTrue(multimap.put("k", "b"));

        assertEquals(Set.of("a", "b"), multimap.get("k"));
        assertTrue(multimap.containsEntry("k", "a"));
        assertFalse(multimap.containsEntry("k", "missing"));
        assertFalse(multimap.containsEntry("missing", "a"));
    }

    @Test
    void removingTheLastValueDropsTheKey() {
        final ConcurrentSetMultimap<String, String> multimap = new ConcurrentSetMultimap<>();
        multimap.put("k", "a");

        assertTrue(multimap.remove("k", "a"));
        assertFalse(multimap.remove("k", "a"));
        assertFalse(multimap.keySet().contains("k"));
        assertTrue(multimap.get("k").isEmpty());
        assertTrue(multimap.view("k").isEmpty());
    }

    @Test
    void viewReflectsLaterChangesWhileGetIsASnapshot() {
        final ConcurrentSetMultimap<String, String> multimap = new ConcurrentSetMultimap<>();
        multimap.put("k", "a");

        final Set<String> view = multimap.view("k");
        final Set<String> snapshot = multimap.get("k");

        multimap.put("k", "b");

        assertEquals(Set.of("a", "b"), view);
        assertEquals(Set.of("a"), snapshot);
    }

    @Test
    void removeAllReturnsEveryValueAndClearsTheKey() {
        final ConcurrentSetMultimap<String, String> multimap = new ConcurrentSetMultimap<>();
        multimap.putAll("k", List.of("a", "b", "c"));

        assertEquals(Set.of("a", "b", "c"), multimap.removeAll("k"));
        assertTrue(multimap.removeAll("k").isEmpty());
        assertTrue(multimap.get("k").isEmpty());
    }

    /**
     * A bucket that empties is dropped from the backing map. A put that resolved its bucket just before that
     * happened used to add to the detached bucket, so the value vanished — for {@code TrackerManager} that is a
     * viewer who never gets a nametag. Each round races one put against a put/remove pair that empties the
     * bucket, on a fresh key so the bucket starts absent and the detach window is open.
     */
    @Test
    void concurrentPutIsNotLostWhenTheBucketIsConcurrentlyEmptied() throws InterruptedException {
        final ConcurrentSetMultimap<String, String> multimap = new ConcurrentSetMultimap<>();
        final int rounds = 5_000;
        final ExecutorService pool = Executors.newFixedThreadPool(2);
        final AtomicInteger lost = new AtomicInteger();

        try {
            for (int round = 0; round < rounds; round++) {
                final String key = "k" + round;
                final CountDownLatch start = new CountDownLatch(1);
                final CountDownLatch done = new CountDownLatch(2);

                pool.execute(() -> {
                    awaitQuietly(start);
                    multimap.put(key, "keep");
                    done.countDown();
                });
                pool.execute(() -> {
                    awaitQuietly(start);
                    multimap.put(key, "churn");
                    multimap.remove(key, "churn");
                    done.countDown();
                });

                start.countDown();
                assertTrue(done.await(30, TimeUnit.SECONDS), "round " + round + " did not finish");

                if (!multimap.get(key).contains("keep")) {
                    lost.incrementAndGet();
                }
                assertFalse(multimap.containsEntry(key, "churn"), "churn value survived in round " + round);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(0, lost.get(), lost.get() + " of " + rounds + " concurrent puts were lost");
    }

    /**
     * Mixed churn over a small set of shared keys, which drives buckets to empty (and be dropped) constantly
     * while other threads are mid-put. Every value belongs to exactly one thread and is added, then removed
     * only by that thread, so the surviving set is fully determined regardless of interleaving — any deviation
     * means an operation was applied to a bucket nobody could see.
     */
    @Test
    void concurrentChurnConvergesToTheValuesThatWereNotRemoved() throws InterruptedException {
        final ConcurrentSetMultimap<String, String> multimap = new ConcurrentSetMultimap<>();
        final int threads = 4;
        final int roundsPerThread = 2_000;
        final int keyCount = 8;

        final Map<String, Set<String>> expected = new HashMap<>();
        for (int t = 0; t < threads; t++) {
            for (int round = 0; round < roundsPerThread; round++) {
                if (round % 2 == 0) {
                    expected.computeIfAbsent("k" + (round % keyCount), k -> new HashSet<>())
                            .add("t" + t + "-r" + round);
                }
            }
        }

        final ExecutorService pool = Executors.newFixedThreadPool(threads);
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(threads);
        try {
            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                pool.execute(() -> {
                    awaitQuietly(start);
                    for (int round = 0; round < roundsPerThread; round++) {
                        final String key = "k" + (round % keyCount);
                        final String value = "t" + threadId + "-r" + round;
                        multimap.put(key, value);
                        if (round % 2 != 0) {
                            multimap.remove(key, value);
                        }
                    }
                    done.countDown();
                });
            }
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "workers did not finish");
        } finally {
            pool.shutdownNow();
        }

        for (int k = 0; k < keyCount; k++) {
            final String key = "k" + k;
            assertEquals(expected.getOrDefault(key, Set.of()), multimap.get(key), "contents of " + key);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
