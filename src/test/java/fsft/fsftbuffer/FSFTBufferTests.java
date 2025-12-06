package fsft.fsftbuffer;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Optimized test suite for FSFTBuffer that achieves high coverage efficiently
 * while effectively finding bugs in incorrect implementations.
 *
 * Test suite is organized to minimize redundancy while maximizing bug detection.
 */
public class FSFTBufferTests {
 
 /**
  * Simple test implementation of Bufferable for testing purposes.
  */
 private static class TestBufferable implements Bufferable {
  private final String id;
  private final String data;
  
  public TestBufferable(String id, String data) {
   this.id = id;
   this.data = data;
  }
  
  public TestBufferable(String id) {
   this(id, "data-" + id);
  }
  
  @Override
  public String id() {
   return id;
  }
  
  public String getData() {
   return data;
  }
  
  @Override
  public String toString() {
   return "TestBufferable{id='" + id + "', data='" + data + "'}";
  }
 }
 
 // ========== Constructor Tests ==========
 
 @Test
 public void testConstructorValid() {
  assertDoesNotThrow(() -> new FSFTBuffer<>(10, Duration.ofSeconds(60)));
  assertDoesNotThrow(() -> new FSFTBuffer<>());
 }
 
 @Test
 public void testConstructorInvalidCapacity() {
  assertThrows(IllegalArgumentException.class,
   () -> new FSFTBuffer<TestBufferable>(0, Duration.ofSeconds(60)));
  assertThrows(IllegalArgumentException.class,
   () -> new FSFTBuffer<TestBufferable>(-5, Duration.ofSeconds(60)));
 }
 
 @Test
 public void testConstructorInvalidTimeout() {
  assertThrows(IllegalArgumentException.class,
   () -> new FSFTBuffer<TestBufferable>(10, null));
  assertThrows(IllegalArgumentException.class,
   () -> new FSFTBuffer<TestBufferable>(10, Duration.ZERO));
  assertThrows(IllegalArgumentException.class,
   () -> new FSFTBuffer<TestBufferable>(10, Duration.ofSeconds(-10)));
 }
 
 // ========== Basic Put/Get Tests ==========
 
 @Test
 public void testPutAndGet() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  TestBufferable item = new TestBufferable("item1");
  
