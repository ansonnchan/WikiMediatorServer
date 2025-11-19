package cryptography;

import java.security.SecureRandom;
import java.util.Base64;

public class AccessToken {
    private static final int ACCESS_TOKEN_RANDOM_BYTES = 32;
    private static final int SESSION_TOKEN_RANDOM_BYTES = 10;


    /**
     * Compute a new access token
     * @return an access token
     */
    public static String getAccessToken() {
        return getToken(ACCESS_TOKEN_RANDOM_BYTES);
    }

    /**
     * Compute a short session token
     * @return a short session token
     */
    public static String getSessionToken() {
        return getToken(SESSION_TOKEN_RANDOM_BYTES);
    }

    /**
     * Compute a random token
     * @param number_of_random_bytes > 0
     * @return a "secure" random token
     */
    private static String getToken(final int number_of_random_bytes) {
        SecureRandom secureRandom = new SecureRandom();
        Base64.Encoder base64Encoder = Base64.getUrlEncoder();
        byte[] randomBytes = new byte[number_of_random_bytes];
        secureRandom.nextBytes(randomBytes);
        return base64Encoder.encodeToString(randomBytes);
    }

    public static void main(String[] args) {
        System.out.println(AccessToken.getAccessToken());
        System.out.println(AccessToken.getSessionToken());
    }
}
