package com.larsons.engine;

import com.larsons.engine.graphics.AssetLoader;
import com.larsons.engine.graphics.SkinDef;
import com.larsons.engine.graphics.Skins;
import com.larsons.engine.graphics.SpriteCanvas;
import com.larsons.engine.graphics.SpriteSheet;
import com.larsons.engine.graphics.TexturePack;
import com.larsons.engine.input.InputManager;
import com.larsons.engine.ui.SpriteEditorPanel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Create texture": the in-game sprite-sheet editor. A creator draws a
 * texture frame by frame — each new frame starting as the last one — picks a
 * frame rate, and saves; the finished sheet lands in the drop-in texture pack
 * under the object's own file name, which is all it takes for the game to
 * draw with it.
 */
@Timeout(60)
class SpriteEditorTest {

    private static final int RED = 0xffff0000;
    private static final int BLUE = 0xff0000ff;
    private static final int VW = 1280, VH = 720;

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @AfterEach
    void resetRuntime() {
        Skins.install(List.of());
        TexturePack.useDir("");
        AssetLoader.clearCache();
    }

    @Test
    void pencilStrokesPaintPixelsAndTheBrushHasWidth() {
        SpriteCanvas canvas = new SpriteCanvas(16, 16);
        assertEquals(1, canvas.frameCount(), "a new canvas is one blank frame");
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(4, 4));

