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
 * User authentication with access tokens (hashed storage)
 * Session management with short-lived session keys
 * AES encryption for all client-server communication
 * Concurrent request handling up to the configured concurrency level
 * Optional timeout support for long-running Wikipedia operations
 * Persistence of user credentials across server restarts
 *
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
  *  A server listening on 'port' that handles up to 'concurrencyLevel' concurrent
  *  requests. The server wraps 'mediator' to provide network access to Wikipedia
  *  services. Registered users are stored in 'userDatabase' as (username -> hashedToken)
  *  pairs. Active sessions are tracked in 'sessionKeys' as (sessionKey -> username)
  *  mappings. The server is active iff running == true.
  *
  *  Representation Invariant:
  *  - port is between 0 and 65535
  *  - concurrencyLevel > 0
  *  - mediator != null
  *  - userDatabase != null
  *  - sessionKeys != null
  *  - gson != null
  *  - aesCipher != null
  *  - timeoutExecutor != null
  *  - For all entries in userDatabase: key (username) matches USERNAME_PATTERN
  *  - For all entries in userDatabase: value is a Blowfish-hashed token
  *  - For all entries in sessionKeys: value is a valid username in userDatabase
  * - If running == true, then serverSocket != null and executor != null
  *
  * Thread Safety Argument:
  *  This class is thread-safe because:
  *  1. userDatabase and sessionKeys use ConcurrentHashMap for thread-safe access
  *  2. The executor manages concurrent client connections using a fixed thread pool
  *  3. Each ClientHandler processes one connection independently
  *   4. The WikiMediator instance is thread-safe (as documented in WikiMediator)
  *   5. The 'running' flag is volatile, ensuring visibility across threads
  *   6. Socket I/O is contained within each ClientHandler thread
  *  7. Gson is thread-safe for read operations (no shared mutable state)
  *   8. AESCipher is stateless and thread-safe
  *
  */
 
 /**
  * Container for user data persistence.
  */
 private static class UserData {
  Map<String, String> users;
  
  UserData() {
   this.users = new HashMap<>();
  }
 }
 
 /**
  * Creates and starts a new WikiMediatorServer.
  * <p>
  * Effects: Constructs a server that listens on the specified port,
  * handles up to concurrencyLevel concurrent requests, loads existing user
  * data from local/users.json if available, and starts accepting client
  * connections in a background thread.
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
  * Username requirements (regex: ^[a-zA-Z][a-zA-Z0-9_.][a-zA-Z0-9]{4,15}$):
  * <p>
  * Total length: 6-17 characters
  * First character: letter (a-z, A-Z)
  * Second character: letter, digit, underscore, or dot
  * Remaining 4-15 characters: letters or digits only
  * <p>
  * <p>
  * Effects: If userName is valid and not already registered, generates a
  * new access token, hashes it with Blowfish (salted), stores the mapping
  * (userName -> hashedToken) in userDatabase, persists to disk, and returns
  * the plaintext access token. Otherwise returns null.
  *
  * @param userName the username to register; must match USERNAME_PATTERN
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
  
  userDatabase.put(userName, hashedToken);
  saveUsers();
  
  return accessToken;
 }
 
 /**
  * Shuts down the server, closing all connections and saving state.
  * <p>
  * Effects: Stops accepting new connections, attempts graceful shutdown
  * of the executor (waiting up to 5 seconds), saves user data to disk,
  * and saves WikiMediator statistics to disk. Forcibly terminates remaining
  * tasks if graceful shutdown times out.
  */
 public void shutdown() {
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
  * Effects: Creates a fixed thread pool of size concurrencyLevel,
  * creates a ServerSocket on the configured port, and starts accepting
  * client connections. Each accepted connection is handled by a ClientHandler
  * submitted to the executor.
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
  * Thread Safety: Each instance runs in its own thread and manages one
  * socket connection. Multiple ClientHandlers can run concurrently.
  */
 private class ClientHandler implements Runnable {
  private final Socket socket;
  
  /**
   * Creates a handler for the given client socket.
   *
   * @param socket the client connection to handle
   */
  ClientHandler(Socket socket) {
   this.socket = socket;
  }
  
  @Override
  public void run() {
   try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true)) {
    
    String encryptedRequest;
    while ((encryptedRequest = in.readLine()) != null) {
     try {
      
      encryptedRequest = encryptedRequest.trim();
      String decryptedRequest = aesCipher.decrypt(encryptedRequest);
      
      if (decryptedRequest == null) {
       throw new IllegalArgumentException("Decryption returned null");
      }
      
      JsonObject request = gson.fromJson(decryptedRequest, JsonObject.class);
      
      JsonObject response = handleRequest(request);
      
      String jsonResponse = gson.toJson(response);
      String encryptedResponse = aesCipher.encrypt(jsonResponse);
      out.println(encryptedResponse);
      
     } catch (Exception e) {
      System.err.println("Error processing request: " + e);
      e.printStackTrace();
      
      JsonObject errorResponse = new JsonObject();
      errorResponse.addProperty("status", "failed");
      errorResponse.addProperty("response", "Invalid request: " + e.getMessage());
      
      try {
       String jsonError = gson.toJson(errorResponse);
       String encryptedError = aesCipher.encrypt(jsonError);
       out.println(encryptedError);
      } catch (Exception e2) {
       System.err.println("Could not send error response: " + e2);
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
  * Requires: request is a valid JsonObject with a "type" field
  * Effects: Dispatches to the appropriate handler based on request type
  *
  * @param request the decrypted JSON request object
  * @return a JSON response object with status and result/error
  */
 private JsonObject handleRequest(JsonObject request) {
  if (request == null) {
   return createErrorResponse(null, "Request is null");
  }
  
  String type = request.has("type") ? request.get("type").getAsString() : null;
  
  if (type == null) {
   return createErrorResponse(null, "Missing request type");
  }
  
  if ("initiate_session".equals(type)) {
   return handleInitiateSession(request);
  }
  
  String sessionKey = request.has("session_key") ? request.get("session_key").getAsString() : null;
  if (sessionKey == null || !sessionKeys.containsKey(sessionKey)) {
   return createErrorResponse(null, "Invalid or missing session key");
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
    return createErrorResponse(id, "Unknown request type: " + type);
  }
 }
 
 /**
  * Handles session initiation requests.
  * <p>
  * Effects: Verifies the provided access token against stored hashed
  * tokens. If valid, generates a new session key and maps it to the user.
  * Returns success with session key or failure with error message.
  *
  * @param request JSON object containing "access_token" field
  * @return JSON response with status "success" and "session_key", or
  * status "failure" and error message
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
  *
  * @param accessToken the plaintext access token to verify
  * @return the username if token is valid, null otherwise
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
  * Handles search requests.
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
   return createErrorResponse(id, "Search failed: " + e.getMessage());
  }
 }
 
 /**
  * Handles getPage requests.
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
   return createErrorResponse(id, "GetPage failed: " + e.getMessage());
  }
 }
 
 /**
  * Handles zeitgeist requests.
  */
 private JsonObject handleZeitgeist(JsonObject request, String sessionKey, String id) {
  try {
   int durationSeconds = request.get("duration").getAsInt();
   int limit = request.get("limit").getAsInt();
   
   List<String> results = mediator.zeitgeist(Duration.ofSeconds(durationSeconds), limit);
   return createSuccessResponse(sessionKey, id, results);
   
  } catch (Exception e) {
   return createErrorResponse(id, "Zeitgeist failed: " + e.getMessage());
  }
 }
 
 /**
  * Handles peakLoad requests.
  */
 private JsonObject handlePeakLoad(JsonObject request, String sessionKey, String id) {
  try {
   int durationSeconds = request.get("duration").getAsInt();
   
   int peak = mediator.peakLoad(Duration.ofSeconds(durationSeconds));
   return createSuccessResponse(sessionKey, id, peak);
   
  } catch (Exception e) {
   return createErrorResponse(id, "PeakLoad failed: " + e.getMessage());
  }
 }
 
 /**
  * Executes an operation with a timeout.
  * <p>
  * Uses the shared timeoutExecutor pool to avoid creating new executors
  * for each timeout operation.
  *
  * @param operation      the operation to execute
  * @param timeoutSeconds the maximum time to wait in seconds
  * @param sessionKey     the session key to include in response
  * @param id             the request ID to include in response
  * @return success response with result or error response if timeout/failure
  */
 private <T> JsonObject executeWithTimeout(Callable<T> operation, int timeoutSeconds,
                                           String sessionKey, String id) {
  Future<T> future = timeoutExecutor.submit(operation);
  
  try {
   T result = future.get(timeoutSeconds, TimeUnit.SECONDS);
   return createSuccessResponse(sessionKey, id, result);
  } catch (TimeoutException e) {
   future.cancel(true);
   return createErrorResponse(id, "Operation timed out");
  } catch (Exception e) {
   return createErrorResponse(id, "Operation failed: " + e.getMessage());
  }
 }
 
 /**
  * Creates a success response JSON object.
  *
  * @param sessionKey the session key to include
  * @param id         the request ID to include
  * @param result     the result data to include in "response" field
  * @return JSON object with status "success"
  */
 private JsonObject createSuccessResponse(String sessionKey, String id, Object result) {
  JsonObject response = new JsonObject();
  response.addProperty("session_key", sessionKey);
  if (id != null) {
   response.addProperty("id", id);
  }
  response.addProperty("status", "success");
  response.add("response", gson.toJsonTree(result));
  return response;
 }
 
 /**
  * Creates an error response JSON object.
  *
  * @param id           the request ID to include
  * @param errorMessage the error message to include
  * @return JSON object with status "failed"
  */
 private JsonObject createErrorResponse(String id, String errorMessage) {
  JsonObject response = new JsonObject();
  if (id != null) {
   response.addProperty("id", id);
  }
  response.addProperty("status", "failed");
  response.addProperty("response", errorMessage);
  return response;
 }
 
 /**
  * Loads user data from disk.
  * <p>
  * Effects: If local/users.json exists and is readable, loads the
  * username -> hashedToken mappings into userDatabase. If the file doesn't
  * exist or cannot be read, starts with an empty database.
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
  * Effects: Creates local/ directory if needed, then writes all
  * username -> hashedToken mappings to local/users.json in JSON format.
  * If saving fails, prints a warning but does not throw.
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
