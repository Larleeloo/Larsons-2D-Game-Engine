package com.larsons.engine.core;

import com.larsons.engine.net.Lan;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Builds a shareable, double-clickable game jar automatically on launch —
 * so starting the game from IntelliJ (or {@code gradle run}) leaves a
 * {@code share/} folder containing everything a friend needs to join:
 *
 * <ul>
 *   <li>{@code larsons-2d-game-engine.jar} — the whole game, runnable with
 *       {@code java -jar} on any machine with Java 21+ (the engine has no
 *       other dependencies by design);</li>
 *   <li>{@code run.bat} / {@code run.sh} — double-click launchers;</li>
 *   <li>{@code HOW_TO_PLAY_ONLINE.txt} — hosting/joining instructions,
 *       including this machine's LAN address for same-network play.</li>
 * </ul>
 *
 * <p>When launched from an IDE/Gradle the classpath is class directories;
 * they're bundled into the jar (with a {@code Main-Class} manifest). When
 * already running from a jar, that jar is just copied. Rebuilds are skipped
 * while the jar is newer than every class/resource it would contain, so the
 * launch-time cost after the first build is a timestamp scan.
 */
public final class ShareJar {

    public static final String JAR_NAME = "larsons-2d-game-engine.jar";
    public static final String DEFAULT_DIR = "share";

    private ShareJar() {}

    /** Build (or refresh) the share folder in the background; never blocks launch. */
    public static void writeAsync() {
        Thread t = new Thread(() -> {
            try {
                Path jar = write(Path.of(DEFAULT_DIR));
                System.out.println("[share] Shareable game ready: " + jar.toAbsolutePath()
                        + " (send the whole '" + DEFAULT_DIR + "' folder to friends)");
            } catch (Exception e) {
                System.err.println("[share] Could not build the shareable jar: " + e);
            }
        }, "share-jar");
        t.setDaemon(true);
        t.start();
    }

    /** Build the share folder at {@code outDir}; returns the jar path. */
    public static Path write(Path outDir) throws IOException {
        Files.createDirectories(outDir);
        Path jar = outDir.resolve(JAR_NAME);

        Path runningJar = runningJar();
        if (runningJar != null) {
            // Already packaged (e.g. a friend re-sharing): just copy it along.
            if (!Files.exists(jar) || Files.getLastModifiedTime(jar)
                    .compareTo(Files.getLastModifiedTime(runningJar)) < 0) {
                Files.copy(runningJar, jar, StandardCopyOption.REPLACE_EXISTING);
            }
        } else {
            List<Path> roots = classpathDirectories();
            if (roots.isEmpty()) {
                throw new IOException("No class directories on the classpath to package");
            }
            if (needsRebuild(jar, roots)) buildJar(jar, roots);
        }

        writeScripts(outDir);
        writeInstructions(outDir);
        return jar;
    }

    /** The jar this code runs from, or {@code null} when running from classes. */
    private static Path runningJar() {
        try {
            URL loc = ShareJar.class.getProtectionDomain().getCodeSource().getLocation();
            Path p = Path.of(loc.toURI());
            return Files.isRegularFile(p) && p.toString().endsWith(".jar") ? p : null;
        } catch (URISyntaxException | RuntimeException e) {
            return null;
        }
    }

    /** Classpath entries that are directories (IDE/Gradle class + resource dirs). */
    private static List<Path> classpathDirectories() {
        List<Path> dirs = new ArrayList<>();
        String cp = System.getProperty("java.class.path", "");
        for (String entry : cp.split(File.pathSeparator)) {
            if (entry.isBlank()) continue;
            Path p = Path.of(entry);
            if (Files.isDirectory(p)) dirs.add(p);
        }
        return dirs;
    }

