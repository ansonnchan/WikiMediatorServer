package fsft.fsftbuffer;

/**
 * Represents an object that can be stored in an FSFTBuffer.
 * Objects must provide a unique identifier for cache lookup.
 *
 * <p>Implementations must ensure that:
 * <ul>
 *   <li>id() never returns null</li>
 *   <li>id() returns the same value for the lifetime of the object</li>
 *   <li>Two objects are considered equal for caching purposes if they have the same id()</li>
 * </ul>
 */
public interface Bufferable {
 
 /**
  * Returns a unique identifier for this object.
  * The identifier is used as the key for storing and retrieving
  * the object from a buffer.
  *
  * @return a non-null unique identifier for this object
  */
 String id();
}
