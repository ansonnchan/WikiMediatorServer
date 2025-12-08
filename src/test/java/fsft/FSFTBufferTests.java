package fsft;

import fsft.fsftbuffer.Bufferable;
import fsft.fsftbuffer.FSFTBuffer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


public class FSFTBufferTests {
 
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
 public void testGetNonExistent() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  assertThrows(IllegalStateException.class, () -> buffer.get("nonexistent"));
 }
 
 @Test
 public void testGetNullId() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  assertThrows(IllegalArgumentException.class, () -> buffer.get(null));
 }
 
 @Test
 public void testPutUpdatesSameId() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1", "data1"));
  buffer.put(new TestBufferable("item1", "data2"));
  
  assertEquals("data2", buffer.get("item1").getData());
 }
 
 @Test
 public void testLRUEvictionBasic() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(3, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  buffer.put(new TestBufferable("item3"));
  
  buffer.get("item1");
  
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
  
  assertThrows(IllegalStateException.class, () -> buffer.get("item1"));
  assertDoesNotThrow(() -> buffer.get("item2"));
  assertDoesNotThrow(() -> buffer.get("item3"));
 }
 
 @Test
 public void testLRUCriterionUsesLastAccessNotPutTime() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(2, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1"));
  Thread.sleep(50);
  buffer.put(new TestBufferable("item2"));
  
  buffer.get("item1");
  buffer.touch("item2");
  
  buffer.put(new TestBufferable("item3"));
  
  assertDoesNotThrow(() -> buffer.get("item1"), "Item1 (MRU by get) should remain.");
  assertThrows(IllegalStateException.class, () -> buffer.get("item2"),
   "Item2 (LRU by tl, despite touch) should be evicted.");
  assertDoesNotThrow(() -> buffer.get("item3"));
 }
 
 @Test
 public void testLRUTieBreaker() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(2, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  
  buffer.get("item1");
  buffer.get("item2");
  
  buffer.put(new TestBufferable("item3"));
  
  assertThrows(IllegalStateException.class, () -> buffer.get("item1"),
   "Item1 should be evicted as it was accessed before item2.");
  assertDoesNotThrow(() -> buffer.get("item2"));
 }
 
 
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
  
  Thread.sleep(150);
  
  buffer.put(new TestBufferable("item3"));
  buffer.put(new TestBufferable("item4"));
  
  assertThrows(IllegalStateException.class, () -> buffer.get("item1"));
  assertDoesNotThrow(() -> buffer.get("item3"));
  assertDoesNotThrow(() -> buffer.get("item4"));
 }
 
 @Test
 public void testTouchPreventsStalenessWhileLRURemainsUnchanged() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(2, Duration.ofMillis(300));
  
  // Add two items
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  
  // Wait until both are about to expire
  Thread.sleep(250);
  
  // Touch item1 to refresh its timeout (but NOT its LRU position)
  assertTrue(buffer.touch("item1"), "Touch should succeed on fresh item");
  
  // Wait a bit more - now item2 should be stale, but item1 is fresh
  Thread.sleep(100);
  
  // Verify item2 is stale
  assertThrows(IllegalStateException.class, () -> buffer.get("item2"),
   "Item2 should be stale and throw exception");
  
  // Verify item1 is still fresh (because we touched it)
  assertDoesNotThrow(() -> buffer.get("item1"),
   "Item1 should still be fresh because touch extended its timeout");
 }
 
 @Test
 public void testTouchRefreshesTimeout() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofMillis(200));
  
  buffer.put(new TestBufferable("item1"));
  Thread.sleep(150);
  
  assertTrue(buffer.touch("item1"));
  Thread.sleep(150);
  
  assertDoesNotThrow(() -> buffer.get("item1"));
 }
 
 @Test
 public void testTouchDoesNotAffectLRU() {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(2, Duration.ofSeconds(60));
  
  buffer.put(new TestBufferable("item1"));
  buffer.put(new TestBufferable("item2"));
  
  buffer.touch("item1");
  
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
 
 // ========== Thread Safety Tests ==========
 
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
  
  int count = 0;
  for (int t = 0; t < numThreads; t++) {
   for (int i = 0; i < putsPerThread; i++) {
    try {
     buffer.get("t" + t + "i" + i);
     count++;
    } catch (IllegalStateException e) {
     // Evicted
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
  
  for (int i = 0; i < 10; i++) {
   buffer.put(new TestBufferable("init" + i));
  }
  
  ExecutorService executor = Executors.newFixedThreadPool(numThreads);
  
  for (int t = 0; t < numThreads; t++) {
   final int threadId = t;
   executor.submit(() -> {
    try {
     startLatch.await();
     
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
          // Expected
         }
         break;
        case 4:
         buffer.touch(id);
         break;
       }
      } catch (Exception e) {
       errors.incrementAndGet();
      }
     }
    } catch (InterruptedException e) {
     errors.incrementAndGet();
    } finally {
     endLatch.countDown();
    }
   });
  }
  
  startLatch.countDown();
  
  assertTrue(endLatch.await(15, TimeUnit.SECONDS));
  executor.shutdown();
  
  assertEquals(0, errors.get(), "Should have no errors in concurrent access");
  
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
         // Expected
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
 
 @Test
 public void testTouchStaleItem() throws InterruptedException {
  FSFTBuffer<TestBufferable> buffer = new FSFTBuffer<>(5, Duration.ofMillis(100));
  buffer.put(new TestBufferable("stale"));
  Thread.sleep(150);
  
  assertFalse(buffer.touch("stale"));
 }
}
