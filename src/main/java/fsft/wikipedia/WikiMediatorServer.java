/**
 * Start a server at a given port number, with the ability to process
 * upto n requests concurrently.
 *
 * @param port the port number to bind the server to
 * @param concurrencyLevel the number of concurrent requests the server can handle; requires {@code concurrencyLevel > 0}
 */
package fsft.wikipedia;

import cryptography.AESCipher;
import cryptography.BlowfishCipher;
import cryptography.AccessToken;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/**
 * WikiMediatorServer: Thread-safe, persistent, concurrent server that wraps a WikiMediator.
 */
public class WikiMediatorServer {
 
 private final int port;
 private final int concurrencyLevel;
 private final WikiMediator mediator;
 private final ExecutorService threadPool;
 
 private final Map<String, String> userTokens; // username -> token
 private final Map<String, String> sessions;   // sessionKey -> username
 private ServerSocket serverSocket;
 
 private final AESCipher aesCipher = new AESCipher(AES_KEY);
 private static final String AES_KEY = "The sky above the port was the color of television, tuned to a dead channel.";
 private static final Path USER_FILE = Paths.get("local", "users.dat");
 private static final Gson gson = new Gson();
 
 public WikiMediatorServer(int port, int concurrencyLevel) throws IOException {
  if (concurrencyLevel <= 0) throw new IllegalArgumentException("concurrencyLevel must be > 0");
  
  this.port = port;
  this.concurrencyLevel = concurrencyLevel;
  this.mediator = new WikiMediator();
  this.threadPool = Executors.newFixedThreadPool(concurrencyLevel);
  this.userTokens = new ConcurrentHashMap<>();
  this.sessions = new ConcurrentHashMap<>();
  
  loadUsersFromDisk();
  
  serverSocket = new ServerSocket(port);
  System.out.println("WikiMediatorServer started on port " + port);
  
  new Thread(this::acceptClients).start();
 }
 
 private void acceptClients() {
  while (!serverSocket.isClosed()) {
   try {
    Socket client = serverSocket.accept();
    threadPool.submit(() -> handleClient(client));
   } catch (IOException e) {
    if (!serverSocket.isClosed()) e.printStackTrace();
   }
  }
 }
 
 /** Add a new user and generate a unique access token. */
 public String addUser(String userName) {
  if (!userName.matches("^[a-zA-Z][a-zA-Z0-9_.][a-zA-Z0-9]{4,15}$")) return null;
  
  String token = AccessToken.getAccessToken();
  while (userTokens.containsValue(token)) {
   token = AccessToken.getAccessToken();
  }
  
  userTokens.put(userName, token);
  return token;
 }
 
 /** Shut down server gracefully: save users, close socket, terminate threads. */
 public void shutdown() {
  try {
   saveUsersToDisk();
   serverSocket.close();
   threadPool.shutdownNow();
   System.out.println("WikiMediatorServer shutdown complete.");
  } catch (IOException e) {
   e.printStackTrace();
  }
 }
 
 /** Handle a client connection */
 /** Handle a client connection */
 private void handleClient(Socket client) {
  try (BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream()));
       BufferedWriter out = new BufferedWriter(new OutputStreamWriter(client.getOutputStream()))) {
   
   String encryptedRequest;
   while ((encryptedRequest = in.readLine()) != null) {
    // Decrypt using the aesCipher instance
    String jsonRequest = aesCipher.decrypt(encryptedRequest);
    
    // Process the request
    String jsonResponse = processRequest(jsonRequest);
    
    // Encrypt the response using the aesCipher instance
    String encryptedResponse = aesCipher.encrypt(jsonResponse);
    
    // Send back the encrypted response
    out.write(encryptedResponse);
    out.newLine();
    out.flush();
   }
  } catch (Exception e) {
   e.printStackTrace();
  }
 }
 
 
 /** Process a single JSON request */
 private String processRequest(String json) {
  JsonObject request = gson.fromJson(json, JsonObject.class);
  JsonObject response = new JsonObject();
  
  try {
   String type = request.get("type").getAsString();
   
   switch (type) {
    case "initiate_session" -> {
     String token = request.get("access_token").getAsString();
     String sessionKey = initiateSession(token);
     if (sessionKey != null) {
      response.addProperty("status", "success");
      response.addProperty("session_key", sessionKey);
     } else {
      response.addProperty("status", "failure");
      response.addProperty("response", "Authentication Failure");
     }
    }
    
    case "search" -> {
     verifySession(request);
     String searchTerm = request.get("searchTerm").getAsString();
     int limit = request.get("limit").getAsInt();
     List<String> searchResults = mediator.search(searchTerm, limit);
     response.addProperty("status", "success");
     response.add("response", gson.toJsonTree(searchResults));
    }
    
    case "getPage" -> {
     verifySession(request);
     String pageTitle = request.get("pageTitle").getAsString();
     String page = mediator.getPage(pageTitle);
     response.addProperty("status", "success");
     response.addProperty("response", page);
    }
    
    case "zeitgeist" -> {
     verifySession(request);
     int durationSec = request.get("duration").getAsInt();
     int limitZ = request.get("limit").getAsInt();
     List<String> zeitgeist = mediator.zeitgeist(Duration.ofSeconds(durationSec), limitZ);
     response.addProperty("status", "success");
     response.add("response", gson.toJsonTree(zeitgeist));
    }
    
    case "peakLoad" -> {
     verifySession(request);
     int durationSec = request.get("duration").getAsInt();
     int peak = mediator.peakLoad(Duration.ofSeconds(durationSec));
     response.addProperty("status", "success");
     response.addProperty("response", peak);
    }
    
    default -> {
     response.addProperty("status", "failed");
     response.addProperty("response", "Unknown request type");
    }
   }
  } catch (Exception e) {
   response.addProperty("status", "failed");
   response.addProperty("response", e.getMessage());
  }
  
  // Include client id if present
  if (request.has("id")) {
   response.add("id", request.get("id"));
  }
  
  return gson.toJson(response);
 }
 
 /** Validate session key */
 private void verifySession(JsonObject request) {
  String sessionKey = request.get("session_key").getAsString();
  if (!sessions.containsKey(sessionKey)) {
   throw new RuntimeException("Invalid session key");
  }
 }
 
 /** Start a new session given a valid access token */
 private String initiateSession(String accessToken) {
  Optional<String> user = userTokens.entrySet().stream()
   .filter(e -> e.getValue().equals(accessToken))
   .map(Map.Entry::getKey)
   .findFirst();
  
  if (user.isPresent()) {
   String sessionKey = AccessToken.getAccessToken();
   sessions.put(sessionKey, user.get());
   return sessionKey;
  }
  return null;
 }
 
 /** Load users from disk */
 private void loadUsersFromDisk() {
  try {
   if (!Files.exists(USER_FILE)) return;
   
   List<String> lines = Files.readAllLines(USER_FILE);
   for (String line : lines) {
    String[] parts = line.split(":");
    if (parts.length == 2) {
     userTokens.put(parts[0], parts[1]);
    }
   }
  } catch (IOException e) {
   e.printStackTrace();
  }
 }
 
 /** Save users to disk */
 private void saveUsersToDisk() {
  try {
   Files.createDirectories(USER_FILE.getParent());
   try (BufferedWriter writer = Files.newBufferedWriter(USER_FILE, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
    for (Map.Entry<String, String> entry : userTokens.entrySet()) {
     writer.write(entry.getKey() + ":" + entry.getValue());
     writer.newLine();
    }
   }
  } catch (IOException e) {
   e.printStackTrace();
  }
 }
}
