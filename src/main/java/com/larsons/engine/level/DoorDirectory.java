package com.larsons.engine.level;

import com.larsons.engine.util.Json;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The externally-referenced door list for one game type: which kinds of door
 * exist and which level each one loads. Stored as {@code doors.json} beside
 * the game type's saved levels ({@code resources/levels/<game-type>/}), so a
 * game type's whole world graph — levels plus the doors wiring them together
 * — travels as one folder.
 *
 * <p>The creative editor's <em>Doors</em> palette is built from this list;
 * painting a door stamps its {@code key} into the level, and walking into it
 * in play/test loads {@link DoorLink#targetLevel()} from the same game type's
 * {@link LevelStore}. Editing an entry here (say, retargeting "door_boss" at
 * a new arena) re-routes every painted instance across every level at once.
 */
public final class DoorDirectory {

    public static final String FILE_NAME = "doors.json";

    private final LevelStore store;
    private final List<DoorLink> doors = new ArrayList<>();

    public DoorDirectory(String gameTypeName) {
        this(new LevelStore(gameTypeName));
    }

    public DoorDirectory(LevelStore store) {
        this.store = store;
        load();
    }

    public Path file() {
        return store.directory().resolve(FILE_NAME);
    }

    /** All door links, in file order (drives the creative palette). */
    public List<DoorLink> all() {
        return List.copyOf(doors);
    }

    public DoorLink get(String key) {
        if (key == null) return null;
        for (DoorLink d : doors) {
            if (d.key().equals(key)) return d;
        }
        return null;
    }

    /** Add or replace the link with the same key, then persist. */
    public void put(DoorLink link) {
        doors.removeIf(d -> d.key().equals(link.key()));
        doors.add(link);
        save();
    }

    public void remove(String key) {
        if (doors.removeIf(d -> d.key().equals(key))) save();
    }

    /** A key not yet in the directory ("door_1", "door_2", …). */
    public String freshKey() {
        for (int i = 1; ; i++) {
            if (get("door_" + i) == null) return "door_" + i;
        }
    }

    private void load() {
        doors.clear();
        Path f = file();
        if (!Files.exists(f)) return;
        try {
            Map<String, Object> root = Json.asObject(Json.parse(Files.readString(f)));
            if (root.get("doors") instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?, ?> m) doors.add(DoorLink.fromMap(Json.asObject(m)));
                }
            }
        } catch (IOException | RuntimeException e) {
            System.err.println("DoorDirectory: unreadable " + f + " (" + e.getMessage() + ")");
        }
    }

    private void save() {
        Map<String, Object> root = new LinkedHashMap<>();
        List<Object> list = new ArrayList<>(doors.size());
        for (DoorLink d : doors) list.add(d.toMap());
        root.put("doors", list);
        try {
            Files.createDirectories(store.directory());
            Files.writeString(file(), Json.stringify(root));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
