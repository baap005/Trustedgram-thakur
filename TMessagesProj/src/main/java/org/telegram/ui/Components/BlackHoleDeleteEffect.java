package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES30;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Looper;
import android.os.Message;
import android.view.Choreographer;
import android.view.TextureView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;

import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;

/**
 * "Clear history" as a black hole: a port of the iOS BlackHoleDelete effect (UIKit + Metal) to
 * Android (TextureView + OpenGL ES 3.0).
 *
 * The view covers the whole chat screen and renders three passes in one GL context:
 *  1. backdrop - opaque snapshot of the screen without the message list (lens, twist, shade, bloom);
 *  2. mesh     - snapshot of the message list on a grid, pulled into the hole ("spaghettification");
 *  3. overlay  - black hole shape and the light ring from the disk texture.
 *
 * All coordinates are in pixels of this view, origin top-left.
 */
public class BlackHoleDeleteEffect extends TextureView {

    private static Boolean unsupported = null;

    public static boolean supports() {
        if (unsupported == null) {
            unsupported = MessagesController.getGlobalMainSettings().getBoolean("noblackhole", false);
        }
        return !unsupported && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP;
    }

    public interface Listener {
        /** First frame is on screen: it looks exactly like the real chat, so the real history can be hidden now. */
        void onFirstFrame();
        /** Everything swallowed and the hole has closed. The effect view can be removed. */
        void onFinished();
        /** GL could not be initialized: fall back to the regular clearing. */
        void onFailed();
    }

    // ---------------------------------------------------------------------------------------------
    // Configuration (tuned values of the iOS prototype; lengths scaled by density)
    // ---------------------------------------------------------------------------------------------

    private static class Config {
        final double holeRadius = AndroidUtilities.dp(90);
        final double emergeDuration = 0.8;
        final double collapseDuration = 0.3;
        final double settleDuration = 0.04;

        final double ringBrightness = 1.03;
        final double ringSpeed = 2.0;
        final double innerBoost = 4.5;
        final double innerWidth = 0.08;
        final double ringSqueeze = 0.7;
        final double ringGamma = 0.75;
        final double ringSoftness = 1.2;

        final double glow = 0.82;
        final double lensStrength = 1.5;
        final double spaceDarkness = 0.55;
        final double spaceSize = 2.0;
        final double backgroundSwirl = 0.9;
        final double backgroundSpin = 0.8;

        final double swallowLead = 0.1;
        final double swallowDuration = 1.15;
        final double arcShape = 1.0;
        final double pullStrength = 3;
        final double pullReach = 0.8;
        final double flowRamp = 0.35;
        final double flowAccel = 8;
        final double sinkDepth = AndroidUtilities.dp(30);
        final double fadeOuter = 1.6;
        final double fadeInner = 1.03;
        final double fadeBlur = AndroidUtilities.dp(14);
        final double emergePull = 1.0;
    }

    // ---------------------------------------------------------------------------------------------
    // Timeline (port of BlackHoleTimeline.swift)
    // ---------------------------------------------------------------------------------------------

    private static class TidalFlow {
        final double speed, strength, reach, ramp, sink, shape, accel, fadeHeight;

        TidalFlow(Config c, double speed) {
            this.speed = Math.max(speed, 1);
            strength = c.pullStrength;
            reach = Math.max(c.pullReach * c.holeRadius, 1);
            ramp = c.flowRamp;
            sink = c.sinkDepth;
            shape = c.arcShape;
            accel = c.flowAccel;
            fadeHeight = (Math.min(c.fadeInner, c.fadeOuter) - 1) * c.holeRadius;
        }

        double height(double px, double py, double hx, double hy, double horizon) {
            double dx = px - hx, dy = py - hy;
            double rho = Math.max(Math.hypot(dx, dy), 0.001);
            double cosPhi = Math.min(Math.max(dy / rho, 0.65), 1);
            double t = Math.min(Math.max((rho - horizon) / (horizon * 3), 0), 1);
            double w = t * t * (3 - 2 * t);
            return rho * Math.pow(cosPhi, shape * w) - horizon;
        }

        double travelTime(double x) {
            return (x + reach * Math.log(1 + strength * Math.exp(-x / reach))) / speed;
        }

        double eased(double tau) {
            double ramped = ramp > 0.001 ? tau - ramp * (1 - Math.exp(-tau / ramp)) : tau;
            return accel > 0.001 ? (Math.exp(accel * ramped) - 1) / accel : ramped;
        }

        double timeToSink(double x) {
            double need = travelTime(x) - travelTime(Math.max(fadeHeight, -sink));
            double lo = 0, hi = need + ramp + 1;
            for (int i = 0; i < 40; i++) {
                double mid = (lo + hi) / 2;
                if (eased(mid) < need) lo = mid; else hi = mid;
            }
            return hi;
        }
    }

    private static class Run {
        final Config config;
        final double holeX, holeY, islandW, islandH;
        final double flowSpeed, swallowStart, swallowEnd;

