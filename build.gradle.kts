import java.time.Instant

plugins {
    id("java")
    id("application")
}

group = "com.larsons"
version = "0.1.0"

repositories {
    mavenCentral()
}

// The engine itself depends only on the JDK (Java2D / AWT / Swing) so it runs
// out of the box on any machine with a JRE. We target the installed JDK rather
// than a toolchain to avoid any network provisioning step.
java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

// The OS this build is running on, for LWJGL's native artifacts. Test-only
// here — see the dependencies block — and the real runtime in `:gl`, which is
// why the detection moved out to a script both apply rather than staying a
// local `val` for one of them to copy.
apply(from = "gradle/lwjgl-natives.gradle.kts")
val lwjglNatives: String by extra
val lwjglVersion: String by extra

dependencies {
    // Test-only. Nothing here ships in the runtime classpath.
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Also test-only, and deliberately so. The engine ships with zero runtime
    // dependencies (requirement #4) and this must not change that — LWJGL is
    // here to *check* the shaders, by compiling every ShaderPass.glsl() through
    // a real driver. Until this existed the GLSL had never been compiled by
    // anything, and STEAM_PLAN's Appendix A was right to call the exported
    // .frag files never-compiled drafts.
    testImplementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    testImplementation("org.lwjgl:lwjgl")
    testImplementation("org.lwjgl:lwjgl-opengl")
    testImplementation("org.lwjgl:lwjgl-glfw")
    testRuntimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    testRuntimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    testRuntimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
}

// The core's test classes, published so `:gl` can drive the same golden
// catalogue rather than a copy of it.
//
// B8 compares `GlTarget` against `Java2DTarget` over B0's frames. Those frames
// live in `src/test/java` and are the *only* acceptable input to that
// comparison: a second catalogue in `:gl` would be a set of scenes that could
// drift from the ones B0, B3, B5 and B6 all published numbers against, and the
// first thing anyone would do with a failing parity number is wonder whether
// the two catalogues still agree. There is one catalogue, and both backends
// render it.
val coreTestClasses = configurations.consumable("coreTestClasses")

val testJar = tasks.register<Jar>("testJar") {
    description = "The core's test classes, for :gl's parity tests"
    archiveClassifier = "tests"
    from(sourceSets["test"].output)
}

artifacts.add(coreTestClasses.name, testJar)

application {
    // Boots a window, the game loop, and the bundled demo scenes.
    mainClass = "com.larsons.engine.core.Main"
}

// IntelliJ starts the Gradle daemon with -Didea.active=true, but the daemon
// forks a *fresh* JVM for `run` and that fork inherits none of the IDE's
// markers. Hand the signal down so ShareJar can tell an IDE launch from a
// player double-clicking a jar (see ShareJar.insideIntelliJ).
val launchedFromIdea = System.getProperty("idea.active") != null
        || System.getProperty("idea.version") != null

tasks.named<JavaExec>("run") {
    if (launchedFromIdea) systemProperty("idea.active", "true")
}

tasks.test {
    useJUnitPlatform()

    // Gradle forks a JVM for the tests, and that fork inherits none of the
    // -D flags on the gradle command line. The golden frames are regenerated
    // with one (-Dlarsons.golden.rewrite=true), so without this the flag is
    // silently ignored and the run looks like sixteen inexplicable failures.
    // Forwarded by prefix rather than by name so a new switch does not need a
    // build change to work.
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("larsons.")) systemProperty(name, value.toString())
    }
}

// Launch the game with the frame profiler armed but not yet running:
//   ./gradlew runProfiled
//
// The timer deliberately does not start at launch. Walk into the level you
// actually want measured, press F3 there, and the run times itself out and
// writes a report. Profiling the menu the game booted into would measure a
// scene that draws no world, which is the easiest way to get a confident
// wrong answer out of this.
//
// Tunable without editing this file:
//   ./gradlew runProfiled -Pprofile.seconds=60      # longer sample
//   ./gradlew runProfiled -Pprofile.hud=true        # watch it live instead
//   ./gradlew runProfiled -Pprofile.out=air-on.txt  # name the report
tasks.register<JavaExec>("runProfiled") {
    group = "application"
    description = "Run the game with the frame profiler armed (press F3 in a level)"
    mainClass = "com.larsons.engine.core.Main"
    classpath = sourceSets["main"].runtimeClasspath

    // Reports land in the project root, where the IDE's project view will show
    // them, rather than wherever a terminal happened to be.
    workingDir = projectDir

    val seconds = (findProperty("profile.seconds") as String?)?.takeIf { it.isNotBlank() } ?: "30"
    val hud = (findProperty("profile.hud") as String?)?.takeIf { it.isNotBlank() } ?: "false"
    val out = (findProperty("profile.out") as String?)?.takeIf { it.isNotBlank() }
            ?: "frame-profile.txt"

    systemProperty("larsons.profile.seconds", seconds)
    systemProperty("larsons.profile.overlay", hud)
    systemProperty("larsons.profile.out", out)
    if (launchedFromIdea) systemProperty("idea.active", "true")

    doFirst {
        println(
            """
            |
            |  Frame profiler armed — it is NOT recording yet.
            |
            |    1. Load a level and start playing (not the menu).
            |    2. Press F3 to start recording.
            |    3. Play normally for $seconds seconds; it stops on its own.
            |    4. Report is written to: $projectDir/$out
            |
            |  Press F3 again for another run. Reports never overwrite each
            |  other — the second lands beside the first as *-2.txt.
            |
            """.trimMargin()
        )
    }
}

