package ch.alpenflight.migration.bundle.crypto;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

public interface MigrationBundleCipher {

    SecureBytes unwrapSessionKey(byte[] rsaPrivateKeyPkcs8, byte[] wrappedSessionKey);

    InputStream newDecryptingStream(SecureBytes sessionKey,
                                    UUID uploadId,
                                    InputStream ciphertextBody);

    OutputStream newEncryptingStream(SecureBytes sessionKey,
                                     UUID uploadId,
                                     OutputStream ciphertextSink);

    byte[] wrapSessionKey(byte[] rsaPublicKeyDer, byte[] sessionKey);
}
