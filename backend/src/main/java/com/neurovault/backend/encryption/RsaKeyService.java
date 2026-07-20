package com.neurovault.backend.encryption;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Base64;

/**
 * Service providing RSA-4096 key pair generation and AES key wrapping/unwrapping.
 *
 * <p>Uses RSA/ECB/OAEPWithSHA-256AndMGF1Padding for key wrapping, which provides
 * IND-CCA2 security. The wrapped (encrypted) AES key is returned as a Base64 string
 * suitable for storage in the {@code FileMetadata.encryptedAesKey} field.</p>
 *
 * <p>The coordinator stores only the wrapped key. The RSA private key is owned
 * exclusively by the client and is never transmitted to the coordinator or hosts.</p>
 */
@Service
@Slf4j
public class RsaKeyService {

    private static final String RSA_ALGORITHM = "RSA";
    private static final String TRANSFORMATION = "RSA/ECB/OAEPWithSHA-256AndMGF1Padding";
    private static final int KEY_SIZE_BITS = 4096;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * Generates a new RSA-4096 key pair.
     *
     * @return a {@link KeyPair} containing the public and private keys
     * @throws GeneralSecurityException if key generation fails
     */
    public KeyPair generateKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance(RSA_ALGORITHM);
        generator.initialize(KEY_SIZE_BITS, secureRandom);
        KeyPair keyPair = generator.generateKeyPair();
        log.debug("Generated RSA-{} key pair", KEY_SIZE_BITS);
        return keyPair;
    }

    /**
     * Wraps (encrypts) an AES secret key using an RSA public key.
     *
     * @param aesKey       the AES secret key to wrap
     * @param rsaPublicKey the RSA public key used for wrapping
     * @return the wrapped key as a Base64-encoded string
     * @throws GeneralSecurityException if wrapping fails
     */
    public String wrapKey(SecretKey aesKey, PublicKey rsaPublicKey) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance(TRANSFORMATION);

        OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
        );

        cipher.init(Cipher.WRAP_MODE, rsaPublicKey, oaepSpec);
        byte[] wrappedKey = cipher.wrap(aesKey);
        String encoded = Base64.getEncoder().encodeToString(wrappedKey);
        log.debug("Wrapped AES key ({} bytes wrapped -> {} chars Base64)",
                wrappedKey.length, encoded.length());
        return encoded;
    }

    /**
     * Unwraps (decrypts) an AES secret key using an RSA private key.
     *
     * @param wrappedKeyBase64 the Base64-encoded wrapped key
     * @param rsaPrivateKey    the RSA private key used for unwrapping
     * @return the original AES {@link SecretKey}
     * @throws GeneralSecurityException if unwrapping fails (e.g., wrong private key)
     */
    public SecretKey unwrapKey(String wrappedKeyBase64, PrivateKey rsaPrivateKey)
            throws GeneralSecurityException {

        byte[] wrappedKeyBytes = Base64.getDecoder().decode(wrappedKeyBase64);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);

        OAEPParameterSpec oaepSpec = new OAEPParameterSpec(
                "SHA-256",
                "MGF1",
                MGF1ParameterSpec.SHA256,
                PSource.PSpecified.DEFAULT
        );

        cipher.init(Cipher.UNWRAP_MODE, rsaPrivateKey, oaepSpec);
        SecretKey unwrappedKey = (SecretKey) cipher.unwrap(wrappedKeyBytes, "AES", Cipher.SECRET_KEY);
        log.debug("Unwrapped AES key successfully");
        return unwrappedKey;
    }

    /**
     * Encodes an RSA public key to a Base64 string for transport/storage.
     *
     * @param publicKey the RSA public key
     * @return Base64-encoded public key bytes
     */
    public String encodePublicKey(PublicKey publicKey) {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    /**
     * Encodes an RSA private key to a Base64 string.
     * <strong>Security note:</strong> Private keys should only be returned to the client
     * and never stored on the coordinator.
     *
     * @param privateKey the RSA private key
     * @return Base64-encoded private key bytes
     */
    public String encodePrivateKey(PrivateKey privateKey) {
        return Base64.getEncoder().encodeToString(privateKey.getEncoded());
    }
}
