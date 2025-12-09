package fsft.wikipedia;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.github.fastily.jwiki.core.Wiki;
import fsft.fsftbuffer.Bufferable;
import fsft.fsftbuffer.FSFTBuffer;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * A mediator service for Wikipedia that caches pages and collects statistics
 * about requests.
 * <p>
 * This service provides:
 * <ul>
 * <li>Page search functionality with Wikipedia's search API</li>
 * <li>Page content retrieval with caching to minimize network requests</li>
 * <li>Statistical analysis of request patterns (zeitgeist, peak load)</li>
 * <li>Persistence of statistics across service restarts</li>
 * </ul>
 * <p>
 * Only getPage requests are cached. Search results are not cached as they
 * may change frequently.
 * <p>
 * Instances of WikiMediator are thread-safe and can handle concurrent requests.
 */
public class WikiMediator {
 
 private static final int DEFAULT_CACHE_CAPACITY = 256;
 private static final Duration DEFAULT_CACHE_TIMEOUT = Duration.ofMinutes(30);
 private static final String STATISTICS_FILE = "local/wikimediator_stats.json";
 
 private final Wiki wiki;
 private final FSFTBuffer<WikiPage> pageCache;
 private final List<QueryEntry> queryLog;
 private final List<Instant> requestLog;
 private final ReadWriteLock statsLock;
 private final Map<String, Object> pageFetchLocks;
 private final Gson gson;
 
 /**
  * Abstraction Function:
  *  AF(wiki, pageCache, queryLog, requestLog, pageFetchLocks) =
  *    A Wikipedia mediator service where:
  *    - wiki represents the connection to Wikipedia API
  *    - pageCache contains recently fetched pages {page | page in pageCache}
  *    - queryLog tracks all search terms and page titles requested, ordered chronologically
  *    - requestLog tracks timestamps of all API method calls for load analysis
  *    - pageFetchLocks ensures only one thread fetches each unique page at a time
  *
  *  The service provides cached access to Wikipedia pages and statistical analysis
  *  of usage patterns over time.
  *
  * Representation Invariant:
  *  - wiki != null
  *  - pageCache != null
  *  - queryLog != null
  *  - requestLog != null
  *  - statsLock != null
  *  - pageFetchLocks != null
  *  - gson != null
  *  - All QueryEntry objects in queryLog have non-null query and valid timestamp
  *  - All Instant objects in requestLog are non-null
  *  - Both queryLog and requestLog are chronologically ordered (newer entries
  *    are appended at the end)
  *  - All values in pageFetchLocks are non-null Object instances
  *
  * Thread Safety Argument:
  *  This class is thread-safe through the following mechanisms:
  *
  *  1. Page Cache (pageCache):
  *     - FSFTBuffer is internally thread-safe and handles its own synchronization
  *     - Can be accessed concurrently without external synchronization
  *
  *  2. Statistics (queryLog and requestLog):
  *     - Protected by statsLock (ReentrantReadWriteLock)
  *     - Multiple readers can access concurrently (readLock)
  *     - Writers have exclusive access (writeLock)
  *     - All methods that read/write these collections acquire appropriate locks
  *
  *  3. Page Fetching:
  *     - pageFetchLocks is a ConcurrentHashMap that provides per-page synchronization
  *     - When fetching a page not in cache, threads synchronize on a page-specific
  *       lock object (obtained via computeIfAbsent, which is atomic)
  *     - This ensures only one thread fetches each unique page from Wikipedia
  *     - Different pages can be fetched concurrently by different threads
  *     - Double-checked locking pattern: check cache → acquire page lock →
  *       check cache again → fetch if still needed
  *
  *  4. Wikipedia API (wiki):
  *     - JWiki's Wiki class is documented as thread-safe
  *     - Multiple concurrent calls to wiki.search() and wiki.getPageText() are safe
  *
  *  5. JSON Serialization (gson):
  *     - Gson instances are thread-safe for reading operations
  *     - All gson usage is for serialization/deserialization, not state modification
  *     - File I/O operations (load/save statistics) are protected by statsLock.writeLock()
  *
  *  6. No Escape of Mutable State:
  *     - Methods return defensive copies or newly created lists
  *     - Internal collections (queryLog, requestLog) never escape to clients
  *     - WikiPage objects in cache are accessed only through FSFTBuffer's interface
  *
  *  Lock Ordering (to prevent deadlock):
  *     - statsLock is never held while acquiring page-specific locks
  *     - Page-specific locks are acquired one at a time (no nested acquisition)
  *     - When both statsLock and cache operations are needed, statsLock is
  *       acquired first, released, then cache is accessed
  */
 
