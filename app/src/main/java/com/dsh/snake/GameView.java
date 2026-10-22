package com.dsh.snake;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.WindowInsets;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Random;

/**
 * Snake 2D - drawn on a SurfaceView with a dedicated render thread.
 *
 * The simulation runs on a grid (20 columns, dynamic row count so the board
 * fills the phone screen) with a time based tick. Rendering interpolates every
 * segment between its previous and current cell, so movement stays silky smooth
 * even though the logic steps cell by cell.
 *
 * Two ways to play are offered on the start screen:
 *   - "CHƠI"    manual, swipe controls
 *   - "TỰ ĐỘNG" the AutoPilot plays by itself (watch mode)
 */
public class GameView extends SurfaceView implements SurfaceHolder.Callback, Runnable {

    private static final int COLS = 20;
    private static final int ROWS_MIN = 12;
    private static final int ROWS_MAX = 46;

    private static final int STATE_READY = 0;
    private static final int STATE_RUNNING = 1;
    private static final int STATE_PAUSED = 2;
    private static final int STATE_DEAD = 3;
    private static final int STATE_WON = 4;

    private static final int MODE_MANUAL = 0;
    private static final int MODE_AUTO = 1;

    private static final String PREFS = "snake_prefs";
    private static final String KEY_HIGH = "high_score";
    private static final String KEY_SPEED = "speed_index";
    /** Tick-interval multipliers cycled by the speed − / + buttons. */
    private static final float[] SPEEDS = {0.5f, 0.75f, 1f, 1.5f, 2f, 3f};

    private static final int C_HEAD = 0xFFB9FFD8;
    private static final int C_MID = 0xFF35D07F;
    private static final int C_TAIL = 0xFF11784F;

    /** Snake palette; the colour advances on every death. */
    private static final int[] SNAKE_COLORS = {
            0xFF35D07F, 0xFF4FA8FF, 0xFFFFB74D, 0xFFB388FF, 0xFFFF6E8A, 0xFF4DD0E1,
    };

    private static final float SWIPE_SLOP_DP = 20f;

    /** 60 fps frame budget (16.67 ms); the render thread paces itself to this. */
    private static final long FRAME_NS = 16_666_667L;
    /** Safe-search mode restarts a run that has not eaten for this many ticks: at the
     * end of the board the heuristic can circle forever, which looks like a freeze. */
    private static final int STALL_LIMIT = 900;

    /** 4-neighbour offsets used by the manual route helper. */
    private static final int[][] NEIGH = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private final SurfaceHolder holder;
    private Thread thread;
    private volatile boolean running;
    private volatile boolean surfaceReady;

    private final float density;
    private float insetTop;

    private int rows;
    private boolean gridDecided;

    private float boardLeft;
    private float boardTop;
    private float cell;
    private float boardW;
    private float boardH;
    private final RectF boardRect = new RectF();
    private final Path boardPath = new Path();
    private final RectF pauseBtn = new RectF();
    private final RectF startBtn = new RectF();
    private final RectF autoBtn = new RectF();
    private final RectF restartBtn = new RectF();
    private final RectF autoRestartBtn = new RectF();
    private final RectF pathRestartBtn = new RectF();
    private final RectF mangoRestartBtn = new RectF();
    private final RectF resumeBtn = new RectF();
    private final RectF autoResumeBtn = new RectF();
    private final RectF pathResumeBtn = new RectF();
    private final RectF mangoResumeBtn = new RectF();
    private final RectF pathBtn = new RectF();
    private final RectF mangoBtn = new RectF();

    /* --- manual mode: waypoint route editor --------------------------- */
    /** Planned route cells (in order) for the manual mode. */
    private final ArrayList<Integer> waypoints = new ArrayList<>();
    /** Waypoint being edited (-1 = none); shows the arrow pad. */
    private int selectedWp = -1;
    /** True while the snake is walking the planned route. */
    private boolean manualMoving;
    private final RectF moveBtn = new RectF();
    private final RectF padUpBtn = new RectF();
    private final RectF padDownBtn = new RectF();
    private final RectF padLeftBtn = new RectF();
    private final RectF padRightBtn = new RectF();
    private final RectF padDelBtn = new RectF();
    private final RectF padAutoBtn = new RectF();
    private final RectF padListBtn = new RectF();
    private final RectF padFollowBtn = new RectF();
    private final RectF dumpBtn = new RectF();
    private final RectF clearBtn = new RectF();

    /* --- settings screen: speed + algorithm, opened from home / pause --- */
    private boolean settingsOpen;
    private final RectF settingsBtn = new RectF();
    private final RectF settingsPanel = new RectF();
    private final RectF settingsCloseBtn = new RectF();
    private final RectF[] settingsSpeedBtn = new RectF[SPEEDS.length];
    private final RectF[] settingsAlgoBtn = new RectF[6];
    private float settingsAlgoLabelY;
    private Paint settingsDimPaint;

    private static final int ALG_OFF = 0;
    private static final int ALG_TAIL = 1;
    private static final int ALG_BFS = 2;
    private static final int ALG_SC = 3;
    private static final int ALG_ZZ = 4;
    private static final int ALG_RND = 5;
    private static final String[] ALGO_LABELS = {"TẮT", "TAIL", "BFS", "SC", "ZZ", "RND"};
    private static final String[] SPEED_LABELS = {"0.5×", "0.75×", "1×", "1.5×", "2×", "3×"};
    private final RectF padPanel = new RectF();
    private final RectF resetBtn = new RectF();

    /* --- FOLLOW: generated waypoint route following the tail to fruit -- */
    private boolean followRouteActive;

    /* --- drag & drop waypoint list dialog ------------------------------ */
    private boolean wpListOpen;
    private int listDragIndex = -1;
    private boolean listDragging;
    private float listDownY;
    private float listRowH = 0f;
    private final RectF listPanel = new RectF();
    private final RectF listCloseBtn = new RectF();

    private final RectF autoBadge = new RectF();
    private final RectF tmpRect = new RectF();
    private float pauseCx;
    private float pauseCy;
    private float pauseR;
    private float hudTop;
    private float hudH;
    private float bottomH;
    private float hintTop;
    /** Short-lived message shown in the bottom hint line (AUTO route result). */
    private volatile String noticeText = "";
    private volatile long noticeUntilMs;
    private volatile boolean noticeError;
    /** Index into SPEEDS: the runner divides the tick interval by it. */
    private int speedIndex = 2;
    /** AI toggle: safe shortcuts that may leave the Hamiltonian cycle. */
    private boolean smartShortcuts = true;
    /** AI toggle: zig-zag detour while the head is next to the tail. */
    private boolean zigzagOn;

    private final ArrayDeque<Integer> body = new ArrayDeque<>();
    private final ArrayDeque<int[]> dirQueue = new ArrayDeque<>();
    private int dirX = 1;
    private int dirY = 0;
    private int foodCell = -1;
    /** Current body cells, head first: the chain the renderer draws. */
    private int[] curCells = new int[0];
    /** Manual planning pause: no step is coming, so the drawing is settled. */
    private boolean planPaused;
    /** Head rotation: from the angle of the last cell move to the angle of this one. */
    private float headAngleFrom;
    private float headAngleTo;
    /** Just the interpolated value used by the drawing (1 tick = one cell of travel). */
    private float headAngleDeg;
    /** Predicted next step (unit direction) so the head can be drawn leading into it. */
    private int nextDx;
    private int nextDy;
    private boolean hasNext;
    /** True for the tick after the apple spawned: it fades in instead of popping. */
    private boolean foodFresh;
    private int score;
    private int highScore;
    private int deaths;
    private int snakeColor;
    private int state = STATE_READY;
    private float tickAccum;
    private float currentInterval = 168f;
    private long lastEatMs;
    private float deadWait;
    private float foodTimer;
    private float winWait;
    private float animTime;
    private float deadTime;
    private final Random rnd = new Random();

    private int mode = MODE_MANUAL;
    private boolean autoMode;
    /** Auto mode the player left when taking manual control (for resume). */
    private int lastAutoMode = -1;
    private AutoPilot pilot;
    /** Autoplay with a fresh random Hamiltonian cycle each run (RND toggle). */
    private AutoPilot randomPilot;
    /** RND toggle: autoplay uses the random cycle instead of the fixed snake. */
    private boolean randomCycleOn;
    /** RND was selected under a live snake not on its cycle: apply it on the next run. */
    private boolean rndCyclePending;
    /** Engine kept running until the pending RND cycle can be applied on a fresh run. */
    private AutoPilot rndFallbackPilot;
    /** BFS toggle: autoplay uses the verified BFS + simulation engine instead. */
    private boolean bfsOn;
    /** The verified ("guaranteed") pilot: BFS to the fruit, whole-snake simulation. */
    private AutoPilot bfsPilot;
    /** Safe-search toggle: greedy BFS + tail chasing instead of the cycle. */
    private boolean tailChaseOn;
    /** The safe-search pilot (zig-zag tail chasing, never on a Hamiltonian cycle). */
    private AutoPilot tailPilot;
    /** Ticks without eating, used to restart a stalled safe-search run. */
    private int stallTicks;

    private final ArrayList<Particle> particles = new ArrayList<>();
    private final ArrayList<Popup> popups = new ArrayList<>();

    private Bitmap staticBmp;
    private Canvas staticCanvas;
    private Paint blitPaint;

    private final Path bodyPath = new Path();
    private final RectF tmpOval = new RectF();

    private Paint bgPaint;
    private Paint glowPaint;
    private Paint boardPaint;
    private Paint boardStroke;
    private Paint gridPaint;
    private Paint bodyPaint;
    private Paint bodyShadowPaint;
    private Paint bodyDarkPaint;
    private Paint bodyLightPaint;
    private Paint bodySpecPaint;
    private Paint bodyPlatePaint;
    private Paint headSpecPaint;
    private Paint headChinPaint;
    private Paint contactShadowPaint;
    private final PathMeasure bodyMeasure = new PathMeasure();
    private final float[] measurePos = new float[2];
    private final float[] measureTan = new float[2];
    private Paint headFillPaint;
    private Paint headOutlinePaint;
    private Paint headGlowPaint;
    private Paint nostrilPaint;
    private Paint tonguePaint;
    private Paint eyeWhitePaint;
    private Paint eyePupilPaint;
    private Paint eyePupilStroke;
    private Paint eyeShinePaint;
    private Paint foodGlowPaint;
    private Paint foodPaint;
    private Paint foodShinePaint;
    private Paint stemPaint;
    private Paint leafPaint;
    private Paint hudPillPaint;
    private Paint hudPillStroke;
    private Paint labelPaint;
    private Paint scorePaint;
    private Paint hintPaint;
    private Paint pauseBgPaint;
    private Paint pauseStroke;
    private Paint pauseIconPaint;
    private Paint dimPaint;
    private Paint cardPaint;
    private Paint cardStroke;
    private Paint cardTitlePaint;
    private Paint cardBigPaint;
    private Paint cardSubPaint;
    private Paint buttonPaint;
    private Paint buttonTextPaint;
    private Paint buttonTextSecondaryPaint;
    private Paint secondaryFillPaint;
    private Paint secondaryStrokePaint;
    private Paint autoBadgePaint;
    private Paint autoBadgeStroke;
    private Paint autoBadgeText;
    private Paint autoDotPaint;
    private Paint popupPaint;
    private Paint particlePaint;
    private Paint readyTitlePaint;
    private Paint readySubPaint;
    private Paint readyHintPaint;
    private Paint pausedTitlePaint;
    private Paint previewPaint;
    private Paint wpFillPaint;
    private Paint wpStrokePaint;
    private Paint wpSelPaint;
    private Paint wpTextPaint;
    private Paint padBgPaint;
    private Paint padBtnPaint;
    private Paint padBtnStrokePaint;
    private Paint padIconPaint;
    private Paint padDelPaint;
    private Paint padLabelPaint;
    private Paint listRowPaint;
    private Paint listNumPaint;
    private Paint listTitlePaint;
    private Paint listCardPaint;
    private Paint listCardStroke;

    private Paint ghostBodyPaint;
    private Paint ghostHeadPaint;

    private float downX;
    private float downY;
    private boolean gestureConsumed;
    private boolean tapOnPause;
    private float[] drawnPts = new float[0];
    /** Draw through SurfaceHolder.lockHardwareCanvas() when the device allows it. */
    private boolean hardwareCanvas = true;
    private int drawnCount;
    private Shader greenShader;
    private float greenShaderTop = Float.NaN;
    private float greenShaderBottom = Float.NaN;
    private SoundFx sound;

    public GameView(Context context) {
        super(context);
        holder = getHolder();
        holder.addCallback(this);
        density = getResources().getDisplayMetrics().density;
        setKeepScreenOn(true);
        setFocusable(true);
        setFocusableInTouchMode(true);

        highScore = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getInt(KEY_HIGH, 0);
        speedIndex = Math.max(0, Math.min(SPEEDS.length - 1,
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .getInt(KEY_SPEED, 2)));
        for (int i = 0; i < settingsSpeedBtn.length; i++) {
            settingsSpeedBtn[i] = new RectF();
        }
        for (int i = 0; i < settingsAlgoBtn.length; i++) {
            settingsAlgoBtn[i] = new RectF();
        }

        buildPaints();
        try {
            sound = new SoundFx(context);
        } catch (Throwable ignored) {
            sound = null;   // sound is cosmetic: never block the game on audio
        }
        resetBoardState();
    }

    /* ------------------------------------------------------------------ */
    /* Setup                                                              */
    /* ------------------------------------------------------------------ */

    private float dp(float v) {
        return v * density;
    }

    /** Round stroke paint used by the body shading passes. */
    private static Paint tubePaint(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
        p.setColor(color);
        return p;
    }

