/*
 * Luke Diamond
 * 01/22/2018
 * Grade 11 Final Project
 * Mr. Patterson
 */

package rasterizer;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Graphics;
import java.awt.Color;
import java.awt.Font;
import javax.swing.JPanel;
import java.awt.Point;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.io.IOException;
import java.io.File;

import rasterizer.Vector2;
import rasterizer.Mesh;

/**
 * Raster panel, responsible for all rasterization/drawing to screen.
 */
public class RasterPanel extends JPanel {
    // Back buffers for rendering to/drawing from.
    private volatile BufferedImage m_backBuffer;
    private volatile float[][] m_depthBuffer;


    // Render thread state.
    private Thread[] m_renderThreads;
    private int m_threadCount;
    private volatile ArrayDeque<DrawAction> m_drawQueue;
    private volatile Integer m_drawCount = 0;

    // Debug info.
    private Font m_debugFont;
    private volatile Integer m_drawnFragments = 0;
    private volatile Integer m_discardedFragments = 0;
    private volatile Integer m_discardedPolys = 0;
    private volatile Integer m_occludedFragments = 0;
    private Integer m_FPS = 0;

    // Camera state.
    private Vector3 m_cameraPosition;
    private Vector3 m_cameraRotation;

    // Texture array (for sampler textuers).
    BufferedImage m_textures[];
    // Mesh array.
    Mesh m_meshes[];

    // Current mesh index.
    private int m_meshIndex = 0;

    // Screen dimensions.
    private int m_screenWidth;
    private int m_screenHeight;

    // Current update listener.
    IUpdateListener m_listener;

    // Resolution divisor (for low-res upscaling, improves FPS).
    final int RES_DIVISOR = 2;
    // Iteration scale (creates holes in mesh when the camera is close,
    // but greatly improves FPS).
    final float ITER_SCALE = 0.5f;

    /**
     * Linearly interpolates between two floats given an alpha value.
     * @param a The first value.
     * @param b The second value.
     * @param alpha The amount to blend the first and second values.
     * @return Some value where a <= retval <= b given a < b.
     */
    private float lerp(float a, float b, float alpha) {
        // Linear interpolation equation. Lower alpha means bigger weight
        // on A (alpha * a),
        // while higher alpha menas bigger weight on B (alpha * b).
        return (1.0f - alpha) * a + alpha * b;
    }

    /**
     * Linearly interpolates between two colors given an alpha value.
     * @param a The first color.
     * @param b The second color.
     * @param alpha The amount to blend the first and second colors.
     * @return A new color representing A blended with B.
     */
    private Color lerpColor(Color a, Color b, float alpha) {
        // Blend colors using lerp method.
        return new Color(
            Math.round(
                lerp((float) a.getRed(), (float) b.getRed(), alpha)),
            Math.round(
                lerp((float) a.getGreen(), (float) b.getGreen(), alpha)),
            Math.round(
                lerp((float) a.getBlue(), (float) b.getBlue(), alpha)));

    }

    /**
     * Clamps value between range.
     * @param x The value to clamp.
     * @param min The minimum value in the range.
     * @param max The maximum value in the range.
     * @return The clamped value.
     */
    private float clamp(float x, float min, float max) {
        // If X is greater than the maximum, return the maximum, if X is lower
        // than the minimum, return the minimum, else return X.
        if (x > max) return max;
        if (x < min) return min;
        return x;
    }

    /**
     * Sample a texture given X/Y coordinate in range [0, 1].
     * @param image The image to sample.
     * @param x The X texture coordinate.
     * @param y The Y texture coordinate.
     * @return The color sampled from the image at the X/Y pair.
     */
    private Color sampleImage(BufferedImage image, float x, float y) {
        // If the image is undefined return pure black.
        if (image == null) return Color.BLACK;
        // X and  positions are rounded, then clamped within the image borders.
        int xpos =
            (int) clamp(
                (int) Math.ceil(x * image.getWidth()),
                0, image.getWidth() - 1);
        int ypos =
            (int) clamp(
                (int) Math.ceil(y * image.getHeight()),
                0, image.getHeight() - 1);
        // Return the color as sampled from the computed coordinates.
        return new Color(image.getRGB(xpos, image.getHeight() - ypos - 1));
    }

