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
