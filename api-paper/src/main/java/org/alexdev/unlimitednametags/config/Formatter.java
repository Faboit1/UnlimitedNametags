package org.alexdev.unlimitednametags.config;

import lombok.AccessLevel;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.alexdev.unlimitednametags.api.UnlimitedNameTagsInstancePaper;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.regex.Pattern;

@SuppressWarnings("unused")
public enum Formatter {

    MINIMESSAGE(
            (plugin, player, text) -> plugin.formatTextForNametag(player, text),
            "MiniMessage"
    ),
    LEGACY(
            (plugin, player, text) -> getSTUPID().deserialize(replaceHexColorCodes(text)),
            "Legacy Text"
    ),
    UNIVERSAL(
            (plugin, player, text) -> {
                final String legacy = getHEX().serialize(getSTUPID().deserialize(replaceHexColorCodes(text)));
                final Component component = getHEX().deserialize(legacy);
                final String string = MiniMessage.miniMessage().serialize(component).replace("\\<", "<").replace("\\", "");
                return MINIMESSAGE.formatter.apply(plugin, player, string);
            },
            "Universal"
    );

    @NotNull
    private static String replaceHexColorCodes(@NotNull String text) {
        return text.replace('§', '&');
    }

    @Getter
    private final String name;

    private final TriFunction<UnlimitedNameTagsInstancePaper, CommandSender, String, Component> formatter;

    @Getter(value = AccessLevel.PRIVATE)
    private static final Pattern HEX_PATTERN = Pattern.compile("&#[a-fA-F0-9]{6}");
    @Getter(value = AccessLevel.PRIVATE)
    private static final String LEGACY_RESET = "&r";
    @Getter(value = AccessLevel.PRIVATE)
    private static final String REPLACE_RESET = "###RESET###";
    @Getter(value = AccessLevel.PRIVATE)
    private static final LegacyComponentSerializer STUPID = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .useUnusualXRepeatedCharacterHexFormat()
            .hexColors()
            .build();
    @Getter(value = AccessLevel.PRIVATE)
    private static final LegacyComponentSerializer HEX = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .build();

    Formatter(@NotNull TriFunction<UnlimitedNameTagsInstancePaper, CommandSender, String, Component> formatter, @NotNull String name) {
        this.formatter = formatter;
        this.name = name;
    }

    /**
     * {@link TextFormatter} ordinal → {@link Formatter}, resolved once instead of doing a
     * {@code valueOf(name())} string lookup for every nametag line of every viewer on every refresh.
     * An entry stays {@code null} when no constant matches, so {@link #from(TextFormatter)} keeps
     * throwing the same {@link IllegalArgumentException} it always did.
     */
    private static final Formatter[] BY_TEXT_FORMATTER = buildTextFormatterLookup();

    @NotNull
    private static Formatter[] buildTextFormatterLookup() {
        final TextFormatter[] textFormatters = TextFormatter.values();
        final Formatter[] lookup = new Formatter[textFormatters.length];
        for (int i = 0; i < textFormatters.length; i++) {
            try {
                lookup[i] = valueOf(textFormatters[i].name());
            } catch (IllegalArgumentException ignored) {
                lookup[i] = null;
            }
        }
        return lookup;
    }

    @NotNull
    public static Formatter from(@NotNull TextFormatter textFormatter) {
        final Formatter cached = BY_TEXT_FORMATTER[textFormatter.ordinal()];
        return cached != null ? cached : valueOf(textFormatter.name());
    }

    public Component format(@NotNull UnlimitedNameTagsInstancePaper plugin, @NotNull CommandSender audience, @NotNull String text) {
        return formatter.apply(plugin, audience, text);
    }
}
