package fsft.wikipedia;

import fsft.fsftbuffer.Bufferable;
import fsft.fsftbuffer.FSFTBuffer;
import io.github.fastily.jwiki.core.Wiki;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * A mediator service for Wikipedia that caches pages and collects statistics.
 *
 * <p>This service uses the JWiki API to access Wikipedia content and maintains:
 * - A cache of Wikipedia pages to minimize network requests
 * - Statistics about search terms and page requests (for zeitgeist)
 * - Request timestamps for peak load analysis
 *
 * <p>Thread Safety: This class is thread-safe and can handle concurrent requests
 * from multiple threads. It uses locks to protect shared data structures and
 * ensure consistency of statistics.
 */
public class WikiMediator {
 // Rep Invariant:
 // - wiki is non-null
 // - pageCache is non-null
 // - termHistory is non-null and sorted by timestamp (ascending)
 // - requestHistory is non-null and sorted by timestamp (ascending)
 // - statsLock is non-null
 
 // Abstraction Function:
 // AF(this) = a Wikipedia mediator service that:
 //   - caches Wikipedia pages in pageCache
 //   - tracks search term/page title requests in termHistory
 //   - maintains request timeline in requestHistory
 //   - provides search and retrieval services via wiki API
 
 // Thread Safety Argument:
 // - wiki is thread-safe (per JWiki documentation)
 // - pageCache is thread-safe (per FSFTBuffer implementation)
 // - termHistory uses synchronizedList for thread-safe access
 // - requestHistory uses synchronizedList for thread-safe access
 // - recordRequest and recordSearchTerm append to synchronized lists
 // - zeitgeist and peakLoad synchronize on their respective lists when reading
 // - All public methods can be called concurrently safely
 
 private static final int DEFAULT_CACHE_CAPACITY = 50;
 private static final Duration DEFAULT_CACHE_TIMEOUT = Duration.ofMinutes(10);
 
 private final Wiki wiki;
 private final FSFTBuffer<WikiPage> pageCache;
 
 // Statistics tracking
 private final List<TermRequest> termHistory; // search terms and page titles with timestamps
 private final List<RequestRecord> requestHistory; // all requests with timestamps
 private final ReadWriteLock statsLock;
 
 /**
  * Internal class to represent a Wikipedia page in the cache.
  */
 private static class WikiPage implements Bufferable {
  private final String title;
  private final String content;
  
  WikiPage(String title, String content) {
   this.title = title;
   this.content = content;
  }
  
  @Override
  public String id() {
   return title;
  }
  
  String getContent() {
   return content;
  }
 }
 
 /**
  * Internal class to track individual requests with timestamps.
  */
 private static class RequestRecord {
  final Instant timestamp;
  final String type; // "search", "getPage", "zeitgeist", "peakLoad"
  
  RequestRecord(Instant timestamp, String type) {
   this.timestamp = timestamp;
   this.type = type;
  }
 }
 
 /**
  * Internal class to track search terms and page titles with timestamps.
  */
 private static class TermRequest {
  final Instant timestamp;
  final String term;
  
  TermRequest(Instant timestamp, String term) {
   this.timestamp = timestamp;
   this.term = term;
  }
 }
 
 /**
  * Create a new WikiMediator instance with default cache settings.
  * Initializes the Wikipedia API client and cache.
  */
 public WikiMediator() {
  this(DEFAULT_CACHE_CAPACITY, DEFAULT_CACHE_TIMEOUT);
 }
 
 /**
  * Create a new WikiMediator instance with custom cache settings.
  * This constructor is useful for testing and custom configurations.
  *
  * @param cacheCapacity the maximum number of pages to cache
  * @param cacheTimeout the duration to keep pages in cache
  */
 public WikiMediator(int cacheCapacity, Duration cacheTimeout) {
  this.wiki = new Wiki.Builder().withDomain("en.wikipedia.org").build();
  this.pageCache = new FSFTBuffer<>(cacheCapacity, cacheTimeout);
  this.termHistory = Collections.synchronizedList(new ArrayList<>());
  this.requestHistory = Collections.synchronizedList(new ArrayList<>());
  this.statsLock = new ReentrantReadWriteLock();
 }
 
 /**
  * Search Wikipedia for pages matching the given search term.
  *
  * @param searchTerm the term to search for
  * @param limit the maximum number of results to return
  * @return a list of up to limit page titles matching the search term
  */
 public List<String> search(String searchTerm, int limit) {
  recordRequest("search");
  recordSearchTerm(searchTerm);
  
  List<String> results = wiki.search(searchTerm, limit);
  return results != null ? results : new ArrayList<>();
 }
 