    /**
     * Read a texture from the disk and assign it to an index.
     * @param id The ID/index to assign the texture to.
     * @param path The path of the image to load.
     */
    public void addTexture(Integer id, String path) {
        // Attempt to read the image from the disk.
        try {
            BufferedImage img = ImageIO.read(new File(path));
            // Assign the texture to the ID.
            m_textures[id] = img;
        } catch (IOException e) {
            // Print stack trace if an exception is thrown.
            e.printStackTrace();
        }
    }


    // Define the position of the point light in the scene.
    Vector3 lightPos = new Vector3(0.0f, 3.0f, 3.0f);
    // Define the color of the point light.
    Color lightColor = new Color(255, 255, 255);

    /**
     * The meat of the rasterizer, handles filling triangles.
     * @param id The ID of the texture to sample.
     * @param model The model matrix (transformation).
     * @param proj The projection matrix (world->screen)
     * @param va The first vertex.
     * @param vb The second vertex.
     * @param vc The third vertex.
     * @param ta The first texture coordinate.
     * @param tb The second texture coordinate.
     * @param tc The third texture coordinate.
     */
    private synchronized void fillTriangle(DrawAction action) {
        // Get the texture at the given ID.
        BufferedImage tex = m_textures[action.tex];
        // Compute model-view-projection matrix.
        Matrix4 mv = action.view.mult(action.model);
        Matrix4 mvp = action.proj.mult(mv);

        // Compute vertices in world space.
        Vector3 ta = action.model.mult(new Vector4(action.va, 1.0f)).wdivide();
        Vector3 tb = action.model.mult(new Vector4(action.vb, 1.0f)).wdivide();
        Vector3 tc = action.model.mult(new Vector4(action.vc, 1.0f)).wdivide();
        // Compute vertices in screen space.
        Vector3 sa = mvp.mult(new Vector4(action.va, 1.0f)).wdivide();
        Vector3 sb = mvp.mult(new Vector4(action.vb, 1.0f)).wdivide();
        Vector3 sc = mvp.mult(new Vector4(action.vc, 1.0f)).wdivide();

        // Compute surface normal from world-space triangle poly.
        Vector3 surfaceNormal =
            ta.sub(tb).normalize().cross(tc.sub(ta).normalize());

        // Discard face if it is facing backwards.
        if (new Vector3(action.view.c).normalize().dot(surfaceNormal) < 0.0f) {
            // Increment discarded poly counter.
            synchronized (m_discardedPolys) {
                ++m_discardedPolys;
            }
            return;
        }

        // Compute maximum screen-space distance of vertices.
        final float maxDist =
            Math.max(
                sa.distance(sb),
                Math.max(
                    sa.distance(sc),
                    sb.distance(sc)));

        // Compute the amount to increment alphaX and alphaY each iteration.
        final float ITER_X_INV =
            1.0f / (m_screenWidth * maxDist * ITER_SCALE);
        final float ITER_Y_INV =
            1.0f / (m_screenHeight * maxDist * ITER_SCALE);

        // Return if computation is too strenuous.
        if (ITER_X_INV < 1E-4 || ITER_Y_INV < 1E-4) { return; }

        // Local debug variables.
        int drawnFragments = 0;
        int discardedFragments = 0;
        int occludedFragments = 0;

        // Get time from beginning of rasterization.
        double start = System.nanoTime() * 1E-9;

        // Perform X iterations (from 0 to 1).
        for (float alphaX = 0.0f; alphaX < 1.0f; alphaX += ITER_X_INV) {
            // Interpolate between first and second vertex.
            Vector3 ia = action.va.lerp(action.vb, alphaX);
            // Interpolate first and third vertex.
            Vector3 ib = action.va.lerp(action.vc, alphaX);

            // Perform Y iterations (from 0 to 1).
            for (float alphaY = 0.0f; alphaY < 1.0f; alphaY += ITER_Y_INV) {
                if (System.nanoTime() * 1E-9 - start > 0.1f) return;

                // Interpolate between the first interpolated variable and the
                // second interpolated variable.
                Vector3 ic = ia.lerp(ib, alphaY);
                // Compute screen-space coordinate by multiplying the
                // world-space coordinate by the model-projection matrix, then
                // divide by w to make it a 3-dimensional vector.
                Vector3 ssc = mvp.mult(new Vector4(ic, 1.0f)).wdivide();

                if (
                    ssc.x < -1.0f
                    || ssc.x > 1.0f
                    || ssc.y < -1.0f
                    || ssc.y > 1.0f) {
                    // Increment discarded (offscreen) fragment debug counter.
                    ++discardedFragments;
                    continue;
                }

                // Calculate the screen coordinates to draw the pixel to.
                int dcoordX =
                    (int) ((ssc.x + 1.0f) * 0.5f * (m_screenWidth - 1));
                int dcoordY =
                    (int) ((ssc.y + 1.0f) * 0.5f * (m_screenHeight - 1));

                // Perform depth test to prevent drawing occluded fragments.
                synchronized (m_depthBuffer) {
                    if (ssc.z < m_depthBuffer[dcoordX][dcoordY]) {
                        // Compute texture coordinate by blending first
                        // interpolated coordinate
                        // with second interpolated coordinate.
                        Vector2 it =
                            action.ta.lerp(action.tb, alphaX).lerp(
                                action.ta.lerp(action.tc, alphaX), alphaY);
                        // Compute world-space position
                        // for lighting calculations.
                        Vector3 world =
                            action.model.mult(new Vector4(ic, 1.0f)).wdivide();
                        // Compute light-to-surface direction.
                        Vector3 ldir = world.sub(lightPos).normalize();
                        // Sample texture using texture coordinate.
                        Color color = sampleImage(tex, it.x, it.y);

                        // Compute the inverse square attenuation
                        // factor on the light.
                        float dist = world.distance(lightPos);
                        float atten = 1.0f / (1.0f + dist * dist);

                        // Calculate light factors.
                        float diffac =
                            8.0f
                            * clamp(ldir.dot(surfaceNormal), 0.0f, 1.0f)
                            * atten;

                        // Apply attenuation by blending sampled color
                        // with black using the attenuation factor as the alpha.
                        // Also apply directional occlusion by finding the dot
                        // product between the light-to-surf direction and the
                        // surface normal.
                        color =
                            lerpColor(
                                lerpColor(
                                    Color.BLACK,
                                    color,
                                    clamp(
                                        diffac,
                                        0.0f,
                                        1.0f)),
                                lightColor,
                                clamp(diffac * diffac, 0.0f, 1.0f));

                        // Write screen-space depth value to depth buffer.
                        m_depthBuffer[dcoordX][dcoordY] = ssc.z;

                        // Write color to backbuffer.
                        m_backBuffer.setRGB(
                            (int) dcoordX,
                            (int) m_screenHeight - dcoordY - 1,
                            color.getRGB());

                        // Increment drawn fragment counter.
                        ++drawnFragments;
                    } else {
                        // Increment occluded fragment debug counter.
                        ++occludedFragments;
                    }
                }
            }
        }

        synchronized (m_drawnFragments) {
            m_drawnFragments += drawnFragments;
        }

        synchronized (m_occludedFragments) {
            m_occludedFragments += occludedFragments;
        }

        synchronized (m_discardedFragments) {
            m_discardedFragments += discardedFragments;
        }
    }

