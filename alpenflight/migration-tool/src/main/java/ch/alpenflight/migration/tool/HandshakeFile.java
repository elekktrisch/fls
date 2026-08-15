package ch.alpenflight.migration.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Pattern;

public final class HandshakeFile {

    private static final int REQUIRED_MODULUS_BITS = 4096;

    private static final Pattern PEM_BODY = Pattern.compile(
            "-----BEGIN PUBLIC KEY-----(.*?)-----END PUBLIC KEY-----",
            Pattern.DOTALL);

    private final UUID uploadId;
    private final RSAPublicKey publicKey;
    private final byte[] publicKeyDer;

    private HandshakeFile(UUID uploadId, RSAPublicKey publicKey, byte[] publicKeyDer) {
        this.uploadId = uploadId;
        this.publicKey = publicKey;
        this.publicKeyDer = publicKeyDer;
    }

    public UUID uploadId() {
        return uploadId;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public byte[] publicKeyDer() {
        return publicKeyDer.clone();
    }

    public static HandshakeFile parse(Path file) {
        byte[] raw;
        try {
            raw = Files.readAllBytes(file);
        } catch (IOException e) {
            throw new ExportException(ExitCode.USAGE,
                    "Cannot read handshake file " + file + ": " + e.getMessage(), e);
        }
        JsonNode root;
        try {
            root = new ObjectMapper().readTree(raw);
        } catch (IOException e) {
            throw new ExportException(ExitCode.USAGE,
                    "Handshake file " + file + " is not valid JSON: " + e.getMessage(), e);
        }
        UUID uploadId = parseUploadId(root, file);
        JsonNode pem = root.get("publicKeyPem");
        if (pem == null || !pem.isTextual() || pem.asText().isBlank()) {
            throw new ExportException(ExitCode.USAGE,
                    "Handshake file " + file + " is missing a 'publicKeyPem' string");
        }
        byte[] der = decodeSpki(pem.asText());
        RSAPublicKey key = toRsaPublicKey(der);
        return new HandshakeFile(uploadId, key, der);
    }

    private static UUID parseUploadId(JsonNode root, Path file) {
        JsonNode node = root.get("uploadId");
        if (node == null || !node.isTextual()) {
            throw new ExportException(ExitCode.USAGE,
                    "Handshake file " + file + " is missing an 'uploadId' string");
        }
        try {
            return UUID.fromString(node.asText());
        } catch (IllegalArgumentException e) {
            throw new ExportException(ExitCode.USAGE,
                    "Handshake 'uploadId' is not a valid UUID: " + node.asText(), e);
        }
    }

    private static byte[] decodeSpki(String pem) {
        var matcher = PEM_BODY.matcher(pem);
        if (!matcher.find()) {
            throw new ExportException(ExitCode.PUBLIC_KEY_INVALID,
                    "publicKeyPem is not a PEM 'PUBLIC KEY' block");
        }
        String base64 = matcher.group(1).replaceAll("\\s", "");
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new ExportException(ExitCode.PUBLIC_KEY_INVALID,
                    "publicKeyPem base64 body is malformed: " + e.getMessage(), e);
        }
    }

    private static RSAPublicKey toRsaPublicKey(byte[] der) {
        PublicKey key;
        try {
            key = KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(der));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA KeyFactory unavailable", e);
        } catch (InvalidKeySpecException e) {
            throw new ExportException(ExitCode.PUBLIC_KEY_INVALID,
                    "publicKeyPem is not a valid RSA X.509 public key: " + e.getMessage(), e);
        }
        if (!(key instanceof RSAPublicKey rsa)) {
            throw new ExportException(ExitCode.PUBLIC_KEY_INVALID,
                    "publicKeyPem is not an RSA key (got " + key.getAlgorithm() + ")");
        }
        int bits = rsa.getModulus().bitLength();
        if (bits != REQUIRED_MODULUS_BITS) {
            throw new ExportException(ExitCode.PUBLIC_KEY_INVALID,
                    "publicKeyPem modulus must be " + REQUIRED_MODULUS_BITS
                            + " bits, got " + bits);
        }
        return rsa;
    }
}
