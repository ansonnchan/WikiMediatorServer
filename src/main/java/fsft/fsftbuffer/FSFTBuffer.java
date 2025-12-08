package fsft.fsftbuffer;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A thread-safe finite-space finite-time buffer that caches objects implementing
 * the Bufferable interface. Objects are automatically removed when they become
 * stale (exceed timeout duration) or when the buffer reaches capacity (using
 * Least Recently Used eviction policy).
 * <p>
 * This buffer implements a caching abstraction with both spatial and temporal
 * constraints:
 * <p>
 * - Finite Space: Limited to a maximum capacity of non-stale objects
 * - Finite Time: Objects expire after a configurable timeout duration
 * <p>
 * <p>
 * When the buffer is full and a new object needs to be added, the Least Recently
 * Used (LRU) object is evicted based on the last access time (tl). Stale objects
 * are automatically removed and do not count toward capacity.
 *
 * @param <B> the type of bufferable objects stored in this buffer
 */
public class FSFTBuffer<B extends Bufferable> {
 public static final int DEFAULT_CAPACITY = 32;
 public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);
 private final int capacity;
 private final Duration timeout;
 private final Map<String, Entry<B>> map;
 private final Lock lock;
 private long insertionCounter = 0;
 
 /**
  * Abstraction Function:
  *  AF(capacity, timeout, map, insertionCounter) = A cache that stores at most
  *  'capacity' non-stale Bufferable objects, where each object remains valid for
  *  'timeout' duration from its last refresh (put/touch). The cache contains the
  *  set of objects {map.get(id).value | id ∈ map.keySet() ∧ isNotStale(map.get(id))}.
  *  Objects are ordered by access time for LRU eviction, with 'insertionCounter'
  *  serving as a tiebreaker to maintain total ordering.
  *
  *  Representation Invariant:
  *    - capacity > 0
  *     - timeout != null && timeout > Duration.ZERO
  *     - map != null
  *     - lock != null
  *     - For all entries e in map.values():
  *         * e.value != null
  *         * e.lastRefresh != null
  *         * e.lastAccess != null
  *         * e.value.id() equals the key that maps to e
  *     - The number of non-stale entries in map <= capacity
  *     - insertionCounter >= 0
  *
  *   Thread Safety Argument:
  *     This class is thread-safe because:
  *     1. All mutable state (map, insertionCounter) is guarded by the 'lock' field
  *     2. All public methods acquire the lock before accessing or modifying shared state
  *     3. All private helper methods that access shared state are suffixed with 'Unsafe'
  *        and document that the lock must be held by the caller
  *     4. The lock is always released in a finally block, ensuring release even if
  *        exceptions occur
  *     5. No references to internal mutable state escape to clients
  *     6. Entry objects are immutable from the client's perspective (no getters exposed)
  */
 
 
 /**
  * Represents a single entry in the buffer with timing metadata.
  *
  * @param <V> the type of value stored in this entry
  */
 private static final class Entry<V> {
  V value;
  Instant lastRefresh;
  Instant lastAccess;
  long accessOrder;
  
  /**
   * Creates a new entry with the specified value and timing information.
   *
   * @param value      the bufferable object to store
   * @param putTime    the time this entry was added (tp, lastRefresh)
   * @param accessTime the time this entry was last accessed (tl, lastAccess)
   * @param order      the insertion order for LRU tiebreaking
   */
  Entry(V value, Instant putTime, Instant accessTime, long order) {
   this.value = value;
   this.lastRefresh = putTime;
   this.lastAccess = accessTime;
   this.accessOrder = order;
  }
 }
 
 /**
  * Creates a buffer with specified capacity and timeout duration.
  * <p>
  * Effects: Constructs a new empty FSFTBuffer that can hold at most 'capacity'
  * non-stale objects, where objects become stale 'timeout' duration after their
  * last refresh (put or touch operation).
  *
  * @param capacity the maximum number of non-stale objects the buffer can hold;
  *                 must be positive
  * @param timeout  the duration after which an object becomes stale if not refreshed;
  *                 must be positive and non-null
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
  this.map = new HashMap<>();
  this.lock = new ReentrantLock();
 }
 
 /**
  * Creates a buffer with default capacity (32) and default timeout (180 seconds).
  * <p>
  * Effects: Constructs a new empty FSFTBuffer with DEFAULT_CAPACITY and
  * DEFAULT_TIMEOUT.
  */
 public FSFTBuffer() {
  this(DEFAULT_CAPACITY, DEFAULT_TIMEOUT);
 }
 
 /**
  * Adds or updates an object in the buffer.
  * <p>
  * If the object (identified by b.id()) is already present in the buffer:
  * <p>
  * - Updates the stored value to b
  * - Resets the timeout: new to = now + timeout
  * - Updates last access time: tl = now
  * <p>
  * <p>
  * If the object is new and the buffer is at capacity (after removing stale
  * entries), evicts the least recently accessed (LRU) entry before adding the
  * new object.
  * <p>
  * Effects: Removes all stale entries from the buffer, then either updates the
  * existing entry for b.id() or adds a new entry (possibly after LRU eviction).
  * Sets tp = tl = now and to = now + timeout for the entry.
  *
  * @param b the object to add; must be non-null and b.id() must be non-null
  * @return true if the object was successfully added or updated, false if b is null
  */
 public boolean put(B b) {
  if (b == null) {
   return false;
  }
  
  lock.lock();
  try {
   Instant now = Instant.now();
   removeExpiredUnsafe(now);
   
   String id = b.id();
   Entry<B> existingEntry = map.get(id);
   
   if (existingEntry != null) {
    
    existingEntry.value = b;
    existingEntry.lastRefresh = now;
    existingEntry.lastAccess = now;
    existingEntry.accessOrder = insertionCounter++;
   } else {
    
    if (map.size() >= capacity) {
     evictLRUUnsafe();
    }
    
    Entry<B> newEntry = new Entry<>(b, now, now, insertionCounter++);
    map.put(id, newEntry);
   }
   
   return true;
  } finally {
   lock.unlock();
  }
 }
 
 /**
  * Retrieves an object from the buffer by its id and updates its last access time.
  * <p>
  * If a fresh (non-stale) object with the given id exists:
  * <p>
  * - Returns the object
  * - Updates last access time: tl = now
  * - Updates LRU position (moves to most recently used)
  * <p>
  * <p>
  * Does NOT affect the timeout time (to remains unchanged).
  * <p>
  * Effects: Removes all stale entries, then retrieves and updates the last
  * access time for the entry with the given id.
  *
  * @param id the unique identifier of the object to retrieve; must be non-null
  * @return the non-stale object with the given id
  * @throws IllegalArgumentException if id is null
  * @throws IllegalStateException    if no fresh object with the given id exists
  */
 public B get(String id) {
  if (id == null) {
   throw new IllegalArgumentException("id cannot be null");
  }
  
  lock.lock();
  try {
   Instant now = Instant.now();
   removeExpiredUnsafe(now);
   
   Entry<B> entry = map.get(id);
   if (entry == null) {
    throw new IllegalStateException("Object with id '" + id + "' not in buffer");
   }
   
   entry.lastAccess = now;
   entry.accessOrder = insertionCounter++;
   
   return entry.value;
  } finally {
   lock.unlock();
  }
 }
 
 /**
  * Refreshes the timeout for an object without affecting its LRU position.
  * <p>
  * If a fresh object with the given id exists, extends its lifetime by
  * resetting: to = now + timeout. Does NOT update last access time (tl) or
  * LRU position.
  * <p>
  * This operation is useful for keeping objects fresh without affecting
  * eviction order.
  * <p>
  * Effects: Removes all stale entries, then updates the lastRefresh time
  * for the entry with the given id if it exists.
  *
  * @param id the unique identifier of the object to touch; must be non-null
  * @return true if a fresh object was successfully touched, false if id is null
  * or no fresh object with the given id exists
  */
 public boolean touch(String id) {
  if (id == null) {
   return false;
  }
  
  lock.lock();
  try {
   Instant now = Instant.now();
   removeExpiredUnsafe(now);
   
   Entry<B> entry = map.get(id);
   if (entry == null) {
    return false;
   }
   
   entry.lastRefresh = now;
   return true;
  } finally {
   lock.unlock();
  }
 }
 
 /**
  * Removes all entries that have exceeded their timeout duration.
  * <p>
  * An entry is stale if: now > entry.lastRefresh + timeout
  * <p>
  * Requires: The lock must be held by the calling thread.
  * <p>
  * Effects: Removes all stale entries from the map.
  *
  * @param now the current time for staleness comparison
  */
 private void removeExpiredUnsafe(Instant now) {
  if (map.isEmpty()) {
   return;
  }
  
  Iterator<Map.Entry<String, Entry<B>>> iter = map.entrySet().iterator();
  while (iter.hasNext()) {
   Map.Entry<String, Entry<B>> mapEntry = iter.next();
   Entry<B> entry = mapEntry.getValue();
   
   Instant expirationTime = entry.lastRefresh.plus(timeout);
   
   if (now.isAfter(expirationTime)) {
    iter.remove();
   }
  }
 }
 
 /**
  * Evicts the least recently used (accessed) entry from the buffer.
  * <p>
  * LRU determination:
  * - Primary: entry with earliest lastAccess time (smallest tl)
  * - Tiebreaker: entry with smallest accessOrder value
  * Requires: The lock must be held by the calling thread.
  * <p>
  * Effects: Removes the LRU entry from the map. If the map is empty,
  * does nothing.
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