 /**
  * Represents a Wikipedia page that can be cached.
  * Instances are immutable after construction.
  */
 private static class WikiPage implements Bufferable {
  private final String title;
  private final String content;
  
  /**
   * Creates a cached Wikipedia page.
   *
   * @param title   the page title (used as unique identifier); must be non-null
   * @param content the full text content of the page; must be non-null
   */
  WikiPage(String title, String content) {
   this.title = title;
   this.content = content;
  }
  
  /**
   * Returns the unique identifier for this page.
   *
   * @return the title of the page, serving as the unique identifier
   */
  @Override
  public String id() {
   return title;
  }
  
  /**
   * Returns the full content of this Wikipedia page.
   *
   * @return the text content of the page
   */
  String getContent() {
   return content;
  }
 }
 
 /**
  * Represents a query (search term or page title) with timestamp.
  * Used for zeitgeist statistics.
  * Instances are immutable after construction.
  */
 private static class QueryEntry {
  final String query;
  final long timestamp;
  
  /**
   * Creates a query entry with the specified time.
   *
   * @param query   the search term or page title; must be non-null
   * @param instant the time this query was made; must be non-null
   */
  QueryEntry(String query, Instant instant) {
   this.query = query;
   this.timestamp = instant.toEpochMilli();
  }
  
  /**
   * Returns the instant at which this query was made.
   *
   * @return the query timestamp as an Instant
   */
  Instant getInstant() {
   return Instant.ofEpochMilli(timestamp);
  }
 }
 
 /**
  * Container for statistics persistence to JSON.
  * Used for serialization/deserialization only.
  */
 private static class Statistics {
  List<QueryEntry> queries;
  List<Long> requests;
  
  /**
   * Creates an empty statistics container.
   */
  Statistics() {
   this.queries = new ArrayList<>();
   this.requests = new ArrayList<>();
  }
 }
 
 /**
  * Creates a new WikiMediator with default cache settings.
  * <p>
  * Default configuration:
  * <ul>
  * <li>Cache capacity: 256 pages</li>
  * <li>Cache timeout: 30 minutes</li>
  * </ul>
  * <p>
  * Effects: Constructs a new WikiMediator and attempts to load existing
  * statistics from {@code local/wikimediator_stats.json}. If the file doesn't
  * exist or cannot be read, starts with empty statistics. Creates a connection
  * to en.wikipedia.org.
  */
 public WikiMediator() {
  this(DEFAULT_CACHE_CAPACITY, DEFAULT_CACHE_TIMEOUT);
 }
 
 /**
  * Creates a new WikiMediator with custom cache settings.
  * <p>
  * This constructor is primarily for testing purposes, allowing control
  * over cache behavior.
  * <p>
  * Effects: Constructs a new WikiMediator with specified cache parameters
  * and attempts to load existing statistics from {@code local/wikimediator_stats.json}.
  * If the statistics file doesn't exist or cannot be read, starts with empty statistics.
  * Creates a connection to en.wikipedia.org.
  *
  * @param cacheCapacity the maximum number of pages to store in the cache;
  *                      must be positive
  * @param cacheTimeout  the duration after which a cached page becomes stale;
  *                      must be positive and non-null
  * @throws IllegalArgumentException if cacheCapacity <= 0 or cacheTimeout
  *                                  is null/non-positive (thrown by FSFTBuffer)
  */
 public WikiMediator(int cacheCapacity, Duration cacheTimeout) {
  this.wiki = new Wiki.Builder().withDomain("en.wikipedia.org").build();
  this.pageCache = new FSFTBuffer<>(cacheCapacity, cacheTimeout);
  this.queryLog = new ArrayList<>();
  this.requestLog = new ArrayList<>();
  this.statsLock = new ReentrantReadWriteLock();
  this.pageFetchLocks = new ConcurrentHashMap<>();
  this.gson = new GsonBuilder().setPrettyPrinting().create();
  
  loadStatistics();
 }
 
