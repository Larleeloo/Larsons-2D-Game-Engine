package com.larsons.engine.audio;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One creator's assignment for a sound key: where its audio comes from and
 * how it plays. The audio twin of
 * {@link com.larsons.engine.graphics.SkinDef}.
 *
 * <p>Two ways to supply the audio, and the first is the default:
 * <ul>
 *   <li><b>{@link #usePack} on</b> — the file is whatever sits at this
 *       sound's file name inside the drop-in {@link SoundPack} folder.
 *       Nothing there? Silence (or the engine's built-in voice, for the
 *       handful of actions that have one), so the switch is safe to leave on
 *       for everything.</li>
 *   <li><b>A file elsewhere</b> — switch the pack off, or just fill in
 *       {@link #file}, to point this one sound at any WAV or MP3 on disk.</li>
 * </ul>
 *
 * <p>{@link #volume}, {@link #pitch} and {@link #loop} override the pack's
 * settings for this key; {@link #varyPitch} decides whether the subtle
 * per-playback pitch drift applies to it (off for anything that must sound
 * identical every time). {@link #builtin} keeps the engine's synthesized
 * fallback for keys that have one — turn it off to make an action truly
 * silent until a file is supplied.
 *
 * @param key       the {@link SoundKeys} key this is for
 * @param file      an explicit path to a WAV/MP3, or blank for pack-only
 * @param volume    level, 1 = as recorded
 * @param pitch     playback speed, 1 = as recorded
 * @param loop      whether the sound repeats until stopped
 * @param varyPitch whether the fresh-pitch drift applies
 * @param usePack   whether the sound pack folder supplies the audio
 * @param builtin   whether the engine's synthesized fallback may be used
 */
public record SoundDef(String key, String file, double volume, double pitch,
                       boolean loop, boolean varyPitch, boolean usePack, boolean builtin) {

    public SoundDef {
        key = key == null ? "" : key.trim();
        file = file == null ? "" : file.trim();
        volume = clamp(volume, 0, 4);
        pitch = clamp(pitch, 0.25, 4);
    }

    /** A pack-supplied sound at the pack's own settings — the default state. */
    public static SoundDef packDefault(String key) {
        SoundPack.Playback p = SoundPack.playbackFor(key);
        return new SoundDef(key, "", p.volume(), p.pitch(), p.loop(), p.varyPitch(),
                true, true);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /** Whether this differs from what the key would do with no assignment. */
    public boolean isDefault() {
        return file.isEmpty() && usePack && builtin && equalsPlayback(SoundPack.playbackFor(key));
    }

    private boolean equalsPlayback(SoundPack.Playback p) {
        return volume == p.volume() && pitch == p.pitch()
                && loop == p.loop() && varyPitch == p.varyPitch();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("file", file);
        m.put("volume", volume);
        m.put("pitch", pitch);
        m.put("loop", loop);
        m.put("varyPitch", varyPitch);
        m.put("usePack", usePack);
        m.put("builtin", builtin);
        return m;
    }

    public static SoundDef fromMap(Map<String, Object> m) {
        String key = str(m, "key");
        return new SoundDef(key, str(m, "file"),
                dbl(m, "volume", SoundPack.DEFAULT_VOLUME),
                dbl(m, "pitch", SoundPack.DEFAULT_PITCH),
                bool(m, "loop", SoundKeys.isLooping(key)),
                bool(m, "varyPitch", !SoundKeys.isMusic(key)),
                bool(m, "usePack", true),
                bool(m, "builtin", true));
    }

    private static String str(Map<String, Object> m, String k) {
        return m.get(k) instanceof String s ? s : "";
    }

    private static double dbl(Map<String, Object> m, String k, double def) {
        return m.get(k) instanceof Number n ? n.doubleValue() : def;
    }

    private static boolean bool(Map<String, Object> m, String k, boolean def) {
        return m.get(k) instanceof Boolean b ? b : def;
    }
}
