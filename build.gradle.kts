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

dependencies {
    // Test-only. Nothing here ships in the runtime classpath.
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

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

// Make `java -jar build/libs/<name>.jar` work directly (no external deps).
tasks.jar {
    manifest {
        attributes["Main-Class"] = "com.larsons.engine.core.Main"
    }
}
