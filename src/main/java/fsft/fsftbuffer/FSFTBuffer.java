package fsft.fsftbuffer;

import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * A thread-safe finite-space finite-time buffer that caches objects implementing
 * the Bufferable interface. Objects are automatically removed when they become
 * stale (exceed timeout duration) or when the buffer reaches capacity (using
 * Least Recently Used eviction policy).
 *
 * <p>This implementation uses a read-write lock to allow concurrent reads while
 * ensuring exclusive access for writes and structural modifications.
 *
 * <p><b>Abstraction Function:</b>
 * AF(capacity, timeout, map) = A cache containing at most 'capacity' non-stale
 * entries, where each entry represents a Bufferable object that expires 'timeout'
 * duration after its last refresh. Entries are uniquely identified by their id()
 * and evicted using LRU policy when capacity is reached.
 *
 * <p><b>Rep Invariant:</b>
 * <ul>
 *   <li>capacity > 0</li>
 *   <li>timeout is non-null and positive (not zero or negative)</li>
 *   <li>map is non-null</li>
 *   <li>All keys in map equal their corresponding entry.value.id()</li>
 *   <li>Number of non-stale entries <= capacity after any public method completes</li>
 * </ul>
 *
 * <p><b>Thread Safety Argument:</b>
 * This class is thread-safe through the following mechanisms:
 * <ul>
 *   <li>All mutable shared state (map, insertionCounter) is guarded by a ReadWriteLock</li>
 *   <li>Read operations (get) acquire write lock since they modify lastAccess/accessOrder</li>
 *   <li>Write operations (put, touch) acquire write lock for exclusive access</li>
 *   <li>Internal Entry objects use volatile fields for visibility across threads</li>
 *   <li>ConcurrentHashMap provides additional thread-safety for map operations</li>
 *   <li>insertionCounter provides total ordering for operations, resolving timestamp ties</li>
 * </ul>
 */
public class FSFTBuffer<B extends Bufferable> {
 
 /* Default buffer size is 32 objects */
 public static final int DEFAULT_CAPACITY = 32;
 
 /* Default timeout value is 180 seconds */
 public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
 
 private final int capacity;
 private final Duration timeout;
 private final ConcurrentHashMap<String, Entry<B>> map;
 private final ReadWriteLock lock;
 private long insertionCounter = 0;  // For ordering when timestamps are equal
 
 /**
  * Represents a single entry in the buffer with timing metadata.
  *
  * <p>Thread Safety: Fields are volatile to ensure visibility across threads.
  * The Entry itself is immutable except for its timing fields.
  */
 private static final class Entry<V> {
  final V value;
  volatile Instant lastRefresh;  // For staleness calculation
  volatile Instant lastAccess;   // For LRU eviction
  volatile long accessOrder;     // Tiebreaker when timestamps are equal
  
  Entry(V value, Instant putTime, Instant accessTime, long order) {
   this.value = value;
   this.lastRefresh = putTime;
   this.lastAccess = accessTime;
   this.accessOrder = order;
  }
 }
 
 /**
  * Creates a buffer with specified capacity and timeout duration.
  *
  * @param capacity the maximum number of non-stale objects the buffer can hold,
  *                 must be positive
  * @param timeout  the duration after which an object becomes stale if not refreshed,
  *                 must be positive
  * @throws IllegalArgumentException if capacity <= 0 or timeout is null/non-positive
  */
 public FSFTBuffer(int capacity, Duration timeout) {
  if (capacity <= 0) {
   throw new IllegalArgumentException("capacity must be positive, got: " + capacity);
  }
  if (timeout == null || timeout.isNegative() || timeout.isZero()) {
   throw new IllegalArgumentException("timeout must be positive and non-null");
  }
  
  this.capacity = capacity;
  this.timeout = timeout;
  this.map = new ConcurrentHashMap<>();
  this.lock = new ReentrantReadWriteLock();
 }
 
 /**
  * Creates a buffer with default capacity (32) and default timeout (180 seconds).
  */
 public FSFTBuffer() {
  this(DEFAULT_CAPACITY, DEFAULT_TIMEOUT);
 }
 
 /**
  * Adds or updates an object in the buffer. If the object's id already exists,
  * its timeout is refreshed but its last access time is preserved (per LRU spec).
  * If the buffer is at capacity, the least recently accessed non-stale entry is evicted.
  *
  * <p>Spec Note: "The first put of an item into a cache does affect its access time.
  * Subsequently, we will only consider reads (gets) as affecting the access time for
  * LRU implementations."
  *
  * <p>Effects: Removes all stale entries, adds/updates the entry for b,
  * evicts LRU entries if necessary to maintain capacity constraint.
  *
  * @param b the object to add, must be non-null
  * @return true if the object was successfully added or updated, false if b is null
  */
 public boolean put(B b) {
  if (b == null) {
   return false;
  }
  
  lock.writeLock().lock();
  try {
   Instant now = Instant.now();
   removeExpiredUnsafe(now);
   
   String id = b.id();
   Entry<B> existing = map.get(id);
   
   // First put: sets initial access time and order
   // Subsequent put: preserves existing access time and order (only get() updates it)
   Instant accessTime = (existing != null) ? existing.lastAccess : now;
   long accessOrder = (existing != null) ? existing.accessOrder : insertionCounter++;
   
   Entry<B> newEntry = new Entry<>(b, now, accessTime, accessOrder);
   map.put(id, newEntry);
   
   // Evict LRU entries until we're at capacity
   while (map.size() > capacity) {
    evictLRUUnsafe();
   }
   
   return true;
  } finally {
   lock.writeLock().unlock();
  }
 }
 
