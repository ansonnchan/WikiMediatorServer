package fsft.fsftbuffer;

/**
 * Represents an object that can be stored in an FSFTBuffer.
 * Objects must provide a unique identifier for cache lookup.
 *
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
