package fsft.wikipedia;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import cryptography.AccessToken;
import cryptography.AESCipher;
import cryptography.BlowfishCipher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * A multi-threaded server that wraps WikiMediator with authentication,
 * encryption, and network communication capabilities.
 * <p>
 * This server provides:
 * <ul>
 * <li>User authentication with access tokens (hashed storage using Blowfish)</li>
 * <li>Session management with short-lived session keys for reduced token exposure</li>
 * <li>AES encryption for all client-server communication</li>
 * <li>Concurrent request handling up to the configured concurrency level</li>
 * <li>Optional timeout support for long-running Wikipedia operations</li>
 * <li>Persistence of user credentials across server restarts</li>
 * </ul>
 * <p>
 * Protocol:
 * <ol>
 * <li>Client connects and sends encrypted "initiate_session" request with access_token</li>
 * <li>Server verifies token and returns session_key</li>
 * <li>Client uses session_key for all subsequent requests</li>
 * <li>All messages are AES-encrypted with the shared key</li>
 * </ol>
 * <p>
 * Instances of WikiMediatorServer are thread-safe and handle concurrent client
 * connections up to the specified concurrency level.
 */
public class WikiMediatorServer {
 
 private static final String USERS_FILE = "local/users.json";
 private static final String AES_KEY =
  "The sky above the port was the color of television, tuned to a dead channel.";
 private static final Pattern USERNAME_PATTERN =
  Pattern.compile("^[a-zA-Z][a-zA-Z0-9_.][a-zA-Z0-9]{4,15}$");
 
 private final int port;
 private final int concurrencyLevel;
 private final WikiMediator mediator;
 private final ConcurrentHashMap<String, String> userDatabase;
 private final ConcurrentHashMap<String, String> sessionKeys;
 private final Gson gson;
 private final AESCipher aesCipher;
 private final ExecutorService timeoutExecutor;
 
 private ServerSocket serverSocket;
 private ExecutorService executor;
 private volatile boolean running;
 