    private void buildPaints() {
        Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
        Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
        Typeface black = Typeface.create("sans-serif-black", Typeface.NORMAL);

        bgPaint = new Paint();
        glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        boardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        boardStroke.setStyle(Paint.Style.STROKE);
        boardStroke.setStrokeWidth(dp(1.5f));
        boardStroke.setColor(0xFF2A4066);

        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(0x12FFFFFF);
        gridPaint.setStrokeWidth(dp(1f));

        particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // One flat colour for the whole body: a single stroke along the centre
        // line. No gradient, no shading and no outline, so only the head and
        // the tail end need any detail.
        bodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPaint.setStyle(Paint.Style.STROKE);
        bodyPaint.setStrokeCap(Paint.Cap.ROUND);
        bodyPaint.setStrokeJoin(Paint.Join.ROUND);
        bodyPaint.setColor(C_MID);

        // Translucent passes drawn over the same centre line turn the flat stroke
        // into a tube: a soft drop shadow, a dark underside, a light top and a thin
        // specular streak (the light comes from above the board).
        bodyShadowPaint = tubePaint(0x66000000);
        bodyDarkPaint = tubePaint(0x00000000);
        bodyLightPaint = tubePaint(0x00FFFFFF);
        bodySpecPaint = tubePaint(0x00FFFFFF);
        bodyPlatePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bodyPlatePaint.setStyle(Paint.Style.STROKE);
        bodyPlatePaint.setStrokeCap(Paint.Cap.ROUND);
        bodyPlatePaint.setColor(0xFF000000);
        headSpecPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headSpecPaint.setColor(0x66FFFFFF);
        headChinPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headChinPaint.setColor(0x40000000);
        contactShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        contactShadowPaint.setColor(0x55000000);

        headFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headFillPaint.setStyle(Paint.Style.FILL);

        headOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headOutlinePaint.setStyle(Paint.Style.STROKE);
        headOutlinePaint.setStrokeWidth(dp(1.6f));
        headOutlinePaint.setColor(0xFF0B3A26);

        headGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        headGlowPaint.setStyle(Paint.Style.FILL);

        nostrilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        nostrilPaint.setColor(0xFF0B3A26);

        tonguePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        tonguePaint.setStyle(Paint.Style.STROKE);
        tonguePaint.setStrokeCap(Paint.Cap.ROUND);
        tonguePaint.setColor(0xFFFF5D6C);

        eyeWhitePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eyeWhitePaint.setColor(0xFFF2FBFF);
        eyePupilPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eyePupilPaint.setColor(0xFF08131F);
        eyePupilStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        eyePupilStroke.setStyle(Paint.Style.STROKE);
        eyePupilStroke.setStrokeCap(Paint.Cap.ROUND);
        eyePupilStroke.setColor(0xFF08131F);
        eyeShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        eyeShinePaint.setColor(0xCCFFFFFF);

        foodGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        foodPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        foodShinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        foodShinePaint.setColor(0x66FFFFFF);
        stemPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        stemPaint.setStyle(Paint.Style.STROKE);
        stemPaint.setStrokeCap(Paint.Cap.ROUND);
        stemPaint.setColor(0xFF4ADE80);
        leafPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        leafPaint.setColor(0xFF2FBF71);

        hudPillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hudPillPaint.setColor(0xFF131F35);
        hudPillStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        hudPillStroke.setStyle(Paint.Style.STROKE);
        hudPillStroke.setStrokeWidth(dp(1f));
        hudPillStroke.setColor(0xFF253A5C);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setTypeface(bold);
        labelPaint.setTextSize(dp(10.5f));
        labelPaint.setColor(0xFF8DA0BF);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setLetterSpacing(0.16f);

        scorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        scorePaint.setTypeface(black);
        scorePaint.setTextSize(dp(24f));
        scorePaint.setColor(0xFFEAF2FF);
        scorePaint.setTextAlign(Paint.Align.CENTER);

        hintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        hintPaint.setTypeface(sans);
        hintPaint.setTextSize(dp(12f));
        hintPaint.setColor(0xFF647699);

        pauseBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pauseBgPaint.setColor(0xFF131F35);
        pauseStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        pauseStroke.setStyle(Paint.Style.STROKE);
        pauseStroke.setStrokeWidth(dp(1f));
        pauseStroke.setColor(0xFF2A4066);
        pauseIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pauseIconPaint.setColor(0xFFB9FFD8);

        dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dimPaint.setColor(0x5C000000);
        settingsDimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        settingsDimPaint.setColor(0xB3000000);


        cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardPaint.setColor(0xF2131F35);
        cardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardStroke.setStyle(Paint.Style.STROKE);
        cardStroke.setStrokeWidth(dp(1.2f));
        cardStroke.setColor(0xFF2E4670);

        cardTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardTitlePaint.setTypeface(bold);
        cardTitlePaint.setTextSize(dp(13f));
        cardTitlePaint.setColor(0xFF8DA0BF);
        cardTitlePaint.setTextAlign(Paint.Align.CENTER);
        cardTitlePaint.setLetterSpacing(0.22f);

        cardBigPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardBigPaint.setTypeface(black);
        cardBigPaint.setTextSize(dp(52f));
        cardBigPaint.setColor(0xFFEAF2FF);
        cardBigPaint.setTextAlign(Paint.Align.CENTER);

        cardSubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        cardSubPaint.setTypeface(sans);
        cardSubPaint.setTextSize(dp(13f));
        cardSubPaint.setColor(0xFF8DA0BF);
        cardSubPaint.setTextAlign(Paint.Align.CENTER);

        buttonPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonTextPaint.setTypeface(bold);
        buttonTextPaint.setTextSize(dp(15.5f));
        buttonTextPaint.setColor(0xFF062012);
        buttonTextPaint.setTextAlign(Paint.Align.CENTER);
        buttonTextPaint.setLetterSpacing(0.06f);

        buttonTextSecondaryPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        buttonTextSecondaryPaint.setTypeface(bold);
        buttonTextSecondaryPaint.setTextSize(dp(15.5f));
        buttonTextSecondaryPaint.setColor(0xFFC8FFDD);
        buttonTextSecondaryPaint.setTextAlign(Paint.Align.CENTER);
        buttonTextSecondaryPaint.setLetterSpacing(0.06f);

        secondaryFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secondaryFillPaint.setColor(0x1F35D07F);
        secondaryStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        secondaryStrokePaint.setStyle(Paint.Style.STROKE);
        secondaryStrokePaint.setStrokeWidth(dp(1.4f));
        secondaryStrokePaint.setColor(0x9935D07F);

        autoBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        autoBadgePaint.setColor(0x261FA868);
        autoBadgeStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        autoBadgeStroke.setStyle(Paint.Style.STROKE);
        autoBadgeStroke.setStrokeWidth(dp(1f));
        autoBadgeStroke.setColor(0x8035D07F);
        autoBadgeText = new Paint(Paint.ANTI_ALIAS_FLAG);
        autoBadgeText.setTypeface(bold);
        autoBadgeText.setTextSize(dp(10.5f));
        autoBadgeText.setColor(0xFFB9FFD8);
        autoBadgeText.setTextAlign(Paint.Align.LEFT);
        autoBadgeText.setLetterSpacing(0.14f);
        autoDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        autoDotPaint.setColor(0xFF6BFFAE);

        popupPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        popupPaint.setTypeface(black);
        popupPaint.setTextSize(dp(17f));
        popupPaint.setColor(0xFFB9FFD8);
        popupPaint.setTextAlign(Paint.Align.CENTER);

        readyTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        readyTitlePaint.setTypeface(black);
        readyTitlePaint.setTextSize(dp(44f));
        readyTitlePaint.setColor(0xFFC8FFDD);
        readyTitlePaint.setTextAlign(Paint.Align.CENTER);
        readyTitlePaint.setLetterSpacing(0.12f);

        readySubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        readySubPaint.setTypeface(bold);
        readySubPaint.setTextSize(dp(15f));
        readySubPaint.setColor(0xFFEAF2FF);
        readySubPaint.setTextAlign(Paint.Align.CENTER);

        readyHintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        readyHintPaint.setTypeface(sans);
        readyHintPaint.setTextSize(dp(12.5f));
        readyHintPaint.setColor(0xFF8DA0BF);
        readyHintPaint.setTextAlign(Paint.Align.CENTER);

        pausedTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pausedTitlePaint.setTypeface(bold);
        pausedTitlePaint.setTextSize(dp(24f));
        pausedTitlePaint.setColor(0xFFC8FFDD);
        pausedTitlePaint.setTextAlign(Paint.Align.CENTER);
        pausedTitlePaint.setLetterSpacing(0.2f);

        blitPaint = new Paint();
        blitPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));

        // Manual mode: faint route preview + waypoint markers + edit pad.
        previewPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        previewPaint.setStyle(Paint.Style.STROKE);
        previewPaint.setColor(0x66C8FFDD);
        previewPaint.setStrokeWidth(dp(2.6f));
        previewPaint.setStrokeCap(Paint.Cap.ROUND);
        previewPaint.setStrokeJoin(Paint.Join.ROUND);
        previewPaint.setPathEffect(new DashPathEffect(new float[]{dp(11f), dp(8f)}, 0f));

        wpFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wpFillPaint.setColor(0x332FC8FF);
        wpStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wpStrokePaint.setStyle(Paint.Style.STROKE);
        wpStrokePaint.setStrokeWidth(dp(2f));
        wpStrokePaint.setColor(0x99B9FFD8);
        wpSelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wpSelPaint.setStyle(Paint.Style.STROKE);
        wpSelPaint.setStrokeWidth(dp(2.6f));
        wpSelPaint.setColor(0xFFFFD479);
        wpTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        wpTextPaint.setTypeface(bold);
        wpTextPaint.setTextSize(dp(10f));
        wpTextPaint.setColor(0xFF0A1526);
        wpTextPaint.setTextAlign(Paint.Align.CENTER);

        padBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        padBgPaint.setColor(0xF0081120);
        padBtnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        padBtnPaint.setColor(0xFF1B2A44);
        padBtnStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        padBtnStrokePaint.setStyle(Paint.Style.STROKE);
        padBtnStrokePaint.setStrokeWidth(dp(1f));
        padBtnStrokePaint.setColor(0x8035D07F);
        padIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        padIconPaint.setColor(0xE6C8FFDD);
        padIconPaint.setStyle(Paint.Style.FILL);
        padDelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        padDelPaint.setStyle(Paint.Style.STROKE);
        padDelPaint.setStrokeWidth(dp(2.6f));
        padDelPaint.setStrokeCap(Paint.Cap.ROUND);
        padDelPaint.setColor(0xE6FF8FA3);
        padLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        padLabelPaint.setTypeface(bold);
        padLabelPaint.setTextSize(dp(10f));
        padLabelPaint.setColor(0xE6C8FFDD);
        padLabelPaint.setTextAlign(Paint.Align.CENTER);

        // Waypoint list dialog (drag & drop reordering)
        listTitlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        listTitlePaint.setTypeface(bold);
        listTitlePaint.setTextSize(dp(13f));
        listTitlePaint.setColor(0xFFC8FFDD);
        listTitlePaint.setTextAlign(Paint.Align.LEFT);
        listTitlePaint.setLetterSpacing(0.06f);
        listRowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        listRowPaint.setTypeface(sans);
        listRowPaint.setTextSize(dp(13f));
        listRowPaint.setColor(0xFFEAF2FF);
        listRowPaint.setTextAlign(Paint.Align.LEFT);
        listNumPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        listNumPaint.setTypeface(bold);
        listNumPaint.setTextSize(dp(12f));
        listNumPaint.setColor(0xFFB9FFD8);
        listNumPaint.setTextAlign(Paint.Align.CENTER);
        listCardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        listCardPaint.setColor(0xF00B1424);
        listCardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        listCardStroke.setStyle(Paint.Style.STROKE);
        listCardStroke.setStrokeWidth(dp(1.4f));
        listCardStroke.setColor(0xFF35507F);


        // Faint orange ghost of the whole snake at the selected waypoint, so the
        // player can see where the head and the tail would be.
        ghostBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ghostBodyPaint.setStyle(Paint.Style.STROKE);
        ghostBodyPaint.setColor(0x59FFB74D);
        ghostBodyPaint.setStrokeCap(Paint.Cap.ROUND);
        ghostBodyPaint.setStrokeJoin(Paint.Join.ROUND);
        ghostHeadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        ghostHeadPaint.setColor(0x8CFFB74D);
    }

    private void rebuildShaders() {
        if (getWidth() <= 0 || getHeight() <= 0 || boardW <= 0) {
            return;
        }
        bgPaint.setShader(new LinearGradient(0, 0, 0, getHeight(),
                0xFF080D1A, 0xFF020306, Shader.TileMode.CLAMP));

        boardPaint.setShader(new LinearGradient(0, boardTop, 0, boardRect.bottom,
                0xFF12233C, 0xFF0A1526, Shader.TileMode.CLAMP));

        glowPaint.setShader(new RadialGradient(boardRect.centerX(), boardRect.centerY(),
                Math.max(boardH, boardW) * 0.72f,
                new int[]{0x1F2A5FD0, 0x00000000}, null, Shader.TileMode.CLAMP));

        float fr = Math.max(cell, 1f);
        foodGlowPaint.setShader(new RadialGradient(0, 0, fr * 1.8f,
                new int[]{0x66FF5468, 0x00FF5468}, null, Shader.TileMode.CLAMP));
        foodPaint.setShader(new RadialGradient(-fr * 0.12f, -fr * 0.14f, fr * 0.44f,
                new int[]{0xFFFFB4A6, 0xFFFF5B66, 0xFFD7263D}, new float[]{0f, 0.55f, 1f},
                Shader.TileMode.CLAMP));

        applySnakeColor();
    }

    /** Paints the snake in the current palette colour (changes after each death). */
    private void applySnakeColor() {
        int c = SNAKE_COLORS[snakeColor % SNAKE_COLORS.length];
        bodyPaint.setColor(c);
        // Colours of the shading passes: a much darker underside, a lighter top and
        // a near-white specular streak. The light always comes from the top-left,
        // which is what makes the flat stroke read as a rounded body.
        bodyDarkPaint.setColor(mix(c, 0xFF04120B, 0.62f));
        bodyLightPaint.setColor(mix(c, 0xFFFFFFFF, 0.55f));
        bodySpecPaint.setColor(mix(c, 0xFFFFFFFF, 0.92f));
        bodyPlatePaint.setColor(mix(c, 0xFF000000, 0.55f));
        float hr = Math.max(cell * 0.42f, 1f);
        int light = mix(c, 0xFFFFFF, 0.70f);
        int mid = mix(c, 0xFFFFFF, 0.20f);
        int dark = mix(c, 0x06120C, 0.35f);
        headFillPaint.setShader(new RadialGradient(-hr * 0.22f, -hr * 0.30f, hr * 1.18f,
                new int[]{light, mid, dark}, new float[]{0f, 0.45f, 1f},
                Shader.TileMode.CLAMP));
        headGlowPaint.setShader(new RadialGradient(0f, 0f, hr * 2.3f,
                new int[]{Color.argb(0x4D, Color.red(c), Color.green(c), Color.blue(c)),
                        0x00000000}, null, Shader.TileMode.CLAMP));
        // The ghost snake shows where the selected waypoint would put the body, and
        // it is drawn over the real snake. Use the complementary hue of the current
        // snake so the two can never share the same colour (the old fixed orange
        // disappeared when the snake itself cycled to orange/yellow).
        ghostBodyPaint.setColor(ghostColorFor(c, 0x73));
        ghostHeadPaint.setColor(ghostColorFor(c, 0xAA));
    }

    /** Ghost colour: complementary hue, vivid and translucent. */
    private static int ghostColorFor(int snake, int alpha) {
        float[] hsv = new float[3];
        Color.colorToHSV(snake, hsv);
        hsv[0] = (hsv[0] + 180f) % 360f;
        hsv[1] = Math.max(0.68f, Math.min(1f, hsv[1]));
        hsv[2] = 1f;
        int rgb = Color.HSVToColor(hsv);
        return Color.argb(alpha, Color.red(rgb), Color.green(rgb), Color.blue(rgb));
    }

    /** Caches the static scene (background, glow, board, grid) into a bitmap. */
    private void rebuildStatic() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || boardW <= 0) {
            return;
        }
        if (staticBmp == null || staticBmp.getWidth() != w || staticBmp.getHeight() != h) {
            if (staticBmp != null) {
                staticBmp.recycle();
            }
            staticBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            staticCanvas = new Canvas(staticBmp);
        }
        Canvas c = staticCanvas;
        c.drawRect(0f, 0f, w, h, bgPaint);
        c.drawCircle(boardRect.centerX(), boardRect.centerY(),
                Math.max(boardW, boardH) * 0.72f, glowPaint);

        c.drawRoundRect(boardRect, dp(18f), dp(18f), boardPaint);
        c.save();
        c.clipPath(boardPath);
        for (int i = 1; i < COLS; i++) {
            float x = boardLeft + i * cell;
            c.drawLine(x, boardTop, x, boardRect.bottom, gridPaint);
        }
        for (int j = 1; j < rows; j++) {
            float y = boardTop + j * cell;
            c.drawLine(boardLeft, y, boardRect.right, y, gridPaint);
        }
        c.restore();
        c.drawRoundRect(boardRect, dp(18f), dp(18f), boardStroke);
    }

    /* ------------------------------------------------------------------ */
    /* Geometry                                                           */
    /* ------------------------------------------------------------------ */

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            android.graphics.Insets i = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            insetTop = Math.max(insetTop, i.top);
        } else {
            insetTop = Math.max(insetTop, insets.getSystemWindowInsetTop());
        }
        computeGeometry();
        return insets;
    }

    /**
     * Top spacing we must keep free for the camera cutout. System bars are
     * hidden, so the status-bar inset is not needed (and would leave a big
     * empty stripe when the bars are gone).
     */
    private float safeTop() {
        WindowInsets wi = getRootWindowInsets();
        if (wi == null) {
            return Math.max(insetTop, dp(10f));
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Math.max(wi.getInsets(WindowInsets.Type.displayCutout()).top, dp(10f));
        }
        return Math.max(wi.getSystemWindowInsetTop(), dp(10f));
    }

    private void computeGeometry() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        float margin = dp(13f);
        // Dedicated control strip under the grid: waypoint pad and the MOVE /
        // pause buttons. Reserving it always keeps the grid size stable when the
        // player switches modes.
        float pb = dp(40f);
        float pg = dp(6f);
        float padH = pb * 3f + pg * 2f;
        bottomH = dp(18f) + padH + dp(8f);
        float cut = safeTop();
        float pillsH = dp(52f);
        // HUD pills live in the top strip (left/right of the camera cutout) and
        // the board starts just below the cutout, so no big empty band remains.
        float topPad = dp(8f);
        float boardAreaTop = Math.max(topPad + pillsH + dp(8f), cut + dp(6f));
        hudH = boardAreaTop - topPad;

        float availW = w - margin * 2f;
        float availH = h - topPad - hudH - bottomH - margin;
        if (availW <= 0f || availH <= 0f) {
            return;
        }

        if (!gridDecided) {
            float guessCell = availW / COLS;
            // Fill the whole playable area: wide screens stay at 20 columns, the
            // row count grows until the board covers the free height.
            int want = (int) Math.floor(availH / Math.max(guessCell, 1f));
            rows = Math.max(ROWS_MIN, Math.min(ROWS_MAX, want));
            gridDecided = true;
            // AUTOPLAY walks a fixed boustrophedon cycle; RND swaps in a fresh random
            // cycle for each run; SC / ZZ change how it chases the fruit.
            pilot = new AutoPilot(COLS, rows, false);
            randomPilot = new AutoPilot(COLS, rows, false, false);
            bfsPilot = new AutoPilot(COLS, rows, false, false, true);
            tailPilot = new AutoPilot(COLS, rows, false, false);
            tailPilot.setTailChase(true);
            applyAiToggles();
            if (state == STATE_READY) {
                resetBoardState();
            }
            ensureBodyInsideGrid();
        }

        cell = Math.min(availW / COLS, availH / rows);
        boardW = cell * COLS;
        boardH = cell * rows;
        boardLeft = (w - boardW) / 2f;
        float contentTop = topPad + hudH;
        float contentH = h - contentTop - bottomH;
        boardTop = contentTop + Math.max(0f, (contentH - boardH) / 2f);
        boardRect.set(boardLeft, boardTop, boardLeft + boardW, boardTop + boardH);
        boardPath.reset();
        boardPath.addRoundRect(boardRect, dp(18f), dp(18f), Path.Direction.CW);

        hudTop = topPad;

        // --- control strip (outside the grid) -----------------------------
        float ctrlTop = boardRect.bottom + dp(6f);
        float padL = boardLeft;
        float padT = ctrlTop + dp(16f);
        padPanel.set(padL - dp(4f), ctrlTop - dp(2f), boardRect.right + dp(4f),
                h - dp(2f));
        hintTop = ctrlTop + dp(11f);

        pauseR = dp(22f);
        pauseCx = boardRect.right - pauseR;
        pauseCy = padT + padH / 2f;
        pauseBtn.set(pauseCx - pauseR * 1.7f, pauseCy - pauseR * 1.7f,
                pauseCx + pauseR * 1.7f, pauseCy + pauseR * 1.7f);

        // MOVE / STOP pill, vertically centred in the strip (manual mode only).
        float mw = dp(112f);
        float mh = dp(40f);
        float mRight = pauseCx - pauseR - dp(14f);
        moveBtn.set(mRight - mw, pauseCy - mh / 2f, mRight, pauseCy + mh / 2f);
        padUpBtn.set(padL + pb + pg, padT, padL + pb * 2f + pg, padT + pb);
        padLeftBtn.set(padL, padT + pb + pg, padL + pb, padT + pb * 2f + pg);
        padRightBtn.set(padL + pb * 2f + pg * 2f, padT + pb + pg,
                padL + pb * 3f + pg * 2f, padT + pb * 2f + pg);
        padDownBtn.set(padL + pb + pg, padT + pb * 2f + pg * 2f,
                padL + pb * 2f + pg, padT + pb * 3f + pg * 2f);
        padDelBtn.set(padL + pb * 2f + pg * 2f, padT,
                padL + pb * 3f + pg * 2f, padT + pb);
        // AUTO sits in the middle of the four arrows; LIST mirrors DELETE.
        padAutoBtn.set(padL + pb + pg, padT + pb + pg,
                padL + pb * 2f + pg, padT + pb * 2f + pg);
        padListBtn.set(padL, padT, padL + pb, padT + pb);
        // FOLLOW lives in the bottom-left cell of the pad; DUMP fills the empty
        // bottom-right cell so it is always reachable without covering the board.
        padFollowBtn.set(padL, padT + 2f * (pb + pg), padL + pb,
                padT + 3f * pb + 2f * pg);
        dumpBtn.set(padL + 2f * (pb + pg), padT + 2f * (pb + pg),
                padL + 3f * pb + 2f * pg, padT + 3f * pb + 2f * pg);
        // CLEAR WP lives in the free area above MOVE / pause and only appears
        // while a route exists.
        float clearW = dp(96f);
        float clearH = dp(32f);
        float clearX = padL + 3f * pb + 2f * pg + dp(12f);
        clearBtn.set(clearX, padT, clearX + clearW, padT + clearH);
        resetBtn.setEmpty();   // restart lives in the pause menu now

        rebuildShaders();
        rebuildStatic();
        Log.d("SnakeGeom", "view=" + w + "x" + h + " inset=" + insetTop + " rows=" + rows
                + " cell=" + cell + " board=(" + boardLeft + "," + boardTop + ")-("
                + boardRect.right + "," + boardRect.bottom + ") hudTop=" + hudTop);
    }

    @Override
    public void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        computeGeometry();
    }

    /** Keeps every body cell inside the grid if the grid had to shrink. */
    private void ensureBodyInsideGrid() {
        if (body.isEmpty()) {
            return;
        }
        boolean needsReset = false;
        for (int c : body) {
            if (c < 0 || c >= COLS * rows) {
                needsReset = true;
                break;
            }
        }
        if (needsReset) {
            resetBoardState();
        }
    }

    /* ------------------------------------------------------------------ */
    /* Lifecycle / render thread                                          */
    /* ------------------------------------------------------------------ */

    @Override
    public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        ensureLoop();
    }

    @Override
    public void surfaceChanged(SurfaceHolder h, int format, int width, int height) {
        surfaceReady = true;
        computeGeometry();
        ensureLoop();
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopLoop();
    }

    private void ensureLoop() {
        if (thread == null) {
            running = true;
            thread = new Thread(this, "snake-render");
            thread.start();
        }
    }

    private void stopLoop() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(400);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void onResumeGame() {
        if (state == STATE_RUNNING) {
            state = STATE_PAUSED;
        }
        ensureLoop();
    }

    public void onPauseGame() {
        if (state == STATE_RUNNING) {
            state = STATE_PAUSED;
        }
        saveHighScore();
    }

    public void release() {
        saveHighScore();
        stopLoop();
        if (sound != null) {
            sound.release();
            sound = null;
        }
        if (staticBmp != null) {
            staticBmp.recycle();
            staticBmp = null;
        }
    }

    @Override
    public void run() {
        // Fixed 60 fps schedule (16.67 ms per frame). The sleep is computed against
        // an absolute deadline, so the render thread keeps a steady cadence instead
        // of "work + 15 ms" (which gave a 25-35 ms frame = the head looked jerky).
        long nextFrame = System.nanoTime();
        long last = nextFrame;
        while (running) {
            nextFrame += FRAME_NS;
            long frameStart = System.nanoTime();
            float dt = Math.min(0.05f, (frameStart - last) / 1_000_000_000f);
            last = frameStart;

            if (surfaceReady) {
                Canvas c = null;
                try {
                    // Hardware canvas: the snake is 5-6 stroked passes over one path
                    // plus up to 90 scale ribs; on the software canvas that costs
                    // 20-35 ms per frame for a long snake (25-40 fps). The GPU canvas
                    // keeps it at 60 fps.
                    // PathEffect (the dashed route preview) only works on a hardware
                    // canvas from API 28 on, so older devices stay on the software path.
                    if (hardwareCanvas && android.os.Build.VERSION.SDK_INT >= 28) {
                        try {
                            c = holder.lockHardwareCanvas();
                        } catch (Throwable t) {
                            hardwareCanvas = false;
                            Log.w("SnakeGeom", "hardware canvas unavailable", t);
                        }
                    }
                    if (c == null) {
                        c = holder.lockCanvas();
                    }
                    if (c != null) {
                        update(dt);
                        render(c);
                        frameCount++;
                    }
                } catch (Exception e) {
                    Log.e("SnakeCrash", "frame failed", e);
                } finally {
                    if (c != null) {
                        try {
                            holder.unlockCanvasAndPost(c);
                        } catch (Exception ignored) {
                            // ignore
                        }
                    }
                }
            }

            long waitNs = nextFrame - System.nanoTime();
            if (waitNs > 0L) {
                long ms = waitNs / 1_000_000L;
                if (ms > 0L) {
                    try {
                        Thread.sleep(ms);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            } else if (waitNs < -FRAME_NS * 4L) {
                // More than four frames behind: resynchronise instead of trying to
                // catch up (catching up would just freeze the picture).
                nextFrame = System.nanoTime();
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Simulation                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * The cycle pilot of the current mode. SHORT PATH now runs the same
     * boustrophedon cycle as AUTO WIN: it fills the board (0 deaths) instead of
     * chasing the fruit greedily, which could never be proven to win.
     */
    private AutoPilot activePilot() {
        if (mode == MODE_AUTO) {
            if (randomCycleOn && randomPilot != null && !rndCyclePending) {
                // RND: the body is already walking this pilot's cycle, so switching
                // the engine under the live snake is safe.
                return randomPilot;
            }
            if (tailChaseOn && tailPilot != null) {
                // TAIL button: safe search - greedy BFS to the fruit while the tail stays
                // reachable, otherwise run away from the tail (zig-zag sweep).
                return tailPilot;
            }
            if (bfsOn && bfsPilot != null) {
                // BFS button: the verified engine (BFS to the fruit + simulation of
                // the whole snake; cycle walk when the shortcut is not proven safe).
                return bfsPilot;
            }
            if (rndCyclePending && rndFallbackPilot != null) {
                // RND was selected mid-run: keep the engine that matches the live body
                // until the new random cycle can be built on a fresh run.
                return rndFallbackPilot;
            }
            return pilot;
        }
        return null;
    }

    private void updateSpeed(float dt) {
        // No acceleration ramp and no "teleport" warp any more: a steady tick
        // interval. It only shrinks when the fruit is far away, so the snake still
        // reaches it within ~3 seconds.
        float base = autoMode ? 60f : 110f;
        // Never step faster than ~half a cell per rendered frame (that is what made
        // the head look like it jumped instead of gliding).
        float floor = autoMode ? 32f : 60f;
        float target = base;
        if (autoMode) {
            int steps = -1;
            if (activePilot() != null) {
                steps = activePilot().getStepsToFood();
            }
            if (steps > 0) {
                target = Math.min(base, 3000f / steps);
            }
            target = Math.max(target, floor);
        }
        currentInterval = Math.max(floor, Math.min(220f, target));
        // Speed − / + scales the tick interval (1x = the model above). The 16 ms
        // clamp keeps the snake from stepping more than once per rendered frame.
        currentInterval = Math.max(16f, Math.min(220f, currentInterval / speedFactor()));
    }

    /** Pushes the two AI toggles into every pilot (they are global settings). */
    /**
     * Restart the run in the current mode, with whatever the toggles currently say
     * (used by the pause menu's CHƠI LẠI button).
     */
    private void restartRun() {
        applyAiToggles();
        restartGame(mode);
        haptic(true);
    }

    private void applyAiToggles() {
        if (pilot != null) {
            pilot.setSmartShortcuts(smartShortcuts);
            pilot.setZigzag(zigzagOn);
        }
        if (randomPilot != null) {
            randomPilot.setSmartShortcuts(smartShortcuts);
            randomPilot.setZigzag(zigzagOn);
        }
        if (bfsPilot != null) {
            bfsPilot.setSmartShortcuts(smartShortcuts);
            bfsPilot.setZigzag(zigzagOn);
        }
        if (tailPilot != null) {
            tailPilot.setTailChase(true);
        }
    }

    /** Current speed multiplier picked with the − / + buttons. */
    private float speedFactor() {
        return SPEEDS[Math.max(0, Math.min(SPEEDS.length - 1, speedIndex))];
    }


    private float interval() {
        return currentInterval;
    }

    /** Keeps the fruit timer in sync (the speed model itself is now fixed). */
    private void onFruitEaten() {
        lastEatMs = android.os.SystemClock.uptimeMillis();
    }

    private void resetBoardState() {
        body.clear();
        clearRoute();
        dirX = 1;
        dirY = 0;
        AutoPilot cyclePilot = activePilot() != null ? activePilot() : pilot;
        if (cyclePilot != null) {
            // Start on the Hamiltonian cycle so the auto pilot's safety
            // invariant (body contiguous on the cycle) holds from move one.
            int[] start = cyclePilot.initialBody(4);
            for (int c : start) {
                body.addLast(c);
            }
            int[] d = cyclePilot.firstDirection(start[0]);
            if (d != null) {
                dirX = d[0];
                dirY = d[1];
            }

        } else {
            int r = Math.max(rows, ROWS_MIN);
            int hx = COLS / 2 + 1;
            int hy = r / 2;
            for (int i = 0; i < 4; i++) {
                body.addLast(hy * COLS + (hx - i));
            }
        }
        dirQueue.clear();
        curCells = cellsToArray();
        stallTicks = 0;
        hasNext = false;
        headAngleTo = (float) Math.toDegrees(Math.atan2(dirY, dirX));
        headAngleFrom = headAngleTo;
        headAngleDeg = headAngleTo;
        score = 0;
        tickAccum = 0f;
        animTime = 0f;
        deadTime = 0f;
        state = STATE_READY;
        particles.clear();
        popups.clear();
        spawnFood();
    }

    private int[] cellsToArray() {
        int[] a = new int[body.size()];
        int i = 0;
        for (int c : body) {
            a[i++] = c;
        }
        return a;
    }

    /** First cycle cells of the active pilot (diagnostics only). */
    private String cyclePreview() {
        AutoPilot p = activePilot();
        if (p == null) {
            return "-";
        }
        int[] c = p.cyclePreview(12);
        StringBuilder sb = new StringBuilder();
        for (int v : c) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(v);
        }
        return sb.toString();
    }

    /** Free-cycle cells ahead of the head (diagnostics only). */
    private int arcSize() {
        AutoPilot p = activePilot();
        if (p == null || body.isEmpty()) {
            return 0;
        }
        return p.arcSize(cellsToArray());
    }

    /** Planned route cells, for the state dump / tests. */
    private String waypointList() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < waypoints.size(); i++) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(waypoints.get(i));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** Steps to the fruit the autoplay is working with (diagnostics only). */
    private int stepsToFoodDiag() {
        AutoPilot p = activePilot();
        if (p != null) {
            return p.getStepsToFood();
        }
        return -1;
    }

    private void startGame(int newMode) {
        if (state == STATE_READY || state == STATE_DEAD || state == STATE_WON) {
            mode = newMode;
            autoMode = newMode != MODE_MANUAL;
            lastAutoMode = autoMode ? newMode : -1;
            clearRoute();
            // The toggles are global: whatever is active is applied to the new run.
            applyAiToggles();
            rndCyclePending = false;
            rndFallbackPilot = null;
            if (newMode == MODE_AUTO && randomCycleOn && randomPilot != null) {
                // RND toggle: every new run gets a fresh random cycle.
                randomPilot.reshuffleRandomMerge();
            }
            if (autoMode) {
                resetBoardState();
            }
            state = STATE_RUNNING;
            tickAccum = -420f;
            dirQueue.clear();
            clearEatLog();
            lastEatMs = 0L;
            clearOverlayButtons();
            if (sound != null) {
                sound.playStart();
            }
        }
    }

    private void restartGame(int newMode) {
        mode = newMode;
        autoMode = newMode != MODE_MANUAL;
        lastAutoMode = autoMode ? newMode : -1;
        rndCyclePending = false;
        rndFallbackPilot = null;
        if (newMode == MODE_AUTO && randomCycleOn && randomPilot != null) {
            randomPilot.reshuffleRandomMerge();
        }
        resetBoardState();
        state = STATE_RUNNING;
        tickAccum = -520f;
        clearEatLog();
        lastEatMs = 0L;
        clearOverlayButtons();
        if (sound != null) {
            sound.playStart();
        }
    }

    /** Clears the safe-search stall counter (new run / mode change). */
    private void resetStall() {
        stallTicks = 0;
    }

    private void pauseGame() {
        if (state == STATE_RUNNING) {
            state = STATE_PAUSED;
            saveHighScore();
            if (sound != null) {
                sound.playTap();
            }
        }
    }

    private void resumeGame() {
        if (state == STATE_PAUSED) {
            state = STATE_RUNNING;
            tickAccum = Math.min(tickAccum, 0f);
            clearOverlayButtons();
            if (sound != null) {
                sound.playTap();
            }
        }
    }

    /**
     * Drops the hit-boxes of menu buttons that are no longer on screen. Without
     * this, the pause menu's CHƠI LẠI rectangle stayed alive after resuming and a
     * later tap on the board at that spot restarted the run out of nowhere.
     */
    private void clearOverlayButtons() {
        startBtn.setEmpty();
        autoBtn.setEmpty();
        resumeBtn.setEmpty();
        autoResumeBtn.setEmpty();
        resetBtn.setEmpty();
        restartBtn.setEmpty();
        autoRestartBtn.setEmpty();
        settingsBtn.setEmpty();
    }

    /**
     * Pause-menu AUTOPLAY: continue the current run with the AI, even when the run
     * was started in manual mode. It must never restart the run; the body, score and
     * fruit stay exactly where they are.
     */
    private void resumeMode(int targetMode) {
        if (mode == targetMode && autoMode) {
            resumeGame();
            return;
        }
        mode = targetMode;
        autoMode = true;
        lastAutoMode = targetMode;
        manualMoving = false;
        dirQueue.clear();
        applyAiToggles();
        resumeGame();
        haptic(false);
    }

    private void update(float dt) {
        animTime += dt;

        updateSpeed(dt);
        dumpState();

        if (state == STATE_RUNNING) {
            boolean planPause = !autoMode && mode == MODE_MANUAL && !manualMoving;
            if (planPause) {
                // Waiting for waypoints: nothing moves, and the drawing must not stay
                // frozen half-way through the last step.
                planPaused = true;
                hasNext = false;     // no step is coming: stop leading the head
                aimStoppedHead();
                tickAccum = 0f;
            } else {
                planPaused = false;
                foodTimer += dt;
                // Steady stepping: no teleport / multi-step warp (it caused the
                // stutter and hid what the snake was really doing).
                tickAccum += dt * 1000f;
                    int guard = 0;
                while (tickAccum >= interval() && state == STATE_RUNNING && guard++ < 40) {
                    tickAccum -= interval();
                    step();
                }
                if (autoMode && tailChaseOn && stallTicks > STALL_LIMIT) {
                    // Safe search can circle at 99% of the board forever: restart the
                    // run instead of freezing on the last couple of cells.
                    logLine("stall restart tail=" + stallTicks + " len=" + body.size());
                    resetStall();
                    restartGame(MODE_AUTO);
                }
            }
        } else if (state == STATE_WON) {
            deadTime = Math.min(0.6f, deadTime + dt);
            winWait += dt;
            if (autoMode && winWait > 4f) {
                restartGame(mode);
            }
        } else if (state == STATE_DEAD) {
            deadTime = Math.min(0.6f, deadTime + dt);
            deadWait += dt;
            // Watch mode restarts itself, so the demo never stops.
            if (autoMode && deadWait > 1.2f) {
                restartGame(mode);
            }
        }

        for (Iterator<Particle> it = particles.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            p.life -= dt;
            p.x += p.vx * dt;
            p.y += p.vy * dt;
            p.vx *= 0.965f;
            p.vy *= 0.965f;
            p.vy += dp(240f) * dt;
            if (p.life <= 0f) {
                it.remove();
            }
        }

        for (Iterator<Popup> it = popups.iterator(); it.hasNext(); ) {
            Popup p = it.next();
            p.life -= dt;
            if (p.life <= 0f) {
                it.remove();
            }
        }
    }

    /** Branch id of the AI move applied on the last tick (diagnostics only). */
    private int lastAiBranch;

    private void step() {
        if (!autoMode && mode == MODE_MANUAL) {
            boolean moved = stepManualPath();
            if (!moved) {
                // No route left: the snake waits for the player.
                return;
            }
        }
        if (autoMode) {
            dirQueue.clear();
            int[] d = null;
            AutoPilot cycle = activePilot();
            if (hasNext) {
                // The move was already decided and safety-checked at the end of the
                // previous tick - the very cell the head has been drawn leading into.

                d = new int[]{nextDx, nextDy};
                hasNext = false;
            } else if (cycle != null) {
                // Every AI mode walks the classic Hamiltonian cycle: forward only,
                // inside the free arc between the head and the tail. SHORT PATH uses
                // the fixed boustrophedon cycle too, so it fills the board (a win)
                // instead of chasing the fruit and trapping itself.
                d = cycle.decide(cellsToArray(), foodCell, dirX, dirY);
                lastAiBranch = cycle.lastBranchId();
            }
            if (d != null) {
                dirX = d[0];
                dirY = d[1];
            }

            // Safety net (cycle modes only): if the chosen step would hit a wall
            // or the body, take the roomiest free neighbour instead of dying.
            if (cycle != null) {
                Integer headPeek = body.peekFirst();
                if (headPeek != null) {
                    int px = headPeek % COLS;
                    int py = headPeek / COLS;
                    int qx = px + dirX;
                    int qy = py + dirY;
                    boolean bad = qx < 0 || qx >= COLS || qy < 0 || qy >= rows;
                    if (!bad) {
                        int cell = qy * COLS + qx;
                        int tailCell = body.peekLast() == null ? -1 : body.peekLast();
                        bad = body.contains(cell) && cell != tailCell;
                    }
                    if (bad) {
                        int[] safe = cycle.fallbackDirection(cellsToArray());
                        if (safe != null) {
                            dirX = safe[0];
                            dirY = safe[1];
                        }
                    }
                }
            }
        } else {
            hasNext = false;    // the player's input decides, not the prediction
            while (!dirQueue.isEmpty()) {
                int[] d = dirQueue.poll();
                if (d[0] == -dirX && d[1] == -dirY) {
                    continue;
                }
                if (d[0] == dirX && d[1] == dirY) {
                    continue;
                }
                dirX = d[0];
                dirY = d[1];
                break;
            }
        }

        Integer headObj = body.peekFirst();
        if (headObj == null) {
            return;
        }
        int head = headObj;
        int hx = head % COLS;
        int hy = head / COLS;
        int nx = hx + dirX;
        int ny = hy + dirY;

        if (nx < 0 || nx >= COLS || ny < 0 || ny >= rows) {
            die(hx, hy);
            return;
        }

        int ni = ny * COLS + nx;
        boolean eats = ni == foodCell;
        int tailBefore = body.peekLast() == null ? -1 : body.peekLast();
        if (!eats) {
            body.pollLast();
        }
        if (body.contains(ni)) {
            // Biting itself is a death, but the run keeps going: the body is cut
            // from the collision cell back to the tail, the death counter grows
            // and the snake changes colour (every mode, not just SHORT PATH).
            logLine("BITE dir=" + dirX + "," + dirY + " head=" + hx + "," + hy
                    + " hit=" + nx + "," + ny + " len=" + body.size()
                    + " food=" + foodCell
                    + " branch=" + mode + " ai=" + lastAiBranch);
            biteSelf(ni);
            registerDeath("bite");
            spawnDeathFx(nx, ny);
            haptic(true);
            curCells = cellsToArray();
            hasNext = false;
            return;
        }
        body.addFirst(ni);

        if (!eats) {
            stallTicks++;
        }
        if (eats) {
            stallTicks = 0;
            score += 10;
            if (score > highScore) {
                highScore = score;
            }
            spawnFood();
            spawnEatFx(nx, ny);
            popups.add(new Popup(nx, ny));
            if (sound != null) {
                sound.playEat();
            }
            haptic(false);
            logLine("EAT head=" + ni + " prevHead=" + head + " tail=" + body.peekLast()
                    + " tailBefore=" + tailBefore + " len=" + body.size());
            logEat();
            onFruitEaten();
        }
        curCells = cellsToArray();
        predictNext();
    }

    /**
     * Decides the next step ahead of time so the renderer can draw the head leading
     * into that cell: the head sits exactly on its real cell at every tick boundary,
     * glides into the next one in between, and the body follows behind it (the tail
     * retires in the same tick, so the drawn length never changes). The next step
     * consumes this exact move, so it costs no extra decide().
     */
    private void predictNext() {
        hasNext = false;
        if (state != STATE_RUNNING || planPaused || body.isEmpty()) {
            // Nothing is going to move (waiting for a waypoint): the drawing stays
            // exactly on the real cells.
            return;
        }
        Integer headObj = body.peekFirst();
        if (headObj == null) {
            return;
        }
        int head = headObj;
        int dx;
        int dy;
        if (autoMode) {
            AutoPilot cycle = activePilot();
            if (cycle == null) {
                return;
            }
            int[] d = cycle.decide(cellsToArray(), foodCell, dirX, dirY);
            lastAiBranch = cycle.lastBranchId();
            if (d != null) {
                dirX = d[0];
                dirY = d[1];
            }
            int[] safe = safeDirection(cycle);
            if (safe == null) {
                return;
            }
            dx = safe[0];
            dy = safe[1];
        } else {
            // Manual play: predict the very move stepManualPath()/the input queue will
            // take, so the head can be drawn leading into it without guessing.
            int[] md = predictManualDir(head);
            if (md == null) {
                return;
            }
            dirX = md[0];
            dirY = md[1];
            dx = md[0];
            dy = md[1];
        }
        int nx = head % COLS + dx;
        int ny = head / COLS + dy;
        if (nx < 0 || nx >= COLS || ny < 0 || ny >= rows) {
            return;
        }
        nextDx = dx;
        nextDy = dy;
        hasNext = true;
        // The capsule turns over exactly one cell move (the tick length), not per frame:
        // at t=0 it points where the last move went, at t=1 exactly where this one goes.
        headAngleFrom = wrap180(headAngleDeg);
        headAngleTo = wrap180((float) Math.toDegrees(Math.atan2(dy, dx)));
    }

    /**
     * While the snake waits for the player, keep the capsule facing the direction the
     * route will take, without moving anything else.
     */
    private void aimStoppedHead() {
        if (body.isEmpty()) {
            return;
        }
        Integer headObj = body.peekFirst();
        if (headObj == null) {
            return;
        }
        int[] md = predictManualDir(headObj);
        if (md == null) {
            return;
        }
        dirX = md[0];
        dirY = md[1];
        headAngleTo = wrap180((float) Math.toDegrees(Math.atan2(md[1], md[0])));
        headAngleFrom = headAngleTo;
    }
    /**
     * The direction the next manual step will take, computed exactly the way
     * stepManualPath() / the input queue pick it (no side effects). Null when the
     * snake is standing still.
     */
    private int[] predictManualDir(int head) {
        int hx = head % COLS;
        int hy = head / COLS;
        if (manualMoving && !waypoints.isEmpty()) {
            int idx = 0;
            while (idx < waypoints.size() && waypoints.get(idx) == head) {
                idx++;
            }
            if (idx >= waypoints.size()) {
                return null;
            }
            int nxt = directStepCell(head, waypoints.get(idx));
            if (nxt < 0) {
                return null;
            }
            return new int[]{Integer.compare(nxt % COLS, hx),
                    Integer.compare(nxt / COLS, hy)};
        }

        int[] q = dirQueue.peekFirst();
        if (q != null && !(q[0] == -dirX && q[1] == -dirY)) {
            return new int[]{q[0], q[1]};
        }
        return new int[]{dirX, dirY};
    }

    /**
     * Safety net as a helper: the direction to really use for the current
     * (dirX, dirY), replaced by the roomiest free neighbour when it would hit a wall
     * or the body. Null when there is no usable move.
     */
    private int[] safeDirection(AutoPilot cycle) {
        Integer headObj = body.peekFirst();
        if (headObj == null) {
            return null;
        }
        int head = headObj;
        int nx = head % COLS + dirX;
        int ny = head / COLS + dirY;
        boolean bad = nx < 0 || nx >= COLS || ny < 0 || ny >= rows;
        if (!bad && cycle != null) {
            int cell = ny * COLS + nx;
            int tailCell = body.peekLast() == null ? -1 : body.peekLast();
            bad = body.contains(cell) && cell != tailCell;
        }
        if (!bad) {
            return new int[]{dirX, dirY};
        }
        return cycle == null ? null : cycle.fallbackDirection(cellsToArray());
    }

    /** Debug log: one timestamped line per event. */
    private void logLine(String line) {        try {
            java.io.File dir = getContext().getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(dir, "eats.txt"), true);
            w.write(System.currentTimeMillis() + " " + line + "\n");
            w.close();
        } catch (Exception ignored) {
            // diagnostics only
        }
    }

    /** Debug log: timestamp of every fruit eaten (used to check the 3s budget). */
    private void logEat() {
        try {
            java.io.File dir = getContext().getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(dir, "eats.txt"), true);
            w.write(System.currentTimeMillis() + " score=" + score + "\n");
            w.close();
        } catch (Exception ignored) {
            // diagnostics only
        }
    }

    private void clearEatLog() {
        try {
            java.io.File dir = getContext().getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            new java.io.FileWriter(new java.io.File(dir, "eats.txt"), false).close();
        } catch (Exception ignored) {
            // diagnostics only
        }
    }

    /* ------------------------------------------------------------------ */
    /* Manual mode: waypoint route                                        */
    /* ------------------------------------------------------------------ */

    /** BFS distance from the tail over free cells (the body is a wall). */
    private int[] distFromTail() {
        int total = COLS * rows;
        boolean[] occ = new boolean[total];
        for (int c : body) {
            occ[c] = true;
        }
        int[] dist = new int[total];
        java.util.Arrays.fill(dist, -1);
        int[] queue = new int[total];
        int start = body.peekLast();
        // The head cell itself counts as reachable so the gap can be measured.
        Integer headCell = body.peekFirst();
        if (headCell != null) {
            occ[headCell] = false;
        }
        int qh = 0;
        int qt = 0;
        dist[start] = 0;
        queue[qt++] = start;
        occ[start] = false;
        while (qh < qt) {
            int cur = queue[qh++];
            int cx = cur % COLS;
            int cy = cur / COLS;
            for (int k = 0; k < 4; k++) {
                int nx = cx + NEIGH[k][0];
                int ny = cy + NEIGH[k][1];
                if (nx < 0 || nx >= COLS || ny < 0 || ny >= rows) {
                    continue;
                }
                int n = ny * COLS + nx;
                if (occ[n] || dist[n] >= 0) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
            }
        }
        return dist;
    }

    /** Follows the player's waypoint route one cell per tick. */
    private boolean stepManualPath() {
        if (!manualMoving || waypoints.isEmpty()) {
            return false;
        }
        Integer headObj = body.peekFirst();
        if (headObj == null) {
            return false;
        }
        int head = headObj;
        while (!waypoints.isEmpty() && waypoints.get(0) == head) {
            waypoints.remove(0);
            if (selectedWp > 0) {
                selectedWp--;
            } else if (selectedWp == 0) {
                selectedWp = -1;
            }
        }
        if (waypoints.isEmpty()) {
            manualMoving = false;
            // FOLLOW stays active: after MOVE finishes the current route, build the
            // next route from the head's current cell (the player still presses MOVE).
            if (followRouteActive) {
                followRouteToFruit();
            }
            return false;
        }
        int target = waypoints.get(0);
        int hx = head % COLS;
        int hy = head / COLS;
        // Follow the player's own L-shaped route. There is no BFS detour any more:
        // the snake goes where the waypoints tell it to go, one cell per tick. If the
        // next cell is the body, the normal step rules do their job (the tail cell is
        // free to enter; a real self-bite cuts the body instead of stalling MOVE).
        int nxt = directStepCell(head, target);
        if (nxt < 0) {
            manualMoving = false;
            return false;
        }
        dirX = Integer.compare(nxt % COLS, hx);
        dirY = Integer.compare(nxt / COLS, hy);
        return true;
    }

    private int cellAt(float x, float y) {
        if (!boardRect.contains(x, y)) {
            return -1;
        }
        int cx = (int) ((x - boardLeft) / cell);
        int cy = (int) ((y - boardTop) / cell);
        if (cx < 0 || cx >= COLS || cy < 0 || cy >= rows) {
            return -1;
        }
        return cy * COLS + cx;
    }

    /** Manual mode taps: waypoints, the arrow pad and the MOVE button. */
    private void handleManualTap(float x, float y) {
        if (moveBtn.contains(x, y)) {
            if (manualMoving) {
                manualMoving = false;
                if (sound != null) {
                    sound.playTap();
                }
            } else if (!waypoints.isEmpty()) {
                manualMoving = true;
                tickAccum = -120f;
                dirQueue.clear();
                // A step is coming again: re-enable the lead + the turn ramp right away,
                // otherwise the first tick would move the head while it still points the
                // old way and the capsule would flip only afterwards.
                planPaused = false;
                predictNext();
                if (sound != null) {
                    sound.playMove();
                }
            }
            return;
        }
        // AUTO: fill the route with waypoints along the shortest path to the fruit.
        if (padAutoBtn.contains(x, y)) {
            autoRouteToFruit();
            return;
        }
        // LIST: open the drag & drop waypoint list.
        if (padListBtn.contains(x, y)) {
            if (waypoints.isEmpty()) {
                haptic(true);
                return;
            }
            wpListOpen = true;
            listDragIndex = -1;
            listDragging = false;
            layoutListPanel();
            haptic(false);
            return;
        }
        // FOLLOW: generate a tail-following waypoint route to a safe fruit, or
        // remove the route generated by the previous FOLLOW tap.
        if (padFollowBtn.contains(x, y)) {
            if (followRouteActive) {
                clearFollowRoute();
            } else {
                followRouteToFruit();
            }
            return;
        }
        if (selectedWp >= 0 && selectedWp < waypoints.size()) {
            if (padUpBtn.contains(x, y)) {
                moveWaypoint(-1, 0);
                return;
            }
            if (padDownBtn.contains(x, y)) {
                moveWaypoint(1, 0);
                return;
            }
            if (padLeftBtn.contains(x, y)) {
                moveWaypoint(0, -1);
                return;
            }
            if (padRightBtn.contains(x, y)) {
                moveWaypoint(0, 1);
                return;
            }
            if (padDelBtn.contains(x, y)) {
                deleteWaypoint();
                return;
            }
        }
        int c = cellAt(x, y);
        if (c < 0) {
            return;
        }

        for (int i = 0; i < waypoints.size(); i++) {
            if (waypoints.get(i) == c) {
                selectedWp = i;
                return;
            }
        }
        if (manualMoving) {
            return;
        }
        // A waypoint may sit on the body: by the time the snake gets there the
        // tail has usually moved on, so the cell is free again.
        waypoints.add(c);
        selectedWp = waypoints.size() - 1;
        haptic(false);
    }

    private void moveWaypoint(int dRow, int dCol) {
        if (selectedWp < 0 || selectedWp >= waypoints.size()) {
            return;
        }
        int c = waypoints.get(selectedWp);
        int cx = c % COLS;
        int cy = c / COLS;
        int nx = cx + dCol;
        int ny = cy + dRow;
        if (nx < 0 || nx >= COLS || ny < 0 || ny >= rows) {
            return;
        }
        int n = ny * COLS + nx;
        // The body moves, so a waypoint may also be placed on a cell that is
        // currently covered by the snake.
        waypoints.set(selectedWp, n);
    }

    private void deleteWaypoint() {
        if (selectedWp < 0 || selectedWp >= waypoints.size()) {
            return;
        }
        waypoints.remove(selectedWp);
        if (selectedWp >= waypoints.size()) {
            selectedWp = waypoints.size() - 1;
        }
        haptic(false);
    }

    /** Moves the selected waypoint earlier (-1) or later (+1) in the route. */
    private void reorderWaypoint(int dir) {
        if (selectedWp < 0 || selectedWp >= waypoints.size()) {
            return;
        }
        int to = selectedWp + (dir < 0 ? -1 : 1);
        if (to < 0 || to >= waypoints.size()) {
            return;
        }
        int tmp = waypoints.get(selectedWp);
        waypoints.set(selectedWp, waypoints.get(to));
        waypoints.set(to, tmp);
        selectedWp = to;
        haptic(false);
    }

    /* ------------------------------------------------------------------ */
    /* Waypoint list dialog (drag & drop reordering)                      */
    /* ------------------------------------------------------------------ */

    private void layoutListPanel() {
        float w = Math.min(boardW * 0.76f, dp(340f));
        float maxH = boardH * 0.74f;
        int n = Math.max(1, waypoints.size());
        listRowH = Math.max(dp(26f), Math.min(dp(46f), (maxH - dp(52f)) / n));
        float h = Math.min(maxH, dp(52f) + listRowH * n);
        float cx = boardRect.centerX();
        float cy = boardRect.centerY();
        listPanel.set(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f);
        listCloseBtn.set(listPanel.right - dp(34f), listPanel.top + dp(9f),
                listPanel.right - dp(9f), listPanel.top + dp(34f));
    }

    private float listRowsTop() {
        return listPanel.top + dp(48f);
    }

    private int listRowAt(float y) {
        if (waypoints.isEmpty() || listRowH <= 0f) {
            return -1;
        }
        int i = (int) ((y - listRowsTop()) / listRowH);
        if (i < 0 || i >= waypoints.size()) {
            return -1;
        }
        return i;
    }

    private void drawWpList(Canvas c) {
        if (!wpListOpen) {
            return;
        }
        c.drawRoundRect(listPanel, dp(20f), dp(20f), listCardPaint);
        c.drawRoundRect(listPanel, dp(20f), dp(20f), listCardStroke);

        c.drawText("DANH SÁCH WAYPOINT", listPanel.left + dp(16f),
                listPanel.top + dp(30f), listTitlePaint);
        c.drawText("kéo-thả để đổi thứ tự", listPanel.left + dp(16f),
                listPanel.top + dp(45f), hintPaint);

        // close ✕
        float cxc = listCloseBtn.centerX();
        float cyc = listCloseBtn.centerY();
        float cs = listCloseBtn.width() * 0.3f;
        c.drawLine(cxc - cs, cyc - cs, cxc + cs, cyc + cs, padDelPaint);
        c.drawLine(cxc + cs, cyc - cs, cxc - cs, cyc + cs, padDelPaint);

        float top = listRowsTop();
        for (int i = 0; i < waypoints.size(); i++) {
            int wp = waypoints.get(i);
            float ry = top + i * listRowH;
            tmpRect.set(listPanel.left + dp(10f), ry + dp(2f),
                    listPanel.right - dp(10f), ry + listRowH - dp(2f));
            boolean dragged = i == listDragIndex && listDragging;
            c.drawRoundRect(tmpRect, dp(10f), dp(10f),
                    dragged ? wpFillPaint : padBtnPaint);
            c.drawRoundRect(tmpRect, dp(10f), dp(10f),
                    i == selectedWp ? wpSelPaint : padBtnStrokePaint);
            // number badge
            float nx = tmpRect.left + dp(18f);
            c.drawCircle(nx, tmpRect.centerY(), dp(10f), wpFillPaint);
            c.drawText(String.valueOf(i + 1), nx, tmpRect.centerY() + dp(4f), listNumPaint);
            c.drawText("ô (" + (wp % COLS) + ", " + (wp / COLS) + ")", nx + dp(20f),
                    tmpRect.centerY() + dp(4.5f), listRowPaint);
            // drag handle on the right
            float hx = tmpRect.right - dp(18f);
            for (int k = -1; k <= 1; k++) {
                c.drawLine(hx - dp(7f), tmpRect.centerY() + k * dp(5f),
                        hx + dp(7f), tmpRect.centerY() + k * dp(5f), padIconPaint);
            }
        }
        c.drawText("chạm 1 dòng để chọn · chạm ngoài để đóng",
                listPanel.centerX(), listPanel.bottom - dp(12f), readyHintPaint);
    }

    /** Touch while the waypoint list dialog is open. */
    private boolean handleListTouch(MotionEvent e, float x, float y) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                listDownY = y;
                listDragging = false;
                listDragIndex = listRowAt(y);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (listDragIndex >= 0 && Math.abs(y - listDownY) > dp(4f)) {
                    listDragging = true;
                    int target = listRowAt(y);
                    if (target >= 0 && target != listDragIndex) {
                        int item = waypoints.remove(listDragIndex);
                        waypoints.add(target, item);
                        listDragIndex = target;
                        selectedWp = target;
                        haptic(false);
                    }
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (!listDragging) {
                    if (listCloseBtn.contains(x, y) || !listPanel.contains(x, y)) {
                        wpListOpen = false;
                    } else {
                        int i = listRowAt(y);
                        if (i >= 0) {
                            selectedWp = i;
                            wpListOpen = false;
                        }
                    }
                }
                listDragIndex = -1;
                listDragging = false;
                return true;
            default:
                return true;
        }
    }

    /**
     * AUTO: creates a route to the fruit with the autoplay engine currently selected
     * by TAIL / BFS / RND (SC / ZZ are applied to those engines). With no engine
     * selected it falls back to the plain BFS shortest path. Only the corners are
     * kept, so the L-shaped manual route follows the planned path exactly.
     */
    private void autoRouteToFruit() {
        if (body.isEmpty() || foodCell < 0) {
            autoRouteFailed("AUTO: không có quả để tới");
            return;
        }
        applyAiToggles();
        AutoPilot planner = autoRoutePilot();
        String engine = autoRouteEngineName();
        if (planner == null) {
            // No TAIL / BFS / RND selected: plain shortest path, as requested.
            int[] path = findPathTo(cellsToArray(), foodCell);
            if (path == null || path.length < 2) {
                autoRouteFailed(autoRoutePrefix(engine) + ": không tìm được đường tới quả");
                return;
            }
            int[] wps = routeCorners(toList(path));
            if (wps == null || wps.length == 0) {
                autoRouteFailed(autoRoutePrefix(engine) + ": không tìm được đường tới quả");
                return;
            }
            setAutoRoute(wps, engine);
            return;
        }
        // Let the selected engine drive a virtual snake to the fruit. TAIL / BFS /
        // RND keep their own safety rules, and SC / ZZ were pushed into the pilots
        // by applyAiToggles().
        int[] path = simulatePilotPath(planner, Math.max(1200, COLS * rows * 2));
        if (path == null || path.length < 2) {
            autoRouteFailed(autoRoutePrefix(engine) + ": không tìm được route");
            return;
        }
        int[] wps = safeRouteCorners(toList(path));
        if (wps == null || wps.length == 0) {
            autoRouteFailed(autoRoutePrefix(engine) + ": route không còn đường bám đuôi");
            return;
        }
        setAutoRoute(wps, engine);
    }

    /** Engine selected by TAIL / BFS / RND, or null for the plain shortest path. */
    private AutoPilot autoRoutePilot() {
        if (tailChaseOn && tailPilot != null) {
            return tailPilot;
        }
        if (bfsOn && bfsPilot != null) {
            return bfsPilot;
        }
        if (randomCycleOn && randomPilot != null) {
            return randomPilot;
        }
        if ((smartShortcuts || zigzagOn) && pilot != null) {
            // SC / ZZ are modifiers of the default Hamiltonian-cycle engine.
            return pilot;
        }
        return null;
    }

    private String autoRouteEngineName() {
        if (tailChaseOn) {
            return "TAIL";
        }
        if (bfsOn) {
            return "BFS";
        }
        if (randomCycleOn) {
            return "RND";
        }
        if (smartShortcuts) {
            return "SC";
        }
        if (zigzagOn) {
            return "ZZ";
        }
        return "";
    }

    private String autoRoutePrefix(String engine) {
        return engine.length() == 0 ? "AUTO (ngắn nhất)" : "AUTO " + engine;
    }

    private void setAutoRoute(int[] wps, String engine) {
        followRouteActive = false;
        waypoints.clear();
        for (int c : wps) {
            waypoints.add(c);
        }
        selectedWp = 0;
        logLine("AUTO engine=" + (engine.length() == 0 ? "SHORTEST" : engine)
                + " wps=" + wps.length + " food=" + foodCell + " len=" + body.size());
        showNotice(autoRoutePrefix(engine) + ": " + wps.length + " waypoint tới quả", false);
        if (sound != null) {
            sound.playAuto();
        }
        haptic(false);
    }

    /** AUTO could not build a route: notify, but leave the current route alone. */
    private void autoRouteFailed(String message) {
        logLine("AUTO fail " + message + " food=" + foodCell + " len=" + body.size());
        showNotice(message, true);
        if (sound != null) {
            sound.playError();
        }
    }

    /**
     * FOLLOW: builds a waypoint route that follows the snake's tail and ends on the
     * fruit only when the tail can still be reached after eating. Tapping FOLLOW
     * again removes this generated route.
     */
    private void followRouteToFruit() {
        if (body.isEmpty() || foodCell < 0 || tailPilot == null) {
            followRouteFailed("FOLLOW: không có quả để tới");
            return;
        }
        tailPilot.setTailChase(true);
        int[] path = simulatePilotPath(tailPilot, Math.max(1200, COLS * rows * 2));
        if (path == null || path.length < 2) {
            followRouteFailed("FOLLOW: không tìm được path bám đuôi tới quả");
            return;
        }
        int[] wps = safeRouteCorners(toList(path));
        if (wps == null || wps.length == 0) {
            followRouteFailed("FOLLOW: path không còn đường bám đuôi an toàn");
            return;
        }
        waypoints.clear();
        for (int c : wps) {
            waypoints.add(c);
        }
        selectedWp = 0;
        followRouteActive = true;
        // FOLLOW only builds the route: the player inspects it and presses MOVE.
        manualMoving = false;
        tickAccum = 0f;
        dirQueue.clear();
        logLine("FOLLOW wps=" + wps.length + " food=" + foodCell + " len=" + body.size());
        showNotice("FOLLOW: " + wps.length + " waypoint bám đuôi — bấm MOVE", false);
        if (sound != null) {
            sound.playAuto();
        }
        haptic(false);
    }

    /** Removes the waypoint route generated by FOLLOW and stops at once. */
    private void clearFollowRoute() {
        waypoints.clear();
        selectedWp = -1;
        manualMoving = false;
        followRouteActive = false;
        tickAccum = 0f;
        logLine("FOLLOW cleared");
        showNotice("FOLLOW: đã xoá route bám đuôi", false);
        if (sound != null) {
            sound.playTap();
        }
        haptic(false);
    }

    private void followRouteFailed(String message) {
        followRouteActive = false;
        logLine("FOLLOW fail " + message + " food=" + foodCell + " len=" + body.size());
        showNotice(message, true);
        if (sound != null) {
            sound.playError();
        }
    }

    /**
     * Replays {@code planner} on a copy of the live snake until the fruit is eaten,
     * and returns the walked cells (head first). The safety net mirrors step().
     * Null when the engine gets stuck or cannot reach the fruit within {@code limit}
     * ticks.
     */
    private int[] simulatePilotPath(AutoPilot planner, int limit) {
        if (planner == null || body.isEmpty() || foodCell < 0) {
            return null;
        }
        ArrayDeque<Integer> sim = new ArrayDeque<>(body);
        int dx = dirX;
        int dy = dirY;
        int[] route = new int[limit + 2];
        int n = 0;
        Integer headObj = sim.peekFirst();
        if (headObj == null) {
            return null;
        }
        route[n++] = headObj;
        for (int step = 0; step < limit; step++) {
            int[] cells = new int[sim.size()];
            int k = 0;
            for (int c : sim) {
                cells[k++] = c;
            }
            int[] d = planner.decide(cells, foodCell, dx, dy);
            if (d == null) {
                return null;
            }
            dx = d[0];
            dy = d[1];
            int head = cells[0];
            int hx = head % COLS;
            int hy = head / COLS;
            int nx = hx + dx;
            int ny = hy + dy;
            int tail = cells[cells.length - 1];
            boolean bad = nx < 0 || nx >= COLS || ny < 0 || ny >= rows;
            if (!bad) {
                int c = ny * COLS + nx;
                bad = sim.contains(c) && c != tail;
            }
            if (bad) {
                int[] safe = planner.fallbackDirection(cells);
                if (safe == null) {
                    return null;
                }
                dx = safe[0];
                dy = safe[1];
                nx = hx + dx;
                ny = hy + dy;
                if (nx < 0 || nx >= COLS || ny < 0 || ny >= rows) {
                    return null;
                }
                int c = ny * COLS + nx;
                if (sim.contains(c) && c != tail) {
                    return null;
                }
            }
            int ni = ny * COLS + nx;
            boolean eats = ni == foodCell;
            if (!eats) {
                sim.pollLast();
            }
            if (sim.contains(ni)) {
                return null;
            }
            sim.addFirst(ni);
            if (n >= route.length) {
                return null;
            }
            route[n++] = ni;
            if (eats) {
                int[] out = new int[n];
                System.arraycopy(route, 0, out, 0, n);
                return out;
            }
        }
        return null;
    }

    private ArrayList<Integer> toList(int[] path) {
        ArrayList<Integer> out = new ArrayList<>(path.length);
        for (int c : path) {
            out.add(c);
        }
        return out;
    }

    /** Corners of a route (the first cell is the head and is skipped). */
    private int[] routeCorners(ArrayList<Integer> route) {
        ArrayList<Integer> wps = new ArrayList<>();
        for (int i = 1; i < route.size(); i++) {
            boolean corner;
            if (i == route.size() - 1) {
                corner = true;
            } else {
                int a = route.get(i - 1);
                int b = route.get(i);
                int c = route.get(i + 1);
                corner = (b % COLS - a % COLS) != (c % COLS - b % COLS)
                        || (b / COLS - a / COLS) != (c / COLS - b / COLS);
            }
            if (corner) {
                wps.add(route.get(i));
            }
        }
        int[] out = new int[wps.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = wps.get(i);
        }
        return out;
    }

    /** Corners of the route when it is safe, else null. */
    private int[] safeRouteCorners(ArrayList<Integer> route) {
        int[] after = simulateRoute(route);
        if (after == null || after.length < 2) {
            return null;
        }
        if (!tailReachableIn(after)) {
            return null;
        }
        return routeCorners(route);
    }

    /**
     * Walks the route over a virtual snake (growing on the fruit) and returns the
     * resulting body (head first), or null when the head would hit the body.
     */
    private int[] simulateRoute(ArrayList<Integer> route) {
        int total = COLS * rows;
        int bodyLen = body.size();
        if (bodyLen == 0 || route.isEmpty()) {
            return null;
        }
        int cap = bodyLen + route.size() + 2;
        boolean[] occ = new boolean[total];
        int[] seq = new int[cap];
        int k = 0;
        for (int c : body) {
            seq[bodyLen - 1 - k++] = c;   // tail first, head last
            occ[c] = true;
        }
        int tailIdx = 0;
        int headIdx = bodyLen - 1;
        for (int i = 1; i < route.size(); i++) {
            int c = route.get(i);
            boolean eating = c == foodCell;
            boolean tailFree = c == seq[tailIdx] && !eating;
            if (occ[c] && !tailFree) {
                return null;
            }
            if (!eating) {
                occ[seq[tailIdx]] = false;
                tailIdx++;
            }
            if (headIdx + 1 >= cap) {
                return null;
            }
            seq[++headIdx] = c;
            occ[c] = true;
        }
        int len = headIdx - tailIdx + 1;
        int[] out = new int[len];
        for (int i = 0; i < len; i++) {
            out[i] = seq[headIdx - i];
        }
        return out;
    }

    /** Head can reach the tail through free cells of the given body. */
    private boolean tailReachableIn(int[] cells) {
        if (cells.length <= 2) {
            return true;
        }
        int total = COLS * rows;
        boolean[] occ = new boolean[total];
        for (int c : cells) {
            occ[c] = true;
        }
        int[] dist = new int[total];
        java.util.Arrays.fill(dist, -1);
        int[] queue = new int[total];
        int target = cells[cells.length - 1];
        int qh = 0;
        int qt = 0;
        dist[cells[0]] = 0;
        queue[qt++] = cells[0];
        while (qh < qt) {
            int cur = queue[qh++];
            if (cur == target) {
                return true;
            }
            int cx = cur % COLS;
            int cy = cur / COLS;
            for (int k = 0; k < 4; k++) {
                int nx = cx + NEIGH[k][0];
                int ny = cy + NEIGH[k][1];
                if (nx < 0 || nx >= COLS || ny < 0 || ny >= rows) {
                    continue;
                }
                int n = ny * COLS + nx;
                if (dist[n] >= 0 || (occ[n] && n != target)) {
                    continue;
                }
                dist[n] = dist[cur] + 1;
                queue[qt++] = n;
            }
        }
        return false;
    }

    /** BFS shortest path (cells) from the head to {@code target}, body = wall. */
    private int[] findPathTo(int[] cells, int target) {
        if (cells.length == 0 || target < 0) {
            return null;
        }
        return findPathFrom(cells, cells[0], target);
    }

    /**
     * Shortest legal path {@code from -> to} around the snake's own body (the tail
     * cell counts as free, it moves away). Returns the cells after {@code from} ...
     * {@code to}, both included, or null when there is no way through.
     */
    private int[] findPathFrom(int[] cells, int from, int to) {
        int total = COLS * rows;
        if (cells.length == 0 || from < 0 || from >= total || to < 0 || to >= total) {
            return null;
        }
        if (from == to) {
            return new int[]{from};
        }
        boolean[] wall = new boolean[total];
        for (int c : cells) {
            wall[c] = true;
        }
        wall[from] = false;
        if (cells.length > 1) {
            wall[cells[cells.length - 1]] = false;   // the tail moves away
        }
        int[] parent = new int[total];
        java.util.Arrays.fill(parent, -2);
        int[] queue = new int[total];
        int qh = 0;
        int qt = 0;
        parent[from] = -1;
        queue[qt++] = from;
        boolean reached = false;
        while (qh < qt) {
            int cur = queue[qh++];
            if (cur == to) {
                reached = true;
                break;
            }
            int cx = cur % COLS;
            int cy = cur / COLS;
            for (int k = 0; k < 4; k++) {
                int nx = cx + NEIGH[k][0];
                int ny = cy + NEIGH[k][1];
                if (nx < 0 || nx >= COLS || ny < 0 || ny >= rows) {
                    continue;
                }
                int n = ny * COLS + nx;
                if (parent[n] != -2 || (wall[n] && n != to)) {
                    continue;
                }
                parent[n] = cur;
                queue[qt++] = n;
            }
        }
        if (!reached) {
            return null;
        }
        ArrayList<Integer> rev = new ArrayList<>();
        for (int c = to; c != -1; c = parent[c]) {
            rev.add(c);
        }
        int[] path = new int[rev.size()];
        for (int i = 0; i < rev.size(); i++) {
            path[i] = rev.get(rev.size() - 1 - i);
        }
        return path;
    }

    private void clearRoute() {
        waypoints.clear();
        selectedWp = -1;
        manualMoving = false;
        wpListOpen = false;
        listDragIndex = -1;
        listDragging = false;
        followRouteActive = false;
    }

    private void spawnFood() {
        foodTimer = 0f;
        foodFresh = true;
        int total = COLS * Math.max(rows, ROWS_MIN);
        int free = total - body.size();
        if (free <= 0) {
            // The board is full: there is nowhere left to put a fruit - the
            // player (or the AI) has won.
            foodCell = -1;
            if (state == STATE_RUNNING) {
                state = STATE_WON;
                winWait = 0f;
                deadTime = 0f;
                saveHighScore();
                logLine("won score=" + score + " len=" + body.size());
                if (sound != null) {
                    sound.playWin();
                }
                haptic(false);
            }
            return;
        }
        AutoPilot cycle = activePilot();
        if (cycle != null && !body.isEmpty()) {
            // One random free cell inside the free arc: always reachable by
            // walking forward on the cycle, and picked only once per fruit (so
            // the fruit never flickers around).
            int[] cellsNow = cellsToArray();
            for (int attempt = 0; attempt < 12; attempt++) {
                int arcPick = cycle.pickFoodCell(cellsNow, rnd);
                if (arcPick < 0) {
                    break;
                }
                foodCell = arcPick;
                if (mode != MODE_AUTO || cycle.foodReachable(cellsNow, foodCell)) {
                    return;
                }
            }
            if (foodCell >= 0) {
                return;
            }
        }
        int pick = randomFreeCell(total);
        if (pick >= 0) {
            foodCell = pick;
            return;
        }
        foodCell = 0;
    }
    /** Uniformly random empty cell, -1 when the board is full. */
    private int randomFreeCell(int total) {
        int free = total - body.size();
        if (free <= 0) {
            return -1;
        }
        int pick = rnd.nextInt(free);
        for (int c = 0; c < total; c++) {
            if (!body.contains(c)) {
                if (pick-- == 0) {
                    return c;
                }
            }
        }
        return -1;
    }

    /**
     * A self-bite: instead of ending the run the snake bites through itself. The
     * body from the collision cell back to the tail is removed, so the snake gets
     * shorter; the hit still counts as a death (counter + colour change).
     */
    private void biteSelf(int cell) {
        int[] prefix = new int[body.size()];
        int k = 0;
        for (int c : body) {
            prefix[k++] = c;
            if (c == cell) {
                break;
            }
        }
        int cut = k - 1;
        body.clear();
        for (int i = cut - 1; i >= 0; i--) {
            body.addLast(prefix[i]);
        }
        body.addFirst(cell);
        curCells = cellsToArray();
    }

    private void die(int hx, int hy) {
        state = STATE_DEAD;
        deadTime = 0f;
        deadWait = 0f;
        spawnDeathFx(hx, hy);
        haptic(true);
        saveHighScore();
        registerDeath("wall");
    }

    /** Death bookkeeping shared by wall deaths and self-bites. */
    private void registerDeath(String cause) {
        deaths++;
        snakeColor = (snakeColor + 1) % SNAKE_COLORS.length;
        applySnakeColor();
        if (sound != null) {
            if ("bite".equals(cause)) {
                sound.playBite();
            } else {
                sound.playDie();
            }
        }
        logLine("died cause=" + cause + " score=" + score + " len=" + body.size()
                + " deaths=" + deaths);
    }

    private void saveHighScore() {
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_HIGH, highScore).apply();
    }

    /**
     * Writes a tiny state snapshot to the app external files dir so the build /
     * test scripts (and adb) can check what the game is doing.
     */
    private long lastDumpMs;
    private int frameCount;
    private int lastFrameCount;
    /** Set by the DUMP button; the render thread writes the snapshot safely. */
    private volatile boolean dumpRequested;

    private void dumpState() {
        long now = System.currentTimeMillis();
        if (!dumpRequested && now - lastDumpMs < 1500L) {
            return;
        }
        long dtMs = now - lastDumpMs;
        int fps = dtMs > 0 ? Math.round((frameCount - lastFrameCount) * 1000f / dtMs) : 0;
        lastFrameCount = frameCount;
        lastDumpMs = now;
        try {
            java.io.File dir = getContext().getExternalFilesDir(null);
            if (dir == null) {
                return;
            }
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(dir, "state.txt"), false);
            StringBuilder sb = new StringBuilder();
            int count = 0;
            for (int c : body) {
                if (count++ >= 90) {
                    break;
                }
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(c);
            }
            float itp = interpolation();
            StringBuilder pts = new StringBuilder();
            computeDrawnPoints(body.size(), itp);
            int drawn = Math.min(drawnCount, 90);
            for (int i = 0; i < drawn; i++) {
                if (pts.length() > 0) {
                    pts.append(';');
                }
                pts.append(Math.round(drawnPts[i * 2])).append(',')
                        .append(Math.round(drawnPts[i * 2 + 1]));
            }
            String stateText = "state=" + state + " auto=" + autoMode + " score=" + score
                    + " hs=" + highScore + " snake=" + snakeColor + " fps=" + fps
                    + " deaths=" + deaths + " mode=" + mode
                    + " interval=" + Math.round(currentInterval)
                    + " food=" + foodCell
                    + " arc=" + arcSize()
                    + " stf=" + stepsToFoodDiag()
                    + " wps=" + waypoints.size() + "/" + selectedWp + "/" + (manualMoving ? 1 : 0)
                    + " len=" + body.size() + " rows=" + rows + " cell=" + cell
                    + " board=" + Math.round(boardLeft) + "," + Math.round(boardTop) + ","
                    + Math.round(boardRect.right) + "," + Math.round(boardRect.bottom)
                    + "\nbody=" + sb
                    + "\npts=" + pts
                    + "\ncycle=" + cyclePreview()
                    + "\nwpl=" + waypointList()
                    + "\nnotice=" + (noticeText.length() == 0 ? "-" : noticeText)
                    + "\nmove=" + Math.round(moveBtn.centerX()) + ","
                    + Math.round(moveBtn.centerY())
                    + " padUp=" + Math.round(padUpBtn.centerX()) + ","
                    + Math.round(padUpBtn.centerY())
                    + " padDown=" + Math.round(padDownBtn.centerX()) + ","
                    + Math.round(padDownBtn.centerY())
                    + " padLeft=" + Math.round(padLeftBtn.centerX()) + ","
                    + Math.round(padLeftBtn.centerY())
                    + " padRight=" + Math.round(padRightBtn.centerX()) + ","
                    + Math.round(padRightBtn.centerY())
                    + " padDel=" + Math.round(padDelBtn.centerX()) + ","
                    + Math.round(padDelBtn.centerY())
                    + " padAuto=" + Math.round(padAutoBtn.centerX()) + ","
                    + Math.round(padAutoBtn.centerY())
                    + " padList=" + Math.round(padListBtn.centerX()) + ","
                    + Math.round(padListBtn.centerY())
                    + " coll=" + routeCollisions().size()
                    + " stuck=0"
                    + " gap=" + (body.size() > 1 ? distFromTail()[body.peekFirst()] : -1)
                    + " list=" + (wpListOpen ? 1 : 0)
                    + " follow=" + (followRouteActive ? 1 : 0)
                    + " listRect=" + Math.round(listPanel.left) + ","
                    + Math.round(listPanel.top) + ","
                    + Math.round(listPanel.right) + ","
                    + Math.round(listPanel.bottom)
                    + " rowH=" + Math.round(listRowH)
                    + " selRow=" + listDragIndex
                    + " dumpB=" + Math.round(dumpBtn.centerX()) + ","
                    + Math.round(dumpBtn.centerY())
                    + " clearB=" + Math.round(clearBtn.centerX()) + ","
                    + Math.round(clearBtn.centerY())
                    + " settings=" + (settingsOpen ? 1 : 0)
                    + " setBtn=" + Math.round(settingsBtn.centerX()) + ","
                    + Math.round(settingsBtn.centerY())
                    + " setClose=" + Math.round(settingsCloseBtn.centerX()) + ","
                    + Math.round(settingsCloseBtn.centerY())
                    + " setPanel=" + Math.round(settingsPanel.left) + ","
                    + Math.round(settingsPanel.top) + ","
                    + Math.round(settingsPanel.right) + ","
                    + Math.round(settingsPanel.bottom)
                    + " setSpeed=" + rectCenters(settingsSpeedBtn)
                    + " setAlgo=" + rectCenters(settingsAlgoBtn)
                    + "\nstart=" + Math.round(startBtn.centerX()) + ","
                    + Math.round(startBtn.centerY())
                    + " autoBtn=" + Math.round(autoBtn.centerX()) + ","
                    + Math.round(autoBtn.centerY())
                    + " pathBtn=" + Math.round(pathBtn.centerX()) + ","
                    + Math.round(pathBtn.centerY())
                    + " mangoBtn=" + Math.round(mangoBtn.centerX()) + ","
                    + Math.round(mangoBtn.centerY())
                    + " pause=" + Math.round(pauseCx) + "," + Math.round(pauseCy)
                    + " spd=" + speedIndex + " sc=" + (smartShortcuts ? 1 : 0)
                    + " zz=" + (zigzagOn ? 1 : 0)
                    + " bfs=" + (bfsOn ? 1 : 0)
                    + " tail=" + (tailChaseOn ? 1 : 0)
                    + " rnd=" + (randomCycleOn ? 1 : 0)
                    + " followOn=" + (followRouteActive ? 1 : 0)
                    + " ang=" + Math.round(headAngleDeg)
                    + " dir=" + dirX + "," + dirY
                    + " rstB=" + Math.round(resetBtn.centerX()) + ","
                    + Math.round(resetBtn.centerY())
                    + " resume=" + Math.round(resumeBtn.centerX()) + ","
                    + Math.round(resumeBtn.centerY())
                    + " autoResume=" + Math.round(autoResumeBtn.centerX()) + ","
                    + Math.round(autoResumeBtn.centerY())
                    + " pathResume=" + Math.round(pathResumeBtn.centerX()) + ","
                    + Math.round(pathResumeBtn.centerY())
                    + " mangoResume=" + Math.round(mangoResumeBtn.centerX()) + ","
                    + Math.round(mangoResumeBtn.centerY())
                    + " restart=" + Math.round(restartBtn.centerX()) + ","
                    + Math.round(restartBtn.centerY())
                    + " autoRestart=" + Math.round(autoRestartBtn.centerX()) + ","
                    + Math.round(autoRestartBtn.centerY())
                    + " pathRestart=" + Math.round(pathRestartBtn.centerX()) + ","
                    + Math.round(pathRestartBtn.centerY())
                    + " mangoRestart=" + Math.round(mangoRestartBtn.centerX()) + ","
                    + Math.round(mangoRestartBtn.centerY())
                    + "\n";
            w.write(stateText);
            w.close();
            if (dumpRequested) {
                dumpRequested = false;
                writeDumpFile(stateText);
            }
        } catch (Exception ignored) {
            // diagnostics only
        }
    }

    /** Writes the full user-triggered snapshot to dump.txt and a timestamped copy. */
    private void writeDumpFile(String stateText) {
        try {
            java.io.File dir = getContext().getExternalFilesDir(null);
            if (dir == null) {
                showNotice("DUMP: không có thư mục lưu", true);
                return;
            }
            String stamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.US).format(new java.util.Date());
            String extra = "dump=user\n"
                    + "time=" + new java.util.Date() + "\n"
                    + "model=" + Build.MODEL + "\n"
                    + "sdk=" + Build.VERSION.SDK_INT + "\n"
                    + "highScore=" + highScore + "\n"
                    + "snakeColor=" + snakeColor + "\n"
                    + "followActive=" + followRouteActive + "\n"
                    + "activeToggle=" + activeToggleName() + "\n"
                    + "lastAutoMode=" + lastAutoMode + "\n"
                    + "rndPending=" + rndCyclePending + "\n"
                    + "fallbackPilot=" + (rndFallbackPilot != null) + "\n"
                    + "bodyAll=" + allBodyCells() + "\n"
                    + "waypointsAll=" + waypointList() + "\n"
                    + "routeAll=" + routeCellsString() + "\n"
                    + "collisions=" + collisionCells() + "\n";
            String full = extra + stateText;
            writeTextFile(new java.io.File(dir, "dump.txt"), full);
            writeTextFile(new java.io.File(dir, "dump_" + stamp + ".txt"), full);
            logLine("DUMP " + stamp);
            showNotice("Đã lưu dump.txt", false);
            if (sound != null) {
                sound.playTap();
            }
        } catch (Exception e) {
            showNotice("DUMP: lỗi lưu file", true);
        }
    }

    private static void writeTextFile(java.io.File file, String text) throws java.io.IOException {
        java.io.FileWriter w = new java.io.FileWriter(file, false);
        try {
            w.write(text);
        } finally {
            w.close();
        }
    }

    /** Every body cell (head first) without the state.txt 90-cell cap. */
    private String allBodyCells() {
        StringBuilder sb = new StringBuilder();
        for (int c : body) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** Every cell of the currently planned route, head first. */
    private String routeCellsString() {
        ArrayList<Integer> route = routeCellsTo(waypoints.size() - 1);
        StringBuilder sb = new StringBuilder();
        for (int c : route) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    /** Cells where the planned route would hit the body. */
    private String collisionCells() {
        ArrayList<Integer> hits = routeCollisions();
        StringBuilder sb = new StringBuilder();
        for (int c : hits) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(c);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private String activeToggleName() {
        if (tailChaseOn) {
            return "TAIL";
        }
        if (bfsOn) {
            return "BFS";
        }
        if (smartShortcuts) {
            return "SC";
        }
        if (zigzagOn) {
            return "ZZ";
        }
        if (randomCycleOn) {
            return "RND";
        }
        return "OFF";
    }

    /** Shows a short message in the bottom hint line for a few seconds. */
    private void showNotice(String text, boolean error) {
        noticeText = text == null ? "" : text;
        noticeError = error;
        noticeUntilMs = android.os.SystemClock.uptimeMillis() + 3200L;
    }

    private void haptic(final boolean strong) {
        if (sound != null && !strong) {
            sound.playTap();
        }
        post(new Runnable() {
            @Override
            public void run() {
                performHapticFeedback(strong
                        ? HapticFeedbackConstants.LONG_PRESS
                        : HapticFeedbackConstants.CONFIRM);
            }
        });
    }

    /* ------------------------------------------------------------------ */
    /* Input                                                              */
    /* ------------------------------------------------------------------ */

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        float x = e.getX();
        float y = e.getY();
        if (settingsOpen) {
            return handleSettingsTouch(e);
        }
        if (wpListOpen) {
            // The waypoint list dialog owns every touch while it is open.
            return handleListTouch(e, x, y);
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = x;
                downY = y;
                gestureConsumed = false;
                tapOnPause = pauseBtn.contains(x, y);
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_POINTER_UP:
                // A second finger is never a tap: ignore the whole gesture.
                gestureConsumed = true;
                return true;
            case MotionEvent.ACTION_CANCEL:
                gestureConsumed = true;
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (gestureConsumed) {
                    return true;
                }
                float dx = x - downX;
                float dy = y - downY;
                float slop = dp(SWIPE_SLOP_DP);
                if (Math.abs(dx) > slop || Math.abs(dy) > slop) {
                    if (Math.abs(dx) >= Math.abs(dy)) {
                        queueDir(dx > 0 ? 1 : -1, 0);
                    } else {
                        queueDir(0, dy > 0 ? 1 : -1);
                    }
                    gestureConsumed = true;
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                float dx = x - downX;
                float dy = y - downY;
                float slop = dp(SWIPE_SLOP_DP);
                if (!gestureConsumed && Math.abs(dx) < slop && Math.abs(dy) < slop) {
                    onTap(x, y);
                }
                gestureConsumed = true;
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void onTap(float x, float y) {
        if (state == STATE_RUNNING && dumpBtn.contains(x, y)) {
            dumpRequested = true;
            showNotice("Đang lưu dump...", false);
            haptic(false);
            return;
        }
        if (state == STATE_RUNNING && !autoMode && mode == MODE_MANUAL
                && !waypoints.isEmpty() && clearBtn.contains(x, y)) {
            clearWaypoints();
            return;
        }
        switch (state) {
            case STATE_READY:
                if (startBtn.contains(x, y)) {
                    startGame(MODE_MANUAL);
                } else if (autoBtn.contains(x, y)) {
                    startGame(MODE_AUTO);
                } else if (settingsBtn.contains(x, y)) {
                    openSettings();
                } else {
                    startGame(MODE_MANUAL);
                }
                return;
            case STATE_DEAD:
            case STATE_WON:
                // Restart only from the card's buttons: a stray tap on the board must
                // not silently start a new run and erase the final score.
                if (autoRestartBtn.contains(x, y)) {
                    restartGame(MODE_AUTO);
                } else if (restartBtn.contains(x, y)) {
                    restartGame(MODE_MANUAL);
                }
                return;
            case STATE_PAUSED:
                // AUTOPLAY switches the AI back on for the same run; CHƠI LẠI starts
                // a fresh run with the toggles that are active now; anything else
                // simply continues the run.
                if (resumeBtn.contains(x, y)) {
                    resumeGame();
                } else if (autoResumeBtn.contains(x, y)) {
                    resumeMode(MODE_AUTO);
                } else if (resetBtn.contains(x, y)) {
                    restartRun();
                } else if (settingsBtn.contains(x, y)) {
                    openSettings();
                } else {
                    resumeGame();
                }
                return;
            case STATE_RUNNING:
                if (tapOnPause || pauseBtn.contains(x, y)) {
                    pauseGame();
                    return;
                }
                if (!autoMode && mode == MODE_MANUAL) {
                    handleManualTap(x, y);
                    return;
                }
                if (autoMode && autoBadge.contains(x, y)) {
                    // Taking over the AI: stop and let the player plan a route.
                    lastAutoMode = mode;
                    mode = MODE_MANUAL;
                    autoMode = false;
                    clearRoute();
                    tickAccum = 0f;
                    if (sound != null) {
                        sound.playTap();
                    }
                }
                return;
            default:
                return;
        }
    }

    private void queueDir(int dx, int dy) {
        if (state != STATE_RUNNING) {
            return;
        }
        if (!autoMode && mode == MODE_MANUAL) {
            // Manual mode is driven by waypoints, not by swipes.
            return;
        }
        if (autoMode) {
            // A swipe means the player takes over from the AI.
            lastAutoMode = mode;
            autoMode = false;
            mode = MODE_MANUAL;
            dirQueue.clear();
            clearRoute();
            tickAccum = 0f;
            return;
        }
        int[] last = dirQueue.peekLast();
        int lx = last != null ? last[0] : dirX;
        int ly = last != null ? last[1] : dirY;
        if (dx == lx && dy == ly) {
            return;
        }
        if (dx == -lx && dy == -ly) {
            return;
        }
        if (dirQueue.size() >= 2) {
            return;
        }
        dirQueue.add(new int[]{dx, dy});
    }

    /* ------------------------------------------------------------------ */
    /* Rendering                                                          */
    /* ------------------------------------------------------------------ */

    private void render(Canvas c) {
        if (getWidth() <= 0 || getHeight() <= 0) {
            return;
        }
        if (boardW <= 0f) {
            computeGeometry();
            if (boardW <= 0f) {
                return;
            }
        }
        if (staticBmp == null) {
            rebuildStatic();
            if (staticBmp == null) {
                return;
            }
        }
        c.drawBitmap(staticBmp, 0f, 0f, blitPaint);

        if (foodCell >= 0) {
            drawFood(c);
        }
        if (!autoMode && mode == MODE_MANUAL && state == STATE_RUNNING) {
            drawRoute(c);
        }
        drawSnake(c);
        // The ghost is drawn on top of the real snake: with a short route the
        // ghost shares cells with the body and would otherwise be hidden.
        if (!autoMode && mode == MODE_MANUAL && state == STATE_RUNNING) {
            drawGhostSnake(c);
            drawWaypointMarkers(c);
        }
        drawParticles(c);
        drawPopups(c);
        drawHud(c);
        // The control strip belongs to the play screen only: keep the home and
        // pause menus clean (their own buttons live on the overlays).
        if (state == STATE_RUNNING) {
            drawBottomBar(c);
        }
        if (!autoMode && mode == MODE_MANUAL && state == STATE_RUNNING) {
            drawWaypointPad(c);
        }
        if (wpListOpen) {
            drawWpList(c);
        }
        if (state != STATE_RUNNING) {
            drawOverlay(c);
        }
        if (settingsOpen) {
            drawSettings(c);
        }
    }

    /** Faint dashed preview of the planned route (drawn under the snake). */
    private void drawRoute(Canvas c) {
        if (waypoints.isEmpty() || body.isEmpty()) {
            return;
        }
        ArrayList<Integer> route = routeCellsTo(waypoints.size() - 1);
        if (route.size() < 2) {
            return;
        }
        float half = cell / 2f;
        Path path = new Path();
        int first = route.get(0);
        path.moveTo(boardLeft + (first % COLS) * cell + half,
                boardTop + (first / COLS) * cell + half);
        for (int i = 1; i < route.size(); i++) {
            int cc = route.get(i);
            path.lineTo(boardLeft + (cc % COLS) * cell + half,
                    boardTop + (cc / COLS) * cell + half);
        }
        c.drawPath(path, previewPaint);
    }

    /** Waypoint dots with their order numbers (drawn on top of the snake/ghost). */
    private void drawWaypointMarkers(Canvas c) {
        if (waypoints.isEmpty()) {
            return;
        }
        float half = cell / 2f;
        for (int i = 0; i < waypoints.size(); i++) {
            int wp = waypoints.get(i);
            float tx = boardLeft + (wp % COLS) * cell + half;
            float ty = boardTop + (wp / COLS) * cell + half;
            boolean sel = i == selectedWp;
            float r = cell * (sel ? 0.36f : 0.27f);
            c.drawCircle(tx, ty, r, wpFillPaint);
            c.drawCircle(tx, ty, r, sel ? wpSelPaint : wpStrokePaint);
            c.drawText(String.valueOf(i + 1), tx, ty + dp(3.4f), wpTextPaint);
        }
        // Cells where the planned route would hit the snake's own body.
        for (int hit : routeCollisions()) {
            float tx = boardLeft + (hit % COLS) * cell + half;
            float ty = boardTop + (hit / COLS) * cell + half;
            float k = cell * 0.30f;
            c.drawLine(tx - k, ty - k, tx + k, ty + k, padDelPaint);
            c.drawLine(tx + k, ty - k, tx - k, ty + k, padDelPaint);
            c.drawCircle(tx, ty, cell * 0.40f, padDelPaint);
        }
    }


    /**
     * Walks the planned route with a simulated snake and returns the cells where
     * the head would run into its own body (the first hit ends the walk, because
     * the real snake would be cut there).
     */
    private ArrayList<Integer> routeCollisions() {
        ArrayList<Integer> hits = new ArrayList<>();
        if (body.isEmpty() || waypoints.isEmpty()) {
            return hits;
        }
        ArrayList<Integer> route = routeCellsTo(waypoints.size() - 1);
        if (route.isEmpty()) {
            return hits;
        }
        int total = COLS * rows;
        int bodyLen = body.size();
        int cap = bodyLen + route.size() + 2;
        boolean[] occ = new boolean[total];
        int[] seq = new int[cap];
        int n = 0;
        // tail-first order, head at index n-1
        for (int c : body) {
            occ[c] = true;
            n++;
        }
        int k = 0;
        for (int c : body) {
            seq[n - 1 - k++] = c;
        }
        int tailIdx = 0;
        int headIdx = n - 1;
        // route cells after the head
        for (int i = 1; i < route.size(); i++) {
            int c = route.get(i);
            boolean eating = c == foodCell;
            boolean isTailCell = c == seq[tailIdx] && !eating;
            if (occ[c] && !isTailCell) {
                hits.add(c);
                break;
            }
            if (!eating) {
                occ[seq[tailIdx]] = false;
                tailIdx++;
            }
            if (headIdx + 1 >= cap) {
                break;
            }
            seq[++headIdx] = c;
            occ[c] = true;
        }
        return hits;
    }

    /**
     * Faint orange silhouette of the whole snake as it would look when the head
     * reaches the selected waypoint: head on the waypoint, then the cells walked
     * backwards along the route, and - when the route is shorter than the snake -
     * the rest of the current body, so the ghost is always exactly body-sized.
     */
    private void drawGhostSnake(Canvas c) {
        if (selectedWp < 0 || selectedWp >= waypoints.size() || body.isEmpty()) {
            return;
        }
        int bodyLen = body.size();
        if (bodyLen < 2) {
            return;
        }
        ArrayList<Integer> route = routeCellsTo(selectedWp);
        if (route.size() < 2) {
            return;
        }
        int steps = route.size() - 1;             // moves from the head to the waypoint
        int[] ghost = new int[bodyLen];
        int n = 0;
        // head first: the waypoint, then walk the route backwards
        for (int i = steps; i >= 0 && n < bodyLen; i--) {
            ghost[n++] = route.get(i);
        }
        // route shorter than the snake: continue along the cells already behind
        int[] bodyArr = cellsToArray();
        for (int i = 1; i < bodyLen && n < bodyLen; i++) {
            ghost[n++] = bodyArr[i];
        }
        if (n < 2) {
            return;
        }
        float half = cell / 2f;
        ghostBodyPaint.setStrokeWidth(cell * 0.60f);
        Path gp = new Path();
        for (int i = 0; i < n; i++) {
            float px = boardLeft + (ghost[i] % COLS) * cell + half;
            float py = boardTop + (ghost[i] / COLS) * cell + half;
            if (i == 0) {
                gp.moveTo(px, py);
            } else {
                gp.lineTo(px, py);
            }
        }
        c.drawPath(gp, ghostBodyPaint);
        // ghost head (at the waypoint) + tail marker at the far end
        float hpx = boardLeft + (ghost[0] % COLS) * cell + half;
        float hpy = boardTop + (ghost[0] / COLS) * cell + half;
        int tailCell = ghost[n - 1];
        float tpx = boardLeft + (tailCell % COLS) * cell + half;
        float tpy = boardTop + (tailCell / COLS) * cell + half;
        c.drawCircle(hpx, hpy, cell * 0.40f, ghostHeadPaint);
        c.drawCircle(tpx, tpy, cell * 0.24f, ghostHeadPaint);
    }

    /** Next cell of the player's straight leg: horizontal first, then vertical. */
    private int directStepCell(int from, int to) {
        int fx = from % COLS;
        int fy = from / COLS;
        int tx = to % COLS;
        int ty = to / COLS;
        if (fx != tx) {
            return fy * COLS + fx + Integer.compare(tx, fx);
        }
        if (fy != ty) {
            return (fy + Integer.compare(ty, fy)) * COLS + fx;
        }
        return -1;
    }

    /** Appends the straight L-shaped leg {@code from -> to} (target included). */
    private void appendDirectLeg(ArrayList<Integer> out, int from, int to) {
        int cur = from;
        int guard = COLS * rows + 1;
        while (cur != to && guard-- > 0) {
            int nxt = directStepCell(cur, to);
            if (nxt < 0) {
                break;
            }
            out.add(nxt);
            cur = nxt;
        }
    }

    /**
     * Cells from the head through the waypoints, using only the straight
     * horizontal/vertical legs of the player's setup - no path finding.
     */
    private ArrayList<Integer> routeCellsTo(int wpIndex) {
        ArrayList<Integer> out = new ArrayList<>();
        if (body.isEmpty() || wpIndex < 0 || wpIndex >= waypoints.size()) {
            return out;
        }
        int cur = body.peekFirst();
        out.add(cur);
        for (int i = 0; i <= wpIndex; i++) {
            int target = waypoints.get(i);
            if (target != cur) {
                appendDirectLeg(out, cur, target);
                cur = target;
            }
        }
        return out;
    }

    /** Arrow pad (+ delete) shown while a waypoint is selected. */
    private void drawWaypointPad(Canvas c) {
        // The strip background is painted by drawBottomBar (before the hint,
        // MOVE and pause button) so those stay on top of it.
        // FOLLOW (bottom-left cell): generated tail-following route to a safe fruit.
        boolean followOn = followRouteActive;
        c.drawRoundRect(padFollowBtn, dp(12f), dp(12f),
                followOn ? wpFillPaint : padBtnPaint);
        c.drawRoundRect(padFollowBtn, dp(12f), dp(12f), padBtnStrokePaint);
        float oldSize = padLabelPaint.getTextSize();
        padLabelPaint.setTextSize(dp(9f));
        padLabelPaint.setColor(followOn ? 0xFFFFD479 : 0xFFC8FFDD);
        c.drawText(followRouteActive ? "STOP" : "FOLLOW",
                padFollowBtn.centerX(), padFollowBtn.centerY() + dp(3.2f), padLabelPaint);
        padLabelPaint.setTextSize(oldSize);
        padLabelPaint.setColor(0xE6C8FFDD);
        if (selectedWp >= 0 && selectedWp < waypoints.size()) {
            drawPadButton(c, padUpBtn, 0);
            drawPadButton(c, padDownBtn, 1);
            drawPadButton(c, padLeftBtn, 2);
            drawPadButton(c, padRightBtn, 3);
            drawPadButton(c, padDelBtn, 4);
            drawPadButton(c, padListBtn, 5);
        }
        // AUTO is useful with an empty route too: keep the centre button visible
        // even when no waypoint is selected (the arrows stay hidden then).
        drawPadButton(c, padAutoBtn, 6);
    }

    private void setSpeedIndex(int index) {
        int next = Math.max(0, Math.min(SPEEDS.length - 1, index));
        speedIndex = next;
        getContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(KEY_SPEED, speedIndex).apply();
        haptic(false);
    }


    /** Centers of a settings option grid, for adb-driven UI checks. */
    private static String rectCenters(RectF[] rects) {
        StringBuilder sb = new StringBuilder();
        for (RectF r : rects) {
            if (r == null || r.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(';');
            }
            sb.append(Math.round(r.centerX())).append(',').append(Math.round(r.centerY()));
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private int algorithmIndex() {
        if (tailChaseOn) {
            return ALG_TAIL;
        }
        if (bfsOn) {
            return ALG_BFS;
        }
        if (smartShortcuts) {
            return ALG_SC;
        }
        if (zigzagOn) {
            return ALG_ZZ;
        }
        if (randomCycleOn) {
            return ALG_RND;
        }
        return ALG_OFF;
    }

    /** Exactly one algorithm may be selected, or none (ALG_OFF). */
    private void selectAlgorithm(int which) {
        AutoPilot previous = activePilot();
        tailChaseOn = which == ALG_TAIL;
        bfsOn = which == ALG_BFS;
        smartShortcuts = which == ALG_SC;
        zigzagOn = which == ALG_ZZ;
        randomCycleOn = which == ALG_RND;
        rndCyclePending = false;
        rndFallbackPilot = null;
        if (randomCycleOn && (state == STATE_RUNNING || state == STATE_PAUSED)
                && autoMode && randomPilot != null
                && !randomPilot.bodyOnCycle(cellsToArray())) {
            rndCyclePending = true;
            rndFallbackPilot = previous != null ? previous : pilot;
        }
        if (tailChaseOn) {
            stallTicks = 0;
        }
        applyAiToggles();
        haptic(false);
    }
    /* ------------------------------------------------------------------ */
    /* Settings (speed + algorithm)                                       */
    /* ------------------------------------------------------------------ */

    private void openSettings() {
        settingsOpen = true;
        layoutSettings();
        haptic(false);
    }

    private void closeSettings() {
        settingsOpen = false;
        haptic(false);
    }

    private void layoutSettings() {
        int w = getWidth();
        int h = getHeight();
        float panelW = Math.min(w - dp(24f), dp(360f));
        float panelH = Math.min(h - dp(80f), dp(420f));
        float cx = w / 2f;
        float cy = h / 2f;
        settingsPanel.set(cx - panelW / 2f, cy - panelH / 2f,
                cx + panelW / 2f, cy + panelH / 2f);

        float padX = dp(16f);
        float innerW = panelW - padX * 2f;
        float gap = dp(8f);
        float rowH = dp(40f);
        float closeH = dp(42f);
        float needed = dp(66f) + 2f * (rowH + gap) + dp(24f) + dp(10f)
                + 2f * (rowH + gap) + closeH + dp(24f);
        if (needed > panelH) {
            rowH = Math.max(dp(30f), rowH - (needed - panelH) / 4f);
        }
        float colW = (innerW - gap * 2f) / 3f;

        float speedTop = settingsPanel.top + dp(66f);
        for (int i = 0; i < settingsSpeedBtn.length; i++) {
            int col = i % 3;
            int row = i / 3;
            float x = settingsPanel.left + padX + col * (colW + gap);
            float y = speedTop + row * (rowH + gap);
            settingsSpeedBtn[i].set(x, y, x + colW, y + rowH);
        }
        settingsAlgoLabelY = speedTop + 2f * (rowH + gap) + dp(22f);
        float algoTop = settingsAlgoLabelY + dp(10f);
        for (int i = 0; i < settingsAlgoBtn.length; i++) {
            int col = i % 3;
            int row = i / 3;
            float x = settingsPanel.left + padX + col * (colW + gap);
            float y = algoTop + row * (rowH + gap);
            settingsAlgoBtn[i].set(x, y, x + colW, y + rowH);
        }
        float closeW = dp(140f);
        settingsCloseBtn.set(cx - closeW / 2f, settingsPanel.bottom - dp(12f) - closeH,
                cx + closeW / 2f, settingsPanel.bottom - dp(12f));
    }

    private void drawSettings(Canvas c) {
        layoutSettings();
        c.drawRect(0f, 0f, getWidth(), getHeight(), settingsDimPaint);
        c.drawRoundRect(settingsPanel, dp(20f), dp(20f), cardPaint);
        c.drawRoundRect(settingsPanel, dp(20f), dp(20f), cardStroke);

        float cx = settingsPanel.centerX();
        c.drawText("CÀI ĐẶT", cx, settingsPanel.top + dp(30f), cardTitlePaint);
        c.drawText("TỐC ĐỘ", cx, settingsPanel.top + dp(56f), readyHintPaint);
        for (int i = 0; i < settingsSpeedBtn.length; i++) {
            drawToggle(c, settingsSpeedBtn[i], SPEED_LABELS[i], i == speedIndex);
        }
        c.drawText("THUẬT TOÁN AUTO", cx, settingsAlgoLabelY, readyHintPaint);
        int alg = algorithmIndex();
        for (int i = 0; i < settingsAlgoBtn.length; i++) {
            drawToggle(c, settingsAlgoBtn[i], ALGO_LABELS[i], i == alg);
        }
        drawSecondaryButton(c, settingsCloseBtn, "ĐÓNG", false);
    }

    private boolean handleSettingsTouch(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_UP) {
            float x = e.getX();
            float y = e.getY();
            for (int i = 0; i < settingsSpeedBtn.length; i++) {
                if (settingsSpeedBtn[i].contains(x, y)) {
                    setSpeedIndex(i);
                    return true;
                }
            }
            for (int i = 0; i < settingsAlgoBtn.length; i++) {
                if (settingsAlgoBtn[i].contains(x, y)) {
                    selectAlgorithm(i);
                    return true;
                }
            }
            if (settingsCloseBtn.contains(x, y) || !settingsPanel.contains(x, y)) {
                closeSettings();
            }
        }
        return true;
    }

    private void drawToggle(Canvas c, RectF r, String label, boolean on) {
        c.drawRoundRect(r, dp(8f), dp(8f), on ? secondaryFillPaint : padBgPaint);
        c.drawRoundRect(r, dp(8f), dp(8f), on ? secondaryStrokePaint : pauseStroke);
        labelPaint.setLetterSpacing(0.02f);
        labelPaint.setColor(on ? 0xFFB9FFD8 : 0xFF8DA0BF);
        c.drawText(label, r.centerX(), r.centerY() + dp(3.4f), labelPaint);
        labelPaint.setLetterSpacing(0.16f);
        labelPaint.setColor(0xFF8DA0BF);
    }
    private void drawPadButton(Canvas c, RectF r, int icon) {
        c.drawRoundRect(r, dp(12f), dp(12f), padBtnPaint);
        c.drawRoundRect(r, dp(12f), dp(12f), padBtnStrokePaint);
        float cx = r.centerX();
        float cy = r.centerY();
        float s = Math.min(r.width(), r.height()) * 0.2f;
        if (icon == 4) {
            c.drawLine(cx - s, cy - s, cx + s, cy + s, padDelPaint);
            c.drawLine(cx + s, cy - s, cx - s, cy + s, padDelPaint);
            return;
        }
        if (icon == 5) {
            // "list" glyph: three stacked lines with bullets
            int col = 0xFFC8FFDD;
            padIconPaint.setColor(col);
            float gapY = s * 0.62f;
            for (int i = -1; i <= 1; i++) {
                c.drawCircle(cx - s * 0.92f, cy + i * gapY, s * 0.16f, padIconPaint);
                c.drawRect(cx - s * 0.55f, cy + i * gapY - s * 0.13f,
                        cx + s * 0.95f, cy + i * gapY + s * 0.13f, padIconPaint);
            }
            padIconPaint.setColor(0xE6C8FFDD);
            return;
        }
        if (icon == 6) {
            // AUTO button: label instead of an arrow
            c.drawText("AUTO", cx, cy + dp(3.4f), padLabelPaint);
            return;
        }
        Path p = new Path();
        if (icon == 0) {
            p.moveTo(cx, cy - s);
            p.lineTo(cx + s, cy + s * 0.7f);
            p.lineTo(cx - s, cy + s * 0.7f);
        } else if (icon == 1) {
            p.moveTo(cx, cy + s);
            p.lineTo(cx + s, cy - s * 0.7f);
            p.lineTo(cx - s, cy - s * 0.7f);
        } else if (icon == 2) {
            p.moveTo(cx - s, cy);
            p.lineTo(cx + s * 0.7f, cy - s);
            p.lineTo(cx + s * 0.7f, cy + s);
        } else {
            p.moveTo(cx + s, cy);
            p.lineTo(cx - s * 0.7f, cy - s);
            p.lineTo(cx - s * 0.7f, cy + s);
        }
        p.close();
        c.drawPath(p, padIconPaint);
    }

    /** MOVE / DỪNG pill (manual mode). */
    private void drawMoveButton(Canvas c) {
        boolean canMove = !waypoints.isEmpty();
        Paint textPaint = buttonTextSecondaryPaint;
        if (manualMoving) {
            buttonPaint.setShader(null);
            buttonPaint.setColor(0xFFFF5468);
        } else if (canMove) {
            buttonPaint.setShader(greenShaderFor(moveBtn.top, moveBtn.bottom));
            textPaint = buttonTextPaint;
        } else {
            buttonPaint.setShader(null);
            buttonPaint.setColor(0xFF1B2A44);
        }
        c.drawRoundRect(moveBtn, moveBtn.height() / 2f, moveBtn.height() / 2f, buttonPaint);
        buttonPaint.setShader(null);
        c.drawText(manualMoving ? "DỪNG" : "MOVE", moveBtn.centerX(),
                moveBtn.centerY() + dp(5f), textPaint);
    }

    private void drawFood(Canvas c) {
        float t = interpolation();

        if (foodCell >= 0) {
            float a = 1f;
            if (foodFresh) {
                if (t >= 0.999f) {
                    foodFresh = false;
                } else {
                    a = Math.max(0.2f, Math.min(1f, t * 1.5f));
                }
            }
            float pulse = 1f + 0.07f * (float) Math.sin(animTime * 5.2f);
            drawFruitAt(c, foodCell, pulse, a);
        }
    }

    /** One apple at the centre of {@code cellIdx}, scaled by {@code scale}, alpha-blended. */
    private void drawFruitAt(Canvas c, int cellIdx, float scale, float alpha) {
        float fx = boardLeft + ((cellIdx % COLS) + 0.5f) * cell;
        float fy = boardTop + ((cellIdx / COLS) + 0.5f) * cell;
        float r = cell * 0.31f;

        int a0 = foodPaint.getAlpha();
        int a1 = foodGlowPaint.getAlpha();
        int a2 = foodShinePaint.getAlpha();
        int a3 = stemPaint.getAlpha();
        int a4 = leafPaint.getAlpha();
        float a = Math.max(0f, Math.min(1f, alpha));
        foodPaint.setAlpha((int) (a0 * a));
        foodGlowPaint.setAlpha((int) (a1 * a));
        foodShinePaint.setAlpha((int) (a2 * a));
        stemPaint.setAlpha((int) (a3 * a));
        leafPaint.setAlpha((int) (a4 * a));

        c.save();
        c.translate(fx, fy);
        c.scale(scale, scale);
        c.drawCircle(0f, 0f, cell * 1.8f, foodGlowPaint);
        c.drawCircle(0f, 0f, r, foodPaint);

        tmpRect.set(-r * 0.5f, -r * 0.52f, -r * 0.04f, -r * 0.2f);
        c.save();
        c.rotate(-28f);
        c.drawOval(tmpRect, foodShinePaint);
        c.restore();

        stemPaint.setStrokeWidth(cell * 0.075f);
        c.drawLine(0f, -r * 0.92f, r * 0.16f, -r * 1.42f, stemPaint);

        tmpRect.set(r * 0.1f, -r * 1.5f, r * 0.8f, -r * 1.16f);
        c.drawOval(tmpRect, leafPaint);
        c.restore();

        foodPaint.setAlpha(a0);
        foodGlowPaint.setAlpha(a1);
        foodShinePaint.setAlpha(a2);
        stemPaint.setAlpha(a3);
        leafPaint.setAlpha(a4);
    }

    private float interpolation() {
        if (state != STATE_RUNNING && state != STATE_PAUSED) {
            return 1f;
        }
        if (planPaused) {
            // No step is coming while the player plans: finish the drawing instead of
            // leaving the head/tail a fraction of a cell behind (that looked like the
            // snake retreating one cell every time it stopped).
            return 1f;
        }
        float iv = interval();
        if (iv <= 0f) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, tickAccum / iv));
    }

    /** Wraps an angle in degrees into (-180, 180]. */
    private static float wrap180(float deg) {
        float a = deg % 360f;
        if (a <= -180f) {
            a += 360f;
        } else if (a > 180f) {
            a -= 360f;
        }
        return a;
    }

    private float cellPx(int c) {
        return boardLeft + (c % COLS + 0.5f) * cell;
    }

    private float cellPy(int c) {
        return boardTop + (c / COLS + 0.5f) * cell;
    }

    /**
     * Points that define the body outline. The body cells themselves never
     * move during a tick: only the head slides out into the next cell and the
     * tail tip slides in behind it. That keeps every corner vertex exactly in
     * place, so a bend is drawn once and can never "breathe".
     *
     * Layout: head tip, previous head cell, ... previous cells ..., tail tip.
     */
    private void computeDrawnPoints(int n, float t) {
        // One model everywhere: the chain is the CURRENT body and the head is drawn
        // gliding into the next cell the logic will take (hasNext). At t=0 that is
        // exactly the real body, at t=1 exactly the next body - so the snake never
        // lags behind and never jumps back when a step starts.
        int[] chain = curCells;
        int m = chain.length;
        int headCell = m > 0 ? chain[0]
                : (body.peekFirst() == null ? 0 : body.peekFirst());
        int realHead = headCell;
        if (m == 0) {
            if (drawnPts.length < 2) {
                drawnPts = new float[2];
            }
            drawnPts[0] = cellPx(headCell);
            drawnPts[1] = cellPy(headCell);
            drawnCount = 1;
            return;
        }
        int count = m + 1;
        if (drawnPts.length < count * 2) {
            drawnPts = new float[count * 2];
        }

        // The head slides from its real cell into the next one the logic will take,
        // while the body follows behind it (the cell it is leaving becomes the neck).
        // At t=1 the drawn chain is exactly the next body, so nothing ever snaps.
        int leadCell = hasNext ? (headCell + nextDy * COLS + nextDx) : realHead;
        float hx = cellPx(headCell);
        float hy = cellPy(headCell);
        drawnPts[0] = hx + (cellPx(leadCell) - hx) * t;
        drawnPts[1] = hy + (cellPy(leadCell) - hy) * t;

        int idx = 2;
        for (int i = 0; i < m - 1; i++) {
            drawnPts[idx++] = cellPx(chain[i]);
            drawnPts[idx++] = cellPy(chain[i]);
        }

        // The tail retires in the same tick, unless the coming step eats the apple (the
        // body grows, so the tail stays put for that tick).
        // The tail retires only while a step is actually going to happen; eating freezes
        // it for that tick (the body grows instead of retiring).
        boolean tailFreeze = !hasNext || leadCell == foodCell;
        if (!tailFreeze && m >= 2) {
            float tx = cellPx(chain[m - 1]);
            float ty = cellPy(chain[m - 1]);
            float bx = cellPx(chain[m - 2]);
            float by = cellPy(chain[m - 2]);
            drawnPts[idx++] = tx + (bx - tx) * t;
            drawnPts[idx++] = ty + (by - ty) * t;
        } else {
            drawnPts[idx++] = cellPx(chain[m - 1]);
            drawnPts[idx++] = cellPy(chain[m - 1]);
        }
        drawnCount = idx / 2;
    }

    private int bodyColor(float f) {
        if (f < 0.5f) {
            return mix(C_HEAD, C_MID, f / 0.5f);
        }
        return mix(C_MID, C_TAIL, (f - 0.5f) / 0.5f);
    }

    private static int mix(int a, int b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        int ar = (a >> 16) & 0xFF;
        int ag = (a >> 8) & 0xFF;
        int ab = a & 0xFF;
        int br = (b >> 16) & 0xFF;
        int bg = (b >> 8) & 0xFF;
        int bb = b & 0xFF;
        return Color.rgb((int) (ar + (br - ar) * t),
                (int) (ag + (bg - ag) * t),
                (int) (ab + (bb - ab) * t));
    }

    private void drawSnake(Canvas c) {
        int n = body.size();
        if (n == 0) {
            return;
        }
        float t = interpolation();
        float alpha = (state == STATE_DEAD) ? 0.72f : 1f;
        float angleDelta = headAngleTo - headAngleFrom;
        while (angleDelta > 180f) {
            angleDelta -= 360f;
        }
        while (angleDelta < -180f) {
            angleDelta += 360f;
        }
        // Turning is tied to the cell move; with no move in progress the capsule keeps
        // its target angle. (Before, a settled stop left t=1 and starting the run reset
        // t to 0, so the head visibly turned and then turned back.)
        headAngleDeg = hasNext ? wrap180(headAngleFrom + angleDelta * t)
                : wrap180(headAngleTo);

        computeDrawnPoints(n, t);

        // Six passes over one centre line fake a lit tube: drop shadow, base
        // colour, dark underside, light top, specular streak and scale plates.
        if (drawnCount >= 2) {
            buildCentrePath();
            float bw = cell * 0.60f;

            bodyShadowPaint.setStrokeWidth(bw + dp(2.6f));
            bodyShadowPaint.setAlpha((int) (0x4D * alpha));
            c.save();
            c.translate(dp(1.6f), dp(2.6f));
            c.drawPath(bodyPath, bodyShadowPaint);
            c.restore();

            bodyPaint.setAlpha((int) (255 * alpha));
            bodyPaint.setStrokeWidth(bw);
            c.drawPath(bodyPath, bodyPaint);

            bodyDarkPaint.setStrokeWidth(bw * 0.66f);
            bodyDarkPaint.setAlpha((int) (0x7A * alpha));
            c.save();
            c.translate(0f, bw * 0.19f);
            c.drawPath(bodyPath, bodyDarkPaint);
            c.restore();

            bodyLightPaint.setStrokeWidth(bw * 0.36f);
            bodyLightPaint.setAlpha((int) (0x5E * alpha));
            c.save();
            c.translate(0f, -bw * 0.18f);
            c.drawPath(bodyPath, bodyLightPaint);
            c.restore();

            bodySpecPaint.setStrokeWidth(Math.max(dp(1.4f), bw * 0.13f));
            bodySpecPaint.setAlpha((int) (0x4C * alpha));
            c.save();
            c.translate(-bw * 0.03f, -bw * 0.25f);
            c.drawPath(bodyPath, bodySpecPaint);
            c.restore();

            drawBodyScales(c, alpha, bw);
        }

        drawHead(c, drawnPts[0], drawnPts[1], alpha);
    }

    /**
     * Short rib lines across the body (belly plates): they are what reads as
     * "scales" at this size and they break the plastic look of a flat stroke.
     */
    private void drawBodyScales(Canvas c, float alpha, float bw) {
        bodyMeasure.setPath(bodyPath, false);
        float len = bodyMeasure.getLength();
        if (len <= dp(6f)) {
            return;
        }
        float step = Math.max(cell * 0.42f, len / 90f);
        bodyPlatePaint.setAlpha((int) (0x26 * alpha));
        bodyPlatePaint.setStrokeWidth(Math.max(dp(1f), bw * 0.09f));
        for (float d = step; d < len - step * 0.5f; d += step) {
            if (!bodyMeasure.getPosTan(d, measurePos, measureTan)) {
                return;
            }
            float tx = measureTan[0];
            float ty = measureTan[1];
            float nx = -ty * bw * 0.30f;
            float ny = tx * bw * 0.30f;
            c.drawLine(measurePos[0] - nx, measurePos[1] - ny,
                    measurePos[0] + nx, measurePos[1] + ny, bodyPlatePaint);
        }
    }

    /** Polyline through the drawn points; ROUND join bends the corners. */
    private void buildCentrePath() {
        bodyPath.reset();
        bodyPath.moveTo(drawnPts[0], drawnPts[1]);
        for (int i = 1; i < drawnCount; i++) {
            bodyPath.lineTo(drawnPts[i * 2], drawnPts[i * 2 + 1]);
        }
    }

    private void drawHead(Canvas c, float hx, float hy, float alpha) {
        float w = cell * 0.88f;        // head stays noticeably bigger than the body
        float hw = w * 0.5f;
        float hl = cell * 0.50f;       // half length (capsule)
        float er = w * 0.13f;

        float angle = headAngleDeg;
        c.save();
        // Centred exactly on the head cell (no forward shift, no tongue).
        c.translate(hx, hy);
        // Contact shadow first, in screen space, so it shows the head is lifted off
        // the board (the light comes from the top-left).
        contactShadowPaint.setAlpha((int) (0x4D * alpha));
        tmpRect.set(-hl + dp(1.6f), -hw + dp(2.4f), hl + dp(1.6f), hw + dp(2.4f));
        c.drawRoundRect(tmpRect, hw, hw, contactShadowPaint);
        c.rotate(angle);

        headGlowPaint.setAlpha((int) (255 * alpha));
        c.drawCircle(0f, 0f, w * 1.10f, headGlowPaint);

        tmpRect.set(-hl - dp(1.4f), -hw - dp(1.4f), hl + dp(1.4f), hw + dp(1.4f));
        headOutlinePaint.setAlpha((int) (0xE0 * alpha));
        c.drawRoundRect(tmpRect, hw + dp(1.4f), hw + dp(1.4f), headOutlinePaint);

        tmpRect.set(-hl, -hw, hl, hw);
        headFillPaint.setAlpha((int) (255 * alpha));
        c.drawRoundRect(tmpRect, hw, hw, headFillPaint);

        // Rounded shading: darker chin, glossy top-left, so the capsule reads as a
        // rounded volume instead of a flat sticker.
        headChinPaint.setAlpha((int) (0x3A * alpha));
        tmpRect.set(-hl * 0.94f, hw * 0.18f, hl * 0.94f, hw * 0.96f);
        c.drawRoundRect(tmpRect, hw * 0.5f, hw * 0.5f, headChinPaint);
        headSpecPaint.setAlpha((int) (0x55 * alpha));
        tmpRect.set(-hl * 0.66f, -hw * 0.82f, hl * 0.22f, -hw * 0.22f);
        c.drawRoundRect(tmpRect, hw * 0.4f, hw * 0.4f, headSpecPaint);

        for (int s = -1; s <= 1; s += 2) {
            float ex = hl * 0.30f;
            float ey = hw * 0.58f * s;
            eyeWhitePaint.setAlpha((int) (255 * alpha));
            tmpOval.set(ex - er * 0.85f, ey - er, ex + er * 0.85f, ey + er);
            c.drawOval(tmpOval, eyeWhitePaint);

            if (state == STATE_DEAD) {
                float k = er * 0.80f;
                eyePupilStroke.setAlpha((int) (255 * alpha));
                eyePupilStroke.setStrokeWidth(Math.max(dp(1.5f), w * 0.05f));
                c.drawLine(ex - k, ey - k, ex + k, ey + k, eyePupilStroke);
                c.drawLine(ex - k, ey + k, ex + k, ey - k, eyePupilStroke);
            } else {
                eyePupilPaint.setAlpha((int) (255 * alpha));
                c.drawCircle(ex + w * 0.045f, ey, w * 0.075f, eyePupilPaint);
                eyeShinePaint.setAlpha((int) (230 * alpha));
                c.drawCircle(ex + w * 0.075f, ey - w * 0.035f,
                        w * 0.028f, eyeShinePaint);
            }
        }

        nostrilPaint.setAlpha((int) (0x99 * alpha));
        c.drawCircle(hl * 0.74f, -hw * 0.22f, w * 0.030f, nostrilPaint);
        c.drawCircle(hl * 0.74f, hw * 0.22f, w * 0.030f, nostrilPaint);
        // No tongue: the head itself fills the cell it stands on, so what you see
        // is exactly the grid cell the snake occupies.
        c.restore();
    }

    private void drawParticles(Canvas c) {
        for (Particle p : particles) {
            float a = Math.max(0f, p.life / p.maxLife);
            particlePaint.setColor(p.color);
            particlePaint.setAlpha((int) (a * 255));
            c.drawCircle(p.x, p.y, p.r * (0.4f + a * 0.6f), particlePaint);
        }
    }

    private void drawPopups(Canvas c) {
        for (Popup p : popups) {
            float prog = 1f - Math.max(0f, p.life / Popup.DURATION);
            int alpha = (int) (255 * Math.max(0f, 1f - prog * 1.15f));
            popupPaint.setAlpha(alpha);
            float px = boardLeft + (p.cx + 0.5f) * cell;
            float py = boardTop + (p.cy + 0.5f) * cell - prog * dp(42f);
            c.drawText(p.text, px, py, popupPaint);
        }
    }

    private void drawHud(Canvas c) {
        float padX = dp(13f);
        float height = dp(52f);

        String scoreLabel = "ĐIỂM";
        String bestLabel = "KỶ LỤC";
        String scoreText = String.valueOf(score);
        String bestText = String.valueOf(highScore);

        float scoreW = Math.max(dp(76f), Math.max(labelPaint.measureText(scoreLabel),
                scorePaint.measureText(scoreText)) + padX * 2f);
        float bestW = Math.max(dp(76f), Math.max(labelPaint.measureText(bestLabel),
                scorePaint.measureText(bestText)) + padX * 2f);

        drawPill(c, boardLeft, hudTop + dp(0f), scoreW, height, scoreLabel, scoreText);
        drawPill(c, boardRect.right - bestW, hudTop + dp(0f), bestW, height,
                bestLabel, bestText);

        if (autoMode) {
            drawAutoBadge(c);
        } else {
            autoBadge.setEmpty();
        }
    }

    private void drawAutoBadge(Canvas c) {
        String label = bfsOn ? "AUTOPLAY · BFS"
                : (tailChaseOn ? "AUTOPLAY · TAIL" : "AUTOPLAY");
        float bh = dp(24f);
        float bw = (bfsOn || tailChaseOn) ? dp(126f) : dp(86f);
        float bx = pauseCx - pauseR - dp(14f) - bw;
        float by = pauseCy - bh / 2f;
        autoBadge.set(bx - dp(4f), by - dp(6f), bx + bw + dp(4f), by + bh + dp(6f));

        tmpRect.set(bx, by, bx + bw, by + bh);
        c.drawRoundRect(tmpRect, bh / 2f, bh / 2f, autoBadgePaint);
        c.drawRoundRect(tmpRect, bh / 2f, bh / 2f, autoBadgeStroke);

        float pulse = 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.sin(animTime * 4.2f));
        autoDotPaint.setAlpha((int) (255 * pulse));
        c.drawCircle(bx + dp(13f), by + bh / 2f, dp(3.4f), autoDotPaint);
        c.drawText(label, bx + dp(21f), by + bh / 2f + dp(3.9f), autoBadgeText);
    }

    private void drawPill(Canvas c, float x, float y, float w, float h,
                          String label, String value) {
        tmpRect.set(x, y, x + w, y + h);
        c.drawRoundRect(tmpRect, dp(14f), dp(14f), hudPillPaint);
        c.drawRoundRect(tmpRect, dp(14f), dp(14f), hudPillStroke);
        float cx = tmpRect.centerX();
        c.drawText(label, cx, y + dp(16f), labelPaint);
        c.drawText(value, cx, y + dp(41f), scorePaint);
    }

    private void drawBottomBar(Canvas c) {
        if (!autoMode && mode == MODE_MANUAL) {
            // Control strip background first, so hint / MOVE / pause sit on top.
            c.drawRoundRect(padPanel, dp(16f), dp(16f), padBgPaint);
        }
        String hint;
        boolean collision = false;
        if (autoMode) {
            hint = "AI đang chơi — chạm badge để tự điều khiển";
        } else if (mode == MODE_MANUAL) {
            if (followRouteActive) {
                hint = "FOLLOW: route đã tạo — bấm MOVE để chạy, FOLLOW để xoá";
            } else if (manualMoving) {
                hint = "Bấm DỪNG để sửa route";
            } else if (waypoints.isEmpty()) {
                hint = "Chạm board đặt waypoint";
            } else {
                hint = "Bấm MOVE để chạy";
            }
            if (!waypoints.isEmpty()) {
                ArrayList<Integer> hits = routeCollisions();
                if (!hits.isEmpty()) {
                    int hitCell = hits.get(0);
                    hint = "COLLISION: route đâm thân ở (" + (hitCell % COLS) + ","
                            + (hitCell / COLS) + ")  •  dời waypoint";
                }
            }
            collision = hint.startsWith("COLLISION");
        } else {
            hint = "Vuốt để điều khiển";
        }
        boolean noticeActive = noticeText.length() > 0
                && android.os.SystemClock.uptimeMillis() < noticeUntilMs;
        if (noticeActive) {
            hint = noticeText;
            collision = false;
        }
        hint += "  •  Chết: " + deaths;
        int hintColor = noticeActive
                ? (noticeError ? 0xFFFF8FA3 : 0xFFB9FFD8)
                : (collision ? 0xFFFF8FA3 : 0xFF647699);
        hintPaint.setColor(hintColor);
        c.drawText(hint, boardLeft + dp(2f), hintTop, hintPaint);
        hintPaint.setColor(0xFF647699);
        if (!autoMode && mode == MODE_MANUAL) {
            drawMoveButton(c);
        }
        drawDumpButton(c);
        if (!autoMode && mode == MODE_MANUAL && !waypoints.isEmpty()) {
            drawClearButton(c);
        }
        drawPauseButton(c);
    }

    /** CLEAR WP pill: drops the whole waypoint route in one tap. */
    private void drawClearButton(Canvas c) {
        c.drawRoundRect(clearBtn, dp(10f), dp(10f), padBtnPaint);
        c.drawRoundRect(clearBtn, dp(10f), dp(10f), padBtnStrokePaint);
        float oldSize = padLabelPaint.getTextSize();
        padLabelPaint.setTextSize(dp(10f));
        padLabelPaint.setColor(0xFFFFC8D2);
        c.drawText("CLEAR WP", clearBtn.centerX(), clearBtn.centerY() + dp(3.4f),
                padLabelPaint);
        padLabelPaint.setTextSize(oldSize);
        padLabelPaint.setColor(0xE6C8FFDD);
    }

    /** Removes every waypoint and stops the current route. */
    private void clearWaypoints() {
        int removed = waypoints.size();
        waypoints.clear();
        selectedWp = -1;
        manualMoving = false;
        followRouteActive = false;
        wpListOpen = false;
        tickAccum = 0f;
        logLine("CLEAR wps=" + removed);
        showNotice("Đã xoá " + removed + " waypoint", false);
        haptic(false);
    }

    /** Small DUMP pill: saves a full diagnostic snapshot for the user. */
    private void drawDumpButton(Canvas c) {
        c.drawRoundRect(dumpBtn, dp(12f), dp(12f), padBtnPaint);
        c.drawRoundRect(dumpBtn, dp(12f), dp(12f), padBtnStrokePaint);
        float oldSize = padLabelPaint.getTextSize();
        padLabelPaint.setTextSize(dp(9f));
        padLabelPaint.setColor(0xFFC8FFDD);
        c.drawText("DUMP", dumpBtn.centerX(), dumpBtn.centerY() + dp(3.2f), padLabelPaint);
        padLabelPaint.setTextSize(oldSize);
        padLabelPaint.setColor(0xE6C8FFDD);
    }


    private void drawPauseButton(Canvas c) {
        c.drawCircle(pauseCx, pauseCy, pauseR, pauseBgPaint);
        c.drawCircle(pauseCx, pauseCy, pauseR, pauseStroke);

        float w = dp(4.5f);
        float gap = dp(6.5f);
        float h = dp(15f);

        if (state == STATE_PAUSED) {
            Path p = new Path();
            float s = dp(7.5f);
            p.moveTo(pauseCx - s * 0.55f, pauseCy - s);
            p.lineTo(pauseCx + s * 0.95f, pauseCy);
            p.lineTo(pauseCx - s * 0.55f, pauseCy + s);
            p.close();
            c.drawPath(p, pauseIconPaint);
        } else {
            tmpRect.set(pauseCx - gap / 2f - w, pauseCy - h / 2f,
                    pauseCx - gap / 2f, pauseCy + h / 2f);
            c.drawRoundRect(tmpRect, w / 2f, w / 2f, pauseIconPaint);
            tmpRect.set(pauseCx + gap / 2f, pauseCy - h / 2f,
                    pauseCx + gap / 2f + w, pauseCy + h / 2f);
            c.drawRoundRect(tmpRect, w / 2f, w / 2f, pauseIconPaint);
        }
    }

    /* ------------------------------------------------------------------ */
    /* Overlays                                                           */
    /* ------------------------------------------------------------------ */

    private void drawOverlay(Canvas c) {
        c.drawRoundRect(boardRect, dp(18f), dp(18f), dimPaint);

        if (state == STATE_READY) {
            drawReadyOverlay(c);
        } else if (state == STATE_PAUSED) {
            drawPausedOverlay(c);
        } else if (state == STATE_DEAD) {
            drawDeadOverlay(c, false);
        } else if (state == STATE_WON) {
            drawDeadOverlay(c, true);
        }
    }

    /**
     * One vertical menu shared by the home and pause screens. The speed and
     * algorithm rows cycle their value on tap, so there is no second menu page.
     */
    /**
     * Home and pause share one vertical menu style. CÀI ĐẶT opens the settings
     * screen; the action buttons keep the same width, gap and height on both.
     */
    private void drawReadyOverlay(Canvas c) {
        layoutVerticalMenu(true, 3);
        float cx = boardRect.centerX();
        readyTitlePaint.setShadowLayer(dp(18f), 0f, dp(2f), 0x8835D07F);
        c.drawText("SNAKE", cx, startBtn.top - dp(54f), readyTitlePaint);
        readyTitlePaint.clearShadowLayer();

        drawPrimaryButton(c, startBtn, "CHƠI", false);
        drawSecondaryButton(c, autoBtn, "AUTOPLAY", false);
        drawSecondaryButton(c, settingsBtn, "CÀI ĐẶT", false);
    }

    private void drawPausedOverlay(Canvas c) {
        layoutVerticalMenu(false, 4);
        float cx = boardRect.centerX();
        c.drawText("TẠM DỪNG", cx, resumeBtn.top - dp(38f), pausedTitlePaint);

        drawPrimaryButton(c, resumeBtn, "TIẾP TỤC", false);
        drawSecondaryButton(c, autoResumeBtn, "AUTOPLAY", false);
        drawSecondaryButton(c, resetBtn, "CHƠI LẠI", false);
        drawSecondaryButton(c, settingsBtn, "CÀI ĐẶT", false);
    }

    /** Vertical stack used by both menus (home: 3 rows, pause: 4 rows). */
    private void layoutVerticalMenu(boolean home, int count) {
        float gap = dp(10f);
        float bh = Math.min(dp(46f),
                Math.max(dp(30f), (boardH * 0.70f - (count - 1) * gap) / count));
        float bw = Math.min(boardW * 0.86f, dp(320f));
        float total = count * bh + (count - 1) * gap;
        float bx = boardRect.centerX() - bw / 2f;
        float top = boardRect.centerY() - total / 2f + dp(6f);
        if (home) {
            startBtn.set(bx, top, bx + bw, top + bh);
            autoBtn.set(bx, top + bh + gap, bx + bw, top + 2f * bh + gap);
            settingsBtn.set(bx, top + 2f * (bh + gap), bx + bw, top + 3f * bh + 2f * gap);
        } else {
            resumeBtn.set(bx, top, bx + bw, top + bh);
            autoResumeBtn.set(bx, top + bh + gap, bx + bw, top + 2f * bh + gap);
            resetBtn.set(bx, top + 2f * (bh + gap), bx + bw, top + 3f * bh + 2f * gap);
            settingsBtn.set(bx, top + 3f * (bh + gap), bx + bw, top + 4f * bh + 3f * gap);
        }
    }
    private void drawDeadOverlay(Canvas c, boolean won) {
        float cx = boardRect.centerX();
        float cy = boardRect.centerY();
        float cw = Math.min(boardW * 0.86f, dp(360f));
        float ch = Math.min(boardH * 0.68f, dp(340f));
        tmpRect.set(cx - cw / 2f, cy - ch / 2f, cx + cw / 2f, cy + ch / 2f);

        float appear = Math.min(1f, deadTime / 0.18f);
        c.save();
        c.scale(0.94f + 0.06f * appear, 0.94f + 0.06f * appear, cx, cy);

        c.drawRoundRect(tmpRect, dp(22f), dp(22f), cardPaint);
        c.drawRoundRect(tmpRect, dp(22f), dp(22f), cardStroke);

        float top = tmpRect.top;
        int titleColor = cardTitlePaint.getColor();
        cardTitlePaint.setColor(won ? 0xFFFFD479 : titleColor);
        c.drawText(won ? "CHIẾN THẮNG" : "KẾT THÚC", cx, top + ch * 0.17f, cardTitlePaint);
        cardTitlePaint.setColor(titleColor);

        cardBigPaint.setColor(score > 0 && score >= highScore ? 0xFFFFD479 : 0xFFEAF2FF);
        c.drawText(String.valueOf(score), cx, top + ch * 0.41f, cardBigPaint);

        c.drawText("Kỷ lục: " + highScore + "   ·   Chết: " + deaths,
                cx, top + ch * 0.52f, cardSubPaint);

        if (won) {
            c.drawText("Đã lấp kín toàn bộ màn chơi!", cx, top + ch * 0.585f,
                    readyHintPaint);
        }

        // Two choices: CHƠI LẠI (manual) or AUTOPLAY (cycle AI).
        float gap = dp(8f);
        float bw = (cw * 0.86f - gap) / 2f;
        float bh = Math.min(dp(48f), ch * 0.17f);
        float by = top + ch * 0.64f;
        float left = cx - (bw * 2f + gap) / 2f;
        restartBtn.set(left, by, left + bw, by + bh);
        autoRestartBtn.set(left + bw + gap, by, left + 2f * bw + gap, by + bh);
        pathRestartBtn.setEmpty();
        mangoRestartBtn.setEmpty();

        drawPrimaryButton(c, restartBtn, "CHƠI LẠI", false);
        drawSecondaryButton(c, autoRestartBtn, "AUTOPLAY", false);

        c.drawText("chọn CHƠI LẠI / AUTOPLAY để chơi ván mới", cx,
                by + bh + dp(22f), readyHintPaint);

        c.restore();
    }

    /* ------------------------------------------------------------------ */
    /* Buttons                                                            */
    /* ------------------------------------------------------------------ */

    private Shader greenShaderFor(float top, float bottom) {
        if (greenShader == null
                || Math.abs(greenShaderTop - top) > 0.5f
                || Math.abs(greenShaderBottom - bottom) > 0.5f) {
            greenShader = new LinearGradient(0, top, 0, bottom,
                    0xFF8BFFBE, 0xFF1FA868, Shader.TileMode.CLAMP);
            greenShaderTop = top;
            greenShaderBottom = bottom;
        }
        return greenShader;
    }

    private void drawPrimaryButton(Canvas c, RectF r, String label, boolean icon) {
        buttonPaint.setShader(greenShaderFor(r.top, r.bottom));
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, buttonPaint);
        buttonPaint.setShader(null);

        float tw = buttonTextPaint.measureText(label);
        float cy = r.centerY() + dp(5.4f);
        if (icon) {
            float group = dp(11f) + dp(7f) + tw;
            float start = r.centerX() - group / 2f;
            drawPlayIcon(c, start + dp(5.5f), r.centerY(), dp(5.5f), 0xFF062012);
            c.drawText(label, start + dp(11f) + dp(7f) + tw / 2f, cy, buttonTextPaint);
        } else {
            c.drawText(label, r.centerX(), cy, buttonTextPaint);
        }
    }

    private void drawSecondaryButton(Canvas c, RectF r, String label, boolean icon) {
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, secondaryFillPaint);
        c.drawRoundRect(r, r.height() / 2f, r.height() / 2f, secondaryStrokePaint);

        float tw = buttonTextSecondaryPaint.measureText(label);
        float cy = r.centerY() + dp(5.4f);
        if (icon) {
            float group = dp(11f) + dp(7f) + tw;
            float start = r.centerX() - group / 2f;
            drawPlayIcon(c, start + dp(5.5f), r.centerY(), dp(5.5f), 0xFFC8FFDD);
            c.drawText(label, start + dp(11f) + dp(7f) + tw / 2f, cy,
                    buttonTextSecondaryPaint);
        } else {
            c.drawText(label, r.centerX(), cy, buttonTextSecondaryPaint);
        }
    }

    private void drawPlayIcon(Canvas c, float cx, float cy, float s, int color) {
        Path p = new Path();
        p.moveTo(cx - s * 0.55f, cy - s);
        p.lineTo(cx + s * 0.95f, cy);
        p.lineTo(cx - s * 0.55f, cy + s);
        p.close();
        pauseIconPaint.setColor(color);
        c.drawPath(p, pauseIconPaint);
    }

    /* ------------------------------------------------------------------ */
    /* Effects                                                            */
    /* ------------------------------------------------------------------ */

    private void spawnEatFx(int cx, int cy) {
        float px = boardLeft + (cx + 0.5f) * cell;
        float py = boardTop + (cy + 0.5f) * cell;
        for (int i = 0; i < 12; i++) {
            Particle p = new Particle();
            p.x = px;
            p.y = py;
            double a = rnd.nextDouble() * Math.PI * 2.0;
            float sp = dp(70f) + rnd.nextFloat() * dp(180f);
            p.vx = (float) Math.cos(a) * sp;
            p.vy = (float) Math.sin(a) * sp - dp(40f);
            p.maxLife = 0.40f + rnd.nextFloat() * 0.35f;
            p.life = p.maxLife;
            p.r = cell * (0.05f + rnd.nextFloat() * 0.06f);
            p.color = (i % 3 == 0) ? 0xFFFFE18A : 0xFFFF6B6B;
            particles.add(p);
        }
    }

    private void spawnDeathFx(int cx, int cy) {
        float px = boardLeft + (cx + 0.5f) * cell;
        float py = boardTop + (cy + 0.5f) * cell;
        for (int i = 0; i < 22; i++) {
            Particle p = new Particle();
            p.x = px;
            p.y = py;
            double a = rnd.nextDouble() * Math.PI * 2.0;
            float sp = dp(90f) + rnd.nextFloat() * dp(260f);
            p.vx = (float) Math.cos(a) * sp;
            p.vy = (float) Math.sin(a) * sp - dp(60f);
            p.maxLife = 0.5f + rnd.nextFloat() * 0.45f;
            p.life = p.maxLife;
            p.r = cell * (0.05f + rnd.nextFloat() * 0.08f);
            p.color = (i % 3 == 0) ? 0xFF9BE7FF : 0xFFFF5468;
            particles.add(p);
        }
    }

    private static final class Particle {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        float maxLife;
        float r;
        int color;
    }

    private static final class Popup {
        static final float DURATION = 0.85f;
        final String text = "+10";
        final int cx;
        final int cy;
        float life = DURATION;

        Popup(int cx, int cy) {
            this.cx = cx;
            this.cy = cy;
        }
    }
}