 /**
  * Searches Wikipedia for pages matching the search term.
  * <p>
  * This method queries Wikipedia's search API and returns up to {@code limit}
  * page titles that match the search term. The search term is logged for
  * zeitgeist statistics.
  * <p>
  * Search results are NOT cached as they may change frequently.
  * <p>
  * Effects: Logs this request and the search term to internal statistics.
  * Makes a network request to Wikipedia's search API. If the request fails,
  * returns an empty list and logs the error to stderr.
  *
  * @param searchTerm the text to search for; must be non-null and non-empty
  * @param limit      the maximum number of results to return; must be positive
  * @return a list of page titles matching the search (up to {@code limit} results);
  * returns an empty list if the search fails or finds no matches
  * @throws IllegalArgumentException if searchTerm is null/empty or limit <= 0
  */
 public List<String> search(String searchTerm, int limit) {
  if (searchTerm == null || searchTerm.trim().isEmpty()) {
   throw new IllegalArgumentException("searchTerm cannot be null or empty");
  }
  if (limit <= 0) {
   throw new IllegalArgumentException("limit must be positive");
  }
  
  logRequest();
  logQuery(searchTerm);
  
  List<String> results;
  try {
   results = wiki.search(searchTerm, limit);
  } catch (Exception e) {
   System.err.println("Wikipedia search failed: " + e.getMessage());
   results = new ArrayList<>();
  }
  
  return results != null ? results : new ArrayList<>();
 }
 
 /**
  * Retrieves the text content of a Wikipedia page.
  * <p>
  * This method first checks the cache for the requested page. If not found
  * or stale, fetches the page from Wikipedia and caches it for future requests.
  * To prevent multiple threads from fetching the same page simultaneously,
  * this method uses per-page synchronization.
  * <p>
  * Only {@code getPage()} requests affect the cache; {@code search()} results
  * are not cached.
  * <p>
  * The page title is logged for zeitgeist statistics.
  * <p>
  * Effects: Logs this request and the page title to internal statistics.
  * If the page is not in cache or is stale:
  * <ul>
  * <li>Acquires a page-specific lock to ensure only one thread fetches this page</li>
  * <li>Double-checks the cache (another thread may have fetched while waiting)</li>
  * <li>Makes a network request to Wikipedia if still needed</li>
  * <li>Caches the result (including empty content for nonexistent pages)</li>
  * </ul>
  * If the Wikipedia request fails, returns an empty string and logs the error.
  *
  * @param pageTitle the title of the Wikipedia page to retrieve
  * @return the full text content of the page; returns an empty string if the
  * page doesn't exist, if pageTitle is an empty string, or if the
  * Wikipedia request fails
  * @throws IllegalArgumentException if pageTitle is null, empty or consists of only whitespace
  */
 public String getPage(String pageTitle) {
  if (pageTitle == null || pageTitle.trim().isEmpty()) {
   throw new IllegalArgumentException();
  }
  
  logRequest();
  logQuery(pageTitle);
  
  try {
   WikiPage cached = pageCache.get(pageTitle);
   return cached.getContent();
  } catch (IllegalStateException e) {
  }
  
  Object pageLock = pageFetchLocks.computeIfAbsent(
   pageTitle.intern(),
   k -> new Object()
  );
  
  synchronized (pageLock) {
   try {
    WikiPage cached = pageCache.get(pageTitle);
    return cached.getContent();
   } catch (IllegalStateException e) {
   }
   
   String content;
   try {
    content = wiki.getPageText(pageTitle);
    if (content == null) {
     content = "";
    }
   } catch (Exception e) {
    System.err.println("Wikipedia page fetch failed for '" +
     pageTitle + "': " + e.getMessage());
    content = "";
   }
   
   WikiPage page = new WikiPage(pageTitle, content);
   pageCache.put(page);
   
   return content;
  }
 }
 