  assertTrue(buffer.put(item));
  assertEquals("item1", buffer.get("item1").id());
 }
 
 @Test
 public void testPutNull() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  assertFalse(buffer.put(null));
 }
 
 @Test
 public void testGetNullId() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  assertThrows(IllegalArgumentException.class, () -> buffer.get(null));
 }
 
 @Test
 public void testGetNonExistent() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  assertThrows(IllegalStateException.class, () -> buffer.get("nonexistent"));
 }
 
 @Test
 public void testPutUpdatesSameId() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1", "data1"));
  buffer.put(new TestBufferable("item1", "data2"));
  
  assertEquals("data2", buffer.get("item1").getData());
 }
 
 // ========== LRU Eviction Tests - Critical for finding bugs ==========
 
 @Test
 public void testLRUEvictionBasic() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(3, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  buffer.put(new TestBufferable("item3"));
  
  // Access item1 to make it recently used
  buffer.get("item1");
  
  // Add item4, should evict item2 (least recently used)
  buffer.put(new TestBufferable("item4"));
  
  assertDoesNotThrow(() -> buffer.get("item1"));
  assertThrows(IllegalStateException.class, () -> buffer.get("item2"));
  assertDoesNotThrow(() -> buffer.get("item3"));
  assertDoesNotThrow(() -> buffer.get("item4"));
 }
 
 @Test
 public void testCapacityEnforcedOnPut() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(2, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  buffer.put(new TestBufferable("item3"));
  
  // Only 2 items should exist, item1 should be evicted
  assertThrows(IllegalStateException.class, () -> buffer.get("item1"));
  
  // At least one of item2 or item3 must exist
  boolean item2Exists = false;
  boolean item3Exists = false;
  try { buffer.get("item2"); item2Exists = true; } catch (IllegalStateException e) {}
  try { buffer.get("item3"); item3Exists = true; } catch (IllegalStateException e) {}
  
  assertTrue(item2Exists || item3Exists);
 }
 
 // ========== Timeout/Staleness Tests - Critical for finding bugs ==========
 
 @Test
 public void testItemBecomesStale() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofMillis(100));
  
  buffer.put(new TestBufferable("item1"));
  Thread.sleep(150);
  
  assertThrows(IllegalStateException.class, () -> buffer.get("item1"));
 }
 
 @Test
 public void testItemNotStaleWithinTimeout() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofMillis(500));
  
  buffer.put(new TestBufferable("item1"));
  Thread.sleep(100);
  
  assertDoesNotThrow(() -> buffer.get("item1"));
 }
 
 @Test
 public void testStaleItemsDoNotCountTowardCapacity() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(2, Duration.ofMillis(100));
  
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  
  Thread.sleep(150); // Both items now stale
  
  // Should be able to add 2 new items without eviction
  buffer.put(new TestBufferable("item3"));
  buffer.put(new TestBufferable("item4"));
  
  assertDoesNotThrow(() -> buffer.get("item3"));
  assertDoesNotThrow(() -> buffer.get("item4"));
 }
 
 // ========== Touch Tests - Critical for spec compliance ==========
 
 @Test
 public void testTouchRefreshesTimeout() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofMillis(200));
  
  buffer.put(new TestBufferable("item1"));
  Thread.sleep(150);
  
  assertTrue(buffer.touch("item1"));
  Thread.sleep(150);
  
  // Should still exist because touch refreshed timeout
  assertDoesNotThrow(() -> buffer.get("item1"));
 }
 
 @Test
 public void testTouchDoesNotAffectLRU() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(2, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  
  // Touch item1 (refreshes timeout but NOT lastAccess)
  buffer.touch("item1");
  
  // Add item3, should still evict item1 (not item2)
  buffer.put(new TestBufferable("item3"));
  
  assertThrows(IllegalStateException.class, () -> buffer.get("item1"));
  assertDoesNotThrow(() -> buffer.get("item2"));
 }
 
 @Test
 public void testTouchInvalidCases() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  
  assertFalse(buffer.touch(null));
  assertFalse(buffer.touch("nonexistent"));
 }
 
 // ========== Thread Safety Tests - Essential for Task 2 ==========
 
 @Test
 public void testConcurrentPutsRespectCapacity() throws InterruptedException {
  int capacity = 50;
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(capacity, Duration.ofSeconds(60));
  int numThreads = 10;
  int putsPerThread = 20;
  
  ExecutorService executor = Executors.newFixedThreadPool(numThreads);
  CountDownLatch latch = new CountDownLatch(numThreads);
  
  for (int t = 0; t < numThreads; t++) {
   final int threadId = t;
   executor.submit(() -> {
    try {
     for (int i = 0; i < putsPerThread; i++) {
      buffer.put(new TestBufferable("t" + threadId + "i" + i));
     }
    } finally {
     latch.countDown();
    }
   });
  }
  
  assertTrue(latch.await(10, TimeUnit.SECONDS));
  executor.shutdown();
  
  // Count surviving items
  int count = 0;
  for (int t = 0; t < numThreads; t++) {
   for (int i = 0; i < putsPerThread; i++) {
    try {
     buffer.get("t" + t + "i" + i);
     count++;
    } catch (IllegalStateException e) {
     // Evicted, that's ok
    }
   }
  }
  
  assertTrue(count <= capacity, "Buffer exceeded capacity: " + count + " > " + capacity);
 }
 
 @Test
 public void testConcurrentAccessStressTest() throws InterruptedException {
  int capacity = 20;
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(capacity, Duration.ofSeconds(60));
  int numThreads = 20;
  AtomicInteger errors = new AtomicInteger(0);
  CountDownLatch startLatch = new CountDownLatch(1);
  CountDownLatch endLatch = new CountDownLatch(numThreads);
  
  // Pre-populate with some items
  for (int i = 0; i < 10; i++) {
   buffer.put(new TestBufferable("init" + i));
  }
  
  ExecutorService executor = Executors.newFixedThreadPool(numThreads);
  
  for (int t = 0; t < numThreads; t++) {
   final int threadId = t;
   executor.submit(() -> {
    try {
     // Wait for all threads to be ready
     startLatch.await();
     
     // Hammer the buffer with rapid operations
     for (int i = 0; i < 100; i++) {
      String id = "item" + (i % 30);
      
      try {
       switch (i % 5) {
        case 0:
        case 1:
         buffer.put(new TestBufferable(id, "thread" + threadId));
         break;
        case 2:
        case 3:
         try {
          buffer.get(id);
         } catch (IllegalStateException e) {
          // Expected - item might not exist
         }
         break;
        case 4:
         buffer.touch(id);
         break;
       }
      } catch (Exception e) {
       errors.incrementAndGet();
       e.printStackTrace();
      }
     }
    } catch (InterruptedException e) {
     errors.incrementAndGet();
    } finally {
     endLatch.countDown();
    }
   });
  }
  
  // Start all threads simultaneously for maximum contention
  startLatch.countDown();
  
  assertTrue(endLatch.await(15, TimeUnit.SECONDS));
  executor.shutdown();
  
  assertEquals(0, errors.get(), "Should have no errors in concurrent access");
  
  // Verify capacity constraint still holds
  int count = 0;
  for (int i = 0; i < 30; i++) {
   try {
    buffer.get("item" + i);
    count++;
   } catch (IllegalStateException e) {
    // Not in buffer
   }
  }
  assertTrue(count <= capacity, "Capacity violated under stress: " + count + " > " + capacity);
 }
 
 @Test
 public void testConcurrentMixedOperations() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(30, Duration.ofSeconds(60));
  int numThreads = 10;
  AtomicInteger errors = new AtomicInteger(0);
  
  ExecutorService executor = Executors.newFixedThreadPool(numThreads);
  CountDownLatch latch = new CountDownLatch(numThreads);
  
  for (int t = 0; t < numThreads; t++) {
   executor.submit(() -> {
    try {
     for (int i = 0; i < 50; i++) {
      String id = "item" + (i % 20);
      
      switch (i % 3) {
       case 0:
        buffer.put(new TestBufferable(id));
        break;
       case 1:
        try {
         buffer.get(id);
        } catch (IllegalStateException e) {
         // Expected - item might not exist
        }
        break;
       case 2:
        buffer.touch(id);
        break;
      }
     }
    } catch (Exception e) {
     errors.incrementAndGet();
    } finally {
     latch.countDown();
    }
   });
  }
  
  assertTrue(latch.await(10, TimeUnit.SECONDS));
  executor.shutdown();
  assertEquals(0, errors.get(), "Should have no unexpected errors");
 }
 
 @Test
 public void testConcurrentPutSameId() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(10, Duration.ofSeconds(60));
  int numThreads = 20;
  String sameId = "shared-item";
  
  ExecutorService executor = Executors.newFixedThreadPool(numThreads);
  CountDownLatch latch = new CountDownLatch(numThreads);
  
  for (int t = 0; t < numThreads; t++) {
   final int threadId = t;
   executor.submit(() -> {
    try {
     for (int i = 0; i < 10; i++) {
      buffer.put(new TestBufferable(sameId, "data-" + threadId + "-" + i));
     }
    } finally {
     latch.countDown();
    }
   });
  }
  
  assertTrue(latch.await(10, TimeUnit.SECONDS));
  executor.shutdown();
  
  // Item should exist (no race condition corruption)
  assertDoesNotThrow(() -> buffer.get(sameId));
 }
 
 // ========== Edge Cases ==========
 
 @Test
 public void testCapacityOfOne() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(1, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  
  assertThrows(IllegalStateException.class, () -> buffer.get("item1"));
  assertDoesNotThrow(() -> buffer.get("item2"));
 }
 
 @Test
 public void testPutUpdatesExistingItemTimeout() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofMillis(200));
  
  buffer.put(new TestBufferable("item1", "data1"));
  Thread.sleep(150);
  
  buffer.put(new TestBufferable("item1", "data2"));
  Thread.sleep(150);
  
  TestBufferable retrieved = buffer.get("item1");
  assertEquals("data2", retrieved.getData());
 }
}
