package fsft;

import fsft.wikipedia.WikiMediator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;


public class WikiMediatorTests {
 
 private WikiMediator mediator;
 private static final Path STATS_FILE = Paths.get("local/wikimediator_stats.json");
 
 private static final int TEST_CAPACITY = 3;
 private static final Duration TEST_TIMEOUT = Duration.ofMillis(100);
 
 @BeforeEach
 public void setUp() {
  try {
   if (Files.exists(STATS_FILE)) {
    Files.delete(STATS_FILE);
   }
  } catch (IOException e) {
   // Ignore
  }
  mediator = new WikiMediator();
 }
 
 @AfterEach
 public void tearDown() {
  try {
   if (Files.exists(STATS_FILE)) {
    Files.delete(STATS_FILE);
   }
  } catch (IOException e) {
   // Ignore
  }
 }
 
 
 @Test
 public void testConstructorCreatesMediator() {
  WikiMediator newMediator = new WikiMediator();
  assertNotNull(newMediator);
 }
 
 @Test
 public void testCustomConstructor() {
  WikiMediator customMediator = new WikiMediator(1, Duration.ofSeconds(1));
  assertNotNull(customMediator);
 }
 

 
 @Test
 @Timeout(10)
 public void testSearchBasicFunctionality() {
  List<String> results = mediator.search("Java", 5);
  
  assertNotNull(results);
  assertTrue(results.size() <= 5);
  if (results.size() > 0) {
   assertNotNull(results.get(0));
   assertTrue(results.get(0).length() > 0);
  }
 }
 
 @Test
 public void testSearchInvalidInputs() {
  assertThrows(IllegalArgumentException.class, () -> mediator.search(null, 5));
  assertThrows(IllegalArgumentException.class, () -> mediator.search("Test", 0));
  assertThrows(IllegalArgumentException.class, () -> mediator.search("Test", -1));
  assertThrows(IllegalArgumentException.class, () -> mediator.search("", 5));
  assertThrows(IllegalArgumentException.class, () -> mediator.search("   ", 5));
 }
 
 @Test
 @Timeout(10)
 public void testSearchRespectsLimit() {
  List<String> results1 = mediator.search("Java", 2);
  List<String> results2 = mediator.search("Java", 5);
  
  assertTrue(results1.size() <= 2);
  assertTrue(results2.size() <= 5);
 }
 

 
 @Test
 @Timeout(10)
 public void testGetPageBasic() {
  String content = mediator.getPage("Java");
  
  assertNotNull(content);
  assertTrue(content.length() > 50);
 }
 
 @Test
 public void testGetPageInvalidInputs() {
  assertThrows(IllegalArgumentException.class, () -> mediator.getPage(null));
 }
 
 @Test
 @Timeout(8)
 public void testGetPageNonExistent() {
  String content = mediator.getPage("ThisPageDoesNotExist999XYZ");
  assertNotNull(content);
  assertEquals("", content);
 }
 
 @Test
 @Timeout(10)
 public void testGetPageCaching() {
  String pageTitle = "Java";
  
  long start1 = System.currentTimeMillis();
  String content1 = mediator.getPage(pageTitle);
  long time1 = System.currentTimeMillis() - start1;
  
  long start2 = System.currentTimeMillis();
  String content2 = mediator.getPage(pageTitle);
  long time2 = System.currentTimeMillis() - start2;
  
  assertEquals(content1, content2);
  assertTrue(time2 < time1 * 2 || time2 < 500,
   "Second access shouldn't be slower. time1=" + time1 + "ms, time2=" + time2 + "ms");
 }
 

