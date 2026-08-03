// The GL backend.
//
// This project exists so the core does not have to change to get one. It
// depends on the core; the core does not know it exists, cannot name it, and
// still builds and ships with no runtime dependencies at all — which is
// invariant 1, and which `:verifyNoRuntimeDependencies` and
// `ModuleBoundaryTest` between them make impossible to break quietly.
//
// Two artefacts come out of this repository as a result:
//
//   ./gradlew jar          → the plain engine, no dependencies, runs anywhere
//   ./gradlew :gl:glDist   → the same engine plus this backend and LWJGL
//
// and the first one is not built from the second with pieces removed. They are
// separate jars from separate projects, which is the only arrangement in which
// "the plain one has no dependencies" cannot become false by accident.

plugins {
    id("java")
}

// The same detection the root build uses, from the same file. See the note
// there for why it is not two copies of one `when`.
apply(from = rootProject.file("gradle/lwjgl-natives.gradle.kts"))
val lwjglNatives: String by extra
val lwjglVersion: String by extra

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    // The engine. One direction only.
    implementation(project(":"))

    // `implementation`, not `api`: nothing downstream of this project should be
    // compiling against LWJGL either. A caller wanting GL asks for a
    // `GlRenderer` and gets one; the GL calls stay behind it.
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-opengl")
    implementation("org.lwjgl:lwjgl-glfw")
    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")

    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // B0's golden catalogue, the one the Java2D numbers were published from.
    // See `coreTestClasses` in the root build for why this is shared rather
    // than reimplemented.
    testImplementation(project(path = ":", configuration = "coreTestClasses"))
}

tasks.test {
    useJUnitPlatform()

    // The golden catalogue reads `src/main/resources/levels/sample_level.json`
    // and writes scratch stores under `build/`, both by relative path, because
    // it was written for a single-project build and a golden that depends on an
    // absolute path depends on the username. Run it from where those paths mean
    // what they meant, or every scene frame here fails on a missing level while
    // the same code passes in core.
    workingDir = rootProject.projectDir

    // Same forwarding rule as the root build: a `-Dlarsons.*` on the command
    // line has to reach the forked JVM or it is silently ignored.
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("larsons.")) systemProperty(name, value.toString())
    }

    // Reports land beside the core's, in the root project, so
    // `build/reports/gl-parity.md` sits next to `build/reports/draw-calls.md`
    // and `build/reports/glyph-batching.md` and the three can be read together.
    systemProperty("larsons.reports.dir", rootProject.layout.buildDirectory.dir("reports").get().asFile.path)
}

// Same forwarding rule as the root build: a `-Dlarsons.*` on the command line
// has to reach the forked JVM or it is silently ignored.
tasks.withType<JavaExec>().configureEach {
    System.getProperties().forEach { key, value ->
        val name = key.toString()
        if (name.startsWith("larsons.")) systemProperty(name, value.toString())
    }
}

/**
 * Whether a run of this backend needs `-XstartOnFirstThread` on macOS.
 *
 * **Only the GL one, and getting this wrong breaks the other.** GLFW's window
 * and event calls belong to the process's first thread, and on macOS that is
 * enforced by the platform rather than by convention: the JVM's main thread is
 * not thread 0 unless it is told to be, and a GL window created off it does not
 * fail, it hangs. But AWT wants that same first thread for its own run loop, so
 * passing the flag to a Java2D run on a Mac hangs the `JFrame` instead. The
 * flag therefore follows the backend, not the OS — which is why it is a
 * function and not two lines copied into three tasks.
 */
fun needsFirstThread(backend: String): Boolean =
        backend != "java2d" && System.getProperty("os.name", "").lowercase().contains("mac")

// Run the game with this backend on the classpath and the frame profiler armed,
// mirroring the root's `runProfiled` so the two reports are directly
// comparable — same instrument, same flags, same report format, one variable.
//
//   ./gradlew :gl:runGl
//   ./gradlew :gl:runGl -Pprofile.seconds=60 -Pprofile.out=air-gl.txt
//
// `larsons.render.backend` is read by B9's backend selection, which has landed:
// this task now really does launch the GL backend, because the classpath it
// builds contains the `META-INF/services` entry the core's ServiceLoader scan
// finds. Override it to compare the two renderers on one machine:
//
//   ./gradlew :gl:runGl -Prender.backend=java2d
//
// which is the same jar, the same flags and the same report format with one
// variable changed — the arrangement B10's four runs need.
tasks.register<JavaExec>("runGl") {
    group = "application"
    description = "Run the game with the GL backend available and the profiler armed"
    mainClass = "com.larsons.engine.core.Main"
    classpath = sourceSets["main"].runtimeClasspath
    workingDir = rootProject.projectDir

    // -P if given, else the -D the game itself reads, else the default. See
    // `launchOption` in the root build for why both spellings have to be
    // consulted rather than one shadowing the other.
    fun option(gradleProperty: String, systemProperty: String, fallback: String): String =
            (findProperty(gradleProperty) as String?)?.takeIf { it.isNotBlank() }
                    ?: System.getProperty(systemProperty)?.takeIf { it.isNotBlank() }
                    ?: fallback

    val seconds = option("profile.seconds", "larsons.profile.seconds", "30")
    val hud = option("profile.hud", "larsons.profile.overlay", "false")
    val out = option("profile.out", "larsons.profile.out", "frame-profile-gl.txt")
    val backend = option("render.backend", "larsons.render.backend", "gl")

    systemProperty("larsons.profile.seconds", seconds)
    systemProperty("larsons.profile.overlay", hud)
    systemProperty("larsons.profile.out", out)
    systemProperty("larsons.render.backend", backend)

    if (needsFirstThread(backend)) jvmArgs("-XstartOnFirstThread")

    doFirst {
        println(
            """
            |
            |  Frame profiler armed — it is NOT recording yet.
            |
            |    1. Load a level and start playing (not the menu).
            |    2. Press F3 to start recording.
            |    3. Play normally for $seconds seconds; it stops on its own.
            |    4. Report is written to: ${rootProject.projectDir}/$out
            |
            |  Backend requested: $backend
            |
            """.trimMargin()
        )
    }
}