// Headless dedicated multiplayer server (like running a Minecraft server jar):
//   gradle runServer --args="--port 7777 --level levels/sample_level.json --gametype platformer"
tasks.register<JavaExec>("runServer") {
    group = "application"
    description = "Run the headless dedicated game server"
    mainClass = "com.larsons.engine.net.ServerMain"
    classpath = sourceSets["main"].runtimeClasspath
}

// Stamp the build's commit into the jar, so a frame profile taken on another
// machine can be traced to the code that produced it. Two profiles once came
// back that were impossible to interpret without knowing whether the build
// predated the work they were meant to measure.
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.file("generated/build-info.properties")
    outputs.file(out)
    // Always re-run. With only an output declared, Gradle called this
    // UP-TO-DATE after the first build and never ran it again, so the stamp
    // froze — every report for hours claimed a commit that had long since been
    // superseded, and the reports were read as stale builds when only the
    // stamp was stale. A task whose whole purpose is to record "now" cannot be
    // allowed to skip itself.
    outputs.upToDateWhen { false }
    doLast {
        val commit = try {
            providers.exec {
                commandLine("git", "rev-parse", "--short", "HEAD")
            }.standardOutput.asText.get().trim()
        } catch (e: Exception) {
            "unknown"
        }
        val file = out.get().asFile
        file.parentFile.mkdirs()
        file.writeText("commit=$commit\nbuilt=${Instant.now()}\n")
    }
}

tasks.named<ProcessResources>("processResources") {
    from(buildInfo)
}

// Everything on the shipping runtime classpath that came from outside this
// build. Resolved as a provider so the answer is computed once and the task
// below stays configuration-cache clean.
val externalRuntimeArtifacts = configurations.named("runtimeClasspath").flatMap { runtime ->
    runtime.incoming.artifacts.resolvedArtifacts.map { artifacts ->
        artifacts.map { it.id.componentIdentifier.displayName }
                .filter { !it.startsWith("project ") }
                .sorted()
    }
}

/**
 * Invariant 1, as a build failure rather than a note.
 *
 * The core ships with zero runtime dependencies, and `:gl` exists so that can
 * stay true while a GL backend is written. What makes that hold is not the
 * module boundary on its own — it is that nothing can add `implementation
 * "org.lwjgl:..."` to *this* file and have the build still pass. `jar` depends
 * on this, so the shipping artefact cannot be produced without the claim being
 * checked; `check` depends on it too, so a broken boundary fails a plain
 * `./gradlew build` rather than waiting for someone to package a release.
 *
 * `ModuleBoundaryTest` is the other half — this one sees what the build
 * resolves, that one sees what the source imports, and neither sees the
 * other's blind spot.
 */
val verifyNoRuntimeDependencies = tasks.register("verifyNoRuntimeDependencies") {
    group = "verification"
    description = "Fails if the core's runtime classpath gained an external dependency"
    val external = externalRuntimeArtifacts
    doLast {
        val found = external.get()
        if (found.isNotEmpty()) {
            throw GradleException(
                    "the core is supposed to ship with zero runtime dependencies, "
                            + "and its runtime classpath now carries "
                            + found.size + ":\n  " + found.joinToString("\n  ")
                            + "\n\nGL code belongs in :gl. See invariant 1 in RENDER_PLAN.md.")
        }
    }
}

tasks.named("check") {
    dependsOn(verifyNoRuntimeDependencies)
}

// Make `java -jar build/libs/<name>.jar` work directly (no external deps).
//
// **This task is deliberately unchanged by B7.** The GL-enabled distribution is
// a separate artefact (`./gradlew :gl:glDist`); this one stays the single
// self-contained jar a player double-clicks, and the check above is what keeps
// that a fact rather than an intention.
tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.larsons.engine.core.Main"
    }
    dependsOn(verifyNoRuntimeDependencies)
}
