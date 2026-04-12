package com.leaguesai.data;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Persists chat conversation history to disk so the user's session survives
 * RuneLite restarts. Backed by {@code chat-history.json} in the same directory
 * as {@code goals.json}.
 *
 * <p>Entries are capped at {@link #MAX_ENTRIES} on save, mirroring
 * {@code ChatService.MAX_HISTORY}. Writes use a temp-file-and-rename dance so
 * a crash mid-write cannot leave a truncated file on disk.
 */
public class ChatHistoryStore {

    public static final int MAX_ENTRIES = 20;
    private static final Gson GSON = new Gson();

    private final File file;

    public ChatHistoryStore(File file) {
        this.file = file;
    }

    /** A single persisted chat message. Public fields for Gson. */
    public static class Entry {
        public String role;
        public String content;

        public Entry() {}

        public Entry(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public List<Entry> load() {
        if (file == null || !file.exists()) return new ArrayList<>();
        try {
            String json = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (json.isEmpty()) return new ArrayList<>();
            Type type = new TypeToken<List<Entry>>() {}.getType();
            List<Entry> loaded = GSON.fromJson(json, type);
            return loaded != null ? loaded : new ArrayList<>();
        } catch (IOException | JsonSyntaxException e) {
            return new ArrayList<>();
        }
    }

    public void save(List<Entry> entries) {
        if (file == null) return;
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        List<Entry> toWrite = entries.size() > MAX_ENTRIES
                ? entries.subList(entries.size() - MAX_ENTRIES, entries.size())
                : entries;
        try {
            File tmp = new File(file.getParentFile(), file.getName() + ".tmp");
            Files.write(tmp.toPath(), GSON.toJson(toWrite).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Best-effort — plugin must not crash because the filesystem is full.
        }
    }

    public void clear() {
        save(new ArrayList<>());
    }
}