// B10's two runs, as two tasks that take no arguments.
//
//   ./gradlew :gl:profileGl        →  profile-gl.txt
//   ./gradlew :gl:profileJava2d    →  profile-java2d.txt
//
// or, in IntelliJ, the run configurations "Profile — GL backend" and
// "Profile — Java2D backend" from the dropdown. `runGl` above still exists and
// still takes -P overrides; these two exist because a measurement you have to
// remember four flags to take is a measurement that gets taken wrong once and
// then trusted.
//
// **Both run from this project on purpose.** The Java2D one could have gone in
// the root build, where it would have had no LWJGL on its classpath at all —
// and it would then have been a different program, so any difference between
// the two reports could be the backend or could be the classpath. One jar, one
// variable, two reports. That is the whole design of the comparison, and it is
// the reason the task lives in the module it does not use.
fun registerProfileRun(taskName: String, backend: String, report: String, what: String) =
        tasks.register<JavaExec>(taskName) {
            group = "verification"
            description = "Profile the game on the $backend backend (30 s, press F3 in a level)"
            mainClass = "com.larsons.engine.core.Main"
            classpath = sourceSets["main"].runtimeClasspath
            workingDir = rootProject.projectDir

            systemProperty("larsons.render.backend", backend)
            systemProperty("larsons.profile.seconds", "30")
            systemProperty("larsons.profile.overlay", "false")
            systemProperty("larsons.profile.out", report)

            if (needsFirstThread(backend)) jvmArgs("-XstartOnFirstThread")

            doFirst {
                println(
                    """
                    |
                    |  ┌──────────────────────────────────────────────────────────────┐
                    |  │  Profiling the $what
                    |  └──────────────────────────────────────────────────────────────┘
                    |
                    |  The profiler is armed but NOT recording yet. Recording the menu
                    |  would measure a scene that draws no world, which is the easiest
                    |  way to get a confident wrong answer out of this.
                    |
                    |    1. Load a level and play it — not the menu, not the editor.
                    |    2. Press F3 there. Recording starts.
                    |    3. Play normally for 30 seconds. It stops on its own.
                    |    4. Close the window.
                    |
                    |  Report:  ${rootProject.projectDir}/$report
                    |  Backend: $backend  (the report names it too, so the two
                    |           runs cannot be mixed up afterwards)
                    |
                    |  Run the OTHER profile the same way, in the same level, doing the
                    |  same things — then compare the two files. An existing report is
                    |  never overwritten; a second run lands beside it as -2.
                    |
                    """.trimMargin()
                )
            }
        }

registerProfileRun("profileGl", "gl", "profile-gl.txt",
        "GL backend — the GPU renderer")
registerProfileRun("profileJava2d", "java2d", "profile-java2d.txt",
        "Java2D backend — the CPU renderer this replaces")

// The GL-enabled distribution: the engine, this backend, LWJGL and its natives
// for this machine, in one runnable jar.
//
//   ./gradlew :gl:glDist && java -jar gl/build/libs/larsons-engine-gl.jar
//
// Native-classified for the host, because that is what LWJGL needs on the
// classpath to unpack at startup and there is no such thing as a portable
// build of it. A release for three platforms is this task run three times, not
// one jar that works everywhere.
tasks.register<Jar>("glDist") {
    group = "distribution"
    description = "Runnable jar with the engine, the GL backend and LWJGL for this OS"
    archiveBaseName = "larsons-engine-gl"
    archiveVersion = ""

    manifest {
        attributes["Main-Class"] = "com.larsons.engine.core.Main"
    }

    from(sourceSets["main"].output)
    from(configurations.runtimeClasspath.map { classpath ->
        classpath.map { if (it.isDirectory) it else zipTree(it) }
    })

    // Signatures do not survive being repacked, and a module descriptor from a
    // dependency would claim to describe this jar.
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