        Run(Config c, float left, float top, float right, float bottom, double holeX, double holeY, double islandW, double islandH) {
            this.config = c;
            this.holeX = holeX;
            this.holeY = holeY;
            this.islandW = islandW;
            this.islandH = islandH;
            double radius = c.holeRadius;
            swallowStart = c.emergeDuration * c.swallowLead;

            double[][] corners = {{left, top}, {right, top}, {left, bottom}, {right, bottom}};
            TidalFlow unit = new TidalFlow(c, 1);
            double farthest = Double.NEGATIVE_INFINITY;
            for (double[] p : corners) {
                farthest = Math.max(farthest, unit.height(p[0], p[1], holeX, holeY, radius));
            }
            double path = unit.travelTime(farthest) - unit.travelTime(Math.max(unit.fadeHeight, -unit.sink));
            double eased = unit.eased(c.swallowDuration);
            flowSpeed = (path > 0 && eased > 0) ? path / eased : 70;

            TidalFlow flow = new TidalFlow(c, flowSpeed);
            double end = swallowStart;
            for (double[] p : corners) {
                double far = flow.height(p[0], p[1], holeX, holeY, radius);
                end = Math.max(end, swallowStart + flow.timeToSink(far));
            }
            swallowEnd = end;
        }

        double closeStart() {
            return Math.max(swallowEnd - Math.min(0.12, config.collapseDuration * 0.3), swallowStart);
        }

        double total() {
            return closeStart() + config.collapseDuration + config.settleDuration + 0.03;
        }
    }

    private static class State {
        double cx, cy, halfW, halfH, radius, presence, time;
    }

    private static double clamp01(double x) {
        return Math.min(Math.max(x, 0), 1);
    }

    private static double inOut(double x) {
        return x * x * (3 - 2 * x);
    }

    private static double outCubic(double x) {
        return 1 - Math.pow(1 - x, 3);
    }

    private static double outQuint(double x) {
        return 1 - Math.pow(1 - x, 5);
    }

    private static double soft(double x) {
        return outQuint(inOut(x)) * 0.6 + inOut(inOut(x)) * 0.4;
    }

    private static double smoothstep(double a, double b, double x) {
        return inOut(clamp01((x - a) / Math.max(b - a, 0.0001)));
    }

    private static void stateAt(double t, Run run, State s) {
        Config c = run.config;
        double grow = soft(clamp01(t / c.emergeDuration));
        double back = outCubic(clamp01((t - run.closeStart()) / c.collapseDuration));
        double morph = clamp01(grow * (1 - Math.min(back, 1)));
        double halfW = (run.islandW / 2) + (c.holeRadius - run.islandW / 2) * morph;
        double halfH = (run.islandH / 2) + (c.holeRadius - run.islandH / 2) * morph;
        s.cx = run.holeX;
        s.cy = run.holeY;
        s.halfW = Math.max(halfW, 1);
        s.halfH = Math.max(halfH, 1);
        s.radius = Math.max(Math.min(halfW, halfH), 1);
        s.presence = morph;
        s.time = t;
    }

    // ---------------------------------------------------------------------------------------------
    // View
    // ---------------------------------------------------------------------------------------------

