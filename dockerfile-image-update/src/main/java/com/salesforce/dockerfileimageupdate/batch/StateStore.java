/*
 * Copyright (c) 2018, salesforce.com, inc.
 * All rights reserved.
 * Licensed under the BSD 3-Clause license.
 * For full license text, see LICENSE.txt file in the repo root or
 * https://opensource.org/licenses/BSD-3-Clause
 */

package com.salesforce.dockerfileimageupdate.batch;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads and writes {@link BatchState} to a JSON file in the run directory.
 *
 * <p>The file is only ever replaced atomically: the state is written to a sibling temporary file
 * and then moved over the target. A process killed mid-write therefore leaves either the previous
 * complete file or a stray temporary file, never a half-written state file. If a state file is
 * nevertheless missing, truncated or otherwise unreadable, {@link #load()} returns a fresh state
 * and reports that it did so; it never throws for a damaged file. Starting over can cause
 * duplicate work but cannot cause a repository to be skipped.</p>
 */
public final class StateStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path path;

    /**
     * @param path state file path
     */
    public StateStore(Path path) {
        this.path = path;
    }

    /**
     * Load state, falling back to a fresh state when the file is missing or damaged.
     *
     * @return load result, never {@code null}
     */
    public LoadResult load() {
        if (!Files.exists(path)) {
            return new LoadResult(BatchState.empty(), LoadStatus.MISSING, null);
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            BatchState state = decode(text);
            if (!state.isUsable()) {
                return new LoadResult(BatchState.empty(), LoadStatus.CORRUPT,
                        "unsupported or invalid state (version " + state.version() + ")");
            }
            return new LoadResult(state, LoadStatus.LOADED, null);
        } catch (Exception e) {
            // A damaged file must never abort the run; the engine starts over instead. Catch
            // Exception rather than a list of types so that no future parse failure escapes.
            return new LoadResult(BatchState.empty(), LoadStatus.CORRUPT, e.getMessage());
        }
    }

    private static BatchState decode(String text) {
        JsonElement root = JsonParser.parseString(text);
        if (root == null || !root.isJsonObject()) {
            throw new IllegalStateException("state root is not an object");
        }
        JsonObject object = root.getAsJsonObject();
        int version = object.get("version").getAsInt();
        Instant windowStart = object.has("windowStart") && !object.get("windowStart").isJsonNull()
                ? Instant.parse(object.get("windowStart").getAsString())
                : null;
        int usedInWindow = object.has("usedInWindow") ? object.get("usedInWindow").getAsInt() : 0;
        java.util.Set<String> repositoriesInWindow = new java.util.LinkedHashSet<>();
        if (object.has("repositoriesInWindow") && object.get("repositoriesInWindow").isJsonArray()) {
            for (JsonElement element : object.getAsJsonArray("repositoriesInWindow")) {
                repositoriesInWindow.add(element.getAsString());
            }
        }
        Map<String, Instant> successes = new LinkedHashMap<>();
        if (object.has("lastSuccess") && object.get("lastSuccess").isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : object.getAsJsonObject("lastSuccess").entrySet()) {
                successes.put(entry.getKey(), Instant.parse(entry.getValue().getAsString()));
            }
        }
        return new BatchState(version, windowStart, usedInWindow, repositoriesInWindow, successes);
    }

    /**
     * Persist state atomically.
     *
     * @param state state to write
     * @throws IOException if the state cannot be written
     */
    public void save(BatchState state) throws IOException {
        JsonObject object = new JsonObject();
        object.addProperty("version", state.version());
        object.addProperty("windowStart",
                state.windowStart() == null ? null : state.windowStart().toString());
        object.addProperty("usedInWindow", state.usedInWindow());
        com.google.gson.JsonArray repositories = new com.google.gson.JsonArray();
        for (String repository : state.repositoriesInWindow()) {
            repositories.add(repository);
        }
        object.add("repositoriesInWindow", repositories);
        JsonObject successes = new JsonObject();
        for (Map.Entry<String, Instant> entry : state.lastSuccessByKey().entrySet()) {
            successes.addProperty(entry.getKey(), entry.getValue().toString());
        }
        object.add("lastSuccess", successes);

        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = Files.createTempFile(parent, path.getFileName().toString(), ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                GSON.toJson(object, writer);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public Path path() {
        return path;
    }

    /**
     * Result of a load, including whether the engine started over.
     */
    public static final class LoadResult {
        private final BatchState state;
        private final LoadStatus status;
        private final String detail;

        LoadResult(BatchState state, LoadStatus status, String detail) {
            this.state = state;
            this.status = status;
            this.detail = detail;
        }

        public BatchState state() {
            return state;
        }

        public LoadStatus status() {
            return status;
        }

        public String detail() {
            return detail;
        }

        /**
         * @return whether the run must start over rather than resume
         */
        public boolean startedOver() {
            return status != LoadStatus.LOADED;
        }
    }

    /**
     * How the state file was read.
     */
    public enum LoadStatus {
        /** A complete, valid state file was read. */
        LOADED,
        /** No state file existed; this is the first run. */
        MISSING,
        /** A state file existed but could not be used; the run started over. */
        CORRUPT
    }
}
