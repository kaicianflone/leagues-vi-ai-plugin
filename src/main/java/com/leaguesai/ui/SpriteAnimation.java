package com.leaguesai.ui;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SpriteAnimation {

    private static final String[] EMPTY_FRAME = new String[0];
    private static final String FRAME_DELIMITER = "---";

    private final List<String[]> frames;

    private SpriteAnimation(List<String[]> frames) {
        this.frames = frames;
    }

    public int getFrameCount() {
        return frames.size();
    }

    public String[] getFrame(int index) {
        return frames.get(index % frames.size());
    }

    public static SpriteAnimation parse(String content) {
        if (content == null || content.trim().isEmpty()) {
            List<String[]> single = new ArrayList<>();
            single.add(EMPTY_FRAME);
            return new SpriteAnimation(single);
        }

        String[] chunks = content.split("(?m)^---$");
        List<String[]> frames = new ArrayList<>();
        for (String chunk : chunks) {
            // Strip only leading/trailing blank lines, not spaces within lines
            String stripped = chunk.replaceAll("^\\n+", "").replaceAll("\\n+$", "");
            if (!stripped.isEmpty()) {
                frames.add(stripped.split("\n", -1));
            }
        }

        if (frames.isEmpty()) {
            frames.add(EMPTY_FRAME);
        }

        return new SpriteAnimation(frames);
    }

    public static SpriteAnimation loadFromResource(String resourcePath) {
        try (InputStream is = SpriteAnimation.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                return emptyAnimation();
            }
            String content = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            return parse(content);
        } catch (Exception e) {
            return emptyAnimation();
        }
    }

    private static SpriteAnimation emptyAnimation() {
        List<String[]> single = new ArrayList<>();
        single.add(EMPTY_FRAME);
        return new SpriteAnimation(single);
    }
}
