package com.epherical.epherolib.lang;

import net.minecraft.locale.Language;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.StringDecomposer;

import java.util.Map;
import java.util.Optional;

public class ServerLanguage extends Language {

    private final Map<String, String> translations;


    public ServerLanguage(Map<String, String> translations) {
        this.translations = translations;
    }


    @Override
    public String getOrDefault(String key, String fallback) {
        return translations.getOrDefault(key, fallback);
    }

    @Override
    public boolean has(String key) {
        return translations.containsKey(key);
    }

    @Override
    public boolean isDefaultRightToLeft() {
        return false;
    }

    @Override
    public FormattedCharSequence getVisualOrder(FormattedText formattedText) {
        return (sink)
                -> formattedText.visit((style, str)
                -> StringDecomposer.iterateFormatted(str, style, sink) ? Optional.empty() : FormattedText.STOP_ITERATION, Style.EMPTY).isPresent();
    }

}