    /*
     * General timing variables.
     */

    // Last chrono time.
    float m_last = 0.0f;
    // Current elapsed time.
    float m_elapsed = 0.0f;

    /*
     * FPS profiling variables.
     */

    // FPS time accumulator.
    float m_fpsAccumulator = 0.0f;
    // Number of frames drawn.
    int m_frames = 0;

    /**
     * Overriden JPanel paintComponent, for drawing the rasterized scene.
     * @param g The graphics object to draw to.
     */
    @Override
    public void paintComponent(Graphics g) {
        // Get the graphics object from the BufferedImage back buffer.
        Graphics ig = m_backBuffer.getGraphics();

        // Compute projection matrix from screen width/height and fixed FOV
        // and near/far planes.
        Matrix4 proj =
            Matrix4.perspective(
                (float) m_backBuffer.getWidth()
                / (float) m_backBuffer.getHeight(),
                45.0f,
                0.01f,
                1000.0f);
        // Compute view matrix.
        Matrix4 view =
            Matrix4.rotationX(-m_cameraRotation.x)
            .mult(Matrix4.rotationY(-m_cameraRotation.y))
            .mult(Matrix4.rotationZ(-m_cameraRotation.z))
            .mult(Matrix4.translation(m_cameraPosition.mult(-1.0f)));

        // Create pool for render threads.
        ArrayList<Thread> threadPool = new ArrayList<Thread>();

        // Define triangle sum to be displayed as debug info.
        int triangleSum = 0;

        // Iterate through meshes in scene.
        for (int i = 0; i < m_meshIndex; ++i) {
            // Get mesh from index.
            Mesh m = m_meshes[i];
            // Add polycount.
            triangleSum += m.getTriCount();

            // Get the verts of the mesh.
            Vector3[] verts = m.getVerts();

            // Loop through every three verts (every triangle).
            synchronized (m_drawQueue) {
                for (int v = 0; v < m.getTriCount() * 3; v += 3) {
                    // Enqueue draw action.
                    m_drawQueue.add(
                        new DrawAction(
                            m.getTextureID(),
                            m.getTransformMatrix(),
                            view,
                            proj,
                            m.getVerts()[v + 0],
                            m.getVerts()[v + 1],
                            m.getVerts()[v + 2],
                            m.getCoords()[v + 0],
                            m.getCoords()[v + 1],
                            m.getCoords()[v + 2]));
                }
            }
        }

        // Loop until all queued triangles are drawn.
        double start = System.nanoTime() * 1E-9;
        while (m_drawCount != triangleSum) {
            // Force maximum frame time.
            if (System.nanoTime() * 1E-9 - start > 0.5f) break;
            Thread.yield();
        }
        m_drawCount = 0;

        // Draw the backbuffer to the screen.
        g.drawImage(
            m_backBuffer,
            0,
            0,
            m_screenWidth * RES_DIVISOR,
            m_screenHeight * RES_DIVISOR,
            null);
        // Set the text color to draw the debug info.
        g.setColor(Color.WHITE);
        // Get bytes allocated by JVM instance (in megabytes).
        float allocated =
            Math.round(100.0f *
            (((Runtime.getRuntime().totalMemory()
            - Runtime.getRuntime().freeMemory()) / 1024.0f / 1024.0f)))
            / 100.0f;
        // Draw debug info.
        g.setFont(m_debugFont);
        g.drawString("POLYCOUNT:           " + triangleSum, 32, 32);
        g.drawString("THREADS:             " + m_threadCount, 32, 64);
        g.drawString("DRAWN FRAGMENTS:     " + m_drawnFragments, 32, 96);
        g.drawString("DISCARDED FRAGMENTS: " + m_discardedFragments, 32, 128);
        g.drawString("DISCARDED POLYS:     " + m_discardedPolys, 32, 160);
        g.drawString("OCCLUDED FRAGMENTS:  " + m_occludedFragments, 32, 192);
        g.drawString("FPS:                 " + m_FPS, 32, 224);
        g.drawString("MEMORY:              " + allocated + "mb", 32, 256);
        g.drawString(
            "MOVE WITH WASD. TURN WITH ARROW KEYS.",
            32,
            (m_screenHeight * RES_DIVISOR) - 64);
        // Reset debug info.
        m_drawnFragments = 0;
        m_discardedFragments = 0;
        m_discardedPolys = 0;
        m_occludedFragments = 0;

        // Clear the backbuffer.
        ig.clearRect(0, 0, m_screenWidth, m_screenHeight);
        // Fill depth buffer with 100% depth.
        for (int x = 0; x < m_screenWidth; ++x) {
            for (int y = 0; y < m_screenHeight; ++y) {
                m_depthBuffer[x][y] = 1.0f;
            }
        }

        // Compute delta time/elapsed time.
        float now = System.nanoTime() * 1E-9f;
        float delta = now - m_last;
        m_elapsed += delta;
        m_last = now;

        // Add delta time to FPS accumulator and increment frame counter.
        m_fpsAccumulator += delta;
        ++m_frames;
        // Print frame count to console if FPS accumulator goes over 1 second.
        if (m_fpsAccumulator > 1.0f) {
            m_FPS = m_frames;
            // Reset FPS accumulators.
            m_frames = 0;
            m_fpsAccumulator = 0.0f;
        }

        // Update listener if non-null.
        if (m_listener != null) {
            m_listener.update(delta);
        }
    }

