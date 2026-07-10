package com.larsons.engine;

import com.larsons.engine.core.ShareJar;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.jar.JarFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The auto-built shareable launcher: running from class directories (exactly
 * how the IDE/Gradle launches the game) packages a runnable jar plus launch
 * scripts and online-play instructions, and skips the rebuild when nothing
 * changed.
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

        // A second write with nothing changed leaves the jar untouched.
        FileTime before = Files.getLastModifiedTime(jar);
        ShareJar.write(tmp);
        assertEquals(before, Files.getLastModifiedTime(jar), "no needless rebuild");
    }
}