 /**
  * Returns the most common queries in a recent time window (zeitgeist).
  * <p>
  * Zeitgeist tracks the most frequently requested search terms and page titles
  * within the specified time window, ending at the current time. Results are
  * sorted by frequency in descending order, with ties broken alphabetically
  * by query string (ascending).
  * <p>
  * Only the query strings (arguments to {@code search()} and {@code getPage()})
  * are counted, not the method calls themselves.
  * <p>
  * Effects: Logs this request to internal statistics. Reads queryLog to
  * compute statistics but does not modify it.
  *
  * @param duration the time window to analyze, measured backward from current time;
  *                 must be positive and non-null
  * @param limit    the maximum number of results to return; must be positive
  * @return a list of the most common query strings in the time window, sorted
  * by frequency (descending) then alphabetically (ascending); limited
  * to {@code limit} entries. Returns an empty list if no queries were
  * made in the time window.
  * @throws IllegalArgumentException if duration is null/non-positive or limit <= 0
  */
 public List<String> zeitgeist(Duration duration, int limit) {
  if (duration == null || duration.isNegative() || duration.isZero()) {
   throw new IllegalArgumentException("duration must be positive and non-null");
  }
  if (limit <= 0) {
   throw new IllegalArgumentException("limit must be positive");
  }
  
  logRequest();
  
  statsLock.readLock().lock();
  try {
   Instant cutoff = Instant.now().minus(duration);
   
   Map<String, Long> queryCounts = queryLog.stream()
    .filter(entry -> entry.getInstant().isAfter(cutoff))
    .collect(Collectors.groupingBy(
     entry -> entry.query,
     Collectors.counting()
    ));
   
   return queryCounts.entrySet().stream()
    .sorted((e1, e2) -> {
     int countCompare = Long.compare(e2.getValue(), e1.getValue());
     if (countCompare != 0) {
      return countCompare;
     }
     return e1.getKey().compareTo(e2.getKey());
    })
    .limit(limit)
    .map(Map.Entry::getKey)
    .collect(Collectors.toList());
  } finally {
   statsLock.readLock().unlock();
  }
 }
 
 /**
  * Returns the maximum number of requests in any time window of the specified
  * duration.
  * <p>
  * Peak load counts ALL requests to the WikiMediator public API, including
  * {@code search()}, {@code getPage()}, {@code zeitgeist()}, and {@code peakLoad()}
  * itself.
  * <p>
  * This method uses a sliding window algorithm: for each request timestamp as a
  * potential window start, it counts how many requests fall within
  * [start, start + duration) and tracks the maximum count seen.
  * <p>
  * Effects: Logs this request to internal statistics. Reads requestLog to
  * compute statistics but does not modify it.
  *
  * @param duration the time window length; must be positive and non-null
  * @return the maximum number of requests observed in any contiguous time window
  * of length {@code duration}. Returns 1 if this is the first request
  * (the call to peakLoad itself).
  * @throws IllegalArgumentException if duration is null/non-positive
  */
 public int peakLoad(Duration duration) {
  if (duration == null || duration.isNegative() || duration.isZero()) {
   throw new IllegalArgumentException("duration must be positive and non-null");
  }
  
  logRequest();
  
  statsLock.readLock().lock();
  try {
   if (requestLog.isEmpty()) {
    return 0;
   }
   
   int maxLoad = 0;
   int right = 0;
   
   for (int left = 0; left < requestLog.size(); left++) {
    Instant windowStart = requestLog.get(left);
    Instant windowEnd = windowStart.plus(duration);
    
    while (right < requestLog.size() &&
     requestLog.get(right).isBefore(windowEnd)) {
     right++;
    }
    
    int currentLoad = right - left;
    maxLoad = Math.max(maxLoad, currentLoad);
   }
   
   return maxLoad;
  } finally {
   statsLock.readLock().unlock();
  }
 }
 