        canvas.plot(4, 4, RED, 1);
        assertEquals(RED, canvas.pixel(4, 4));
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(5, 4), "a 1px brush is one pixel");

        // A drag between two ticks fills in the pixels in between, so a fast
        // stroke is a line rather than a dotted trail.
        canvas.stroke(0, 0, 0, 5, RED, 1);
        for (int y = 0; y <= 5; y++) assertEquals(RED, canvas.pixel(0, y), "row " + y);

        canvas.plot(10, 10, BLUE, 3);
        assertEquals(BLUE, canvas.pixel(9, 9), "a 3px brush is a 3x3 square");
        assertEquals(BLUE, canvas.pixel(11, 11));
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(12, 12));

        // A brush hanging over the edge is clipped, not an error, and what
        // does land on the canvas still paints.
        canvas.plot(0, 15, RED, 3);
        assertEquals(RED, canvas.pixel(0, 15));
        assertEquals(RED, canvas.pixel(1, 14));
        canvas.plot(-9, 40, RED, 4); // entirely outside: a no-op
    }

    @Test
    void fillFloodsUpToTheColourBoundaryOnly() {
        SpriteCanvas canvas = new SpriteCanvas(8, 8);
        // A wall down the middle: the fill on the left must not cross it.
        for (int y = 0; y < 8; y++) canvas.set(4, y, BLUE);

        canvas.fill(0, 0, RED);
        assertEquals(RED, canvas.pixel(0, 0));
        assertEquals(RED, canvas.pixel(3, 7), "everything reachable on this side");
        assertEquals(BLUE, canvas.pixel(4, 4), "the wall itself is untouched");
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(5, 0), "and so is past it");
    }

    @Test
    void lineAndRectangleCoverTheirPixels() {
        SpriteCanvas canvas = new SpriteCanvas(16, 16);
        canvas.line(0, 0, 5, 5, RED, 1);
        for (int i = 0; i <= 5; i++) assertEquals(RED, canvas.pixel(i, i), "diagonal at " + i);

        canvas.rect(8, 8, 12, 12, BLUE, 1, false);
        assertEquals(BLUE, canvas.pixel(8, 8), "outlined: corners");
        assertEquals(BLUE, canvas.pixel(10, 8), "outlined: edges");
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(10, 10), "…but hollow inside");

        canvas.rect(8, 8, 12, 12, BLUE, 1, true);
        assertEquals(BLUE, canvas.pixel(10, 10), "solid fills the middle in");
    }

    @Test
    void aNewFrameCarriesTheDrawingForwardSoOnlyTheChangeIsDrawn() {
        SpriteCanvas canvas = new SpriteCanvas(8, 8);
        canvas.plot(1, 1, RED, 1);

        assertTrue(canvas.addFrame());
        assertEquals(2, canvas.frameCount());
        assertEquals(1, canvas.frame(), "the editor lands on the frame it just added");
        assertEquals(RED, canvas.pixel(1, 1), "frame 2 starts as a copy of frame 1");

        // Drawing on the new frame is the animation, and leaves frame 1 alone.
        canvas.plot(2, 1, RED, 1);
        assertEquals(RED, canvas.pixel(1, 2, 1));
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(0, 2, 1),
                "the previous frame is not edited from here");

        assertTrue(canvas.addBlankFrame());
        assertEquals(3, canvas.frameCount());
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(1, 1), "a blank frame is empty");

        // An animation has a ceiling, and hitting it is reported, not thrown.
        while (canvas.frameCount() < SpriteCanvas.MAX_FRAMES) assertTrue(canvas.addFrame());
        assertFalse(canvas.addFrame(), "MAX_FRAMES is the limit");
        assertEquals(SpriteCanvas.MAX_FRAMES, canvas.frameCount());
    }

    @Test
    void deletingTheLastFrameClearsItInsteadOfLeavingNothingToDrawOn() {
        SpriteCanvas canvas = new SpriteCanvas(8, 8);
        canvas.plot(1, 1, RED, 1);
        canvas.addFrame();

        canvas.deleteFrame();
        assertEquals(1, canvas.frameCount());
        assertEquals(RED, canvas.pixel(1, 1), "the frame that was left is the one kept");

        canvas.deleteFrame();
        assertEquals(1, canvas.frameCount(), "there is always a frame to draw on");
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(1, 1), "…wiped rather than removed");
    }

    @Test
    void undoTakesBackAWholeStrokeAndRedoPutsItBack() {
        SpriteCanvas canvas = new SpriteCanvas(8, 8);
        assertFalse(canvas.canUndo(), "nothing has happened yet");

        // The editor records one undo point per stroke, so taking back a
        // scribble takes back the scribble, not one pixel of it.
        canvas.pushUndo();
        canvas.stroke(0, 0, 7, 0, RED, 1);
        assertEquals(RED, canvas.pixel(3, 0));

        assertTrue(canvas.undo());
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(3, 0), "the whole stroke is gone");
        assertTrue(canvas.canRedo());
        assertTrue(canvas.redo());
        assertEquals(RED, canvas.pixel(3, 0));

        // Frame operations are undoable too.
        canvas.addFrame();
        assertEquals(2, canvas.frameCount());
        assertTrue(canvas.undo());
        assertEquals(1, canvas.frameCount());
    }

    @Test
    void resizingKeepsTheDrawingAndUndoRestoresTheSizeItWasDrawnAt() {
        SpriteCanvas canvas = new SpriteCanvas(16, 16);
        canvas.plot(2, 2, RED, 1);
        canvas.plot(15, 15, BLUE, 1);

        canvas.resize(8, 8);
        assertEquals(8, canvas.width());
        assertEquals(RED, canvas.pixel(2, 2), "what fits stays put");

        canvas.undo();
        assertEquals(16, canvas.width(), "undo brings the canvas back with the pixels");
        assertEquals(BLUE, canvas.pixel(15, 15), "including what the crop had cut off");
    }

    @Test
    void theExportedSheetSlicesBackIntoTheFramesItWasDrawnAs() {
        SpriteCanvas canvas = new SpriteCanvas(8, 8, 6);
        canvas.plot(0, 0, RED, 1);
        canvas.addFrame();
        canvas.plot(7, 7, BLUE, 1);

        BufferedImage sheet = canvas.toSheet();
        assertEquals(16, sheet.getWidth(), "frames run left to right in one row");
        assertEquals(8, sheet.getHeight());

        List<BufferedImage> frames = SpriteSheet.fromImage(sheet, 8, 8).frames();
        assertEquals(2, frames.size());
        assertEquals(RED, frames.get(0).getRGB(0, 0));
        assertEquals(RED, frames.get(1).getRGB(0, 0), "frame 2 kept what frame 1 drew");
        assertEquals(BLUE, frames.get(1).getRGB(7, 7));

        // …and read back in, it is the same animation, ready to be edited on.
        SpriteCanvas reopened = SpriteCanvas.fromSheet(sheet, 8, 8, 2, canvas.fps());
        assertEquals(2, reopened.frameCount());
        assertEquals(6, reopened.fps());
        assertEquals(BLUE, reopened.pixel(1, 7, 7));
    }

    @Test
    void savingFilesTheSheetInThePackUnderTheObjectsOwnName(@TempDir Path tmp)
            throws IOException {
        Path root = tmp.resolve("textures");
        TexturePack.useDir(root.toString());

        // A custom block a creator just made, with no art anywhere yet.
        String key = "block/moon_rock";
        SpriteCanvas canvas = new SpriteCanvas(16, 16, 4);
        canvas.fillFrame(RED);
        canvas.addFrame();
        canvas.fillFrame(BLUE);

        Path file = TexturePack.writeSheet(key, canvas.toSheet());
        TexturePack.setOverride(key, canvas.width(), canvas.height(),
                canvas.frameCount(), canvas.fps());

        assertEquals(root.resolve("blocks/moon_rock.png"), file,
                "filed where the pack looks the block up");
        assertTrue(Files.isRegularFile(file));
        assertTrue(Files.isRegularFile(root.resolve(TexturePack.KEYS_FILE)),
                "the first drawn texture scaffolds the pack around it");

        // Nothing else needed: the pack now answers for the key, at the size
        // and rate it was drawn at.
        SkinDef def = TexturePack.lookup(key);
        assertNotNull(def, "the drawn sheet is the block's texture");
        assertEquals(16, def.frameWidth);
        assertEquals(2, def.frameCount);
        assertEquals(4, def.fps);
        assertTrue(def.usePack);

        // And it animates: two frames at 4 fps.
        assertEquals(RED, Skins.frame(key, 0).getRGB(8, 8));
        assertEquals(BLUE, Skins.frame(key, 0.3).getRGB(8, 8));

        // Saved into the pack means saved for good: a reload re-reads both the
        // file and the frame settings that came with it.
        TexturePack.reload();
        assertEquals(2, TexturePack.framesFor(key).count());
        assertEquals(RED, Skins.frame(key, 0).getRGB(8, 8));
    }

    // --- the popup window itself -----------------------------------------------------

    private static final Component SOURCE = new Canvas(); // events need a non-null source

    private static MouseEvent mouse(int id, int x, int y, int button) {
        return new MouseEvent(SOURCE, id, System.currentTimeMillis(), 0, x, y, 1,
                false, button);
    }

    @Test
    void paintingInTheWindowDrawsAndCtrlSSavesIntoThePack(@TempDir Path tmp) {
        Path root = tmp.resolve("textures");
        TexturePack.useDir(root.toString());
        String key = "block/hand_drawn";

        SpriteCanvas canvas = new SpriteCanvas(32, 32, 0);
        SpriteEditorPanel panel = new SpriteEditorPanel("Create Texture — Hand Drawn",
                TexturePack.fileNameFor(key), canvas);
        Path[] written = {null};
        panel.onSave(c -> {
            try {
                written[0] = TexturePack.writeSheet(key, c.toSheet());
                TexturePack.setOverride(key, c.width(), c.height(), c.frameCount(), c.fps());
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        });

        // A click on the canvas paints the pixel that was under the cursor,
        // and a drag paints the run between where it was and where it went.
        Rectangle box = panel.canvasBounds(VW, VH);
        int scale = box.width / canvas.width();
        assertTrue(scale >= 1, "the canvas is drawn at least life size");
        int x0 = box.x + 4 * scale + scale / 2, y0 = box.y + 4 * scale + scale / 2;
        int x1 = box.x + 9 * scale + scale / 2;

        InputManager input = new InputManager();
        input.mouseMoved(mouse(MouseEvent.MOUSE_MOVED, x0, y0, MouseEvent.NOBUTTON));
        input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x0, y0, MouseEvent.BUTTON1));
        input.newFrame();
        panel.update(1 / 60.0, input, VW, VH);
        assertNotEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(4, 4),
                "the click painted the pixel it landed on");

        input.mouseDragged(mouse(MouseEvent.MOUSE_DRAGGED, x1, y0, MouseEvent.BUTTON1));
        input.newFrame();
        panel.update(1 / 60.0, input, VW, VH);
        for (int x = 4; x <= 9; x++) {
            assertNotEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(x, 4),
                    "the drag is a stroke, not dots: x=" + x);
        }

        input.mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, x1, y0, MouseEvent.BUTTON1));
        input.newFrame();
        panel.update(1 / 60.0, input, VW, VH);

        // Right-dragging is the eraser, whatever tool is selected.
        input.mousePressed(mouse(MouseEvent.MOUSE_PRESSED, x0, y0, MouseEvent.BUTTON3));
        input.newFrame();
        panel.update(1 / 60.0, input, VW, VH);
        assertEquals(SpriteCanvas.TRANSPARENT, canvas.pixel(4, 4), "right-click erases");
        input.mouseReleased(mouse(MouseEvent.MOUSE_RELEASED, x0, y0, MouseEvent.BUTTON3));
        input.newFrame();
        panel.update(1 / 60.0, input, VW, VH);

        // Ctrl+S hands the sheet to the caller, which files it in the pack.
        input.keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, KeyEvent.VK_CONTROL, KeyEvent.CHAR_UNDEFINED));
        input.keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, KeyEvent.VK_S, 's'));
        input.newFrame();
        panel.update(1 / 60.0, input, VW, VH);

        assertNotNull(written[0], "Ctrl+S saved");
        assertEquals(root.resolve("blocks/hand_drawn.png"), written[0]);
        assertNotNull(Skins.frame(key, 0), "and the block draws with what was painted");
    }

    @Test
    void escapeClosesTheWindowWithoutWritingAnything(@TempDir Path tmp) {
        TexturePack.useDir(tmp.resolve("textures").toString());
        SpriteCanvas canvas = new SpriteCanvas(16, 16);
        SpriteEditorPanel panel = new SpriteEditorPanel("Create Texture — Test",
                "blocks/test.png", canvas);
        boolean[] saved = {false};
        boolean[] cancelled = {false};
        panel.onSave(c -> saved[0] = true);
        panel.onCancel(() -> cancelled[0] = true);

        InputManager input = new InputManager();
        input.keyPressed(new KeyEvent(SOURCE, KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, KeyEvent.VK_ESCAPE, KeyEvent.CHAR_UNDEFINED));
        input.newFrame();
        panel.update(1 / 60.0, input, VW, VH);

        assertTrue(cancelled[0], "Esc backs out");
        assertFalse(saved[0]);
        assertFalse(Files.exists(tmp.resolve("textures")), "nothing written to the pack");
    }

    @Test
    void drawingOverAnExistingTextureReplacesItInPlace(@TempDir Path tmp) throws IOException {
        Path root = tmp.resolve("textures");
        TexturePack.useDir(root.toString());
        String key = "item/star_wand";

        SpriteCanvas first = new SpriteCanvas(16, 16, 0);
        first.fillFrame(RED);
        TexturePack.writeSheet(key, first.toSheet());
        TexturePack.setOverride(key, 16, 16, 1, 0);
        assertEquals(RED, Skins.frame(key, 0).getRGB(1, 1));

        // Re-opening "Create texture" edits what is there rather than starting
        // over, and saving overwrites the same file.
        SpriteCanvas again = SpriteCanvas.load(TexturePack.lookup(key).sheet, 16, 16, 1, 0);
        assertNotNull(again, "the pack's sheet opens back up in the editor");
        assertEquals(RED, again.pixel(1, 1));
        again.plot(1, 1, BLUE, 1);
        Path file = TexturePack.writeSheet(key, again.toSheet());

        assertEquals(root.resolve("items/star_wand.png"), file, "the same file, rewritten");
        assertEquals(BLUE, Skins.frame(key, 0).getRGB(1, 1), "the edit is what draws now");
        assertNotEquals(RED, Skins.frame(key, 0).getRGB(1, 1));
    }
}
