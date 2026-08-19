package com.larsons.engine.config;

import com.larsons.engine.level.LevelStore;
import com.larsons.engine.save.SaveStore;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Renaming a game type — the whole of it, in one place.
 *
 * <p>A game type is not a name on a profile: it is a folder of levels, the
 * doors and custom content wiring them together, and the saved runs through
 * them, all of which are filed <em>under that name</em>. So a rename is four
 * moves that have to happen together, and the failure of doing three of them is
 * a creator whose levels have disappeared.
 *
 * <p><b>Why this is a class rather than a method on a screen.</b> It used to be
 * the main menu's, next to a game-type editor whose name field wrote the new
 * name into the profile and saved it — which created a <em>second</em> game
 * type sharing one folder of levels, with the levels still filed under the old
 * one. Two screens asking the same question, one of which quietly did the wrong
 * half of it. With the menu's duplicate entry gone (the editor <em>is</em> where
 * a game type is edited), the editor needs the real rename, and the real rename
 * needs somewhere to live that is neither screen.
 */
public final class GameTypeRename {

    private GameTypeRename() {}

    /**
     * What a rename did, and what to tell the creator about it.
     *
     * @param renamed whether anything moved — {@code false} for a no-op name
     *                and for a refusal
     * @param message a line for the screen's status, never {@code null}
     */
    public record Result(boolean renamed, String message) {}

    /**
     * Rename the active game type to {@code newName}: move its levels folder
     * (levels, doors, custom content) and its saved runs, rewrite its profile
     * under the new name, repoint the "last played level" at the moved file,
     * and remove the profile it used to be filed under.
     *
     * <p>Refuses to land on a game type that already exists, because that is
     * not a rename — it is two game types merging into one folder, and one of
     * them losing.
     *
     * <p>A game type that has never been saved (no profile file, no levels
     * folder) is simply named: there is nothing to move, and requiring a save
     * before the first rename would make naming a new game type a two-step
     * ritual for no reason.
     */
    public static Result apply(GameContext ctx, String newName) {
        return apply(ctx, newName, LevelStore.DEFAULT_DIR, SaveStore.DEFAULT_DIR);
    }

    /**
     * {@link #apply(GameContext, String)} against the folders named, rather
     * than the ones the game ships with — the seam {@link LevelStore} and
     * {@link SaveStore} both already have, so a test can rename a game type
     * without moving the repository's own levels around.
     */
    public static Result apply(GameContext ctx, String newName,
                               String levelsRoot, String savesRoot) {
        GameProfile p = ctx.profile();
        String name = newName == null ? "" : newName.trim();
        if (name.isEmpty()) return new Result(false, "A game type needs a name");
        if (name.equals(p.name)) return new Result(false, "");

        GameTypeStore store = ctx.store();
        String oldName = p.name;
        Path oldProfile = store.fileFor(oldName);
        Path newProfile = store.fileFor(name);
        LevelStore oldLevels = new LevelStore(levelsRoot, oldName);
        Path oldLevelsDir = oldLevels.directory();
        Path newLevelsDir = new LevelStore(levelsRoot, name).directory();
        boolean differentFile = !newProfile.equals(oldProfile);

        if (differentFile && (Files.exists(newProfile) || Files.exists(newLevelsDir))) {
            return new Result(false, "A game type named \"" + name + "\" already exists");
        }

        try {
            oldLevels.moveGameTypeFolderTo(name);   // levels + doors + custom content
            // …and the runs through them, which belong to the game type just as
            // much as its levels do and would otherwise be orphaned by a rename.
            SaveStore.moveGameTypeSaves(savesRoot, oldName, name);
        } catch (RuntimeException e) {
            return new Result(false, "Could not move levels: " + e.getMessage());
        }

        p.name = name;
        // The "last played level" pointer lives under the old folder — repoint
        // it into the moved one.
        if (differentFile && !p.lastLevelPath.isEmpty()) {
            Path last = Path.of(p.lastLevelPath);
            if (last.startsWith(oldLevelsDir)) {
                p.lastLevelPath = newLevelsDir.resolve(oldLevelsDir.relativize(last)).toString();
            }
        }
        ctx.save(); // writes gametypes/<new>.json
        if (differentFile) store.delete(oldName);
        return new Result(true, "Renamed to \"" + name + "\"");
    }
}