 @Test
 @Timeout(20)
 public void testGetPageLRUEviction() {
  WikiMediator limitedMediator = new WikiMediator(TEST_CAPACITY, Duration.ofHours(1));
  
  String pageA = "LRU_Test_A";
  String pageB = "LRU_Test_B";
  String pageC = "LRU_Test_C";
  String pageD = "LRU_Test_D";
  
  // Fill cache to capacity (3 pages)
  limitedMediator.getPage(pageA);
  limitedMediator.getPage(pageB);
  limitedMediator.getPage(pageC);
  
  limitedMediator.getPage(pageB);
  
  // Small delay to ensure timing differences are measurable
  try {
   Thread.sleep(100);
  } catch (InterruptedException e) {
   Thread.currentThread().interrupt();
  }
  
  limitedMediator.getPage(pageD);
  
  
  long startA = System.currentTimeMillis();
  String contentA = limitedMediator.getPage(pageA);
  long timeA = System.currentTimeMillis() - startA;
  assertNotNull(contentA);
  
  long startB = System.currentTimeMillis();
  String contentB = limitedMediator.getPage(pageB);
  long timeB = System.currentTimeMillis() - startB;
  assertNotNull(contentB);
  
  long startC = System.currentTimeMillis();
  String contentC = limitedMediator.getPage(pageC);
  long timeC = System.currentTimeMillis() - startC;
  assertNotNull(contentC);
  
  long startD = System.currentTimeMillis();
  String contentD = limitedMediator.getPage(pageD);
  long timeD = System.currentTimeMillis() - startD;
  assertNotNull(contentD);
  
  double avgCachedTime = (timeB + timeC + timeD) / 3.0;
  
  assertTrue(timeB < 200 && timeC < 200 && timeD < 200,
   "Pages B, C, D should be reasonably fast (cached). " +
    "timeB=" + timeB + "ms, timeC=" + timeC + "ms, timeD=" + timeD + "ms");
  
  assertTrue(timeA > avgCachedTime * 1.2 || timeA > 50,
   "Page A (evicted) should be slower to fetch. " +
    "timeA=" + timeA + "ms, avgCached=" + avgCachedTime + "ms");
 }
 
 /**
  * Test that custom constructor with short timeout works.
  */
 @Test
 @Timeout(10)
 public void testShortCacheTimeout() {
  // Just verify the constructor works with short timeout
  WikiMediator shortTimeout = new WikiMediator(10, Duration.ofMillis(100));
  
  String content = shortTimeout.getPage("TestTimeout");
  assertNotNull(content);
  
  // Accessing again should work (cache hit or refetch)
  String content2 = shortTimeout.getPage("TestTimeout");
  assertNotNull(content2);
 }
 
 
 @Test
 @Timeout(5)
 public void testZeitgeistBasicFrequency() throws InterruptedException {
  mediator.search("Popular", 3);
  mediator.search("Popular", 3);
  mediator.search("Popular", 3);
  mediator.search("Rare", 3);
  
  Thread.sleep(50);
  
  List<String> trending = mediator.zeitgeist(Duration.ofSeconds(10), 5);
  
  assertNotNull(trending);
  assertTrue(trending.size() > 0);
  assertEquals("Popular", trending.get(0));
 }
 
 @Test
 @Timeout(5)
 public void testZeitgeistTimeWindow() {
  mediator.search("Query1", 3);
  mediator.search("Query2", 3);
  
  List<String> longWindow = mediator.zeitgeist(Duration.ofHours(1), 5);
  List<String> shortWindow = mediator.zeitgeist(Duration.ofMillis(1), 5);
  
  assertTrue(longWindow.size() >= 2);
  assertTrue(shortWindow.size() <= longWindow.size());
 }
 
 @Test
 @Timeout(5)
 public void testZeitgeistRespectLimit() {
  for (int i = 0; i < 6; i++) {
   mediator.search("Query" + i, 3);
  }
  
  List<String> trending = mediator.zeitgeist(Duration.ofSeconds(10), 3);
  assertTrue(trending.size() <= 3);
 }
 