 /**
  * Abstraction Function:
  *  AF(port, concurrencyLevel, mediator, userDatabase, sessionKeys, running) =
  *    A server listening on 'port' that handles up to 'concurrencyLevel' concurrent
  *    client requests. The server wraps 'mediator' to provide encrypted network
  *    access to Wikipedia services with authentication.
  *
  *    Registered users are stored in 'userDatabase' as (username -> hashedToken)
  *    pairs, where hashedToken is a Blowfish-salted hash of the access token.
  *
  *    Active sessions are tracked in 'sessionKeys' as (sessionKey -> username)
  *    mappings, allowing clients to use short-lived session keys instead of
  *    repeatedly sending their full access tokens.
  *
  *    The server is actively accepting connections iff running == true.
  *
  * Representation Invariant:
  *  - 0 <= port <= 65535
  *  - concurrencyLevel > 0
  *  - mediator != null
  *  - userDatabase != null
  *  - sessionKeys != null
  *  - gson != null
  *  - aesCipher != null
  *  - timeoutExecutor != null
  *  - For all entries in userDatabase:
  *      * key (username) matches USERNAME_PATTERN
  *      * value is a Blowfish-hashed token (non-null, non-empty)
  *  - For all entries in sessionKeys:
  *      * key (sessionKey) is non-null and non-empty
  *      * value (username) is non-null and non-empty
  *  - If running == true, then serverSocket != null && executor != null
  *  - If running == false after construction, then server has been shut down
  *
  * Thread Safety Argument:
  *  This class is thread-safe through the following mechanisms:
  *
  *  1. User Database and Session Management:
  *     - userDatabase and sessionKeys use ConcurrentHashMap, providing thread-safe
  *       concurrent read/write operations
  *     - ConcurrentHashMap's iterators are weakly consistent: they reflect the state
  *       at some point at or after creation, and won't throw ConcurrentModificationException
  *     - Iteration in verifyAccessToken() may miss very recently added users, which
  *       is acceptable (users can retry authentication)
  *
  *  2. Request Handling:
  *     - The executor is a fixed thread pool of size concurrencyLevel, limiting
  *       concurrent request processing
  *     - Each ClientHandler processes one connection independently in its own thread
  *     - Multiple ClientHandlers can run concurrently, each managing separate sockets
  *     - Socket I/O is thread-confined: each ClientHandler owns its socket exclusively
  *
  *  3. WikiMediator Access:
  *     - The WikiMediator instance is thread-safe (as documented in WikiMediator)
  *     - Multiple threads can call mediator methods concurrently
  *
  *  4. Shutdown Coordination:
  *     - The 'running' flag is volatile, ensuring visibility of shutdown across threads
  *     - shutdown() is synchronized to prevent concurrent shutdown attempts
  *     - shutdown() closes serverSocket first (stopping new connections), then
  *       shuts down executors (allowing in-flight requests to complete or timeout)
  *
  *  5. File I/O:
  *     - loadUsers() is only called during construction (single-threaded context)
  *     - saveUsers() may be called concurrently from addUser() and shutdown()
  *     - saveUsers() creates a snapshot copy (new HashMap<>(userDatabase)) before
  *       writing, so concurrent modifications don't affect the write operation
  *     - Concurrent writes to the same file may interleave, but each write is atomic
  *       at the OS level for small files
  *     - shutdown() is synchronized, so shutdown's saveUsers() won't race with itself
  *
  *  6. Immutable/Stateless Components:
  *     - Gson is thread-safe for read operations (no mutable shared state)
  *     - AESCipher is stateless and thread-safe (each encrypt/decrypt is independent)
  *     - Pattern (USERNAME_PATTERN) is immutable after compilation
  *
  *  7. Executor Lifecycle:
  *     - executor is created in startServer() before accepting any connections
  *     - timeoutExecutor is created in constructor before server starts
  *     - Both executors are shut down in shutdown() after serverSocket is closed
  *     - This ensures no new tasks are submitted after shutdown begins
  *
  *  8. No Escape of Mutable State:
  *     - ClientHandler instances don't expose their sockets
  *     - Internal maps (userDatabase, sessionKeys) are never returned to clients
  *     - All responses are newly created JsonObject instances
  *
  *  Lock Ordering (to prevent deadlock):
  *     - No nested lock acquisition occurs
  *     - The synchronized shutdown() method doesn't acquire any other locks
  *     - ConcurrentHashMap operations are lock-free from the caller's perspective
  */
 
 /**
  * Container for user data persistence.
  * Used for JSON serialization/deserialization with Gson.
  */
 private static class UserData {
  Map<String, String> users;
  
  /**
   * Creates an empty user data container.
   */
  UserData() {
   this.users = new HashMap<>();
  }
 }
 
 /**
  * Creates and starts a new WikiMediatorServer.
  * <p>
  * Effects: Constructs a server that listens on the specified port and
  * handles up to concurrencyLevel concurrent client requests. Creates a
  * new WikiMediator instance for serving Wikipedia requests. Loads existing
  * user data from {@code local/users.json} if available (otherwise starts
  * with empty user database). Starts a background thread that accepts client
  * connections and submits them to the executor pool. The server begins
  * accepting connections immediately upon construction.
  *
  * @param port             the port number to listen on; must be between 0 and 65535
  * @param concurrencyLevel the maximum number of concurrent client requests
  *                         to handle; must be positive
  * @throws IllegalArgumentException if port is invalid or concurrencyLevel <= 0
  */
 public WikiMediatorServer(int port, int concurrencyLevel) {
  if (concurrencyLevel <= 0) {
   throw new IllegalArgumentException("concurrencyLevel must be positive");
  }
  if (port < 0 || port > 65535) {
   throw new IllegalArgumentException("port must be between 0 and 65535");
  }
  
  this.port = port;
  this.concurrencyLevel = concurrencyLevel;
  this.mediator = new WikiMediator();
  this.userDatabase = new ConcurrentHashMap<>();
  this.sessionKeys = new ConcurrentHashMap<>();
  this.gson = new GsonBuilder().setPrettyPrinting().create();
  this.aesCipher = new AESCipher(AES_KEY);
  this.timeoutExecutor = Executors.newCachedThreadPool();
  this.running = false;
  
  loadUsers();
  startServer();
 }
 
