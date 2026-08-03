// The OS this build is running on, as LWJGL's native artifacts name it.
//
// Applied by both projects and written down once, because they disagree about
// everything else: the root build wants these natives on the *test* runtime
// only (the engine ships with no runtime dependencies — invariant 1), while
// `:gl` wants them on its real runtime. What neither may have is its own idea
// of which classifier this machine needs. Two copies of this `when` would sit
// side by side looking identical right up until one of them learned about a new
// platform, and the failure that produces — a jar that runs everywhere except
// the one machine whose natives were resolved by the stale copy — is silent
// until somebody launches it.

extra["lwjglNatives"] = when {
    org.gradle.internal.os.OperatingSystem.current().isMacOsX ->
        if (System.getProperty("os.arch") == "aarch64") "natives-macos-arm64" else "natives-macos"
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "natives-windows"
    else -> "natives-linux"
}

// The LWJGL version both projects resolve against, for the same reason.
extra["lwjglVersion"] = "3.3.3"
