package com.karnataka.ubid.ubid;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.UUID;

/**
 * UUID v5 (SHA-1, name-based) generator.
 *
 * Java's {@link UUID#nameUUIDFromBytes(byte[])} produces v3 (MD5). UUID v5
 * uses SHA-1 — both stable and well-supported, but v5 is what RFC 4122
 * recommends for new applications, and what the proposal commits to.
 *
 * UBIDs are namespaced to PAN when available (so the identifier is anchored
 * to the Central identifier and is portable across departments). Records
 * without PAN are namespaced to an internal namespace and can be promoted
 * later when PAN becomes known.
 */
public final class UBIDGenerator {

    /** Namespace UUID for PAN-anchored UBIDs (deterministic constant). */
    public static final UUID PAN_NAMESPACE  = UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");

    /** Namespace UUID for internal (non-PAN) UBIDs. */
    public static final UUID INTERNAL_NAMESPACE = UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

    private UBIDGenerator() {}

    /** Generate a PAN-anchored UBID. The same PAN always yields the same UBID. */
    public static String fromPan(String pan) {
        return "UBID-" + uuidV5(PAN_NAMESPACE, pan).toString();
    }

    /** Generate an internal-namespace UBID for records without PAN. */
    public static String internal(String seed) {
        return "UBID-" + uuidV5(INTERNAL_NAMESPACE, seed).toString();
    }

    private static UUID uuidV5(UUID namespace, String name) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            sha1.update(uuidToBytes(namespace));
            sha1.update(name.getBytes(StandardCharsets.UTF_8));
            byte[] hash = sha1.digest();

            // Set version (5) and variant (RFC 4122) bits per RFC 4122 §4.3
            hash[6] = (byte) ((hash[6] & 0x0F) | 0x50);
            hash[8] = (byte) ((hash[8] & 0x3F) | 0x80);

            ByteBuffer bb = ByteBuffer.wrap(hash, 0, 16);
            return new UUID(bb.getLong(), bb.getLong());
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute UUID v5", e);
        }
    }

    private static byte[] uuidToBytes(UUID u) {
        ByteBuffer bb = ByteBuffer.allocate(16);
        bb.putLong(u.getMostSignificantBits());
        bb.putLong(u.getLeastSignificantBits());
        return bb.array();
    }
}
