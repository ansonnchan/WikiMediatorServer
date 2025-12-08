package fsft;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import cryptography.AESCipher;
import fsft.wikipedia.WikiMediatorServer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;


@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class WikiMediatorServerTests {
 
 private static final int TEST_PORT = 4949;
 private static final int TEST_CONCURRENCY = 5;
 private static final String AES_KEY =
  "The sky above the port was the color of television, tuned to a dead channel.";
 private static final String USERS_FILE = "local/users.json";
 private static final String STATS_FILE = "local/wikimediator_stats.json";
 
 private static WikiMediatorServer server;
 private static Gson gson;
 private static AESCipher cipher;
 
 @BeforeAll
 public static void setUpClass() throws InterruptedException {
  cleanupAllData();
  
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
  cleanupAllData();
 }
 
 private static void cleanupAllData() {
  try {
   Files.deleteIfExists(Paths.get(USERS_FILE));
   Files.deleteIfExists(Paths.get(STATS_FILE));
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
 
 // ========== User Management Tests ==========
 
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
  String token1 = server.addUser("a_bcde1");
  assertNotNull(token1, "Username 'a_bcde1' should be valid (underscore at pos 2)");
  
  String token2 = server.addUser("b.cdef2");
  assertNotNull(token2, "Username 'b.cdef2' should be valid (dot at pos 2)");
  
  String token3 = server.addUser("c3defg3");
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
 
 // ========== Session Initiation Tests ==========
 
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
  JsonObject request1 = new JsonObject();
  request1.addProperty("type", "initiate_session");
  request1.addProperty("access_token", "invalid_token_xyz");
  JsonObject response1 = sendRequest(request1);
  assertEquals("failure", response1.get("status").getAsString());
  
  JsonObject request2 = new JsonObject();
  request2.addProperty("type", "initiate_session");
  JsonObject response2 = sendRequest(request2);
  assertEquals("failure", response2.get("status").getAsString());
 }
 
 @Test
 @Order(10)
 public void testMultipleSessionsSameUser() throws Exception {
  String accessToken = server.addUser("multiuser1");
  
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
  assertNotEquals(
   resp1.get("session_key").getAsString(),
   resp2.get("session_key").getAsString(),
   "Multiple sessions should have unique session keys"
  );
 }
 
 // ========== Request Handling Tests ==========
 
 @Test
 @Order(11)
 public void testRequestWithoutSessionKey() throws Exception {
  JsonObject request = new JsonObject();
  request.addProperty("type", "search");
  request.addProperty("searchTerm", "Obama");
  request.addProperty("limit", 5);
  
  JsonObject response = sendRequest(request);
  assertEquals("failed", response.get("status").getAsString());
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
  assertEquals("failed", response.get("status").getAsString());
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
  
  JsonObject request1 = new JsonObject();
  request1.addProperty("session_key", sessionKey);
  JsonObject response1 = sendRequest(request1);
  assertEquals("failed", response1.get("status").getAsString());
  
  JsonObject request2 = new JsonObject();
  request2.addProperty("session_key", sessionKey);
  request2.addProperty("type", "unknown_operation");
  JsonObject response2 = sendRequest(request2);
  assertEquals("failed", response2.get("status").getAsString());
 }
 

 
 @Test
 @Order(14)
 @Timeout(15)
 public void testSearchRequest() throws Exception {
  String accessToken = server.addUser("searchuser1");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("id", "search1");
  request.addProperty("type", "search");
  request.addProperty("searchTerm", "Java");
  request.addProperty("limit", "5");
  
  JsonObject response = sendRequest(request);
  assertEquals("success", response.get("status").getAsString());
  assertTrue(response.has("response"));
  assertEquals("search1", response.get("id").getAsString());
 }
 
 @Test
 @Order(15)
 @Timeout(15)
 public void testGetPageRequest() throws Exception {
  String accessToken = server.addUser("pageuser1");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("id", "page1");
  request.addProperty("type", "getPage");
  request.addProperty("pageTitle", "Java");
  
  JsonObject response = sendRequest(request);
  assertEquals("success", response.get("status").getAsString());
  assertTrue(response.has("response"));
  assertEquals("page1", response.get("id").getAsString());
 }
 
 @Test
 @Order(16)
 @Timeout(10)
 public void testZeitgeistRequest() throws Exception {
  String accessToken = server.addUser("zeituser1");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("id", "zeit1");
  request.addProperty("type", "zeitgeist");
  request.addProperty("duration", "300");
  request.addProperty("limit", "5");
  
  JsonObject response = sendRequest(request);
  assertEquals("success", response.get("status").getAsString());
  assertTrue(response.has("response"));
  assertEquals("zeit1", response.get("id").getAsString());
 }
 
 @Test
 @Order(17)
 @Timeout(10)
 public void testPeakLoadRequest() throws Exception {
  String accessToken = server.addUser("peakuser1");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("id", "peak1");
  request.addProperty("type", "peakLoad");
  request.addProperty("duration", "300");
  
  JsonObject response = sendRequest(request);
  assertEquals("success", response.get("status").getAsString());
  assertTrue(response.has("response"));
  assertEquals("peak1", response.get("id").getAsString());
 }

 
 @Test
 @Order(18)
 @Timeout(15)
 public void testSearchWithTimeout() throws Exception {
  String accessToken = server.addUser("timeout1");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("id", "timeout1");
  request.addProperty("type", "search");
  request.addProperty("searchTerm", "Java");
  request.addProperty("limit", "5");
  request.addProperty("timeout", "10");  // 10 second timeout
  
  JsonObject response = sendRequest(request);
  // Should succeed within timeout
  assertEquals("success", response.get("status").getAsString());
 }
 
 @Test
 @Order(19)
 @Timeout(15)
 public void testGetPageWithTimeout() throws Exception {
  String accessToken = server.addUser("timeout2");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("id", "timeout2");
  request.addProperty("type", "getPage");
  request.addProperty("pageTitle", "Java");
  request.addProperty("timeout", "10");  // 10 second timeout
  
  JsonObject response = sendRequest(request);
  // Should succeed within timeout
  assertEquals("success", response.get("status").getAsString());
 }

 
 @Test
 @Order(20)
 public void testSearchWithMissingParameters() throws Exception {
  String accessToken = server.addUser("error1");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  // Missing searchTerm and limit
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("type", "search");
  
  JsonObject response = sendRequest(request);
  assertEquals("failed", response.get("status").getAsString());
 }
 
 @Test
 @Order(21)
 public void testGetPageWithMissingParameters() throws Exception {
  String accessToken = server.addUser("error2");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  // Missing pageTitle
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("type", "getPage");
  
  JsonObject response = sendRequest(request);
  assertEquals("failed", response.get("status").getAsString());
 }
 
 @Test
 @Order(22)
 public void testZeitgeistWithMissingParameters() throws Exception {
  String accessToken = server.addUser("error3");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  // Missing duration and limit
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("type", "zeitgeist");
  
  JsonObject response = sendRequest(request);
  assertEquals("failed", response.get("status").getAsString());
 }
 
 @Test
 @Order(23)
 public void testPeakLoadWithMissingParameters() throws Exception {
  String accessToken = server.addUser("error4");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  // Missing duration
  JsonObject request = new JsonObject();
  request.addProperty("session_key", sessionKey);
  request.addProperty("type", "peakLoad");
  
  JsonObject response = sendRequest(request);
  assertEquals("failed", response.get("status").getAsString());
 }

 
 
 @Test
 @Order(25)
 public void testUserPersistence() throws Exception {
  final int PERSISTENCE_PORT = 5005;
  cleanupAllData();
  
  WikiMediatorServer persistServer1 = new WikiMediatorServer(PERSISTENCE_PORT, 2);
  Thread.sleep(500);
  
  String token1 = persistServer1.addUser("p_rsist01");
  assertNotNull(token1);
  
  persistServer1.shutdown();
  Thread.sleep(1000);
  
  assertTrue(Files.exists(Paths.get(USERS_FILE)));
  Thread.sleep(200);
  
  WikiMediatorServer persistServer2 = new WikiMediatorServer(PERSISTENCE_PORT, 2);
  Thread.sleep(500);
  
  String token2 = persistServer2.addUser("p_rsist01");
  assertNull(token2, "User should already exist after server restart");
  
  JsonObject request = new JsonObject();
  request.addProperty("type", "initiate_session");
  request.addProperty("access_token", token1);
  
  JsonObject response = sendRequestToPort(request, PERSISTENCE_PORT);
  assertEquals("success", response.get("status").getAsString());
  
  persistServer2.shutdown();
  Thread.sleep(500);
 }
 
 @Test
 @Order(26)
 public void testShutdownSavesData() throws Exception {
  final int SHUTDOWN_PORT = 5006;
  cleanupAllData();
  
  WikiMediatorServer shutdownServer = new WikiMediatorServer(SHUTDOWN_PORT, 2);
  Thread.sleep(300);
  
  shutdownServer.addUser("shutdown1");
  shutdownServer.shutdown();
  Thread.sleep(500);
  
  assertTrue(Files.exists(Paths.get(USERS_FILE)));
 }
 
 @Test
 @Order(27)
 public void testMultipleShutdownCallsSafe() throws InterruptedException {
  WikiMediatorServer testServer = new WikiMediatorServer(5004, 2);
  Thread.sleep(300);
  
  assertDoesNotThrow(() -> {
   testServer.shutdown();
   Thread.sleep(100);
   testServer.shutdown();
   Thread.sleep(100);
   testServer.shutdown();
  });
 }
 
 
 
 @Test
 @Order(28)
 @Timeout(30)
 public void testMultipleRequestsSameSession() throws Exception {
  String accessToken = server.addUser("multi1");
  JsonObject sessionReq = new JsonObject();
  sessionReq.addProperty("type", "initiate_session");
  sessionReq.addProperty("access_token", accessToken);
  JsonObject sessionResp = sendRequest(sessionReq);
  String sessionKey = sessionResp.get("session_key").getAsString();
  
  // Search request
  JsonObject req1 = new JsonObject();
  req1.addProperty("session_key", sessionKey);
  req1.addProperty("id", "1");
  req1.addProperty("type", "search");
  req1.addProperty("searchTerm", "Python");
  req1.addProperty("limit", "3");
  JsonObject resp1 = sendRequest(req1);
  assertEquals("success", resp1.get("status").getAsString());
  
  // GetPage request
  JsonObject req2 = new JsonObject();
  req2.addProperty("session_key", sessionKey);
  req2.addProperty("id", "2");
  req2.addProperty("type", "getPage");
  req2.addProperty("pageTitle", "Python");
  JsonObject resp2 = sendRequest(req2);
  assertEquals("success", resp2.get("status").getAsString());
  
  // Zeitgeist request
  JsonObject req3 = new JsonObject();
  req3.addProperty("session_key", sessionKey);
  req3.addProperty("id", "3");
  req3.addProperty("type", "zeitgeist");
  req3.addProperty("duration", "100");
  req3.addProperty("limit", "5");
  JsonObject resp3 = sendRequest(req3);
  assertEquals("success", resp3.get("status").getAsString());
  
  // PeakLoad request
  JsonObject req4 = new JsonObject();
  req4.addProperty("session_key", sessionKey);
  req4.addProperty("id", "4");
  req4.addProperty("type", "peakLoad");
  req4.addProperty("duration", "100");
  JsonObject resp4 = sendRequest(req4);
  assertEquals("success", resp4.get("status").getAsString());
 }
 
 // ========== Helper Methods ==========
 
 private JsonObject sendRequest(JsonObject request) throws Exception {
  return sendRequestToPort(request, TEST_PORT);
 }
 
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
