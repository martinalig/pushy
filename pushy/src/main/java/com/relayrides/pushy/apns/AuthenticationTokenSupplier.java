package com.relayrides.pushy.apns;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.SignatureException;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.base64.Base64;
import io.netty.handler.codec.base64.Base64Dialect;

/**
 * Generates JWT tokens with a given private signing key.
 *
 * @author <a href="https://github.com/jchambers">Jon Chambers</a>
 *
 * @since 0.9
 */
class AuthenticationTokenSupplier {

    private final Signature signature;

    private final String issuer;
    private final String keyId;

    private String token;

    private static final Gson gson = new GsonBuilder()
            .disableHtmlEscaping()
            .registerTypeAdapter(Date.class, new DateAsTimeSinceEpochTypeAdapter(TimeUnit.SECONDS))
            .create();

    /**
     * Constructs a new authentication token provider that will generate tokens for the given issuer with the given
     * private key.
     *
     * @param issuer the ten-character team identifier of the team that owns the given signing key
     * @param keyId the ten-character key identifier for the given signing key
     * @param privateKey the PKCS#8 private key to be used when signing authentication tokens
     *
     * @throws NoSuchAlgorithmException if the {@code SHA256withECDSA} algorithm is not available
     * @throws InvalidKeyException if the given private signing key is invalid for any reason
     */
    public AuthenticationTokenSupplier(final String issuer, final String keyId, final PrivateKey privateKey) throws NoSuchAlgorithmException, InvalidKeyException {
        Objects.requireNonNull(issuer);
        Objects.requireNonNull(keyId);
        Objects.requireNonNull(privateKey);

        this.signature = Signature.getInstance("SHA256withECDSA");
        this.signature.initSign(privateKey);

        this.issuer = issuer;
        this.keyId = keyId;
    }

    /**
     * Returns the most recently-generated authentication token created by this provider. Generates a new token if
     * the most recent token has been invalidated (or if no token has ever been generated by this provider).
     *
     * @return a signed, base64url-encoded JWT authentication token
     *
     * @throws SignatureException if the authentication token could not be signed for any reason
     *
     * @see AuthenticationTokenSupplier#invalidateToken(String)
     */
    public String getToken() throws SignatureException {
        return this.getToken(new Date());
    }

    /**
     * Returns the most recently-generated authentication token created by this provider. Generates a new token with the
     * given "issued at" timestamp if the most recent token has been invalidated (or if no token has ever been generated
     * by this provider).
     *
     * @param issuedAt the "issued at" time for newly-generated tokens; ignored if an existing token is available
     *
     * @return a signed, base64url-encoded JWT authentication token
     *
     * @throws SignatureException if the authentication token could not be signed for any reason
     */
    protected String getToken(final Date issuedAt) throws SignatureException {
        if (this.token == null) {
            final String header = gson.toJson(new AuthenticationTokenHeader(this.keyId));
            final String claims = gson.toJson(new AuthenticationTokenClaims(this.issuer, issuedAt));

            final StringBuilder payloadBuilder = new StringBuilder();
            payloadBuilder.append(base64UrlEncodeWithoutPadding(header.getBytes(StandardCharsets.US_ASCII)));
            payloadBuilder.append('.');
            payloadBuilder.append(base64UrlEncodeWithoutPadding(claims.getBytes(StandardCharsets.US_ASCII)));

            final byte[] signatureBytes;
            {
                this.signature.update(payloadBuilder.toString().getBytes(StandardCharsets.US_ASCII));
                signatureBytes = this.signature.sign();
            }

            payloadBuilder.append('.');
            payloadBuilder.append(base64UrlEncodeWithoutPadding(signatureBytes));

            this.token = payloadBuilder.toString();
        }

        return this.token;
    }

    /**
     * Invalidates and discards a previously-generated token.
     *
     * @param invalidToken the token to invalidate
     */
    public void invalidateToken(final String invalidToken) {
        if (invalidToken != null && invalidToken.equals(this.token)) {
            this.token = null;
        }
    }

    private static String base64UrlEncodeWithoutPadding(final byte[] bytes) {
        final ByteBuf wrappedString = Unpooled.wrappedBuffer(bytes);
        final ByteBuf encodedString = Base64.encode(wrappedString, Base64Dialect.URL_SAFE);

        final String encodedUnpaddedString = encodedString.toString(StandardCharsets.US_ASCII).replace("=", "");

        wrappedString.release();
        encodedString.release();

        return encodedUnpaddedString;
    }
}
