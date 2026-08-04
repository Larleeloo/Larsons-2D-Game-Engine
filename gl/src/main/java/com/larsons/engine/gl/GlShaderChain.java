package com.larsons.engine.gl;

import com.larsons.engine.graphics.shader.LightingPass;
import com.larsons.engine.graphics.shader.ShaderChain;
import com.larsons.engine.graphics.shader.ShaderPass;
import com.larsons.engine.graphics.shader.Shaders;
import com.larsons.engine.profile.FrameProfiler;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The engine's {@link ShaderChain}, executed on the GPU — Job A's ping-pong.
 *
 * <p><b>What this replaces.</b> Every pass in the chain ships a complete GLSL
 * fragment shader next to its CPU twin, and until now nothing in a shipping
 * build ever compiled one: the Java2D backend ran the {@code ParallelRows}
 * side, and the GL backend ran neither, printed a line on stderr and presented
 * a frame with no lighting in it. This runs the GLSL. Each pass is one
 * fullscreen triangle reading the previous pass's texture and writing the next,
 * with no transfer in either direction, because A1 already left the scene in a
 * GPU texture.
 *
 * <p><b>The ping-pong is two textures, and the source is neither of them.</b>
 * The scene's resolved texture belongs to {@link GlSurface} and is read by the
 * first pass; from there passes alternate between this class's own pair. A pass
 * that read and wrote one texture would be sampling a texture bound to the
 * framebuffer it is drawing into, which is undefined behaviour that renders
 * plausibly on some drivers and produces feedback smears on others.
 *
 * <p><b>{@code uResolution} is the frame's <em>logical</em> size, not the
 * texture's.</b> That looks wrong for about a minute and is the only choice
 * that keeps the two backends honest. Every pass that uses the uniform uses it
 * as "the pixel space this frame's coordinates are measured in":
 * {@code pixelate} divides a block size by it, {@code bloom} converts a radius
 * in pixels into a UV offset with it, {@code scanlines} counts rows with it,
 * and {@code lighting} multiplies by it to recover the screen position of a
 * light the scene projected through the camera. The Java2D backend composes at
 * logical size, so all of those are logical-pixel quantities on the CPU side.
 * Handing the device size here would, at 2× HiDPI, halve the thickness of a
 * scanline, quarter the area of a pixelate block, halve the apparent radius of
 * bloom, and put every light at half its correct position — four different
 * effects, each subtly wrong, none of them obviously a units bug. The viewport
 * stays at device size, because that is the resolution the pixels are actually
 * shaded at; only the unit the passes measure in is logical.
 *
 * <p><b>Per-pass cost is measured on the GPU, not on this thread.</b> GL calls
 * return long before the driver has done the work, so timing
 * {@code glDrawArrays} with {@code System.nanoTime} would report a submission
 * cost of a few microseconds and invite exactly the conclusion §5.0 of the
 * plan had to correct once already — that the shader stage is free because the
 * number next to it is small. Each pass is therefore wrapped in a
 * {@code GL_TIME_ELAPSED} query and the results are collected one frame later,
 * when they are ready and reading them stalls nothing. The profiler's window is
 * 600 frames; a one-frame lag inside it is not a measurement error.
 */
final class GlShaderChain implements AutoCloseable {

    /**
     * {@code -Dlarsons.render.gl.maxlights=N} — force the light budget down.
     *
     * <p>Same trick as B9's {@code larsons.render.gl.version}: the clamp below
     * only engages on a driver too small to hold {@link LightingPass#MAX_LIGHTS},
     * which is a driver that would not pass the GL 3.3 minimums, so on every
     * machine this is likely to run on the clamp is dead code. Dead code that
     * a player's driver might one day be the first to execute is worse than no
     * code. This makes it reachable on a machine that works.
     */
    static final String MAX_LIGHTS_PROPERTY = "larsons.render.gl.maxlights";

    /**
     * Fragment uniform components left for everything that is not a light
     * array — the standard four uniforms, the pass's own scalars, and whatever
     * the driver charges for varyings it accounts here. Generous on purpose:
     * the cost of over-reserving is a light or two, the cost of
     * under-reserving is a link failure in front of a player.
     */
    private static final int UNIFORM_RESERVE = 64;

    /** Components a driver may charge for one {@code vec3} of a uniform array. */
    private static final int VEC3_COMPONENTS = 4;