 /**
  * Shuts down the WikiMediator and persists statistics to disk.
  * <p>
  * Effects: Saves all query and request logs to
  * {@code local/wikimediator_stats.json} in JSON format. Creates the
  * {@code local/} directory if it doesn't exist. If saving fails, prints
  * an error message to stderr but does not throw an exception.
  * <p>
  * This method should be called before the WikiMediator instance is discarded
  * to ensure statistics are preserved across restarts.
  */
 public void shutdown() {
  statsLock.writeLock().lock();
  try {
   saveStatistics();
  } finally {
   statsLock.writeLock().unlock();
  }
 }
 
 /**
  * Logs a request timestamp.
  * <p>
  * Requires: Called at the start of every public API method.
  * <p>
  * Effects: Appends the current timestamp to requestLog.
  * This method is thread-safe and can be called concurrently.
  */
 private void logRequest() {
  statsLock.writeLock().lock();
  try {
   requestLog.add(Instant.now());
  } finally {
   statsLock.writeLock().unlock();
  }
 }
 
 /**
  * Logs a query string (search term or page title).
  * <p>
  * Requires: Called for search() and getPage() methods after logRequest().
  * <p>
  * Effects: Appends a new QueryEntry to queryLog with the current timestamp.
  * This method is thread-safe and can be called concurrently.
  *
  * @param query the search term or page title being requested; must be non-null
  */
 private void logQuery(String query) {
  statsLock.writeLock().lock();
  try {
   queryLog.add(new QueryEntry(query, Instant.now()));
  } finally {
   statsLock.writeLock().unlock();
  }
 }
 
 /**
  * Loads statistics from disk if available.
  * <p>
  * Requires: Called only during construction (no synchronization needed).
  * <p>
  * Effects: If {@code local/wikimediator_stats.json} exists and is readable,
  * loads queryLog and requestLog from it. If the file doesn't exist, starts
  * with empty logs. If the file exists but cannot be read or is malformed,
  * prints a warning to stderr and starts with empty logs.
  */
 private void loadStatistics() {
  Path path = Paths.get(STATISTICS_FILE);
  
  if (!Files.exists(path)) {
   return;
  }
  
  try (Reader reader = Files.newBufferedReader(path)) {
   Statistics stats = gson.fromJson(reader, Statistics.class);
   
   if (stats != null) {
    if (stats.queries != null) {
     queryLog.addAll(stats.queries);
    }
    if (stats.requests != null) {
     stats.requests.stream()
      .map(Instant::ofEpochMilli)
      .forEach(requestLog::add);
    }
   }
  } catch (IOException e) {
   System.err.println("Warning: Could not load statistics: " + e.getMessage());
  }
 }
 
 /**
  * Saves current statistics to disk.
  * <p>
  * Requires: statsLock.writeLock() must be held by caller.
  * <p>
  * Effects: Creates {@code local/} directory if needed, then writes
  * queryLog and requestLog to {@code local/wikimediator_stats.json} in
  * JSON format. If saving fails, prints a warning to stderr but does not throw.
  */
 private void saveStatistics() {
  try {
   Path dir = Paths.get("local");
   if (!Files.exists(dir)) {
    Files.createDirectories(dir);
   }
   
   Statistics stats = new Statistics();
   stats.queries = new ArrayList<>(queryLog);
   stats.requests = requestLog.stream()
    .map(Instant::toEpochMilli)
    .collect(Collectors.toList());
   
   try (Writer writer = Files.newBufferedWriter(Paths.get(STATISTICS_FILE))) {
    gson.toJson(stats, writer);
   }
  } catch (IOException e) {
   System.err.println("Warning: Could not save statistics: " + e.getMessage());
  }
 }
 
 //Task 5 (not done)
 List<String> executeQuery(String query) {
  return null;
 }
}
