package io.github.foundationgames.splinecart.item.tools;

import com.mojang.brigadier.Message;
import io.github.foundationgames.splinecart.block.TrackMarkerBlockEntity;
import io.github.foundationgames.splinecart.track.TrackStyle;
import io.github.foundationgames.splinecart.track.TrackType;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Colors;

public enum ToolType {

    HEADING(value -> {
        MutableText text = Text.translatable("item.splinecart.heading_tool.msg").append(Text.of(valToQuarterDirection(value) + " (" + value + "°)"));
        text.withColor(Colors.GREEN);
        return text;
    }),
    PITCHING(value -> {
        MutableText text = Text.translatable("item.splinecart.pitching_tool.msg").append(Text.of(valToOrientation(value)));
        text.withColor(Colors.CYAN);
        return text;
    }),
    BANKING(value -> {
        MutableText text = Text.translatable("item.splinecart.banking_tool.msg").append(Text.of(valToOrientation(value)));
        text.withColor(Colors.YELLOW);
        return text;
    }),
    RELATIVE_ORIENTATION(value -> {
        MutableText text = Text.translatable("item.splinecart.relative_orientation_tool.msg").append(Text.of(valToOrientation(value)));
        text.withColor(Colors.RED);
        return text;
    }),
    TRACK_STYLE(value -> {
        MutableText text = Text.translatable("item.splinecart.track_style_tool.msg").append(Text.of(TrackStyle.values()[value].name + " (" + (value + 1) + ")"));
        text.withColor(Colors.GREEN);
        return text;
    }, 8),
    TRACK_TYPE(value -> {
        MutableText text = Text.translatable("item.splinecart.track_type_tool.msg").append(Text.of(TrackType.values()[value].name + " (" + (value + 1) + ")"));
        text.withColor(Colors.GREEN);
        return text;
    }, 3),
    POWER(value -> {
        MutableText text = Text.translatable("item.splinecart.track_power_tool.msg").append(Text.of(value == Integer.MAX_VALUE ? "Unset" : (double) value / 10 + ""));
        text.withColor(Colors.GREEN);
        return text;
    });

    public final MessageBuilder currentStateMsg;
    public final int settings;

    ToolType(MessageBuilder currentStateMsg) {
        this.currentStateMsg = currentStateMsg;
        settings = 0;
    }

    ToolType(MessageBuilder currentStateMsg, int settings) {
        this.currentStateMsg = currentStateMsg;
        this.settings = settings;
    }

    public interface MessageBuilder {
        Message get(int value);
    }

    private static String valToOrientation(int val) {
        if(val == 0) {
            return "0";
        }
        if(val <= TrackMarkerBlockEntity.ORIENTATION_RESOLUTION / 2) {
            return val + "°";
        }
        return "-" + (TrackMarkerBlockEntity.ORIENTATION_RESOLUTION - val) + "°";
    }

    private static String valToQuarterDirection(int val) {
        for(QuarterDirection direction : QuarterDirection.values()) {
            if(val == direction.value) {
                return direction.name;
            }
            if(val > direction.value && val < direction.value + STEP) {
                int relativeDirection = val - direction.value;
                if(relativeDirection == STEP / 2)
                    return direction.compiledName;
                return direction.compiledName + " " + relativeDirection + "°";
            }
        }
        return "ERROR";
    }

    public static final int STEP = 90;

    public enum QuarterDirection {
        SOUTH(0, "S", "SE"),
        EAST(STEP, "E", "NE"),
        NORTH(2 * STEP, "N", "NW"),
        WEST(3 * STEP, "W", "SW");

        public final int value;
        public final String name;
        public final String compiledName;

        QuarterDirection(int value, String name, String compiledName) {
            this.value = value;
            this.name = name;
            this.compiledName = compiledName;
        }
    }

}
