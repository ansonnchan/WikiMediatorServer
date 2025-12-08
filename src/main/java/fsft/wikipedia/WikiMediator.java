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
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

/**
 * A mediator service for Wikipedia that caches pages and collects statistics
 * about requests.
 * <p>
 * This service provides:
 * <p>
 * Page search functionality with Wikipedia's search API
 * Page content retrieval with caching to minimize network requests
 * Statistical analysis of request patterns (zeitgeist, peak load)
 * Persistence of statistics across service restarts
 * <p>
 * Only getPage requests are cached.
 * Search results are not cached as they may change frequently.
 *
 */
public class WikiMediator {
 
 private static final int DEFAULT_CACHE_CAPACITY = 256;
 private static final Duration DEFAULT_CACHE_TIMEOUT = Duration.ofMinutes(30);
 private static final String STATISTICS_FILE = "local/wikimediator_stats.json";
 private final Wiki wiki;
 private final FSFTBuffer<WikiPage> pageCache;
 private final List<QueryEntry> queryLog;
 private final List<Instant> requestLog;
 private final ReadWriteLock lock;
 private final Gson gson;
 
 /**
  * Abstraction Function:
  *  AF(wiki, pageCache, queryLog, requestLog) = A Wikipedia mediator service where:
  *  - wiki represents the connection to Wikipedia API
  *  - pageCache contains recently fetched pages {page | page in pageCache}
  *  - queryLog tracks all search terms and page titles requested, ordered by time
  *  - requestLog tracks timestamps of all API method calls for load analysis
  *
  *  The service provides cached access to Wikipedia pages and statistical analysis
  *  of usage patterns over time.
  *
  *  Representation Invariant:
  *  - wiki != null
  *  - pageCache != null
  *  - queryLog != null
  *  - requestLog != null
  *  - lock != null
  *  - gson != null
  *  - All QueryEntry objects in queryLog have non-null query and valid timestamp
  *  - All Instant objects in requestLog are non-null
  *  - Both queryLog and requestLog are chronologically ordered (newer entries
  *     are appended at the end)
  *
  *  Thread Safety Argument:
  *  This class is thread-safe because:
  *  1. The pageCache (FSFTBuffer) is internally thread-safe and can be accessed
  *   concurrently without external synchronization
  *  2. The queryLog and requestLog are synchronized using a ReentrantReadWriteLock:
  *  - Multiple threads can read statistics concurrently (readLock)
  *  - Write operations (logging queries/requests) are mutually exclusive (writeLock)
  *  3. The wiki object is thread-safe as per JWiki library documentation
  *  4. The gson object is thread-safe for reading (no state modification after construction)
  *  5. All mutable shared state (queryLog, requestLog) is accessed only while
  *   holding appropriate locks
  *  6. No references to internal mutable collections escape to clients
  *    (methods return new lists or defensive copies)
  */
 
 /**
  * Represents a Wikipedia page that can be cached.
  */
 private static class WikiPage implements Bufferable {
  private final String title;
  private final String content;
  
  /**
   * Creates a cached Wikipedia page.
   *
   * @param title   the page title (used as unique identifier)
   * @param content the full text content of the page
   */
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
  * Represents a query (search term or page title) with timestamp.
  * Used for zeitgeist statistics.
  */
 private static class QueryEntry {
  final String query;
  final long timestamp;
  
  /**
   * Creates a query entry with the current time.
   *
   * @param query   the search term or page title
   * @param instant the time this query was made
   */
  QueryEntry(String query, Instant instant) {
   this.query = query;
   this.timestamp = instant.toEpochMilli();
  }
  
  Instant getInstant() {
   return Instant.ofEpochMilli(timestamp);
  }
 }
 
 /**
  * Container for statistics persistence to JSON.
  */
 private static class Statistics {
  List<QueryEntry> queries;
  List<Long> requests;
  
  Statistics() {
   this.queries = new ArrayList<>();
   this.requests = new ArrayList<>();
  }
 }
 