 /**
  * Adds a new user to the server and returns their access token.
  * <p>
  * Username requirements (regex: {@code ^[a-zA-Z][a-zA-Z0-9_.][a-zA-Z0-9]{4,15}$}):
  * <ul>
  * <li>Total length: 6-17 characters</li>
  * <li>First character: letter (a-z, A-Z)</li>
  * <li>Second character: letter, digit, underscore, or dot</li>
  * <li>Remaining 4-15 characters: letters or digits only</li>
  * </ul>
  * <p>
  * Effects: If userName is valid and not already registered:
  * <ol>
  * <li>Generates a new access token using {@code AccessToken.getAccessToken()}</li>
  * <li>Hashes the token with Blowfish (salted) using {@code BlowfishCipher}</li>
  * <li>Stores the mapping (userName -> hashedToken) in userDatabase</li>
  * <li>Persists all user data to {@code local/users.json}</li>
  * <li>Returns the plaintext access token (client must save this)</li>
  * </ol>
  * If userName is null, doesn't match the pattern, or already exists, returns null.
  * <p>
  * Security: The plaintext access token is returned only once and is never stored.
  * Only the salted Blowfish hash is persisted to disk.
  *
  * @param userName the username to register; must match USERNAME_PATTERN and
  *                 must not already be registered
  * @return the plaintext access token for this user, or null if userName is
  * invalid or already exists
  */
 public String addUser(String userName) {
  if (userName == null || !USERNAME_PATTERN.matcher(userName).matches()) {
   return null;
  }
  
  if (userDatabase.containsKey(userName)) {
   return null;
  }
  
  String accessToken = AccessToken.getAccessToken();
  String hashedToken = BlowfishCipher.hashPassword(accessToken, BlowfishCipher.gensalt());
  
  String existing = userDatabase.putIfAbsent(userName, hashedToken);
  if (existing != null) {
   
   return null;
  }
  
  saveUsers();
  
  return accessToken;
 }
 
 /**
  * Shuts down the server, closing all connections and saving state.
  * <p>
  * This method is synchronized to ensure only one shutdown occurs even if
  * called concurrently from multiple threads.
  * <p>
  * Effects: If not already shut down:
  * <ol>
  * <li>Sets running flag to false (stops accepting new connections)</li>
  * <li>Closes the server socket (interrupts accept() call)</li>
  * <li>Initiates graceful shutdown of the executor (waits up to 5 seconds)</li>
  * <li>If tasks don't complete in 5 seconds, forcibly terminates them</li>
  * <li>Shuts down the timeout executor (same 5-second grace period)</li>
  * <li>Calls mediator.shutdown() to save WikiMediator statistics</li>
  * <li>Saves user data to {@code local/users.json}</li>
  * </ol>
  * If an error occurs during shutdown, logs to stderr but completes remaining
  * shutdown steps. If already shut down (running == false), returns immediately
  * without doing anything.
  */
 public synchronized void shutdown() {
  if (!running) {
   return;
  }
  
  running = false;
  
  try {
   if (serverSocket != null && !serverSocket.isClosed()) {
    serverSocket.close();
   }
  } catch (IOException e) {
   System.err.println("Error closing server socket: " + e.getMessage());
  }
  
  if (executor != null) {
   executor.shutdown();
   try {
    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
     System.err.println("Executor did not terminate in time, forcing shutdown");
     executor.shutdownNow();
    }
   } catch (InterruptedException e) {
    executor.shutdownNow();
    Thread.currentThread().interrupt();
   }
  }
  
  timeoutExecutor.shutdown();
  try {
   if (!timeoutExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
    System.err.println("Timeout executor did not terminate in time, forcing shutdown");
    timeoutExecutor.shutdownNow();
   }
  } catch (InterruptedException e) {
   timeoutExecutor.shutdownNow();
   Thread.currentThread().interrupt();
  }
  
