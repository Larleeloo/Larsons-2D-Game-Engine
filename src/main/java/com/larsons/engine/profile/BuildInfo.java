package com.larsons.engine.profile;

import java.io.InputStream;
import java.util.Properties;

/**
 * Which build produced a measurement.
 *
 * <p><b>Why a report needs this.</b> Two profiles came back from an M1 Air
 * showing no draw-call section and a 19 ms scene, which could mean either that
 * the machine was struggling in a new way or that the build predated the work
 * meant to fix it. Answering that took a round trip and a guess. A frame
 * profile is evidence, and evidence that cannot be traced to a build is worth
 * much less than one that can — so every report now names the commit it came
 * from.
 *
 * <p>The value is written into the jar at assembly time by the Gradle build.
 * Running from a plain classpath (an IDE, a test) has no such file, and the
 * report says so rather than inventing a version.
 */
public final class BuildInfo {

    private static final String UNKNOWN = "unpackaged (run from classes, not a built jar)";

    private static final String DESCRIPTION = load();

    private BuildInfo() {}

    /** A one-line build identity for the head of a report. */
    public static String describe() { return DESCRIPTION; }

    private static String load() {
        try (InputStream in = BuildInfo.class.getResourceAsStream("/build-info.properties")) {
            if (in == null) return UNKNOWN;
            Properties props = new Properties();
            props.load(in);
            String commit = props.getProperty("commit", "").trim();
            String built = props.getProperty("built", "").trim();
            if (commit.isEmpty() && built.isEmpty()) return UNKNOWN;
            return (commit.isEmpty() ? "unknown commit" : commit)
                    + (built.isEmpty() ? "" : ", built " + built);
        } catch (Exception e) {
            return UNKNOWN;
        }
    }
}
