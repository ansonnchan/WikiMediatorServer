package fsft;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import cryptography.AESCipher;
import fsft.wikipedia.WikiMediatorServer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;


@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WikiMediatorServerTests {
 
 private static final int TEST_PORT = 4949;
 private static final int TEST_CONCURRENCY = 5;
 private static final String AES_KEY =
  "The sky above the port was the color of television, tuned to a dead channel.";
 private static final String USERS_FILE = "local/users.json";
 
 private static WikiMediatorServer server;
 private static Gson gson;
 private static AESCipher cipher;
 
 @BeforeAll
 public static void setUpClass() throws InterruptedException {
  cleanupUserData();
  
  gson = new Gson();
  cipher = new AESCipher(AES_KEY);
  server = new WikiMediatorServer(TEST_PORT, TEST_CONCURRENCY);
  
  
  Thread.sleep(300);
 }
 
 @AfterAll
 public static void tearDownClass() throws InterruptedException {
  if (server != null) {
   server.shutdown();
  }
  
  Thread.sleep(200);
  cleanupUserData();
 }
 
 private static void cleanupUserData() {
  try {
   Path usersFile = Paths.get(USERS_FILE);
   if (Files.exists(usersFile)) {
    Files.delete(usersFile);
   }
  } catch (IOException e) {
   // Ignore
  }
 }
 
 // ========== Constructor Tests ==========
 
 @Test
 @Order(1)
 public void testConstructorValid() {
  WikiMediatorServer testServer = new WikiMediatorServer(5001, 3);
  assertNotNull(testServer);
  testServer.shutdown();
 }
 
 @Test
 @Order(2)
 public void testConstructorInvalidInputs() {
  
  assertThrows(IllegalArgumentException.class, () -> new WikiMediatorServer(5002, 0));
  assertThrows(IllegalArgumentException.class, () -> new WikiMediatorServer(5003, -1));
  assertThrows(IllegalArgumentException.class, () -> new WikiMediatorServer(-1, 3));
  assertThrows(IllegalArgumentException.class, () -> new WikiMediatorServer(65536, 3));
 }
 
 
 @Test
 @Order(3)
 public void testAddUserValid() {
  String token = server.addUser("alice123");
  assertNotNull(token);
  assertTrue(token.length() > 0);
 }
 
 
 @Test
 @Order(4)
 public void testAddUserValidWithSpecialChars() {
  // Valid examples: special char at position 2, followed by 4+ letters/digits
  String token1 = server.addUser("a_bcde1");  // 7 chars: a + _ + bcde1(5)
  assertNotNull(token1, "Username 'a_bcde1' should be valid (underscore at pos 2)");
  
  String token2 = server.addUser("b.cdef2");  // 7 chars: b + . + cdef2(5)
  assertNotNull(token2, "Username 'b.cdef2' should be valid (dot at pos 2)");
  
  String token3 = server.addUser("c3defg3");  // 7 chars: c + 3 + defg3(5)
  assertNotNull(token3, "Username 'c3defg3' should be valid (digit at pos 2)");
 }
 
 @Test
 @Order(5)
 public void testAddUserInvalidFormats() {
  
  assertNull(server.addUser("alice"), "Too short (5 chars, need 6)");
  assertNull(server.addUser("alice123456789012345"), "Too long (22 chars, max 17)");
  assertNull(server.addUser("1alice123"), "Starts with digit");
  assertNull(server.addUser(null), "Null username");
  
  
  assertNull(server.addUser("ab_cde1"), "Underscore at position 3 (invalid)");
  assertNull(server.addUser("abc.de1"), "Dot at position 4 (invalid)");
  assertNull(server.addUser("abcd_e1"), "Underscore at position 5 (invalid)");
 }
 
 @Test
 @Order(6)
 public void testAddUserDuplicate() {
  String token1 = server.addUser("bob12345");
  assertNotNull(token1);
  
  String token2 = server.addUser("bob12345");
  assertNull(token2, "Duplicate username should be rejected");
 }
 
 @Test
 @Order(7)
 public void testMultipleUsers() {
  
  String token1 = server.addUser("charlie1");
  String token2 = server.addUser("dave1234");
  String token3 = server.addUser("eve12345");
  
  assertNotNull(token1);
  assertNotNull(token2);
  assertNotNull(token3);
  
  
  assertNotEquals(token1, token2);
  assertNotEquals(token2, token3);
  assertNotEquals(token1, token3);
 }
 
 
 @Test
 @Order(8)
 public void testInitiateSessionValid() throws Exception {
  String accessToken = server.addUser("testuser1");
  assertNotNull(accessToken);
  
  JsonObject request = new JsonObject();
  request.addProperty("type", "initiate_session");
  request.addProperty("access_token", accessToken);
  
  JsonObject response = sendRequest(request);
  assertNotNull(response, "Response should not be null");
  
  assertEquals("success", response.get("status").getAsString());
  assertTrue(response.has("session_key"), "Response should have session_key field");
 }
 
 @Test
 @Order(9)
 public void testInitiateSessionInvalidCases() throws Exception {
  
  // Invalid token
  JsonObject request1 = new JsonObject();
  request1.addProperty("type", "initiate_session");
  request1.addProperty("access_token", "invalid_token_xyz");
  JsonObject response1 = sendRequest(request1);
  assertEquals("failure", response1.get("status").getAsString());
  
  // Missing token
  JsonObject request2 = new JsonObject();
  request2.addProperty("type", "initiate_session");
  JsonObject response2 = sendRequest(request2);
  assertEquals("failure", response2.get("status").getAsString());
 }
 
 @Test
 @Order(10)
 public void testMultipleSessionsSameUser() throws Exception {
  String accessToken = server.addUser("multiuser1");
  
  // Create two sessions with same token
  JsonObject req1 = new JsonObject();
  req1.addProperty("type", "initiate_session");
  req1.addProperty("access_token", accessToken);
  JsonObject resp1 = sendRequest(req1);
  
  JsonObject req2 = new JsonObject();
  req2.addProperty("type", "initiate_session");
  req2.addProperty("access_token", accessToken);
  JsonObject resp2 = sendRequest(req2);
  
  assertEquals("success", resp1.get("status").getAsString());
  assertEquals("success", resp2.get("status").getAsString());
  
  // Session keys should be different
  assertNotEquals(
   resp1.get("session_key").getAsString(),
   resp2.get("session_key").getAsString(),
   "Multiple sessions should have unique session keys"
  );
 }
 
 
 @Test
 @Order(11)
 public void testRequestWithoutSessionKey() throws Exception {
  JsonObject request = new JsonObject();
  request.addProperty("type", "search");
  request.addProperty("searchTerm", "Obama");
  request.addProperty("limit", 5);
  
  JsonObject response = sendRequest(request);
  assertEquals("failed", response.get("status").getAsString(),
   "Request without session key should fail");
 }
 
 @Test
 @Order(12)
 public void testRequestWithInvalidSessionKey() throws Exception {
  JsonObject request = new JsonObject();
  request.addProperty("session_key", "invalid_key");
  request.addProperty("type", "search");
  request.addProperty("searchTerm", "Obama");
  request.addProperty("limit", 5);
  
  JsonObject response = sendRequest(request);
  assertEquals("failed", response.get("status").getAsString(),
   "Request with invalid session key should fail");
 }
 
 
 @Test
 @Order(13)
 public void testMissingAndUnknownRequestTypes() throws Exception {
  
  String accessToken = server.addUser("testuser2");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  // Missing type
  JsonObject request1 = new JsonObject();
  request1.addProperty("session_key", sessionKey);
  JsonObject response1 = sendRequest(request1);
  assertEquals("failed", response1.get("status").getAsString(),
   "Request with missing type should fail");
  
  // Unknown type
  JsonObject request2 = new JsonObject();
  request2.addProperty("session_key", sessionKey);
  request2.addProperty("type", "unknown_operation");
  JsonObject response2 = sendRequest(request2);
  assertEquals("failed", response2.get("status").getAsString(),
   "Request with unknown type should fail");
 }
 
 
 @Test
 @Order(14)
 public void testUserPersistence() throws Exception {
  
  final int PERSISTENCE_PORT = 5005;
  
  
  cleanupUserData();
  
  
  WikiMediatorServer persistServer1 = new WikiMediatorServer(PERSISTENCE_PORT, 2);
  Thread.sleep(300);
  
  String token1 = persistServer1.addUser("persist_user");
  assertNotNull(token1, "Should be able to add user to first server instance");
  
  // Shutdown first server
  persistServer1.shutdown();
  Thread.sleep(300);
  
  // Create second server instance (should load persisted data)
  WikiMediatorServer persistServer2 = new WikiMediatorServer(PERSISTENCE_PORT, 2);
  Thread.sleep(300);
  
  // User should already exist
  String token2 = persistServer2.addUser("persist_user");
  assertNull(token2, "User should already exist after server restart");
  
  // Original token should still work with new server
  JsonObject request = new JsonObject();
  request.addProperty("type", "initiate_session");
  request.addProperty("access_token", token1);
  
  JsonObject response = sendRequestToPort(request, PERSISTENCE_PORT);
  assertEquals("success", response.get("status").getAsString(),
   "Original access token should still work after restart");
  
  // Cleanup
  persistServer2.shutdown();
  Thread.sleep(200);
 }
 
 
 @Test
 @Order(15)
 public void testShutdownSavesData() throws Exception {
  final int SHUTDOWN_PORT = 5006;
  
  cleanupUserData();
  
  WikiMediatorServer shutdownServer = new WikiMediatorServer(SHUTDOWN_PORT, 2);
  Thread.sleep(200);
  
  shutdownServer.addUser("shutdown_test");
  shutdownServer.shutdown();
  Thread.sleep(200);
  
  Path usersFile = Paths.get(USERS_FILE);
  assertTrue(Files.exists(usersFile), "Users file should exist after shutdown");
 }
 
 @Test
 @Order(16)
 public void testMultipleShutdownCallsSafe() throws InterruptedException {
  // Create a separate server instance for this test
  WikiMediatorServer testServer = new WikiMediatorServer(5004, 2);
  Thread.sleep(200);
  
  // Multiple shutdowns should not throw
  assertDoesNotThrow(() -> {
   testServer.shutdown();
   Thread.sleep(100); // Give time for first shutdown to complete
   testServer.shutdown();
   Thread.sleep(100);
   testServer.shutdown();
  }, "Multiple shutdown calls should not throw exceptions");
 }
 
 
 /**
  * Sends a request to the shared test server (TEST_PORT).
  */
 private JsonObject sendRequest(JsonObject request) throws Exception {
  return sendRequestToPort(request, TEST_PORT);
 }
 
 /**
  * Sends a request to a server on the specified port.
  */
 private JsonObject sendRequestToPort(JsonObject request, int port) throws Exception {
  try (Socket socket = new Socket("localhost", port);
       PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
       BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
   
   String jsonRequest = gson.toJson(request);
   String encryptedRequest = cipher.encrypt(jsonRequest);
   
   if (encryptedRequest == null) {
    throw new RuntimeException("Encryption failed");
   }
   
   out.println(encryptedRequest);
   
   String encryptedResponse = in.readLine();
   if (encryptedResponse == null) {
    throw new RuntimeException("No response from server");
   }
   
   String decryptedResponse = cipher.decrypt(encryptedResponse);
   if (decryptedResponse == null) {
    throw new RuntimeException("Decryption failed");
   }
   
   return gson.fromJson(decryptedResponse, JsonObject.class);
  }
 }
}
