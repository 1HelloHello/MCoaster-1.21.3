package io.github.foundationgames.splinecart.track;

import io.github.foundationgames.splinecart.entity.TrackFollowerEntity;
import io.github.foundationgames.splinecart.util.SUtil;
import net.fabricmc.fabric.impl.item.ItemExtensions;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

public enum TrackType {
    DEFAULT(MotionModifier.FRICTION, null, "Default"),
    CHAIN_DRIVE((m, g, p) -> p >= 0
                    ? Math.max(MotionModifier.FRICTION.calculate(m, g, p), p * .1 / TrackFollowerEntity.METERS_PER_TICK_TO_KMH)
                    : MotionModifier.FRICTION.calculate(m, g, p),
            (p, t, col, v) -> v[0] = t * 0.0055f * ((float)p / 10),
            "Chain Drive"
    ),
    MAGNETIC((m, g, p) -> {
                double targetSpeed = p / 10 / TrackFollowerEntity.METERS_PER_TICK_TO_KMH;
                return Math.abs(targetSpeed - m) < 0.01
                    ? targetSpeed
                    : m + (targetSpeed < m ? -1 : 1) * TrackFollowerEntity.GRAVITY * TrackFollowerEntity.MAGNETIC_ACCEL_G;
            },
            (p, t, col, v) ->  {
                int absSpeed = Math.abs(p / 10);
                col.set(SUtil.REDSTONE_COLOR_LUT[Math.min(absSpeed, 15)]);
            },
            "Magnetic"
    ),
    TIRE_DRIVE((m, g, p) -> p / 10 / TrackFollowerEntity.METERS_PER_TICK_TO_KMH,
            (p, t, col, v) -> v[0] = t * ((float) p / 15 * 0.05f), // TODO
            "Tire Drive"
    ) ,
    HOLDING_BREAKS((m, g, p) -> p > 0 ? MotionModifier.FRICTION.calculate(m, g, p) : 0,
            (p, t, col, v) -> v[0] = t * ((float) p / 15 * 0.05f), // TODO
            "Holding Breaks"
    ) ;

    public static final int CANVAS_SIZE = 4;
    public static final float INVERSE_CANVAS_SIZE = (float) 1 / CANVAS_SIZE;

    public final MotionModifier motion;
    public final @Nullable Overlay overlay;
    public final String name;

    TrackType(MotionModifier motion, @Nullable Overlay overlay, String name) {
        this.motion = motion;
        this.overlay = overlay;
        this.name = name;
    }

    public static TrackType read(int type) {
        if (type < 0 || type >= values().length) {
            return DEFAULT;
        }

        return values()[type];
    }

    @FunctionalInterface
    public interface MotionModifier {
        MotionModifier FRICTION = (m, g, p) -> m - (m * TrackFollowerEntity.FRICTION);

        double calculate(double motion, double grade, double power);
    }

    @FunctionalInterface
    public interface Overlay {
        void calculateEffects(int power, float time, Vector3f outputColor, float[] outputVOffset);
    }
}