 /**
  * Retrieves an object from the buffer by its id and updates its last access time
  * for LRU tracking.
  *
  * <p>Effects: Removes all stale entries, updates lastAccess time for the retrieved
  * entry if found and non-stale.
  *
  * @param id the unique identifier of the object to retrieve, must be non-null
  * @return the object with the given id
  * @throws IllegalArgumentException if id is null
  * @throws IllegalStateException if no non-stale object with the given id exists
  */
 public B get(String id) {
  if (id == null) {
   throw new IllegalArgumentException("id cannot be null");
  }
  
  lock.writeLock().lock();  // Write lock because we update lastAccess
  try {
   Instant now = Instant.now();
   removeExpiredUnsafe(now);
   
   Entry<B> entry = map.get(id);
   if (entry == null) {
    throw new IllegalStateException("Object with id '" + id + "' not in buffer");
   }
   
   // Update last access time AND order for LRU tracking
   entry.lastAccess = now;
   entry.accessOrder = insertionCounter++;
   return entry.value;
  } finally {
   lock.writeLock().unlock();
  }
 }
 
 /**
  * Refreshes the timeout for an object without affecting its LRU position.
  * The object's timeout is extended to (current_time + timeout_duration).
  *
  * <p>Note: This does NOT update the last access time, so it doesn't affect
  * LRU eviction priority.
  *
  * <p>Effects: Removes all stale entries, updates lastRefresh time for the
  * entry with given id if found and non-stale.
  *
  * @param id the unique identifier of the object to touch, must be non-null
  * @return true if the object was successfully touched, false if id is null
  *         or no non-stale object with the given id exists
  */
 public boolean touch(String id) {
  if (id == null) {
   return false;
  }
  
  lock.writeLock().lock();
  try {
   Instant now = Instant.now();
   removeExpiredUnsafe(now);
   
   Entry<B> entry = map.get(id);
   if (entry == null) {
    return false;
   }
   
   // Only update lastRefresh, NOT lastAccess (per spec)
   entry.lastRefresh = now;
   return true;
  } finally {
   lock.writeLock().unlock();
  }
 }
 
 /**
  * Removes all entries that have exceeded their timeout duration.
  *
  * <p>Requires: Write lock must be held by caller.
  *
  * @param now the current time for staleness calculation
  */
 private void removeExpiredUnsafe(Instant now) {
  if (map.isEmpty()) {
   return;
  }
  
  Iterator<Map.Entry<String, Entry<B>>> iter = map.entrySet().iterator();
  while (iter.hasNext()) {
   Map.Entry<String, Entry<B>> mapEntry = iter.next();
   Entry<B> entry = mapEntry.getValue();
   
   Duration age = Duration.between(entry.lastRefresh, now);
   if (age.compareTo(timeout) > 0) {
    iter.remove();
   }
  }
 }
 
 /**
  * Evicts the least recently used (accessed) entry from the buffer.
  * Uses lastAccess timestamp first, then accessOrder as tiebreaker.
  *
  * <p>Requires: Write lock must be held by caller.
  * <p>Effects: Removes one entry with the oldest lastAccess timestamp
  * (or lowest accessOrder if timestamps are equal).
  */
 private void evictLRUUnsafe() {
  if (map.isEmpty()) {
   return;
  }
  
  String lruKey = null;
  Instant oldestAccess = null;
  long oldestOrder = Long.MAX_VALUE;
  
  for (Map.Entry<String, Entry<B>> mapEntry : map.entrySet()) {
   Entry<B> entry = mapEntry.getValue();
   
   // Compare: first by timestamp, then by order
   boolean isOlder = false;
   if (oldestAccess == null) {
    isOlder = true;
   } else if (entry.lastAccess.isBefore(oldestAccess)) {
    isOlder = true;
   } else if (entry.lastAccess.equals(oldestAccess) && entry.accessOrder < oldestOrder) {
    isOlder = true;
   }
   
   if (isOlder) {
    oldestAccess = entry.lastAccess;
    oldestOrder = entry.accessOrder;
    lruKey = mapEntry.getKey();
   }
  }
  
  if (lruKey != null) {
   map.remove(lruKey);
  }
 }
}