 @Test
 @Timeout(5)
 public void testZeitgeistAlphabeticalTieBreaking() {
  mediator.search("Zebra", 3);
  mediator.search("Apple", 3);
  mediator.search("Middle", 3);
  
  List<String> trending = mediator.zeitgeist(Duration.ofSeconds(10), 5);
  
  int appleIdx = trending.indexOf("Apple");
  int middleIdx = trending.indexOf("Middle");
  int zebraIdx = trending.indexOf("Zebra");
  
  assertTrue(appleIdx < middleIdx && middleIdx < zebraIdx,
   "Tie-breaking failed: Expected Apple < Middle < Zebra alphabetically.");
 }
 
 @Test
 public void testZeitgeistEmpty() {
  WikiMediator newMediator = new WikiMediator();
  List<String> trending = newMediator.zeitgeist(Duration.ofSeconds(10), 5);
  assertTrue(trending.isEmpty());
 }
 
 @Test
 public void testZeitgeistInvalidInputs() {
  assertThrows(IllegalArgumentException.class, () -> mediator.zeitgeist(null, 5));
  assertThrows(IllegalArgumentException.class, () -> mediator.zeitgeist(Duration.ZERO, 5));
  assertThrows(IllegalArgumentException.class, () -> mediator.zeitgeist(Duration.ofSeconds(-1), 5));
  assertThrows(IllegalArgumentException.class, () -> mediator.zeitgeist(Duration.ofSeconds(10), 0));
  assertThrows(IllegalArgumentException.class, () -> mediator.zeitgeist(Duration.ofSeconds(10), -1));
 }
 
 @Test
 @Timeout(5)
 public void testZeitgeistCountsGetPageQueries() {
  mediator.getPage("PageA");
  mediator.getPage("PageA");
  mediator.getPage("PageB");
  
  List<String> trending = mediator.zeitgeist(Duration.ofSeconds(10), 5);
  
  assertTrue(trending.contains("PageA"));
  assertTrue(trending.contains("PageB"));
  assertEquals("PageA", trending.get(0), "PageA should be first (accessed 2x)");
 }
 
 
 @Test
 @Timeout(5)
 public void testPeakLoadBasic() {
  mediator.search("A", 3);
  mediator.search("B", 3);
  mediator.getPage("C");
  
  int peak = mediator.peakLoad(Duration.ofSeconds(10));
  assertEquals(4, peak);
 }
 
 @Test
 @Timeout(5)
 public void testPeakLoadTimeWindow() {
  mediator.search("Req1", 3);
  mediator.search("Req2", 3);
  
  int longPeak = mediator.peakLoad(Duration.ofHours(1));
  int shortPeak = mediator.peakLoad(Duration.ofMillis(1));
  
  assertTrue(longPeak >= 3);
  assertTrue(shortPeak <= longPeak);
  assertTrue(shortPeak >= 1);
 }
 
 @Test
 @Timeout(5)
 public void testPeakLoadAllMethodTypes() {
  mediator.search("S", 3);
  mediator.getPage("G");
  mediator.zeitgeist(Duration.ofSeconds(10), 5);
  
  int peak = mediator.peakLoad(Duration.ofSeconds(10));
  assertEquals(4, peak);
 }
 
 @Test
 public void testPeakLoadEmpty() {
  WikiMediator newMediator = new WikiMediator();
  int peak = newMediator.peakLoad(Duration.ofSeconds(10));
  assertEquals(1, peak);
 }
 
 @Test
 public void testPeakLoadInvalidInputs() {
  assertThrows(IllegalArgumentException.class, () -> mediator.peakLoad(null));
  assertThrows(IllegalArgumentException.class, () -> mediator.peakLoad(Duration.ZERO));
  assertThrows(IllegalArgumentException.class, () -> mediator.peakLoad(Duration.ofSeconds(-1)));
 }
 
 
 @Test
 @Timeout(5)
 public void testPeakLoadSlidingWindow() throws InterruptedException {
  // Make 3 requests in quick succession
  for (int i = 0; i < 3; i++) {
   mediator.search("Burst" + i, 3);
  }
  
  // Wait longer than the short window
  Thread.sleep(200);
  
  // Make another request
  mediator.search("After", 3);
  
  // Short window (100ms) should capture fewer requests than long window
  int peakShort = mediator.peakLoad(Duration.ofMillis(100));
  int peakLong = mediator.peakLoad(Duration.ofSeconds(10));
  
 
  assertTrue(peakShort >= 1 && peakShort <= 4,
   "Short window should capture 1-4 requests, got: " + peakShort);
  
  assertTrue(peakLong >= 6,
   "Long window should capture all requests (>=6), got: " + peakLong);
  
  // Short window should be <= long window
  assertTrue(peakShort <= peakLong,
   "Short window (" + peakShort + ") should <= long window (" + peakLong + ")");
 }
 