 /**
  * Creates a new WikiMediator with default cache settings.
  * <p>
  * Default configuration:
  * <p>
  * Cache capacity: 256 pages
  * Cache timeout: 30 minutes
  * <p>
  * <p>
  * Effects: Constructs a new WikiMediator and attempts to load existing
  * statistics from {@code local/wikimediator_stats.json}. If the file doesn't
  * exist or cannot be read, starts with empty statistics.
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
  this.lock = new ReentrantReadWriteLock();
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
  * Effects: Logs this request and the search term to internal statistics.
  * Makes a network request to Wikipedia unless an error occurs.
  *
  * @param searchTerm the text to search for; must be non-null and non-empty
  * @param limit      the maximum number of results to return; must be positive
  * @return a list of page titles matching the search (up to {@code limit} results);
  * returns an empty list if the search fails or finds no matches
  * @throws IllegalArgumentException if searchTerm is null/empty or limit <= 0
  */
 public List<String> search(String searchTerm, int limit) {
  if (searchTerm == null || searchTerm.trim().isEmpty() || limit <= 0) {
   throw new IllegalArgumentException("Invalid search arguments");
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
  * Only {@code getPage()} requests affect the cache (searches are not cached).
  * <p>
  * The page title is logged for zeitgeist statistics.
  * <p>
  * Effects: Logs this request and the page title to internal statistics.
  * If the page is not in cache, makes a network request to Wikipedia and
  * caches the result (unless it's empty and pageTitle is empty).
  *
  * @param pageTitle the title of the Wikipedia page to retrieve; must be non-null
  * @return the full text content of the page; returns an empty string if the
  * page doesn't exist or if the Wikipedia request fails
  * @throws IllegalArgumentException if pageTitle is null
  */
 public String getPage(String pageTitle) {
  if (pageTitle == null) {
   throw new IllegalArgumentException("pageTitle cannot be null");
  }
  
  logRequest();
  logQuery(pageTitle);
  
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
   System.err.println("Wikipedia page fetch failed: " + e.getMessage());
   content = "";
  }
  
  lock.writeLock().lock();
  try {
   try {
    return pageCache.get(pageTitle).getContent();
   } catch (IllegalStateException e) {
   }
   
   if (!content.isEmpty() || !pageTitle.isEmpty()) {
    WikiPage page = new WikiPage(pageTitle, content);
    pageCache.put(page);
   }
   
   return content;
  } finally {
   lock.writeLock().unlock();
  }
 }
 
 /**
  * Returns the most common queries in a recent time window (zeitgeist).
  * <p>
  * Zeitgeist tracks the most frequently requested search terms and page titles
  * within the specified time window. Results are sorted by frequency (descending),
  * with ties broken alphabetically by query string.
  * <p>
  * Only the query strings (arguments to {@code search()} and {@code getPage()})
  * are counted, not the method calls themselves.
  * <p>
  * Effects: Logs this request to internal statistics. Does not modify queryLog.
  *
  * @param duration the time window to analyze; must be positive and non-null
  * @param limit    the maximum number of results to return; must be positive
  * @return a list of the most common query strings in the time window, sorted
  * by frequency (descending) then alphabetically; limited to {@code limit}
  * entries
  * @throws IllegalArgumentException if duration is null/non-positive or limit <= 0
  */
 public List<String> zeitgeist(Duration duration, int limit) {
  if (duration == null || duration.isNegative() || duration.isZero() || limit <= 0) {
   throw new IllegalArgumentException("Invalid arguments for zeitgeist");
  }
  
  logRequest();
  
  lock.readLock().lock();
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
   lock.readLock().unlock();
  }
 }
 
 /**
  * Returns the maximum number of requests in any time window of the specified
  * duration.
  * <p>
  * Peak load counts ALL requests to the WikiMediator public API, including
  * {@code search()}, {@code getPage()}, {@code zeitgeist()}, and {@code peakLoad()}
  * itself. This method uses an O(N) sliding window algorithm where N is the total
  * number of requests logged.
  * <p>
  * Algorithm: For each request timestamp as a potential window start,
  * count how many requests fall within [start, start + duration). Track the
  * maximum count seen.
  * <p>
  * Effects: Logs this request to internal statistics. Does not modify requestLog.
  *
  * @param duration the time window length; must be positive and non-null
  * @return the maximum number of requests observed in any contiguous time window
  * of length {@code duration}; returns 0 if no requests have been made
  * (though this call itself creates a request)
  * @throws IllegalArgumentException if duration is null/non-positive
  */
 public int peakLoad(Duration duration) {
  if (duration == null || duration.isNegative() || duration.isZero()) {
   throw new IllegalArgumentException("duration must be positive and non-null");
  }
  
  logRequest();
  
  lock.readLock().lock();
  try {
   if (requestLog.isEmpty()) {
    return 0;
   }
   
   int maxLoad = 0;
   int right = 0;
   
   for (int left = 0; left < requestLog.size(); left++) {
    Instant windowStart = requestLog.get(left);
    Instant windowEnd = windowStart.plus(duration);
    
    while (right < requestLog.size() && requestLog.get(right).isBefore(windowEnd)) {
     right++;
    }
    
    int currentLoad = right - left;
    maxLoad = Math.max(maxLoad, currentLoad);
   }
   
   return maxLoad;
  } finally {
   lock.readLock().unlock();
  }
 }
 
 /**
  * Shuts down the WikiMediator and persists statistics to disk.
  * <p>
  * Effects: Saves all query and request logs to
  * {@code local/wikimediator_stats.json} in JSON format. Creates the
  * {@code local/} directory if it doesn't exist. If saving fails, prints
  * an error message but does not throw an exception.
  */
 public void shutdown() {
  lock.writeLock().lock();
  try {
   saveStatistics();
  } finally {
   lock.writeLock().unlock();
  }
 }
 
 
 /**
  * Logs a request timestamp.
  * <p>
  * Requires: Called at the start of every public API method.
  * Effects: Appends the current timestamp to requestLog.
  */
 private void logRequest() {
  lock.writeLock().lock();
  try {
   requestLog.add(Instant.now());
  } finally {
   lock.writeLock().unlock();
  }
 }
 
 /**
  * Logs a query string (search term or page title).
  * <p>
  * Requires: Called for search() and getPage() methods.
  * Effects: Appends a new QueryEntry to queryLog with the current timestamp.
  *
  * @param query the search term or page title being requested
  */
 private void logQuery(String query) {
  lock.writeLock().lock();
  try {
   queryLog.add(new QueryEntry(query, Instant.now()));
  } finally {
   lock.writeLock().unlock();
  }
 }
 
 /**
  * Loads statistics from disk if available.
  * <p>
  * Effects: If {@code local/wikimediator_stats.json} exists and is readable,
  * loads queryLog and requestLog from it. If the file doesn't exist or cannot
  * be read, leaves logs empty and prints a warning.
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
  * Effects: Creates {@code local/} directory if needed, then writes
  * queryLog and requestLog to {@code local/wikimediator_stats.json} in
  * JSON format. If saving fails, prints a warning but does not throw.
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
}
