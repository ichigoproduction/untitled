package untitled.untitled.client;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/** Manifest verification is fail-closed. The trusted key ships inside the mod JAR, never from HTTP. */
public final class UpdateSignature {
    private static final String PUBLIC_KEY_RESOURCE = "/assets/untitled/update-public-key.txt";
    private UpdateSignature() {}

    /**
     * Byte-for-byte protocol shared with tools/sign_update.py.
     * The protocol tag and LF separators prevent ambiguity between fields.
     */
    public static byte[] signedPayload(String version, String minecraft, String download, String sha256, long size) {
        String payload = "152-update-manifest-v1\n"
            + version + "\n"
            + minecraft + "\n"
            + download + "\n"
            + sha256 + "\n"
            + size + "\n";
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    public static void verify(String version, String minecraft, String download, String sha256,
                              long size, String signatureBase64) throws Exception {
        if (signatureBase64 == null || signatureBase64.isBlank())
            throw new SecurityException("Update signature missing");
        byte[] keyBytes;
        try (InputStream stream = UpdateSignature.class.getResourceAsStream(PUBLIC_KEY_RESOURCE)) {
            if (stream == null) throw new SecurityException("Update signing public key is not configured");
            String encoded = new String(stream.readNBytes(4096), StandardCharsets.US_ASCII).trim();
            if (encoded.isEmpty() || encoded.startsWith("#"))
                throw new SecurityException("Update signing public key is not configured");
            keyBytes = Base64.getDecoder().decode(encoded);
        }
        PublicKey key = KeyFactory.getInstance("Ed25519")
            .generatePublic(new X509EncodedKeySpec(keyBytes));
        byte[] signature = Base64.getDecoder().decode(signatureBase64);
        if (signature.length != 64) throw new SecurityException("Invalid Ed25519 signature length");
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(signedPayload(version, minecraft, download, sha256, size));
        if (!verifier.verify(signature)) throw new SecurityException("Update signature invalid");
    }
}