    private DrawingThread drawThread;
    private Listener listener;
    private Request pendingRequest;
    private volatile boolean finished;

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            DrawingThread thread = drawThread;
            if (thread != null && !finished) {
                thread.requestDraw();
                Choreographer.getInstance().postFrameCallback(this);
            }
        }
    };

    private static class Request {
        Bitmap list;            // message list snapshot
        Bitmap backdrop;        // screen snapshot without the list (nullable)
        float listLeft, listTop, listWidth, listHeight; // where the list snapshot goes, in view px
        float holeX, holeY;
        Bitmap disk;
    }

    public BlackHoleDeleteEffect(@NonNull Context context) {
        super(context);
        setOpaque(false);
        setSurfaceTextureListener(new SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                if (drawThread != null) {
                    drawThread.kill();
                    drawThread = null;
                }
                drawThread = new DrawingThread(surface, BlackHoleDeleteEffect.this, width, height);
                if (pendingRequest != null) {
                    drawThread.setRequest(pendingRequest);
                    pendingRequest = null;
                    Choreographer.getInstance().postFrameCallback(frameCallback);
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
                if (drawThread != null) {
                    drawThread.resize(width, height);
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                if (drawThread != null) {
                    drawThread.kill();
                    drawThread = null;
                }
                return false;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            }
        });
    }

    /**
     * Starts the effect.
     *
     * @param list     snapshot of the message list (nullable backdrop below is optional)
     * @param backdrop snapshot of everything else on the screen (wallpaper, bars); null - no lens/shade/bloom
     * @param holeX    hole center in view px
     * @param holeY    hole center in view px
     */
    public void start(Bitmap list, float listLeft, float listTop, float listWidth, float listHeight, Bitmap backdrop, float holeX, float holeY, Listener listener) {
        this.listener = listener;
        Request request = new Request();
        request.list = list;
        request.backdrop = backdrop;
        request.listLeft = listLeft;
        request.listTop = listTop;
        request.listWidth = listWidth;
        request.listHeight = listHeight;
        request.holeX = holeX;
        request.holeY = holeY;
        try {
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inScaled = false;
            request.disk = BitmapFactory.decodeResource(getResources(), R.drawable.black_hole_disk, opts);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        if (request.disk == null) {
            fail();
            return;
        }
        if (drawThread != null) {
            drawThread.setRequest(request);
            Choreographer.getInstance().postFrameCallback(frameCallback);
        } else {
            pendingRequest = request;
        }
    }

    private void fail() {
        if (finished) {
            return;
        }
        finished = true;
        MessagesController.getGlobalMainSettings().edit().putBoolean("noblackhole", unsupported = true).apply();
        Listener l = listener;
        listener = null;
        if (l != null) {
            AndroidUtilities.runOnUIThread(l::onFailed);
        }
    }

    /** Stops immediately; the listener is told the effect finished so the caller can proceed. */
    public void kill() {
        if (drawThread != null) {
            drawThread.kill();
            drawThread = null;
        }
        if (pendingRequest != null) {
            recycle(pendingRequest);
            pendingRequest = null;
        }
        if (!finished) {
            finished = true;
            Listener l = listener;
            listener = null;
            if (l != null) {
                l.onFinished();
            }
        }
    }

    private static void recycle(Request r) {
        if (r.list != null && !r.list.isRecycled()) r.list.recycle();
        if (r.backdrop != null && !r.backdrop.isRecycled()) r.backdrop.recycle();
        if (r.disk != null && !r.disk.isRecycled()) r.disk.recycle();
    }

    // ---------------------------------------------------------------------------------------------
    // GL thread
    // ---------------------------------------------------------------------------------------------

    private static final String SCREEN_VERTEX =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform vec2 uView;\n" +
            "out vec2 vPoint;\n" +
            "void main() {\n" +
            "  vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));\n" +
            "  gl_Position = vec4(c.x * 2.0 - 1.0, 1.0 - c.y * 2.0, 0.0, 1.0);\n" +
            "  vPoint = c * uView;\n" +
            "}\n";

    private static final String BACKDROP_FRAGMENT =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform vec2 uView;\n" +
            "uniform vec4 uHole;\n" +   // center x, y, radius (px), presence
            "uniform vec4 uShape;\n" +  // half size, lens, swirl
            "uniform vec4 uSpace;\n" +  // darkness, size (radii), spin angle, glow
            "uniform sampler2D uTex;\n" +
            "in vec2 vPoint;\n" +
            "out vec4 outColor;\n" +
            "float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453); }\n" +
            "float stadiumDistance(vec2 p, vec2 center, vec2 halfSize) {\n" +
            "  float r = min(halfSize.x, halfSize.y);\n" +
            "  vec2 q = abs(p - center) - (halfSize - r);\n" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 size = max(uView, vec2(1.0));\n" +
            "  vec2 position = vPoint;\n" +
            "  vec2 center = uHole.xy;\n" +
            "  float r = max(uHole.z, 1.0);\n" +
            "  float presence = uHole.w;\n" +
            "  float lens = uShape.z, swirl = uShape.w;\n" +
            "  vec2 d = position - center;\n" +
            "  float dist = max(length(d), 0.001);\n" +
            "  vec2 n = d / dist;\n" +
            "  float pull = lens * r * 0.7 * exp(-max(dist - r, 0.0) / (r * 1.3));\n" +
            "  float e1 = max(dist - r, 0.0) / (r * 1.4);\n" +
            "  float e2 = max(dist - r, 0.0) / (r * 1.0);\n" +
            "  float twist = -(swirl * exp(-e1 * e1) + uSpace.z * exp(-e2 * e2));\n" +
            "  vec2 swirled = vec2(n.x * cos(twist) - n.y * sin(twist), n.x * sin(twist) + n.y * cos(twist));\n" +
            "  vec2 source = center + swirled * max(dist - pull, r * 0.2);\n" +
            "  vec3 c = texture(uTex, source / size).rgb;\n" +
            "  float x = max(dist / r - 0.9, 0.0) / max(uSpace.y, 0.1);\n" +
            "  float a = clamp(uSpace.x * exp(-x * x), 0.0, 1.0);\n" +
            "  vec3 shaded = pow(max(c, vec3(0.0)), vec3(1.0 + 3.2 * a)) * (1.0 - 0.3 * a);\n" +
            "  float glow = uSpace.w;\n" +
            "  if (glow > 0.001 && presence > 0.001) {\n" +
            "    vec2 hs = max(uShape.xy, vec2(1.0));\n" +
            "    float rs0 = min(hs.x, hs.y);\n" +
            "    float sd = stadiumDistance(position, center, hs);\n" +
            "    float rr = (sd + rs0) / rs0;\n" +
            "    float e0 = max(rr - 1.0, 0.0);\n" +
            "    if (e0 < 3.4 && rr >= 1.0) {\n" +
            "      vec3 bloom = (vec3(1.0, 0.9, 0.55) * exp(-e0 / 0.34) * 0.7 + vec3(1.0, 0.85, 0.5) * exp(-e0 / 1.35) * 0.45)\n" +
            "                 * glow * smoothstep(0.0, 0.45, presence) * smoothstep(1.0, 1.06, rr)\n" +
            "                 * (1.0 - smoothstep(2.4, 3.4, e0));\n" +
            "      shaded = min(shaded + bloom, vec3(1.0));\n" +
            "    }\n" +
            "  }\n" +
            "  shaded += (hash(position) - 0.5) / 255.0;\n" +
            "  outColor = vec4(shaded, 1.0);\n" +
            "}\n";

    private static final String OVERLAY_VERTEX =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform vec2 uView;\n" +
            "uniform vec4 uHole;\n" +   // center x, y, radius, visibility
            "uniform vec4 uShape;\n" +  // half size, inner boost, inner width
            "uniform vec4 uRing;\n" +   // arc angle, spin, squeeze, gamma
            "out vec2 vPoint;\n" +
            "void main() {\n" +
            "  vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));\n" +
            "  float r = max(uHole.z, 1.0);\n" +
            "  vec2 extent = uShape.xy + r * (0.425 / max(uRing.z, 0.1) + 0.05);\n" +
            "  vec2 point = uHole.xy - extent + c * extent * 2.0;\n" +
            "  gl_Position = vec4(point.x / uView.x * 2.0 - 1.0, 1.0 - point.y / uView.y * 2.0, 0.0, 1.0);\n" +
            "  vPoint = point;\n" +
            "}\n";

    private static final String OVERLAY_FRAGMENT =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform vec2 uView;\n" +
            "uniform vec4 uHole;\n" +
            "uniform vec4 uShape;\n" +
            "uniform vec4 uImg;\n" +    // shadow center x, y, shadow radius (px), spin speed
            "uniform vec4 uLook;\n" +   // texture w, h, brightness, mip level
            "uniform vec4 uRing;\n" +
            "uniform vec4 uExtra;\n" +  // body opacity, time
            "uniform sampler2D uTex;\n" +
            "in vec2 vPoint;\n" +
            "out vec4 outColor;\n" +
            "float stadiumDistance(vec2 p, vec2 center, vec2 halfSize) {\n" +
            "  float r = min(halfSize.x, halfSize.y);\n" +
            "  vec2 q = abs(p - center) - (halfSize - r);\n" +
            "  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
            "}\n" +
            "void main() {\n" +
            "  vec2 hs = max(uShape.xy, vec2(1.0));\n" +
            "  float sd = stadiumDistance(vPoint, uHole.xy, hs);\n" +
            "  float body = (1.0 - smoothstep(-0.75, 0.75, sd)) * uExtra.x;\n" +
            "  float vis = uHole.w;\n" +
            "  if (vis < 0.002) { outColor = vec4(0.0, 0.0, 0.0, body); return; }\n" +
            "  float r = max(uHole.z, 1.0);\n" +
            "  vec2 q = (vPoint - uHole.xy) / r;\n" +
            "  float qs = 1.0 + sd / r;\n" +
            "  float pd = (qs - 1.012) / 0.0065;\n" +
            "  float photon = exp(-(pd * pd));\n" +
            "  float phi = atan(q.x, q.y);\n" +
            "  float nearK = exp(-max(qs - 1.0, 0.0) / max(uShape.w, 0.01));\n" +
            "  float spin = uExtra.y * uRing.y * uImg.w * (0.6 + uShape.z * nearK);\n" +
            "  float theta = uRing.x * (2.0 / 3.14159265) * asin(sin(phi + spin));\n" +
            "  float xr = max(qs - 1.0, 0.0);\n" +
            "  float ringW = 0.425;\n" +
            "  float ringWs = ringW / max(uRing.z, 0.1);\n" +
            "  float rs = 1.075 + ringW * pow(xr / ringWs, uRing.w);\n" +
            "  vec2 pr = (uImg.xy + vec2(sin(theta), -cos(theta)) * rs * uImg.z) / uLook.xy;\n" +
            "  vec3 c = textureLod(uTex, pr, uLook.w).rgb;\n" +
            "  c += textureLod(uTex, pr, 3.5).rgb * 0.22;\n" +
            "  float l = max(c.r, max(c.g, c.b));\n" +
            "  c = mix(c, l * vec3(1.0, 0.95, 0.86), 0.6) * uLook.z;\n" +
            "  float a = clamp(max(c.r, max(c.g, c.b)) * 0.9, 0.0, 1.0);\n" +
            "  float keep = smoothstep(0.995, 1.004, qs);\n" +
            "  c *= keep;\n" +
            "  a *= keep;\n" +
            "  c += vec3(photon * 0.95);\n" +
            "  a = max(a, photon);\n" +
            "  c = min(c, vec3(1.0)) * vis;\n" +
            "  a *= vis;\n" +
            "  outColor = vec4(c * (1.0 - body), a * (1.0 - body) + body);\n" +
            "}\n";

    private static final String MESH_VERTEX =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform vec2 uView;\n" +
            "uniform vec4 uHole;\n" +   // center x, y, horizon radius, time since swallow start
            "uniform vec4 uFlow;\n" +   // speed, strength, reach, ramp
            "uniform vec4 uExtra;\n" +  // sink, swirl, shape, acceleration
            "uniform vec4 uFade;\n" +   // fade start, fade end (radii), blur (px), pull while opening
            "uniform vec4 uFrame;\n" +  // x, y, w, h
            "uniform vec4 uGrid;\n" +   // columns, rows
            "out vec2 vUV;\n" +
            "out float vAlpha;\n" +
            "out float vLod;\n" +
            "out float vHeat;\n" +
            "float travelTime(float x) {\n" +
            "  float reach = max(uFlow.z, 1.0);\n" +
            "  return (x + reach * log(1.0 + uFlow.y * exp(-x / reach))) / max(uFlow.x, 1.0);\n" +
            "}\n" +
            "float easedTime(float tau, float ramp, float accel) {\n" +
            "  float e = ramp > 0.001 ? tau - ramp * (1.0 - exp(-tau / ramp)) : tau;\n" +
            "  return accel > 0.001 ? (exp(accel * e) - 1.0) / accel : e;\n" +
            "}\n" +
            "float shapeFactor(float rho, float cosPhi, float horizon, float shape) {\n" +
            "  float w = smoothstep(horizon * 1.0, horizon * 4.0, rho);\n" +
            "  return pow(cosPhi, shape * w);\n" +
            "}\n" +
            "void main() {\n" +
            "  int cols = int(uGrid.x);\n" +
            "  int vid = gl_VertexID;\n" +
            "  int cell = vid / 6;\n" +
            "  int k = vid - cell * 6;\n" +
            "  int cx = (k == 1 || k == 3 || k == 4) ? 1 : 0;\n" +
            "  int cy = (k == 2 || k == 5 || k == 4) ? 1 : 0;\n" +
            "  vec2 uv = vec2(float(cell % cols + cx) / uGrid.x, float(cell / cols + cy) / uGrid.y);\n" +
            "  vec2 p = uFrame.xy + uv * uFrame.zw;\n" +
            "  {\n" +
            "    vec2 dp = p - uHole.xy;\n" +
            "    float dl = max(length(dp), 0.001);\n" +
            "    float R = max(uHole.z, 1.0);\n" +
            "    float gap = max(dl - R, 0.0);\n" +
            "    float pull = min(uFade.w * R * 0.7 * exp(-gap / (R * 1.4)), gap * 0.6);\n" +
            "    vec2 dir = normalize(mix(dp / dl, vec2(0.0, 1.0), 0.6));\n" +
            "    p -= dir * pull;\n" +
            "  }\n" +
            "  vec2 center = uHole.xy;\n" +
            "  float horizon = max(uHole.z, 1.0);\n" +
            "  float sink = uExtra.x;\n" +
            "  vec2 d = p - center;\n" +
            "  float rho = max(length(d), 0.001);\n" +
            "  float cosPhi = clamp(d.y / rho, 0.65, 1.0);\n" +
            "  float height = rho * shapeFactor(rho, cosPhi, horizon, uExtra.z) - horizon;\n" +
            "  float t = easedTime(max(uHole.w, 0.0), uFlow.w, uExtra.w);\n" +
            "  float reach = max(uFlow.z, 1.0);\n" +
            "  float speed = max(uFlow.x, 1.0);\n" +
            "  float goal = travelTime(height) - t;\n" +
            "  float landed = height - speed * t;\n" +
            "  for (int i = 0; i < 8; i++) {\n" +
            "    landed = clamp(landed, -sink - 40.0, height);\n" +
            "    float slope = 1.0 / (speed * (1.0 + uFlow.y * exp(-landed / reach)));\n" +
            "    landed -= (travelTime(landed) - goal) / slope;\n" +
            "  }\n" +
            "  landed = clamp(landed, -sink, height);\n" +
            "  float rhoNew = (landed + horizon) / pow(cosPhi, uExtra.z);\n" +
            "  for (int i = 0; i < 4; i++) {\n" +
            "    rhoNew = (landed + horizon) / shapeFactor(rhoNew, cosPhi, horizon, uExtra.z);\n" +
            "  }\n" +
            "  float progress = clamp(1.0 - (landed + sink) / max(height + sink, 1.0), 0.0, 1.0);\n" +
            "  float angle = atan(d.y, d.x) + uExtra.y * progress * progress;\n" +
            "  vec2 q = center + vec2(cos(angle), sin(angle)) * rhoNew;\n" +
            "  float rr = rhoNew / horizon;\n" +
            "  float heat = 1.0 - smoothstep(uFade.y, uFade.x, rr);\n" +
            "  float visible = smoothstep(uFade.y, uFade.y + 0.18, rr);\n" +
            "  if (landed <= -sink + 0.5) { visible = 0.0; }\n" +
            "  float maxLod = log2(max(uFade.z, 1.0));\n" +
            "  gl_Position = vec4(q.x / uView.x * 2.0 - 1.0, 1.0 - q.y / uView.y * 2.0, 0.0, 1.0);\n" +
            "  vUV = uv;\n" +
            "  vAlpha = visible;\n" +
            "  vLod = heat * heat * maxLod;\n" +
            "  vHeat = heat;\n" +
            "}\n";

    private static final String MESH_FRAGMENT =
            "#version 300 es\n" +
            "precision highp float;\n" +
            "uniform sampler2D uTex;\n" +
            "in vec2 vUV;\n" +
            "in float vAlpha;\n" +
            "in float vLod;\n" +
            "in float vHeat;\n" +
            "out vec4 outColor;\n" +
            "void main() {\n" +
            "  vec4 color = textureLod(uTex, vUV, vLod);\n" +
            "  float h = vHeat;\n" +
            "  vec3 glow = mix(vec3(1.0, 0.84, 0.55), vec3(1.0, 0.97, 0.9), h * h);\n" +
            "  color.rgb = mix(color.rgb, glow * color.a, smoothstep(0.0, 0.85, h) * 0.85) + glow * color.a * (h * 0.35);\n" +
            "  outColor = color * vAlpha;\n" +
            "}\n";

    private static class DrawingThread extends DispatchQueue {

        private static final int DO_DRAW = 0;
        private static final int DO_RESIZE = 1;
        private static final int DO_KILL = 2;
        private static final int DO_REQUEST = 3;

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final SurfaceTexture surfaceTexture;
        private final BlackHoleDeleteEffect owner;
        private int width, height;
        public volatile boolean running;

        private EGL10 egl;
        private EGLDisplay eglDisplay;
        private EGLConfig eglConfig;
        private EGLSurface eglSurface;
        private EGLContext eglContext;

        private int backdropProgram, overlayProgram, meshProgram;
        private int listTexture, backdropTexture, diskTexture;
        private int diskWidth, diskHeight;
        private int vao;

        private Run run;
        private Request request;
        private double time;
        private long lastTick;
        private boolean firstFrameReported, finishedReported;
        private final State state = new State();

        DrawingThread(SurfaceTexture surfaceTexture, BlackHoleDeleteEffect owner, int width, int height) {
            super("BlackHoleDeleteEffect.DrawingThread", false);
            this.surfaceTexture = surfaceTexture;
            this.owner = owner;
            this.width = width;
            this.height = height;
            start();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case DO_DRAW:
                    draw();
                    break;
                case DO_RESIZE:
                    width = msg.arg1;
                    height = msg.arg2;
                    if (initialized) {
                        GLES30.glViewport(0, 0, width, height);
                    }
                    break;
                case DO_KILL:
                    killInternal();
                    break;
                case DO_REQUEST:
                    applyRequest((Request) msg.obj);
                    break;
            }
        }

        private boolean initialized;

        @Override
        public void run() {
            try {
                initGl();
            } catch (Throwable e) {
                FileLog.e(e);
                initialized = false;
                failInternal();
                return;
            }
            Request first;
            synchronized (lock) {
                ready = true;
                first = queued;
                queued = null;
            }
            if (first != null) {
                applyRequest(first);
            }
            super.run();
        }

        private final Object lock = new Object();
        private Request queued;
        private volatile boolean ready;

        void setRequest(Request r) {
            synchronized (lock) {
                if (!ready) {
                    queued = r;
                    return;
                }
            }
            if (alive.get()) {
                sendMessage(Message.obtain(null, DO_REQUEST, r), 0);
            }
        }

        void requestDraw() {
            if (ready && alive.get()) {
                sendMessage(Message.obtain(null, DO_DRAW), 0);
            }
        }

        void resize(int w, int h) {
            if (alive.get()) {
                sendMessage(Message.obtain(null, DO_RESIZE, w, h), 0);
            }
        }

        void kill() {
            running = false;
            if (alive.get()) {
                try {
                    sendMessage(Message.obtain(null, DO_KILL), 0);
                } catch (Exception ignore) {
                }
            }
        }

        private void failInternal() {
            owner.fail();
            killInternal();
        }

        private void killInternal() {
            if (!alive.getAndSet(false)) {
                return;
            }
            running = false;
            try {
                if (egl != null && eglDisplay != null) {
                    egl.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT);
                    if (eglSurface != null) egl.eglDestroySurface(eglDisplay, eglSurface);
                    if (eglContext != null) egl.eglDestroyContext(eglDisplay, eglContext);
                    egl.eglTerminate(eglDisplay);
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (request != null) {
                BlackHoleDeleteEffect.recycle(request);
                request = null;
            }
            Looper looper = Looper.myLooper();
            if (looper != null) {
                looper.quit();
            }
        }

        private void initGl() {
            egl = (EGL10) EGLContext.getEGL();
            eglDisplay = egl.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL10.EGL_NO_DISPLAY) {
                throw new RuntimeException("no display");
            }
            int[] version = new int[2];
            if (!egl.eglInitialize(eglDisplay, version)) {
                throw new RuntimeException("eglInitialize failed");
            }
            int[] configAttributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] num = new int[1];
            if (!egl.eglChooseConfig(eglDisplay, configAttributes, configs, 1, num) || num[0] <= 0) {
                throw new RuntimeException("eglChooseConfig failed");
            }
            eglConfig = configs[0];
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
            eglContext = egl.eglCreateContext(eglDisplay, eglConfig, EGL10.EGL_NO_CONTEXT, contextAttributes);
            if (eglContext == null || eglContext == EGL10.EGL_NO_CONTEXT) {
                throw new RuntimeException("eglCreateContext failed");
            }
            eglSurface = egl.eglCreateWindowSurface(eglDisplay, eglConfig, surfaceTexture, null);
            if (eglSurface == null || eglSurface == EGL10.EGL_NO_SURFACE) {
                throw new RuntimeException("eglCreateWindowSurface failed");
            }
            if (!egl.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new RuntimeException("eglMakeCurrent failed");
            }
            backdropProgram = program(SCREEN_VERTEX, BACKDROP_FRAGMENT);
            overlayProgram = program(OVERLAY_VERTEX, OVERLAY_FRAGMENT);
            meshProgram = program(MESH_VERTEX, MESH_FRAGMENT);
            int[] v = new int[1];
            GLES30.glGenVertexArrays(1, v, 0);
            vao = v[0];
            GLES30.glViewport(0, 0, width, height);
            initialized = true;
        }

        private static int program(String vs, String fs) {
            int v = shader(GLES30.GL_VERTEX_SHADER, vs);
            int f = shader(GLES30.GL_FRAGMENT_SHADER, fs);
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, v);
            GLES30.glAttachShader(p, f);
            GLES30.glLinkProgram(p);
            int[] status = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0);
            if (status[0] != GLES30.GL_TRUE) {
                throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(p));
            }
            GLES30.glDeleteShader(v);
            GLES30.glDeleteShader(f);
            return p;
        }

        private static int shader(int type, String source) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, source);
            GLES30.glCompileShader(s);
            int[] status = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0);
            if (status[0] != GLES30.GL_TRUE) {
                throw new RuntimeException("compile: " + GLES30.glGetShaderInfoLog(s));
            }
            return s;
        }

        private int texture(Bitmap bitmap, boolean mipmaps, int wrap) {
            int[] t = new int[1];
            GLES30.glGenTextures(1, t, 0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0]);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, mipmaps ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap);
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0);
            if (mipmaps) {
                GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
            }
            return t[0];
        }

        private void applyRequest(Request r) {
            if (!alive.get() || !initialized) {
                BlackHoleDeleteEffect.recycle(r);
                return;
            }
            try {
                request = r;
                listTexture = texture(r.list, true, GLES30.GL_CLAMP_TO_EDGE);
                if (r.backdrop != null) {
                    backdropTexture = texture(r.backdrop, false, GLES30.GL_MIRRORED_REPEAT);
                }
                diskTexture = texture(r.disk, true, GLES30.GL_CLAMP_TO_EDGE);
                diskWidth = r.disk.getWidth();
                diskHeight = r.disk.getHeight();
                // Pixels are on the GPU now.
                r.list.recycle();
                r.list = null;
                if (r.backdrop != null) {
                    r.backdrop.recycle();
                    r.backdrop = null;
                }
                r.disk.recycle();
                r.disk = null;

                Config c = new Config();
                float iw = AndroidUtilities.dp(16), ih = AndroidUtilities.dp(16);
                run = new Run(c, r.listLeft, r.listTop, r.listLeft + r.listWidth, r.listTop + r.listHeight, r.holeX, r.holeY, iw, ih);
                time = 0;
                lastTick = System.nanoTime();
                firstFrameReported = false;
                finishedReported = false;
                running = true;
            } catch (Throwable e) {
                FileLog.e(e);
                failInternal();
            }
        }

        private void draw() {
            if (!alive.get() || !initialized || run == null || !running) {
                return;
            }
            long now = System.nanoTime();
            double dt = (now - lastTick) / 1e9;
            lastTick = now;
            // Capped step: after a dropped frame the animation continues instead of jumping ahead.
            time += Math.min(dt, 1.0 / 30);

            if (time >= run.total()) {
                time = run.total();
            }
            stateAt(time, run, state);
            Config c = run.config;
            double tau = time - run.swallowStart;

            GLES30.glViewport(0, 0, width, height);
            GLES30.glDisable(GLES30.GL_DEPTH_TEST);
            GLES30.glDisable(GLES30.GL_CULL_FACE);
            GLES30.glClearColor(0, 0, 0, 0);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            GLES30.glBindVertexArray(vao);

            float w = width, h = height;

            // 1. Backdrop (opaque)
            if (backdropTexture != 0) {
                GLES30.glDisable(GLES30.GL_BLEND);
                GLES30.glUseProgram(backdropProgram);
                u2(backdropProgram, "uView", w, h);
                u4(backdropProgram, "uHole", (float) state.cx, (float) state.cy, (float) state.radius, (float) state.presence);
                u4(backdropProgram, "uShape", (float) state.halfW, (float) state.halfH, (float) (state.presence * c.lensStrength), (float) (state.presence * c.backgroundSwirl));
                u4(backdropProgram, "uSpace", (float) (state.presence * c.spaceDarkness), (float) c.spaceSize, (float) (state.time * state.presence * c.backgroundSpin), (float) c.glow);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, backdropTexture);
                GLES30.glUniform1i(GLES30.glGetUniformLocation(backdropProgram, "uTex"), 0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            }

            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA);

            // 2. Mesh (messages)
            Request r = request;
            if (r != null && listTexture != 0) {
                int cols = Math.max(4, (int) (r.listWidth / AndroidUtilities.dp(6)));
                int rows = Math.max(3, (int) (r.listHeight / AndroidUtilities.dp(6)));
                GLES30.glUseProgram(meshProgram);
                u2(meshProgram, "uView", w, h);
                u4(meshProgram, "uHole", (float) state.cx, (float) state.cy, (float) state.radius, (float) tau);
                u4(meshProgram, "uFlow", (float) run.flowSpeed, (float) c.pullStrength, (float) (c.pullReach * c.holeRadius), (float) c.flowRamp);
                u4(meshProgram, "uExtra", (float) c.sinkDepth, 0f, (float) c.arcShape, (float) c.flowAccel);
                u4(meshProgram, "uFade", (float) c.fadeOuter, (float) Math.min(c.fadeInner, c.fadeOuter - 0.05), (float) c.fadeBlur, (float) (state.presence * c.emergePull));
                u4(meshProgram, "uFrame", r.listLeft, r.listTop, r.listWidth, r.listHeight);
                u4(meshProgram, "uGrid", cols, rows, 0, 0);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, listTexture);
                GLES30.glUniform1i(GLES30.glGetUniformLocation(meshProgram, "uTex"), 0);
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, cols * rows * 6);
            }

            // 3. Overlay (black shape + ring)
            GLES30.glUseProgram(overlayProgram);
            double bodyOpacity = smoothstep(0, 0.06, state.presence);
            u2(overlayProgram, "uView", w, h);
            u4(overlayProgram, "uHole", (float) state.cx, (float) state.cy, (float) state.radius, (float) smoothstep(0, 0.45, state.presence));
            u4(overlayProgram, "uShape", (float) state.halfW, (float) state.halfH, (float) c.innerBoost, (float) c.innerWidth);
            u4(overlayProgram, "uImg", 1000f, 451f, 223f, (float) c.ringSpeed);
            u4(overlayProgram, "uLook", diskWidth, diskHeight, (float) c.ringBrightness, (float) c.ringSoftness);
            u4(overlayProgram, "uRing", 1.0f, 0.35f, (float) c.ringSqueeze, (float) c.ringGamma);
            u4(overlayProgram, "uExtra", (float) bodyOpacity, (float) state.time, 0f, 0f);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, diskTexture);
            GLES30.glUniform1i(GLES30.glGetUniformLocation(overlayProgram, "uTex"), 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

            egl.eglSwapBuffers(eglDisplay, eglSurface);

            if (!firstFrameReported) {
                firstFrameReported = true;
                final Listener l = owner.listener;
                if (l != null) {
                    AndroidUtilities.runOnUIThread(l::onFirstFrame);
                }
            }
            if (!finishedReported && time >= run.total()) {
                finishedReported = true;
                running = false;
                owner.finished = true;
                final Listener l = owner.listener;
                owner.listener = null;
                if (l != null) {
                    AndroidUtilities.runOnUIThread(l::onFinished);
                }
            }
        }

        private static void u2(int p, String n, float a, float b) {
            GLES30.glUniform2f(GLES30.glGetUniformLocation(p, n), a, b);
        }

        private static void u4(int p, String n, float a, float b, float c, float d) {
            GLES30.glUniform4f(GLES30.glGetUniformLocation(p, n), a, b, c, d);
        }
    }
}