  mediator.shutdown();
  saveUsers();
 }
 
 /**
  * Starts the server in a background thread.
  * <p>
  * Effects: Creates a fixed thread pool of size concurrencyLevel for handling
  * client connections. Creates a ServerSocket on the configured port. Starts
  * a NON-DAEMON thread that accepts client connections in a loop while the
  * running flag is true. Each accepted connection is wrapped in a ClientHandler
  * and submitted to the executor. If ServerSocket creation fails, logs error
  * to stderr and the server will not accept connections (but the WikiMediatorServer
  * object is still constructed).
  * <p>
  * The server thread continues running until shutdown() is called, which sets
  * running to false and closes the serverSocket.
  */
 private void startServer() {
  executor = Executors.newFixedThreadPool(concurrencyLevel);
  running = true;
  
  Thread serverThread = new Thread(() -> {
   try {
    serverSocket = new ServerSocket(port);
    System.out.println("WikiMediatorServer started on port " + port);
    
    while (running) {
     try {
      Socket clientSocket = serverSocket.accept();
      executor.submit(new ClientHandler(clientSocket));
     } catch (IOException e) {
      if (running) {
       System.err.println("Error accepting connection: " + e.getMessage());
      }
     }
    }
   } catch (IOException e) {
    System.err.println("Failed to start server: " + e.getMessage());
   }
  });
  
  serverThread.setDaemon(false);
  serverThread.start();
 }
 
 /**
  * Handles communication with a single client connection.
  * <p>
  * Each ClientHandler manages the complete lifecycle of one client socket
  * connection. It reads encrypted JSON requests line-by-line, decrypts them
  * using AES, routes them to appropriate handlers based on request type,
  * encrypts the JSON responses, and writes them back to the client. The
  * handler continues processing requests until the client closes the connection
  * (EOF on input stream) or an unrecoverable error occurs.
  * <p>
  * Thread Safety: Each ClientHandler instance runs in its own thread from the
  * executor pool and manages one socket connection independently. Multiple
  * ClientHandlers execute concurrently (limited by concurrencyLevel). All
  * access to shared state (userDatabase, sessionKeys, mediator) uses thread-safe
  * data structures and methods. Socket I/O is thread-confined (each socket is
  * accessed by only one thread).
  * <p>
  * Error Handling: If a request cannot be decrypted or parsed, sends an error
  * response and continues processing (doesn't close connection). If socket I/O
  * fails, logs error and closes connection.
  * <p>
  * Effects: For each line read from the socket input stream:
  * <ol>
  * <li>Decrypts the line using AES</li>
  * <li>Parses as JSON and routes to handleRequest()</li>
  * <li>Encrypts the JSON response using AES</li>
  * <li>Writes encrypted response to socket output stream</li>
  * </ol>
  * On completion or error, closes the socket. Logs errors to stderr.
  */
 private class ClientHandler implements Runnable {
  private final Socket socket;
  
  /**
   * Creates a handler for the given client socket.
   *
   * @param socket the client connection to handle; must be non-null and open
   */
  ClientHandler(Socket socket) {
   this.socket = socket;
  }
  
  /**
   * Processes incoming client requests until the client disconnects or an I/O
   * error occurs.
   * <p>
   * Requires:
   * - socket, gson, and aesCipher are non-null and initialized;
   * - socket is open and connected to a client.
   * <p>
   * Effects:
   * - Repeatedly reads one line of AES-encrypted text from the socket;
   * - decrypts the line and parses it into a JsonObject;
   * - routes the request to handleRequest();
   * - encrypts and sends the JSON response back to the client;
   * - on any request-processing error, sends an encrypted error response and
   * continues processing;
   * - closes the socket upon termination;
   * - logs any encountered errors to stderr.
   * <p>
   * This method never throws exceptions to its caller.
   */
  @Override
  public void run() {
   try (BufferedReader in = new BufferedReader(
    new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
    
    String encryptedRequest;
    while ((encryptedRequest = in.readLine()) != null) {
     try {
      encryptedRequest = encryptedRequest.trim();
      String decryptedRequest = aesCipher.decrypt(encryptedRequest);
      
      if (decryptedRequest == null) {
       throw new IllegalArgumentException("Decryption failed or returned null");
      }
      
      JsonObject request = gson.fromJson(decryptedRequest, JsonObject.class);
      
      if (request == null) {
       throw new IllegalArgumentException("JSON parsing returned null");
      }
      
      JsonObject response = handleRequest(request);
      
      String jsonResponse = gson.toJson(response);
      String encryptedResponse = aesCipher.encrypt(jsonResponse);
      out.println(encryptedResponse);
      
     } catch (Exception e) {
      System.err.println("Error processing request: " + e.getMessage());
      e.printStackTrace();
      
      try {
       JsonObject errorResponse = new JsonObject();
       errorResponse.addProperty("status", "failed");
       errorResponse.addProperty("response",
        "Invalid request: " + e.getMessage());
       
       String jsonError = gson.toJson(errorResponse);
       String encryptedError = aesCipher.encrypt(jsonError);
       out.println(encryptedError);
      } catch (Exception e2) {
       System.err.println("Could not send error response: " + e2.getMessage());
      }
     }
    }
   } catch (IOException e) {
    System.err.println("Client connection error: " + e.getMessage());
   } finally {
    try {
     socket.close();
    } catch (IOException e) {
    }
   }
  }
 }
 
 /**
  * Routes a decrypted JSON request to the appropriate handler.
  * <p>
  * Request types:
  * <ul>
  * <li>"initiate_session": Authenticate and create session (no session key required)</li>
  * <li>"search": Search Wikipedia (requires valid session key)</li>
  * <li>"getPage": Get Wikipedia page content (requires valid session key)</li>
  * <li>"zeitgeist": Get trending queries (requires valid session key)</li>
  * <li>"peakLoad": Get peak request load (requires valid session key)</li>
  * </ul>
  * <p>
  * Requires: request is a valid JsonObject (not null)
  * <p>
  * Effects: Validates request structure, checks session key (except for
  * initiate_session), dispatches to the appropriate handler method based
  * on the "type" field, and returns the handler's response.
  *
  * @param request the decrypted JSON request object; must have a "type" field
  * @return a JSON response object with "status" ("success" or "failed"),
  * "response" (result or error message), and optionally "id" and "session_key"
  */
 private JsonObject handleRequest(JsonObject request) {
  if (request == null) {
   return createErrorResponse(null, null, "Request is null");
  }
  
  String type = request.has("type") ? request.get("type").getAsString() : null;
  
  if (type == null) {
   return createErrorResponse(null, null, "Missing request type");
  }
  
  if ("initiate_session".equals(type)) {
   return handleInitiateSession(request);
  }
  
  String sessionKey = request.has("session_key") ?
   request.get("session_key").getAsString() : null;
  
  if (sessionKey == null || !sessionKeys.containsKey(sessionKey)) {
   return createErrorResponse(null, null, "Invalid or missing session key");
  }
  
  String id = request.has("id") ? request.get("id").getAsString() : null;
  
  switch (type) {
   case "search":
    return handleSearch(request, sessionKey, id);
   case "getPage":
    return handleGetPage(request, sessionKey, id);
   case "zeitgeist":
    return handleZeitgeist(request, sessionKey, id);
   case "peakLoad":
    return handlePeakLoad(request, sessionKey, id);
   default:
    return createErrorResponse(sessionKey, id, "Unknown request type: " + type);
  }
 }
 
 /**
  * Handles session initiation requests.
  * <p>
  * Requires: request contains "access_token" field with a String value
  * <p>
  * Effects: Extracts the access_token from the request and verifies it
  * against all stored user credentials by checking if the Blowfish hash
  * of the provided token matches any stored hash. If valid:
  * <ol>
  * <li>Generates a new session key using {@code AccessToken.getAccessToken()}</li>
  * <li>Maps the session key to the authenticated username in sessionKeys</li>
  * <li>Returns success response with the session key</li>
  * </ol>
  * If the token is missing or doesn't match any user, returns failure response
  * with "Authentication Failure" message.
  *
  * @param request JSON object that must contain "access_token" field
  * @return JSON response with status "success" and "session_key" if authenticated,
  * or status "failure" and error message if authentication fails
  */
 private JsonObject handleInitiateSession(JsonObject request) {
  String accessToken = request.has("access_token") ?
   request.get("access_token").getAsString() : null;
  
  if (accessToken == null) {
   JsonObject response = new JsonObject();
   response.addProperty("status", "failure");
   response.addProperty("response", "Missing access token");
   return response;
  }
  
  String userName = verifyAccessToken(accessToken);
  if (userName == null) {
   JsonObject response = new JsonObject();
   response.addProperty("status", "failure");
   response.addProperty("response", "Authentication Failure");
   return response;
  }
  
  String sessionKey = AccessToken.getAccessToken();
  sessionKeys.put(sessionKey, userName);
  
  JsonObject response = new JsonObject();
  response.addProperty("status", "success");
  response.addProperty("session_key", sessionKey);
  return response;
 }
 
 /**
  * Verifies an access token against the user database.
  * <p>
  * Uses Blowfish's {@code verifyPassword} to check if the provided plaintext
  * token matches any stored hashed token. This method is secure against timing
  * attacks because Blowfish verification is constant-time per comparison.
  * <p>
  * Thread Safety: Iterates over userDatabase using ConcurrentHashMap's weakly
  * consistent iterator. May miss very recently added users (they can retry),
  * but will never throw ConcurrentModificationException.
  *
  * @param accessToken the plaintext access token to verify; must be non-null
  * @return the username associated with this token if valid, null otherwise
  */
 private String verifyAccessToken(String accessToken) {
  for (Map.Entry<String, String> entry : userDatabase.entrySet()) {
   if (BlowfishCipher.verifyPassword(accessToken, entry.getValue())) {
    return entry.getKey();
   }
  }
  return null;
 }
 
 /**
  * Handles Wikipedia search requests.
  * <p>
  * Requires: request contains "searchTerm" (String) and "limit" (int) fields.
  * sessionKey must be valid (already verified by caller).
  * <p>
  * Effects: Extracts searchTerm and limit from request. If request contains
  * "timeout" field (int, in seconds), executes search with timeout using
  * timeoutExecutor. If timeout is exceeded, cancels the operation and returns
  * error response. Otherwise executes search directly via mediator.search().
  * Returns success response with list of page titles, or error response if
  * search fails.
  *
  * @param request    JSON object containing "searchTerm", "limit", and optional "timeout"
  * @param sessionKey the validated session key to include in response
  * @param id         the request ID to include in response (may be null)
  * @return JSON response with status "success" and search results (List&lt;String&gt;),
  * or status "failed" with error message
  */
 private JsonObject handleSearch(JsonObject request, String sessionKey, String id) {
  try {
   String searchTerm = request.get("searchTerm").getAsString();
   int limit = request.get("limit").getAsInt();
   
   if (request.has("timeout")) {
    int timeout = request.get("timeout").getAsInt();
    return executeWithTimeout(() -> mediator.search(searchTerm, limit),
     timeout, sessionKey, id);
   }
   
   List<String> results = mediator.search(searchTerm, limit);
   return createSuccessResponse(sessionKey, id, results);
   
  } catch (Exception e) {
   return createErrorResponse(sessionKey, id, "Search failed: " + e.getMessage());
  }
 }
 
 /**
  * Handles Wikipedia getPage requests.
  * <p>
  * Requires: request contains "pageTitle" (String) field.
  * sessionKey must be valid (already verified by caller).
  * <p>
  * Effects: Extracts pageTitle from request. If request contains "timeout"
  * field (int, in seconds), executes getPage with timeout using timeoutExecutor.
  * If timeout is exceeded, cancels the operation and returns error response.
  * Otherwise executes getPage directly via mediator.getPage(). Returns success
  * response with page content (String), or error response if operation fails.
  *
  * @param request    JSON object containing "pageTitle" and optional "timeout"
  * @param sessionKey the validated session key to include in response
  * @param id         the request ID to include in response (may be null)
  * @return JSON response with status "success" and page content (String),
  * or status "failed" with error message
  */
 private JsonObject handleGetPage(JsonObject request, String sessionKey, String id) {
  try {
   String pageTitle = request.get("pageTitle").getAsString();
   
   if (request.has("timeout")) {
    int timeout = request.get("timeout").getAsInt();
    return executeWithTimeout(() -> mediator.getPage(pageTitle),
     timeout, sessionKey, id);
   }
   
   String content = mediator.getPage(pageTitle);
   return createSuccessResponse(sessionKey, id, content);
   
  } catch (Exception e) {
   return createErrorResponse(sessionKey, id, "GetPage failed: " + e.getMessage());
  }
 }
 
 /**
  * Handles zeitgeist requests.
  * <p>
  * Requires: request contains "duration" (int, in seconds) and "limit" (int) fields.
  * sessionKey must be valid (already verified by caller).
  * <p>
  * Effects: Extracts duration (converted to Duration) and limit from request.
  * Calls mediator.zeitgeist() to get the most common queries in the time window.
  * Returns success response with list of query strings sorted by frequency, or
  * error response if operation fails.
  *
  * @param request    JSON object containing "duration" and "limit"
  * @param sessionKey the validated session key to include in response
  * @param id         the request ID to include in response (may be null)
  * @return JSON response with status "success" and trending queries (List&lt;String&gt;),
  * or status "failed" with error message
  */
 private JsonObject handleZeitgeist(JsonObject request, String sessionKey, String id) {
  try {
   int durationSeconds = request.get("duration").getAsInt();
   int limit = request.get("limit").getAsInt();
   
   List<String> results = mediator.zeitgeist(Duration.ofSeconds(durationSeconds), limit);
   return createSuccessResponse(sessionKey, id, results);
   
  } catch (Exception e) {
   return createErrorResponse(sessionKey, id, "Zeitgeist failed: " + e.getMessage());
  }
 }
 
 /**
  * Handles peakLoad requests.
  * <p>
  * Requires: request contains "duration" (int, in seconds) field.
  * sessionKey must be valid (already verified by caller).
  * <p>
  * Effects: Extracts duration (converted to Duration) from request. Calls
  * mediator.peakLoad() to get the maximum number of requests in any time
  * window of the specified duration. Returns success response with peak
  * load count (int), or error response if operation fails.
  *
  * @param request    JSON object containing "duration"
  * @param sessionKey the validated session key to include in response
  * @param id         the request ID to include in response (may be null)
  * @return JSON response with status "success" and peak load (int),
  * or status "failed" with error message
  */
 private JsonObject handlePeakLoad(JsonObject request, String sessionKey, String id) {
  try {
   int durationSeconds = request.get("duration").getAsInt();
   
   int peak = mediator.peakLoad(Duration.ofSeconds(durationSeconds));
   return createSuccessResponse(sessionKey, id, peak);
   
  } catch (Exception e) {
   return createErrorResponse(sessionKey, id, "PeakLoad failed: " + e.getMessage());
  }
 }
 
 /**
  * Executes an operation with a timeout using the shared timeoutExecutor.
  * <p>
  * This method submits the operation to a thread pool, waits up to the
  * specified timeout, and cancels the task if it doesn't complete in time.
  * <p>
  * Uses the shared timeoutExecutor pool (cached thread pool) to avoid
  * creating new executors for each timeout operation, which would be
  * expensive and could lead to thread exhaustion.
  * <p>
  * Effects: Submits operation to timeoutExecutor. If it completes within
  * timeoutSeconds, returns success response with result. If it times out,
  * cancels the future (with interrupt flag = true) and returns error
  * response. If operation throws exception, returns error response.
  *
  * @param operation      the operation to execute; must be non-null
  * @param timeoutSeconds the maximum time to wait in seconds; must be positive
  * @param sessionKey     the session key to include in response
  * @param id             the request ID to include in response (may be null)
  * @param <T>            the result type of the operation
  * @return success response with result of type T, or error response if
  * timeout or failure occurs
  */
 private <T> JsonObject executeWithTimeout(Callable<T> operation, int timeoutSeconds,
                                           String sessionKey, String id) {
  Future<T> future = timeoutExecutor.submit(operation);
  
  try {
   T result = future.get(timeoutSeconds, TimeUnit.SECONDS);
   return createSuccessResponse(sessionKey, id, result);
  } catch (TimeoutException e) {
   future.cancel(true);
   return createErrorResponse(sessionKey, id, "Operation timed out");
  } catch (Exception e) {
   return createErrorResponse(sessionKey, id,
    "Operation failed: " + e.getMessage());
  }
 }
 
 /**
  * Creates a success response JSON object.
  * <p>
  * The response includes the session key (for client to use in next request),
  * the request ID (for client to match requests with responses), status "success",
  * and the result data in the "response" field.
  *
  * @param sessionKey the session key to include; may be null for initiate_session
  * @param id         the request ID to include; may be null if not provided
  * @param result     the result data to include in "response" field; will be
  *                   serialized to JSON by Gson
  * @return JSON object with "session_key", "id", "status": "success", and "response"
  */
 private JsonObject createSuccessResponse(String sessionKey, String id, Object result) {
  JsonObject response = new JsonObject();
  if (sessionKey != null) {
   response.addProperty("session_key", sessionKey);
  }
  if (id != null) {
   response.addProperty("id", id);
  }
  response.addProperty("status", "success");
  response.add("response", gson.toJsonTree(result));
  return response;
 }
 
 /**
  * Creates an error response JSON object.
  * <p>
  * The response includes the session key (if available), the request ID
  * (for client to match requests with responses), status "failed", and
  * the error message in the "response" field.
  *
  * @param sessionKey   the session key to include; may be null if error occurred
  *                     before authentication
  * @param id           the request ID to include; may be null if not provided
  * @param errorMessage the error message to include in "response" field
  * @return JSON object with optional "session_key", optional "id",
  * "status": "failed", and "response" with error message
  */
 private JsonObject createErrorResponse(String sessionKey, String id, String errorMessage) {
  JsonObject response = new JsonObject();
  if (sessionKey != null) {
   response.addProperty("session_key", sessionKey);
  }
  if (id != null) {
   response.addProperty("id", id);
  }
  response.addProperty("status", "failed");
  response.addProperty("response", errorMessage);
  return response;
 }
 
 /**
  * Loads user data from disk.
  * Effects: If {@code local/users.json} exists and is readable, loads the
  * username -> hashedToken mappings into userDatabase using Gson deserialization.
  * If the file doesn't exist, starts with an empty database (no error). If the
  * file exists but cannot be read or is malformed, logs warning to stderr and
  * starts with empty database.
  */
 private void loadUsers() {
  Path path = Paths.get(USERS_FILE);
  
  if (!Files.exists(path)) {
   return;
  }
  
  try (Reader reader = Files.newBufferedReader(path)) {
   UserData data = gson.fromJson(reader, UserData.class);
   if (data != null && data.users != null) {
    userDatabase.putAll(data.users);
   }
  } catch (IOException e) {
   System.err.println("Warning: Could not load users: " + e.getMessage());
  }
 }
 
 /**
  * Saves user data to disk.
  * <p>
  * May be called concurrently from addUser() and shutdown(). Creates a
  * snapshot of userDatabase before writing to avoid interference from
  * concurrent modifications.
  * <p>
  * Effects: Creates {@code local/} directory if it doesn't exist. Creates
  * a snapshot copy of userDatabase (new HashMap), serializes it to JSON
  * using Gson, and writes to {@code local/users.json}. If saving fails
  * (e.g., I/O error, insufficient permissions), logs warning to stderr but
  * does not throw exception (user data will be lost if server crashes).
  */
 private void saveUsers() {
  try {
   Path dir = Paths.get("local");
   if (!Files.exists(dir)) {
    Files.createDirectories(dir);
   }
   
   UserData data = new UserData();
   data.users = new HashMap<>(userDatabase);
   
   try (Writer writer = Files.newBufferedWriter(Paths.get(USERS_FILE))) {
    gson.toJson(data, writer);
   }
  } catch (IOException e) {
   System.err.println("Warning: Could not save users: " + e.getMessage());
  }
 }
}