 /**
  * Get the content of a Wikipedia page by its title.
  * Uses caching to minimize network requests. If the page is in cache
  * and fresh, returns the cached version. Otherwise, fetches from Wikipedia
  * and updates the cache.
  *
  * @param pageTitle the title of the page to retrieve
  * @return the text content of the Wikipedia page, or empty string if not found
  */
 public String getPage(String pageTitle) {
  recordRequest("getPage");
  recordSearchTerm(pageTitle);
  
  // Try to get from cache first
  try {
   WikiPage cachedPage = pageCache.get(pageTitle);
   return cachedPage.getContent();
  } catch (BufferMissException e) {
   // Not in cache or expired, fetch from Wikipedia
   String content = wiki.getPageText(pageTitle);
   if (content == null) {
    content = ""; // Return empty string if page doesn't exist
   }
   
   // Add to cache
   WikiPage newPage = new WikiPage(pageTitle, content);
   pageCache.put(newPage);
   
   return content;
  }
 }
 
 /**
  * Get the most frequently requested search terms and page titles within
  * a recent time window.
  *
  * <p>This method analyzes search() and getPage() requests made within the
  * specified duration and returns the most common terms/titles in descending
  * order of frequency.
  *
  * @param duration the time window to analyze (from now backwards)
  * @param limit the maximum number of items to return
  * @return a list of the most common search terms/page titles, sorted by
  *         frequency (most common first), limited to the specified count
  */
 public List<String> zeitgeist(Duration duration, int limit) {
  recordRequest("zeitgeist");
  
  Instant cutoffTime = Instant.now().minus(duration);
  Map<String, Integer> recentCounts = new HashMap<>();
  
  synchronized (termHistory) {
   // Count terms within the time window
   for (int i = termHistory.size() - 1; i >= 0; i--) {
    TermRequest tr = termHistory.get(i);
    if (tr.timestamp.isBefore(cutoffTime)) {
     break; // History is ordered, so we can stop here
    }
    recentCounts.merge(tr.term, 1, Integer::sum);
   }
  }
  
  // Sort by count (descending), then by term (ascending) for ties
  return recentCounts.entrySet().stream()
   .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
    .thenComparing(Map.Entry.comparingByKey()))
   .limit(limit)
   .map(Map.Entry::getKey)
   .collect(Collectors.toList());
 }
 
 /**
  * Calculate the maximum number of requests in any time window of the
  * specified duration.
  *
  * <p>This method uses a sliding window approach to find the peak load.
  * It counts all API method calls (search, getPage, zeitgeist, peakLoad)
  * and finds the maximum number of requests that occurred within any
  * continuous time window of the specified duration.
  *
  * @param duration the size of the time window to analyze
  * @return the maximum number of requests in any window of the given duration
  */
 public int peakLoad(Duration duration) {
  recordRequest("peakLoad");
  
  if (duration == null || duration.isZero() || duration.isNegative()) {
   return 0;
  }
  
  synchronized (requestHistory) {
   if (requestHistory.isEmpty()) {
    return 0;
   }
   
   int maxLoad = 0;
   
   // Use sliding window approach
   // For each request, count how many requests fall within duration after it
   for (int i = 0; i < requestHistory.size(); i++) {
    Instant windowStart = requestHistory.get(i).timestamp;
    Instant windowEnd = windowStart.plus(duration);
    
    int count = 0;
    for (int j = i; j < requestHistory.size(); j++) {
     Instant reqTime = requestHistory.get(j).timestamp;
     if (reqTime.isBefore(windowEnd) || reqTime.equals(windowEnd)) {
      count++;
     } else {
      break; // Requests are ordered by time
     }
    }
    
    maxLoad = Math.max(maxLoad, count);
   }
   
   return maxLoad;
  }
 }
 
 /**
  * Record a request for peak load tracking.
  *
  * @param requestType the type of request (search, getPage, etc.)
  */
 private void recordRequest(String requestType) {
  Instant now = Instant.now();
  RequestRecord record = new RequestRecord(now, requestType);
  requestHistory.add(record);
 }
 
 /**
  * Record a search term or page title for zeitgeist tracking.
  *
  * @param term the search term or page title to record
  */
 private void recordSearchTerm(String term) {
  if (term == null || term.isEmpty()) {
   return;
  }
  
  Instant now = Instant.now();
  TermRequest tr = new TermRequest(now, term);
  termHistory.add(tr);
 }
 
}