    /**
     * Set the update listener to notify once per update.
     * @param listener The listener to assign.
     */
    public void setUpdateListener(IUpdateListener listener) {
        m_listener = listener;
    }

    /**
     * Set the position of the camera in the world.
     * @param pos The new position to use.
     */
    public void setCameraPosition(Vector3 pos) {
        m_cameraPosition = pos;
    }

    /**
     * Get the position of the camera in the world.
     * @return The camera position vector.
     */
    public Vector3 getCameraPosition() {
        return m_cameraPosition;
    }

    /**
     * Set the rotation of the camera.
     * @param rot The new rotation to use for the camera.
     */
    public void setCameraRotation(Vector3 rot) {
        m_cameraRotation = rot;
    }

    /**
     * Get the rotation of the camera in degrees.
     * @return The camera's rotation as a 3-dimensional vector of angles.
     */
    public Vector3 getCameraRotation() {
        return m_cameraRotation;
    }

    /**
     * Add a mesh to the render array.
     * @param m The mesh to add.
     */
    public void addMesh(Mesh m) {
        m_meshes[m_meshIndex++] = m;
    }

    /**
     * Cnstruct a render panel given a width/height.
     * @param width The width of the render target in pixels.
     * @param height The height of the render target in pixels.
     */
    RasterPanel(int width, int height) {
        // Initialize back buffer.
        m_backBuffer = new BufferedImage(
            width / RES_DIVISOR,
            height / RES_DIVISOR,
            BufferedImage.TYPE_INT_RGB);
        // Initialize depth buffer.
        m_depthBuffer = new float[width / RES_DIVISOR][height / RES_DIVISOR];
        // Initialize threads.

        // Use one thread per CPU core.
        m_threadCount = Runtime.getRuntime().availableProcessors();
        m_renderThreads = new Thread[m_threadCount];
        m_drawQueue = new ArrayDeque<DrawAction>();
        for (int i = 0; i < m_threadCount; ++i) {
            Thread t = new Thread(new Runnable() {
                @Override
                public synchronized void run() {
                    for (;;) {
                        // Initialize next draw action to null.
                        DrawAction action = null;

                        // Lock draw queue.
                        synchronized (m_drawQueue) {
                            // Process the next action if not empty.
                            if (!m_drawQueue.isEmpty()) {
                                action = m_drawQueue.pop();
                            }
                        }

                        if (action != null) {
                            // Draw triangle and increment counter.
                            fillTriangle(action);
                            synchronized (m_drawCount) {
                                ++m_drawCount;
                            }
                        }
                    }
                }
            });
            t.start();
            m_renderThreads[i] = t;
        }
        // Create debug font.
        m_debugFont = new Font(Font.MONOSPACED, Font.PLAIN, 16);
        // Initialize state.
        m_textures = new BufferedImage[32];
        m_meshes = new Mesh[32];
        m_screenWidth = width / RES_DIVISOR;
        m_screenHeight = height / RES_DIVISOR;
        // Initialize camera.
        m_cameraPosition = Vector3.ZERO;
        m_cameraRotation = Vector3.ZERO;
    }

}
