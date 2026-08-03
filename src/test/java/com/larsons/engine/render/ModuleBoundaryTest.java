package com.larsons.engine.render;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The core ships with zero runtime dependencies, and {@code :gl} is why that
 * can stay true while a GL backend exists.
 *
 * <p><b>Why a test as well as a build check.</b> {@code :verifyNoRuntimeDependencies}
 * resolves the runtime classpath and fails the build if anything external is on
 * it, which is the strongest possible statement about what <em>ships</em>. It
 * says nothing about what the source <em>imports</em>: a core class naming
 * {@code org.lwjgl} would not compile, but a core class naming
 * {@code com.larsons.engine.gl} would fail at a much less obvious point, and a
 * dependency added under the wrong configuration in a subproject would not fail
 * either check on its own. This one reads the files. Between them the boundary
 * is checked from both sides, which is the same arrangement
 * {@link SealedSeamTest} and {@code SceneFramesTest} use on the Java2D seam and
 * for the same reason.
 *
 * <p><b>The direction is the whole invariant.</b> {@code :gl} depends on the
 * core. The core does not know {@code :gl} exists. A player with a bare JRE and
 * no GL module gets a working game, and a driver bug in a backend that is not
 * on the classpath cannot affect them.
 */
class ModuleBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final Path ROOT_BUILD = Path.of("build.gradle.kts");
    private static final Path SETTINGS = Path.of("settings.gradle.kts");
    private static final Path GL_BUILD = Path.of("gl/build.gradle.kts");

    /**
     * Dependency configurations that put a jar on the shipping runtime
     * classpath. Anything declared under one of these in the root build breaks
     * invariant 1.
     */
    private static final List<String> RUNTIME_SCOPES = List.of(
            "implementation", "api", "runtimeOnly", "compileOnly",
            "compileOnlyApi", "annotationProcessor");

    @Test
    void theCoreBuildDeclaresNothingOutsideTestScope() throws IOException {
        List<String> declared = runtimeDeclarations(Files.readString(ROOT_BUILD));
        assertEquals(List.of(), declared,
                "the core is supposed to ship with zero runtime dependencies, and "
                        + "build.gradle.kts now declares:\n  "
                        + String.join("\n  ", declared)
                        + "\n\nGL code belongs in :gl. See invariant 1 in RENDER_PLAN.md.");
    }

    /**
     * The negative control. A scanner that cannot fail is a comment with a
     * test framework attached.
     */
    @Test
    void theScannerRejectsADependencyThatWouldShip() {
        assertFalse(runtimeDeclarations("""
                dependencies {
                    implementation("org.lwjgl:lwjgl")
                    testImplementation("org.junit.jupiter:junit-jupiter")
                }
                """).isEmpty(), "a runtime LWJGL dependency was let through");
        assertTrue(runtimeDeclarations("""
                dependencies {
                    testImplementation("org.lwjgl:lwjgl")
                    testRuntimeOnly("org.lwjgl:lwjgl::natives-linux")
                }
                """).isEmpty(), "test-scoped dependencies are not a violation");
    }

    @Test
    void noCoreSourceImportsTheGlBindings() throws IOException {
        assertNoSourceMentions("org.lwjgl",
                "LWJGL is test-only in the core and belongs to :gl at runtime");
    }

    /**
     * And nothing in the core names the GL module, which is the other
     * direction and the one a compiler would not catch until far too late —
     * a core class importing {@code com.larsons.engine.gl} does not fail to
     * compile, it fails to <em>exist</em> for a player who has the plain jar.
     */
    @Test
    void noCoreSourceNamesTheGlModule() throws IOException {
        assertNoSourceMentions("com.larsons.engine.gl",
                "the core must not know the GL backend exists; B9 selects it "
                        + "from outside, not from within");
    }

    @Test
    void theGlModuleIsInTheBuildAndPointsAtTheCore() throws IOException {
        assertTrue(Files.readString(SETTINGS).contains("include(\"gl\")"),
                "settings.gradle.kts no longer includes the gl project");
        assertTrue(Files.exists(GL_BUILD), "gl/build.gradle.kts is missing");
        String gl = Files.readString(GL_BUILD);
        assertTrue(gl.contains("implementation(project(\":\"))"),
                ":gl must depend on the core project — the dependency runs one way, "
                        + "and this is the end that declares it");
        assertTrue(gl.contains("org.lwjgl:lwjgl-opengl"),
                ":gl is where LWJGL lives at runtime; it is not there any more");
    }

    /**
     * Both artefacts are declared, and the plain one is still the default jar
     * rather than something derived from the GL distribution.
     */
    @Test
    void twoDistinctArtefactsAreDeclared() throws IOException {
        assertTrue(Files.readString(ROOT_BUILD).contains("tasks.jar {"),
                "the plain self-contained jar is no longer built by the root project");
        assertTrue(Files.readString(GL_BUILD).contains("\"glDist\""),
                "the GL distribution task is gone; there is no way to ship the backend");
    }

    // --- implementation --------------------------------------------------------

    /** Every runtime-scoped dependency declaration in a build script. */
    private static List<String> runtimeDeclarations(String script) {
        List<String> found = new ArrayList<>();
        for (String raw : script.split("\n", -1)) {
            String line = raw.strip();
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) continue;
            for (String scope : RUNTIME_SCOPES) {
                // The configuration name, called as a function, at the start of
                // a statement — so `testImplementation(...)` does not match
                // `implementation` as a substring of itself.
                if (line.startsWith(scope + "(")) {
                    found.add(line);
                    break;
                }
            }
        }
        return found;
    }

    private static void assertNoSourceMentions(String token, String why) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (source.contains(token)) {
                    offenders.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertEquals(List.of(), offenders,
                "%s — found in:\n  %s".formatted(why, String.join("\n  ", offenders)));
    }
}
