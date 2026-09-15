package untitled.untitled.client;

import java.util.List;
import java.util.Locale;

public final class ItemSoundInspectorFormatter {
    private ItemSoundInspectorFormatter() {
    }

    public static List<String> format(
            String soundId,
            String category,
            float volume,
            float pitch,
            double x,
            double y,
            double z
    ) {
        return List.of(
                "sound : " + soundId,
                "category : " + category,
                "volume : " + formatNumber(volume),
                "pitch : " + formatNumber(pitch),
                "position : " + formatNumber(x) + ", " + formatNumber(y) + ", " + formatNumber(z)
        );
    }

    private static String formatNumber(double value) {
        String valueText = String.format(Locale.ROOT, "%.3f", value);
        valueText = valueText.replaceFirst("0+$", "");
        if (valueText.endsWith(".")) {
            valueText += "0";
        }
        return valueText;
    }
}