 @Test
 @Timeout(10)
 public void testStatisticsPersistence() throws InterruptedException {
  mediator.search("Persist1", 3);
  mediator.search("Persist1", 3);
  mediator.getPage("Persist2");
  
  mediator.shutdown();
  
  assertTrue(Files.exists(STATS_FILE));
  
  WikiMediator newMediator = new WikiMediator();
  
  List<String> trending = newMediator.zeitgeist(Duration.ofMinutes(10), 5);
  assertTrue(trending.contains("Persist1"));
  assertTrue(trending.contains("Persist2"));
  
  int peak = newMediator.peakLoad(Duration.ofMinutes(10));
  assertTrue(peak >= 3);
 }
 
 
 @Test
 @Timeout(15)
 public void testConcurrentSearch() throws InterruptedException {
  int numThreads = 5;
  ExecutorService executor = Executors.newFixedThreadPool(numThreads);
  CountDownLatch latch = new CountDownLatch(numThreads);
  
  for (int i = 0; i < numThreads; i++) {
   final int threadId = i;
   executor.submit(() -> {
    try {
     List<String> results = mediator.search("Thread" + threadId, 3);
     assertNotNull(results);
    } catch (Exception e) {
     fail("Exception: " + e.getMessage());
    } finally {
     latch.countDown();
    }
   });
  }
  
  assertTrue(latch.await(12, TimeUnit.SECONDS));
  executor.shutdown();
 }
 
 @Test
 @Timeout(15)
 public void testConcurrentMixedOperations() throws InterruptedException {
  int numThreads = 3;
  ExecutorService executor = Executors.newFixedThreadPool(numThreads * 2);
  CountDownLatch latch = new CountDownLatch(numThreads * 4);
  
  for (int i = 0; i < numThreads; i++) {
   final int id = i;
   
   executor.submit(() -> {
    try {
     mediator.search("Mixed" + id, 3);
    } finally {
     latch.countDown();
    }
   });
   
   executor.submit(() -> {
    try {
     mediator.getPage("Page" + id);
    } finally {
     latch.countDown();
    }
   });
   
   executor.submit(() -> {
    try {
     mediator.zeitgeist(Duration.ofSeconds(10), 5);
    } finally {
     latch.countDown();
    }
   });
   
   executor.submit(() -> {
    try {
     mediator.peakLoad(Duration.ofSeconds(10));
    } finally {
     latch.countDown();
    }
   });
  }
  
  assertTrue(latch.await(15, TimeUnit.SECONDS));
  executor.shutdown();
  
  int peak = mediator.peakLoad(Duration.ofMinutes(1));
  assertTrue(peak >= 13, "Total peak load should include all concurrent calls.");
 }
 

 
 @Test
 @Timeout(15)
 public void testRealWorldUsagePattern() throws InterruptedException {
  mediator.search("Java", 3);
  mediator.getPage("Java");
  mediator.search("Python", 3);
  mediator.search("Java", 3);
  
  Thread.sleep(50);
  
  List<String> trending = mediator.zeitgeist(Duration.ofSeconds(10), 3);
  assertTrue(trending.contains("Java"));
  
  int peak = mediator.peakLoad(Duration.ofSeconds(10));
  assertEquals(6, peak);
 }
}