    private final int[] fbo = {0, 0};
    private final int[] texture = {0, 0};
    private int width;
    private int height;

    /** Compiled programs, one per pass, kept for the life of the chain. */
    private final Map<ShaderPass, Program> programs = new IdentityHashMap<>();

    /** Passes whose shader would not build. Reported once, then skipped. */
    private final Map<ShaderPass, String> broken = new IdentityHashMap<>();

    /** The shared fullscreen vertex shader, compiled once and linked into each. */
    private int vertexShader;

    /** This chain's own vertex array — see {@link #run}. */
    private int vao;

    /** Lights this driver can afford, computed once against its real limits. */
    private int lightBudget = -1;

    /** The framebuffer holding the finished frame, valid after a {@link #run}. */
    private int resultFbo;

    /** Scratch for uniform uploads, grown on demand and never per frame. */
    private FloatBuffer uploads = MemoryUtil.memAllocFloat(64);

    /** GPU timer queries: this frame's are issued, last frame's are read. */
    private final Timers timers = new Timers();

    /**
     * A linked program and everything the driver will tell us about its
     * uniforms, asked once.
     *
     * <p><b>The types are not bookkeeping; a pass got them wrong.</b>
     * {@link ShaderPass#uniforms()} is a map of {@code Float}, and
     * {@link LightingPass} advertises {@code uLightCount} through it — but the
     * shader declares that one {@code int}. {@code glUniform1f} against an
     * {@code int} uniform is {@code GL_INVALID_OPERATION}: the driver refuses
     * the call, raises an error nobody is reading, and leaves the uniform at
     * zero. The result is a lighting pass that runs, costs what lighting costs,
     * darkens the frame correctly and lights nothing, which is precisely the
     * failure §5.1 describes a player hitting. So the value is bound as the
     * type the shader declared, not as the type the map happens to hold.
     *
     * <p>{@code glGetUniformLocation} is also a string lookup in the driver and
     * the answer cannot change for the life of a program. The harness this was
     * lifted from called it per uniform per invocation, which is fine for a
     * test that runs a pass once and wasteful for one running seven of them at
     * 120 FPS.
     */
    private static final class Program {
        final int id;
        final String source;
        private final Map<String, Integer> locations = new HashMap<>();
        private final Map<String, Integer> types = new HashMap<>();

        Program(int id, String source) {
            this.id = id;
            this.source = source;
            int count = glGetProgrami(id, GL_ACTIVE_UNIFORMS);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer size = stack.mallocInt(1);
                IntBuffer type = stack.mallocInt(1);
                for (int i = 0; i < count; i++) {
                    String name = glGetActiveUniform(id, i, size, type);
                    // An array comes back as "uLightPos[0]"; callers name it
                    // "uLightPos", and so does glGetUniformLocation.
                    int bracket = name.indexOf('[');
                    if (bracket > 0) name = name.substring(0, bracket);
                    locations.put(name, glGetUniformLocation(id, name));
                    types.put(name, type.get(0));
                }
            }
        }

        /** Where a uniform lives, or {@code -1} if the program has no such active uniform. */
        int location(String name) {
            Integer known = locations.get(name);
            return known == null ? -1 : known;
        }