    /** True when any packaged file is newer than the existing jar. */
    private static boolean needsRebuild(Path jar, List<Path> roots) throws IOException {
        if (!Files.exists(jar)) return true;
        long jarTime = Files.getLastModifiedTime(jar).toMillis();
        for (Path root : roots) {
            try (Stream<Path> files = Files.walk(root)) {
                boolean newer = files.filter(Files::isRegularFile).anyMatch(f -> {
                    try {
                        return Files.getLastModifiedTime(f).toMillis() > jarTime;
                    } catch (IOException e) {
                        return true;
                    }
                });
                if (newer) return true;
            }
        }
        return false;
    }

    private static void buildJar(Path jar, List<Path> roots) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS,
                "com.larsons.engine.core.Main");

        Path tmp = jar.resolveSibling(jar.getFileName() + ".tmp");
        Set<String> seen = new HashSet<>();
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(tmp), manifest)) {
            for (Path root : roots) {
                try (Stream<Path> files = Files.walk(root)) {
                    for (Path f : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                        String name = root.relativize(f).toString().replace(File.separatorChar, '/');
                        if (name.isBlank() || name.startsWith("META-INF/")
                                || !seen.add(name)) {
                            continue; // first classpath entry wins, like a real classpath
                        }
                        out.putNextEntry(new JarEntry(name));
                        try (InputStream in = Files.newInputStream(f)) {
                            in.transferTo(out);
                        }
                        out.closeEntry();
                    }
                }
            }
        }
        Files.move(tmp, jar, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void writeScripts(Path outDir) throws IOException {
        Path bat = outDir.resolve("run.bat");
        if (!Files.exists(bat)) {
            Files.writeString(bat, "@echo off\r\n"
                    + "java -jar \"%~dp0" + JAR_NAME + "\"\r\n"
                    + "if errorlevel 1 pause\r\n");
        }
        Path sh = outDir.resolve("run.sh");
        if (!Files.exists(sh)) {
            Files.writeString(sh, "#!/bin/sh\n"
                    + "cd \"$(dirname \"$0\")\"\n"
                    + "exec java -jar " + JAR_NAME + "\n");
            try {
                Set<PosixFilePermission> perms =
                        new HashSet<>(Files.getPosixFilePermissions(sh));
                perms.add(PosixFilePermission.OWNER_EXECUTE);
                perms.add(PosixFilePermission.GROUP_EXECUTE);
                perms.add(PosixFilePermission.OTHERS_EXECUTE);
                Files.setPosixFilePermissions(sh, perms);
            } catch (UnsupportedOperationException ignored) {
                // non-POSIX filesystem (Windows) — the .bat covers it
            }
        }
    }

    private static void writeInstructions(Path outDir) throws IOException {
        String lan = Lan.siteLocalAddress();
        String lanLine = lan != null
                ? "   On this machine, that address is currently:  " + lan
                : "   (Find yours with `ipconfig` on Windows or `ifconfig` on Mac/Linux.)";
        Files.writeString(outDir.resolve("HOW_TO_PLAY_ONLINE.txt"), """
                LARSON'S 2D GAME ENGINE — PLAY WITH FRIENDS
                ===========================================

                RUNNING THE GAME
                  Needs Java 21+ (https://adoptium.net). Then either double-click
                  run.bat (Windows) / run.sh (Mac & Linux), or run:
                      java -jar %s

                HOSTING AN AUTO-BATTLER GAME
                  Launch menu -> Auto Battler -> set a port (default 7788) -> Host Game.
                  The lobby shows the address friends should join.

                JOINING
                  Launch menu -> Auto Battler -> type the host's address -> Join Game.

                WHICH ADDRESS DO I TYPE?
                  * Same machine as the host (testing): localhost:7788
                    "localhost" always means "this same computer" - it will NOT
                    reach a host on another machine.
                  * Same network (same house/wifi): the HOST's LAN IP, e.g.
                    192.168.1.23:7788. The host's lobby screen shows it.
                %s
                  * Over the internet: the host's public IP, with TCP port 7788
                    forwarded on their router (exactly like hosting Minecraft).

                The world game hosts the same way from Multiplayer (default port 7777).
                """.formatted(JAR_NAME, lanLine));
    }
}
