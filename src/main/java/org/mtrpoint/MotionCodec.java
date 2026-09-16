package org.mtrpoint;

import net.minecraft.network.FriendlyByteBuf;
import java.util.ArrayList;
import java.util.List;

/** Wire format for detached visual movement snapshots. */
public final class MotionCodec {
    // MTR 4.0.3 TwoPositionsBase.getHexIdRaw: six padded 64-bit hex coordinates
    // separated by five hyphens. This is 101 characters, not a 16-character ID.
    private static final int RAIL_ID_LENGTH = 6 * 16 + 5;
    private static final int NODE_LENGTH = 100;
    private static final int MAX_MOVEMENTS = 8192;

    private MotionCodec() {}

    public static void encode(PointNetwork.Motion motion, FriendlyByteBuf buffer) {
        if (motion.entries().size() > MAX_MOVEMENTS) throw new IllegalArgumentException("Too many movements");
        buffer.writeUtf(motion.dimension());
        buffer.writeBoolean(motion.brPresent());
        buffer.writeLong(motion.timestamp());
        buffer.writeVarInt(motion.entries().size());
        for (var entry : motion.entries()) {
            buffer.writeUtf(entry.node(), NODE_LENGTH);
            buffer.writeUtf(entry.from(), RAIL_ID_LENGTH);
            buffer.writeUtf(entry.to(), RAIL_ID_LENGTH);
            buffer.writeBoolean(entry.occupied());
            buffer.writeLong(entry.vehicle());
            buffer.writeDouble(entry.distance());
        }
    }

    public static PointNetwork.Motion decode(FriendlyByteBuf buffer) {
        String dimension = buffer.readUtf();
        boolean br = buffer.readBoolean();
        long timestamp = buffer.readLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_MOVEMENTS) throw new IllegalArgumentException("Too many movements");
        var entries = new ArrayList<PointNetwork.Movement>(count);
        for (int i = 0; i < count; i++) {
            entries.add(new PointNetwork.Movement(buffer.readUtf(NODE_LENGTH),
                    buffer.readUtf(RAIL_ID_LENGTH), buffer.readUtf(RAIL_ID_LENGTH),
                    buffer.readBoolean(), buffer.readLong(), buffer.readDouble()));
        }
        return new PointNetwork.Motion(dimension, br, timestamp, List.copyOf(entries));
    }
}