        /** Whether the shader declared this uniform as an integer sort of thing. */
        boolean isInteger(String name) {
            Integer type = types.get(name);
            if (type == null) return false;
            return type == GL_INT || type == GL_UNSIGNED_INT || type == GL_BOOL;
        }
    }

    // --- the frame -------------------------------------------------------------

    /**
     * Run every pass over {@code sourceTexture} and leave the result in
     * {@link #resultFramebuffer()}.
     *
     * @param sourceTexture the scene, resolved to a single-sample texture
     * @param deviceWidth   the drawable in device pixels — the shading resolution
     * @param logicalWidth  the frame in logical pixels — the unit passes measure in
     * @param profiler      may be null, and may be disabled; either way this costs
     *                      nothing when it is not recording
     * @return whether anything ran, i.e. whether the result is this chain's
     *         framebuffer rather than the source the caller passed in
     */
    boolean run(int sourceTexture, int deviceWidth, int deviceHeight,
                int logicalWidth, int logicalHeight,
                ShaderChain chain, FrameProfiler profiler) {
        List<ShaderPass> passes = chain.passes();
        if (passes.isEmpty() || sourceTexture == 0) return false;

        ensureSize(deviceWidth, deviceHeight);
        boolean timed = profiler != null && profiler.isEnabled();
        if (timed) timers.collect(profiler);
        else timers.abandon();   // profiling was switched off; nobody is coming for these

        // A core-profile draw needs a bound vertex array even when the vertex
        // shader invents its own positions — and it must not be the one
        // GlBatch has left its attribute pointers on, because those arrays stay
        // enabled and a driver is entitled to fetch from them for a shader that
        // declares no attributes at all. Hence a private, empty one, and the
        // caller's restored on the way out: this runs in the middle of a frame
        // that has already drawn.
        int callersVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
        glBindVertexArray(vao);
        glDisable(GL_BLEND);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_STENCIL_TEST);
        glDisable(GL_SCISSOR_TEST);
        glViewport(0, 0, width, height);
        glActiveTexture(GL_TEXTURE0);

        float time = chain.time();
        float strength = chain.getStrength();
        int source = sourceTexture;
        int slot = 0;
        for (ShaderPass pass : passes) {
            Program program = programOf(pass);
            if (program == null) continue;   // would not build; already reported
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[slot]);
            glUseProgram(program.id);
            glBindTexture(GL_TEXTURE_2D, source);
            bindUniforms(program, pass, logicalWidth, logicalHeight, strength, time);
            if (timed) timers.begin(pass.name());
            glDrawArrays(GL_TRIANGLES, 0, 3);   // the fullscreen triangle
            if (timed) timers.end();
            resultFbo = fbo[slot];
            source = texture[slot];
            slot ^= 1;
        }

        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glBindVertexArray(callersVao);
        return source != sourceTexture;
    }

    /** The framebuffer the last {@link #run} finished in. */
    int resultFramebuffer() { return resultFbo; }

    /**
     * Copy the finished frame onto the window.
     *
     * <p>A blit rather than a textured quad, for the reason
     * {@link GlSurface#blitToWindow} gives: no shader, no vertex buffer and no
     * state of its own, at the point in the frame where disturbing state is
     * most expensive to diagnose.
     */
    void blitToWindow(int deviceWidth, int deviceHeight) {
        if (resultFbo == 0) return;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, resultFbo);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glDisable(GL_SCISSOR_TEST);
        glBlitFramebuffer(0, 0, width, height, 0, 0, deviceWidth, deviceHeight,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }

    /**
     * The shaded frame as {@code 0xAARRGGBB}, top row first — the same
     * convention as {@link GlSurface#readPixels()}, and flipped for the same
     * reason: GL numbers rows from the bottom and every raster in this engine
     * starts at the top.
     */
    int[] readPixels() {
        if (resultFbo == 0) return new int[0];
        glBindFramebuffer(GL_READ_FRAMEBUFFER, resultFbo);
        glPixelStorei(GL_PACK_ALIGNMENT, 4);
        IntBuffer buffer = MemoryUtil.memAllocInt(width * height);
        try {
            glReadPixels(0, 0, width, height,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, buffer);
            int[] out = new int[width * height];
            for (int row = 0; row < height; row++) {
                buffer.position((height - 1 - row) * width);
                buffer.get(out, row * width, width);
            }
            return out;
        } finally {
            MemoryUtil.memFree(buffer);
            glBindFramebuffer(GL_FRAMEBUFFER, 0);
        }
    }

    /** Lights this driver was found able to hold, for a report or a test. */
    int lightBudget() {
        if (lightBudget < 0) lightBudget = computeLightBudget();
        return lightBudget;
    }

    /**
     * The GLSL a pass was actually compiled from, or null if it has not run.
     * For the test that checks the light clamp reached the shader rather than
     * only the uniform — the two are separate mistakes and only one of them
     * shows up as a wrong picture.
     */
    String compiledSource(ShaderPass pass) {
        Program program = programs.get(pass);
        return program == null ? null : program.source;
    }

    // --- uniforms (A3) ---------------------------------------------------------

    /**
     * The uniform contract from {@link ShaderPass}, bound in one place.
     *
     * <p>Lifted from {@code GlShaderHarness.bindUniforms}, which is the version
     * {@code ShaderParityTest} measured every number in §2 of the plan
     * through — so a pass that matched its CPU twin in that test matches it
     * here for the same reasons, and a difference is this backend's fault
     * rather than the shader's.
     */
    private void bindUniforms(Program program, ShaderPass pass,
                              int width, int height, float strength, float time) {
        setInt(program, "uTexture", 0);
        int resolution = program.location("uResolution");
        if (resolution >= 0) glUniform2f(resolution, width, height);
        setFloat(program, "uTime", time);
        setFloat(program, "uStrength", strength);
        for (Map.Entry<String, Float> extra : pass.uniforms().entrySet()) {
            setFloat(program, extra.getKey(), extra.getValue());
        }
        for (Map.Entry<String, ShaderPass.Vector> extra : pass.vectorUniforms().entrySet()) {
            setVector(program, extra.getKey(), extra.getValue());
        }
        // A4's clamp, and the one place this class knows a pass by name. The
        // budget below caps how many array elements were uploaded; the loop
        // inside the lighting shader is bounded by uLightCount, so leaving that
        // at the scene's figure would read past the end of an array the driver
        // could not afford to declare at full size. Everything else about the
        // pass goes through the contract above.
        if (pass instanceof LightingPass lighting
                && lighting.lightCount() > lightBudget()) {
            setFloat(program, "uLightCount", lightBudget());
        }
    }

    private void setInt(Program program, String name, int value) {
        int location = program.location(name);
        if (location >= 0) glUniform1i(location, value);
    }

    /**
     * Bind a scalar as the type its shader declared — see {@link Program}. The
     * contract carries {@code Float} because a uniform map has to carry
     * something; the shader is what says whether that is a {@code float}.
     */
    private void setFloat(Program program, String name, float value) {
        int location = program.location(name);
        if (location < 0) return;
        if (program.isInteger(name)) glUniform1i(location, Math.round(value));
        else glUniform1f(location, value);
    }

    /**
     * Bind a vector or an array of them, capped at what this driver can hold.
     *
     * <p>The cap applies to arrays only: a lone {@code vec3} costs four
     * components and no driver is short of those. An array longer than the
     * budget is uploaded truncated rather than rejected, because the shader it
     * is going into was compiled at the same budget — see {@link #sourceOf}.
     */
    private void setVector(Program program, String name, ShaderPass.Vector vector) {
        int location = program.location(name);
        if (location < 0) return;
        int elements = vector.elements();
        if (elements > 1) elements = Math.min(elements, lightBudget());
        if (elements <= 0) return;
        int floats = elements * vector.components();
        if (uploads.capacity() < floats) {
            uploads = MemoryUtil.memRealloc(uploads, Math.max(floats, uploads.capacity() * 2));
        }
        uploads.clear();
        uploads.put(vector.values(), 0, floats).flip();
        switch (vector.components()) {
            case 1 -> glUniform1fv(location, uploads);
            case 2 -> glUniform2fv(location, uploads);
            case 3 -> glUniform3fv(location, uploads);
            default -> glUniform4fv(location, uploads);
        }
    }

    // --- programs --------------------------------------------------------------

    /** The compiled program for a pass, built on first sight and kept. */
    private Program programOf(ShaderPass pass) {
        Program known = programs.get(pass);
        if (known != null) return known;
        if (broken.containsKey(pass)) return null;
        try {
            Program built = build(pass);
            programs.put(pass, built);
            return built;
        } catch (RuntimeException e) {
            // A shader that will not build must not take the frame with it, and
            // must not say so sixty times a second either. ShaderCompileTest
            // holds every shipped pass to building on a real driver, so this is
            // a custom pass or a driver nobody has met.
            broken.put(pass, e.getMessage());
            System.err.println("[gl] the '" + pass.name() + "' pass will not build on this "
                    + "driver and is being skipped: " + e.getMessage());
            return null;
        }
    }

    /**
     * The GLSL to compile for a pass.
     *
     * <p>Identical to {@link ShaderPass#glsl()} for every pass and every
     * driver that meets the GL 3.3 minimums. The one exception is the lighting
     * pass on a driver too small for {@link LightingPass#MAX_LIGHTS}, which
     * gets its arrays declared at the size that fits — the alternative being a
     * link failure at the exact moment a player turns lighting on.
     */
    private String sourceOf(ShaderPass pass) {
        if (pass instanceof LightingPass lighting && lightBudget() < LightingPass.MAX_LIGHTS) {
            return lighting.glsl(lightBudget());
        }
        return pass.glsl();
    }

    private Program build(ShaderPass pass) {
        if (vertexShader == 0) {
            vertexShader = compile(GL_VERTEX_SHADER, Shaders.VERTEX_GLSL, "the fullscreen vertex");
        }
        String source = sourceOf(pass);
        int fs = compile(GL_FRAGMENT_SHADER, source, pass.name());
        int program = glCreateProgram();
        try {
            glAttachShader(program, vertexShader);
            glAttachShader(program, fs);
            glLinkProgram(program);
            if (glGetProgrami(program, GL_LINK_STATUS) != GL_TRUE) {
                String log = glGetProgramInfoLog(program);
                glDeleteProgram(program);
                throw new IllegalStateException(pass.name() + " failed to link: " + log);
            }
        } finally {
            // Detached so the shader object dies with this program rather than
            // with the chain: the vertex shader is shared by every pass and
            // must outlive each of them.
            glDetachShader(program, vertexShader);
            glDetachShader(program, fs);
            glDeleteShader(fs);
        }
        return new Program(program, source);
    }

    private static int compile(int type, String source, String what) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) != GL_TRUE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException(what + " shader failed to compile: " + log);
        }
        return shader;
    }

    /**
     * How many lights the arrays may hold on this driver.
     *
     * <p>Two {@code vec3} arrays per light, budgeted at four components each
     * because a driver is allowed to pad a {@code vec3} to a full vector and
     * several do. GL 3.3 guarantees 1,024 fragment uniform components, so a
     * conforming driver arrives at {@link LightingPass#MAX_LIGHTS} and the
     * clamp never engages.
     */
    private int computeLightBudget() {
        int components = glGetInteger(GL_MAX_FRAGMENT_UNIFORM_COMPONENTS);
        int affordable = (components - UNIFORM_RESERVE) / (2 * VEC3_COMPONENTS);
        int budget = Math.min(LightingPass.MAX_LIGHTS, affordable);
        int forced = Integer.getInteger(MAX_LIGHTS_PROPERTY, 0);
        if (forced > 0) budget = Math.min(budget, forced);
        return Math.max(1, budget);
    }

    // --- the ping-pong buffers -------------------------------------------------

    /**
     * Allocate, or reallocate for a new drawable size.
     *
     * <p><b>A stale-sized ping-pong is the bug this method exists to prevent</b>,
     * and it is the one A2 called out by name: the passes would keep rendering
     * into buffers from before the resize, the blit would stretch them to the
     * window, and the frame would look subtly soft rather than broken.
     *
     * <p>{@code GL_NEAREST} and {@code GL_CLAMP_TO_EDGE}, matching both
     * {@link GlSurface}'s resolve texture and the harness every parity number
     * in the plan was measured through. The CPU passes index an {@code int[]}
     * directly and clamp at the edges; a linear filter here would put a
     * half-texel blur between the two implementations of every geometric pass
     * and there would be no way to tell that from a wrong formula.
     */
    private void ensureSize(int deviceWidth, int deviceHeight) {
        int w = Math.max(1, deviceWidth), h = Math.max(1, deviceHeight);
        if (w == width && h == height && fbo[0] != 0) return;
        releaseBuffers();
        width = w;
        height = h;
        if (vao == 0) vao = glGenVertexArrays();
        for (int i = 0; i < 2; i++) {
            texture[i] = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, texture[i]);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0,
                    GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, (IntBuffer) null);

            fbo[i] = glGenFramebuffers();
            glBindFramebuffer(GL_FRAMEBUFFER, fbo[i]);
            glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                    GL_TEXTURE_2D, texture[i], 0);
            int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                throw new IllegalStateException("shader chain framebuffer " + i
                        + " incomplete: 0x" + Integer.toHexString(status));
            }
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        resultFbo = 0;
    }

    private void releaseBuffers() {
        for (int i = 0; i < 2; i++) {
            if (fbo[i] != 0) glDeleteFramebuffers(fbo[i]);
            if (texture[i] != 0) glDeleteTextures(texture[i]);
            fbo[i] = 0;
            texture[i] = 0;
        }
        resultFbo = 0;
    }

    @Override
    public void close() {
        releaseBuffers();
        for (Program program : programs.values()) glDeleteProgram(program.id);
        programs.clear();
        if (vertexShader != 0) glDeleteShader(vertexShader);
        vertexShader = 0;
        if (vao != 0) glDeleteVertexArrays(vao);
        vao = 0;
        timers.close();
        if (uploads != null) MemoryUtil.memFree(uploads);
        uploads = null;
        width = height = 0;
    }

    // --- GPU timing ------------------------------------------------------------

    /**
     * {@code GL_TIME_ELAPSED} queries, issued one frame and read the next.
     *
     * <p><b>Why not {@code System.nanoTime}.</b> The CPU chain's per-pass
     * numbers are honest because the CPU chain is the work; a GL draw call
     * returns as soon as the command is queued, so the same instrument applied
     * here would report tens of microseconds for a pass costing milliseconds on
     * the GPU. The plan's whole justification for Job A is a per-pass table
     * (lighting 2.894 ms, bloom 2.563 ms) that the GPU side has to be
     * comparable against, and a number that is wrong by two orders of magnitude
     * in the flattering direction is worse than no number — §5.0 already had to
     * correct a reading of {@code shaders 0.000} that meant "not run".
     *
     * <p>Reading a query result blocks until the GPU reaches it, so results are
     * collected at the start of the <em>next</em> frame's chain, by which point
     * they are available and reading costs nothing. Availability is checked
     * rather than assumed: a frame whose results are not ready is skipped, not
     * waited for.
     */
    private static final class Timers {

        /** Queries allowed to be awaiting a result before the oldest is abandoned. */
        private static final int MAX_IN_FLIGHT = 256;

        /** Query objects not currently in flight, reused rather than regenerated. */
        private final List<Integer> free = new ArrayList<>();
        private final List<Entry> pending = new ArrayList<>();
        private Entry open;

        private record Entry(String name, int query) {}

        void begin(String name) {
            int query = free.isEmpty() ? glGenQueries() : free.remove(free.size() - 1);
            glBeginQuery(GL_TIME_ELAPSED, query);
            open = new Entry(name, query);
        }

        void end() {
            if (open == null) return;
            glEndQuery(GL_TIME_ELAPSED);
            pending.add(open);
            open = null;
        }

        /**
         * Feed whatever the GPU has finished into the profiler, and account the
         * total to {@link FrameProfiler.Stage#SHADERS} so the stage and its
         * per-pass breakdown are the same measurement rather than two.
         */
        void collect(FrameProfiler profiler) {
            if (pending.isEmpty()) return;
            long total = 0;
            // A query still in flight stays pending and is asked again next
            // frame. The first version dropped it instead, and the effect was
            // not random: the last pass in the chain is the last to finish, so
            // it was the one whose result was never ready and never recorded.
            // A two-pass chain reported one pass, every frame, for ever — a
            // per-pass breakdown that is silently missing its most expensive
            // entry, which is worse than one that is missing altogether.
            Iterator<Entry> it = pending.iterator();
            while (it.hasNext()) {
                Entry entry = it.next();
                if (glGetQueryObjecti(entry.query(), GL_QUERY_RESULT_AVAILABLE) != GL_TRUE) {
                    continue;
                }
                long nanos = glGetQueryObjecti64(entry.query(), GL_QUERY_RESULT);
                profiler.recordPassElapsed(entry.name(), nanos);
                total += nanos;
                it.remove();
                free.add(entry.query());
            }
            if (total > 0) profiler.recordElapsed(FrameProfiler.Stage.SHADERS, total);
            // A driver that never reports a result would otherwise leak one
            // query object per pass per frame. Nothing is known to do that;
            // the valve costs a comparison and removes the possibility.
            while (pending.size() > MAX_IN_FLIGHT) free.add(pending.remove(0).query());
        }

        /**
         * Stop waiting for results nobody will read. Profiling is a toggle in
         * the game (F3), so the frame after it is switched off would otherwise
         * leave a query per pass pending for ever.
         */
        void abandon() {
            if (pending.isEmpty()) return;
            for (Entry entry : pending) free.add(entry.query());
            pending.clear();
        }

        void close() {
            if (open != null) {
                glEndQuery(GL_TIME_ELAPSED);
                pending.add(open);
                open = null;
            }
            for (Entry entry : pending) glDeleteQueries(entry.query());
            for (int query : free) glDeleteQueries(query);
            pending.clear();
            free.clear();
        }
    }
}
