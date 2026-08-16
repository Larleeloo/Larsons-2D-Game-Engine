package com.larsons.engine;

import com.larsons.engine.core.ShareJar;
import com.larsons.engine.graphics.TexturePack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auto-built shareable launcher: running from class directories (exactly
 * how the IDE launches the game) packages a runnable jar plus launch scripts,
 * online-play instructions and an empty texture pack, skips the rebuild when
 * nothing changed — and only happens at all inside IntelliJ, so a shipped
 * game never writes a copy of itself beside wherever the player put it.
 */
@Timeout(60)
class ShareJarTest {

    @Test
    void buildsARunnableJarWithScriptsAndInstructions(@TempDir Path tmp) throws Exception {
        Path jar = ShareJar.write(tmp);

        assertTrue(Files.exists(jar), "jar was built");
        assertEquals(ShareJar.JAR_NAME, jar.getFileName().toString());
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertEquals("com.larsons.engine.core.Main",
                    jf.getManifest().getMainAttributes().getValue("Main-Class"),
                    "java -jar launches the game");
            assertNotNull(jf.getEntry("com/larsons/engine/core/Main.class"),
                    "the engine's classes are inside");
        }

        assertTrue(Files.exists(tmp.resolve("run.bat")), "windows launcher");
        assertTrue(Files.exists(tmp.resolve("run.sh")), "mac/linux launcher");
        Path howTo = tmp.resolve("HOW_TO_PLAY_ONLINE.txt");
        assertTrue(Files.exists(howTo), "online-play instructions");
        String text = Files.readString(howTo);
        assertTrue(text.contains("localhost"), "explains what localhost means");
        assertTrue(text.contains("7788"), "names the auto-battler port");
        assertTrue(text.contains(TexturePack.DIR_NAME), "points at the texture pack folder");

        // A second write with nothing changed leaves the jar untouched.
        FileTime before = Files.getLastModifiedTime(jar);
        ShareJar.write(tmp);
        assertEquals(before, Files.getLastModifiedTime(jar), "no needless rebuild");
    }

    @Test
    void leavesAnEmptyTexturePackBesideTheJar(@TempDir Path tmp) throws Exception {
        ShareJar.write(tmp);

        Path pack = tmp.resolve(TexturePack.DIR_NAME);
        assertTrue(Files.isDirectory(pack), "the pack sits next to the jar");
        assertTrue(Files.isDirectory(pack.resolve("blocks")), "a folder per palette category");
        assertTrue(Files.isDirectory(pack.resolve("mobs")));
        assertTrue(Files.exists(pack.resolve(TexturePack.KEYS_FILE)), "the key list");
        assertTrue(Files.exists(pack.resolve(TexturePack.CONFIG_FILE)), "the universal spec");
    }

    @Test
    void theShareFolderIsAnIntelliJOnlyConvenience() {
        // A plain `java -jar` run on a player's machine: none of the signals.
        Properties plain = new Properties();
        plain.setProperty("java.class.path", "larsons-game-engine.jar");
        plain.setProperty("sun.java.command", "com.larsons.engine.core.Main");
        assertFalse(ShareJar.launchedByIntelliJ(plain, List.of(), Map.of()),
                "a shipped game builds nothing");

        // IntelliJ gives itself away several ways, depending on the run config.
        Properties ideaProps = new Properties();
        ideaProps.setProperty("idea.paths.selector", "IntelliJIdea2024.1");
        assertTrue(ShareJar.launchedByIntelliJ(ideaProps, List.of(), Map.of()),
                "its own system properties");
        Properties viaGradle = new Properties();
        viaGradle.setProperty("idea.active", "true");
        assertTrue(ShareJar.launchedByIntelliJ(viaGradle, List.of(), Map.of()),
                "the flag the build script hands down through a Gradle fork");
        Properties onClasspath = new Properties();
        onClasspath.setProperty("java.class.path",
                "out/production/classes:/opt/idea/lib/idea_rt.jar");
        assertTrue(ShareJar.launchedByIntelliJ(onClasspath, List.of(), Map.of()),
                "its helper jar on the classpath");
        assertTrue(ShareJar.launchedByIntelliJ(plain,
                        List.of("-javaagent:/opt/idea/lib/idea_rt.jar=41337"), Map.of()),
                "its helper attached as an agent");
        assertTrue(ShareJar.launchedByIntelliJ(plain, List.of(),
                        Map.of("IDEA_INITIAL_DIRECTORY", "/home/dev/project")),
                "the environment it exports");
        assertTrue(ShareJar.launchedByIntelliJ(plain, List.of(),
                        Map.of("TERMINAL_EMULATOR", "JetBrains-JediTerm")),
                "its built-in terminal");
    }

    /**
     * IntelliJ's default for a Gradle project forks the game into a JVM that
     * carries none of the IDE's markers, so the project checkout itself is
     * the signal that survives — without ever matching a player's install.
     */
    @Test
    void aGradleForkStillCountsAsRunningFromTheProject(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve(".idea"));
        assertTrue(ShareJar.ideaProjectCheckout(tmp, false),
                "an IntelliJ project run from its class files");
        assertFalse(ShareJar.ideaProjectCheckout(tmp, true),
                "…but a jar sitting in that folder is a build artifact, not the IDE");

        Path elsewhere = tmp.resolve("player-install");
        Files.createDirectories(elsewhere);
        assertFalse(ShareJar.ideaProjectCheckout(elsewhere, true),
                "a player's folder: a jar and no project");
        assertFalse(ShareJar.ideaProjectCheckout(elsewhere, false),
                "no .idea/ means no IntelliJ project either way");
    }

    @Test
    void theForcePropertyOverridesTheCheckEitherWay() {
        String previous = System.getProperty(ShareJar.FORCE_PROPERTY);
        try {
            System.setProperty(ShareJar.FORCE_PROPERTY, "false");
            assertFalse(ShareJar.insideIntelliJ(), "forced off even inside the IDE");
            System.setProperty(ShareJar.FORCE_PROPERTY, "true");
            assertTrue(ShareJar.insideIntelliJ(), "forced on even outside it");
        } finally {
            if (previous == null) System.clearProperty(ShareJar.FORCE_PROPERTY);
            else System.setProperty(ShareJar.FORCE_PROPERTY, previous);
        }
    }
}
