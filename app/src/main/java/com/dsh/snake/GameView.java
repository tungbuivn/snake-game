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
    private static final int MODE_AUTOWIN = 1;
    private static final int MODE_SHORTPATH = 2;
    /** MangoSnake flavour: randomized Hamiltonian cycle every game. */
    private static final int MODE_MANGO = 3;

    private static final String PREFS = "snake_prefs";
    private static final String KEY_HIGH = "high_score";

    private static final int C_HEAD = 0xFFB9FFD8;
    private static final int C_MID = 0xFF35D07F;
    private static final int C_TAIL = 0xFF11784F;

    /** Snake palette; the colour advances on every death. */
    private static final int[] SNAKE_COLORS = {
            0xFF35D07F, 0xFF4FA8FF, 0xFFFFB74D, 0xFFB388FF, 0xFFFF6E8A, 0xFF4DD0E1,
    };

    private static final float SWIPE_SLOP_DP = 20f;

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
    private final RectF padReplayBtn = new RectF();
    private final RectF padFollowBtn = new RectF();
    private final RectF padPanel = new RectF();

    /* --- manual FOLLOW: walk along the body / follow the tail ---------- */
    private boolean followPicking;
    private boolean following;
    private int followStop = -1;

    /* --- drag & drop waypoint list dialog ------------------------------ */
    private boolean wpListOpen;
    private int listDragIndex = -1;
    private boolean listDragging;
    private float listDownY;
    private float listRowH = 0f;
    private final RectF listPanel = new RectF();
    private final RectF listCloseBtn = new RectF();

    /* --- replay playback (like a video player) ------------------------- */
    private boolean replayMode;
    private int[] replayPath = new int[0];
    private int replayPos;
    private float replayAccum;
    private static final float REPLAY_STEP_MS = 55f;
    private final RectF autoBadge = new RectF();
    private final RectF tmpRect = new RectF();
    private float pauseCx;
    private float pauseCy;
    private float pauseR;
    private float hudTop;
    private float hudH;
    private float bottomH;
    private float hintTop;

    private final ArrayDeque<Integer> body = new ArrayDeque<>();
    private final ArrayDeque<int[]> dirQueue = new ArrayDeque<>();
    private int[] prevCells = new int[0];
    private int dirX = 1;
    private int dirY = 0;
    private int foodCell = -1;
    private int score;
    private int highScore;
    private int deaths;
    private int snakeColor;
    private int state = STATE_READY;
    private float tickAccum;
    private float currentInterval = 168f;
    private float speedScale = 0.5f;
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
    private AutoPilot mangoPilot;
    private PathPilot pathPilot;

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
    private Paint replayBodyPaint;
    private Paint replayHeadPaint;
    private Paint replayLabelPaint;
    private Paint ghostBodyPaint;
    private Paint ghostHeadPaint;

    private float downX;
    private float downY;
    private boolean gestureConsumed;
    private boolean tapOnPause;
    private float[] drawnPts = new float[0];
    private int drawnCount;
    private Shader greenShader;
    private float greenShaderTop = Float.NaN;
    private float greenShaderBottom = Float.NaN;

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

        buildPaints();
        resetBoardState();
    }

    /* ------------------------------------------------------------------ */
    /* Setup                                                              */
    /* ------------------------------------------------------------------ */

    private float dp(float v) {
        return v * density;
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

        // Replay playback: translucent cyan snake walking the recorded route.
        replayBodyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        replayBodyPaint.setStyle(Paint.Style.STROKE);
        replayBodyPaint.setColor(0xB37FE7FF);
        replayBodyPaint.setStrokeCap(Paint.Cap.ROUND);
        replayBodyPaint.setStrokeJoin(Paint.Join.ROUND);
        replayHeadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        replayHeadPaint.setColor(0xE67FE7FF);
        replayLabelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        replayLabelPaint.setTypeface(bold);
        replayLabelPaint.setTextSize(dp(10f));
        replayLabelPaint.setColor(0xFFBFF4FF);
        replayLabelPaint.setTextAlign(Paint.Align.CENTER);

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
        // Dedicated control strip under the grid: waypoint pad + PREVIEW row and
        // the MOVE / pause buttons. Reserving it always keeps the grid size
        // stable when the player switches modes.
        float pb = dp(40f);
        float pg = dp(6f);
        float padW = pb * 3f + pg * 2f;
        float padH = pb * 3f + pg * 2f;
        bottomH = dp(18f) + padH + dp(8f) + dp(32f) + dp(8f);
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
            // AUTOWIN keeps the classic fixed Hamiltonian cycle; MANGO gets a
            // fresh randomized cycle (MangoSnake) on every game.
            pilot = new AutoPilot(COLS, rows, false);
            mangoPilot = new AutoPilot(COLS, rows, true);
            pathPilot = new PathPilot(COLS, rows);
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
        float replayH = dp(32f);
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
        // FOLLOW lives in the bottom-left cell of the pad.
        padFollowBtn.set(padL, padT + 2f * (pb + pg), padL + pb,
                padT + 3f * pb + 2f * pg);
        // PREVIEW sits under the pad, still inside the control strip.
        float replayTop = padT + padH + dp(8f);
        padReplayBtn.set(padL, replayTop, padL + padW, replayTop + replayH);

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
        if (staticBmp != null) {
            staticBmp.recycle();
            staticBmp = null;
        }
    }

    @Override
    public void run() {
        long last = System.nanoTime();
        int frames = 0;
        while (running) {
            long frameStart = System.nanoTime();
            float dt = Math.min(0.05f, (frameStart - last) / 1_000_000_000f);
            last = frameStart;

            if (surfaceReady) {
                Canvas c = null;
                try {
                    c = holder.lockCanvas();
                    if (c != null) {
                        update(dt);
                        render(c);
                        frames++;
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

            long spent = (System.nanoTime() - frameStart) / 1_000_000L;
            long target = (state == STATE_RUNNING) ? 15L : 30L;
            long sleep = target - spent;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Simulation                                                         */
    /* ------------------------------------------------------------------ */

    /**
     * Speed model with acceleration. The target interval shrinks as the score
     * grows, and in auto mode it is also limited so the snake covers the
     * remaining distance to the fruit within at most 3 seconds:
     *
     *     interval <= 3000ms / stepsToFood
     *
     * A feedback loop refines it: every fruit that took longer than 3s speeds
     * the snake up (speedScale down), a quick one lets it relax again. The
     * current interval walks towards the target at a bounded rate, which is the
     * visible acceleration.
     *
     * If the fruit is still not eaten after 3 seconds, the target keeps shrinking
     * exponentially with the overdue time (down to a hard 5ms floor) and the
     * acceleration limit is lifted, so the snake keeps gaining speed until it
     * reaches the fruit.
     */
    /** The cycle pilot of the current mode (null for manual / short path). */
    private AutoPilot activePilot() {
        if (mode == MODE_AUTOWIN) {
            return pilot;
        }
        if (mode == MODE_MANGO) {
            return mangoPilot;
        }
        return null;
    }

    private void updateSpeed(float dt) {
        float floor = autoMode ? 22f : 70f;
        float target = Math.max(floor, 168f - (score / 10) * 4f);
        boolean catchingUp = false;
        if (autoMode) {
            target *= speedScale;
            int steps = -1;
            if (mode == MODE_SHORTPATH && pathPilot != null) {
                steps = pathPilot.getStepsToFood();
            } else if (activePilot() != null) {
                steps = activePilot().getStepsToFood();
            }
            if (steps > 0) {
                target = Math.min(target, 3000f / steps);
            }
            target = Math.max(target, floor);

            if (foodTimer > 3f) {
                catchingUp = true;
                float overdue = Math.min(20f, foodTimer - 3f);
                target *= (float) Math.exp(-overdue * 1.2f);
                target = Math.max(target, 5f);
            }
        }

        // acceleration limit: interval may change by at most this much per second
        float maxDelta = (catchingUp ? 60000f : 3000f) * dt;
        if (target < currentInterval - maxDelta) {
            currentInterval -= maxDelta;
        } else if (target > currentInterval + maxDelta) {
            currentInterval += maxDelta;
        } else {
            currentInterval = target;
        }
        currentInterval = Math.max(5f, Math.min(220f, currentInterval));
    }

    private float interval() {
        return currentInterval;
    }

    /** Adaptive speed: keep the time to the next fruit at or below 3 seconds. */
    private void onFruitEaten() {
        long now = android.os.SystemClock.uptimeMillis();
        if (autoMode && lastEatMs > 0L) {
            long took = now - lastEatMs;
            if (took > 2500L) {
                speedScale = Math.max(0.10f, speedScale * 0.72f);
            } else if (took < 1500L) {
                speedScale = Math.min(1f, speedScale * 1.08f);
            }
        }
        lastEatMs = now;
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
            int r = Math.max(ROWS_MIN, rows);
            int hx = COLS / 2 + 1;
            int hy = r / 2;
            for (int i = 0; i < 4; i++) {
                body.addLast(hy * COLS + (hx - i));
            }
        }
        dirQueue.clear();
        prevCells = cellsToArray();
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
        return pathPilot != null ? pathPilot.getStepsToFood() : -1;
    }

    private void startGame(int newMode) {
        if (state == STATE_READY || state == STATE_DEAD || state == STATE_WON) {
            mode = newMode;
            autoMode = newMode != MODE_MANUAL;
            lastAutoMode = autoMode ? newMode : -1;
            clearRoute();
            if (newMode == MODE_MANGO && mangoPilot != null) {
                // MangoSnake style: every run gets a fresh random cycle.
                mangoPilot.reshuffleCycle();
            }
            if (autoMode) {
                resetBoardState();
            }
            state = STATE_RUNNING;
            tickAccum = -420f;
            dirQueue.clear();
            clearEatLog();
            lastEatMs = 0L;
            speedScale = 1f;
        }
    }

    private void restartGame(int newMode) {
        mode = newMode;
        autoMode = newMode != MODE_MANUAL;
        lastAutoMode = autoMode ? newMode : -1;
        if (newMode == MODE_MANGO && mangoPilot != null) {
            mangoPilot.reshuffleCycle();
        }
        resetBoardState();
        state = STATE_RUNNING;
        mode = newMode;
        autoMode = newMode != MODE_MANUAL;
        tickAccum = -520f;
        clearEatLog();
        lastEatMs = 0L;
        speedScale = 1f;
    }

    private void pauseGame() {
        if (state == STATE_RUNNING) {
            state = STATE_PAUSED;
            saveHighScore();
        }
    }

    private void resumeGame() {
        if (state == STATE_PAUSED) {
            state = STATE_RUNNING;
            tickAccum = Math.min(tickAccum, 0f);
        }
    }

    /**
     * Pause-menu mode button: continue the current run with that AI. If the mode
     * is already active it simply resumes; if the player took over by hand the AI
     * of that mode is switched back on for the same run. Another mode restarts.
     */
    private void resumeMode(int targetMode) {
        if (mode == targetMode && autoMode) {
            resumeGame();
            return;
        }
        if (lastAutoMode == targetMode || mode == targetMode) {
            mode = targetMode;
            autoMode = true;
            manualMoving = false;
            following = false;
            followPicking = false;
            dirQueue.clear();
            resumeGame();
            haptic(false);
            return;
        }
        restartGame(targetMode);
    }

    private void update(float dt) {
        animTime += dt;
        updateSpeed(dt);
        dumpState();

        if (state == STATE_RUNNING) {
            boolean planPause = replayMode
                    || (!autoMode && mode == MODE_MANUAL && !manualMoving && !following);
            if (planPause) {
                // Waiting for waypoints / watching the replay: nothing moves.
                tickAccum = 0f;
                updateReplay(dt);
            } else {
                foodTimer += dt;
                // "Teleport" catch-up: once a fruit is overdue for more than 2s
                // the simulation is stepped many times per rendered frame. The
                // snake still follows its path - only frames are skipped.
                float speedMul = 1f;
                if (autoMode && foodTimer > 2f) {
                    speedMul = 1f + Math.min(30f, (foodTimer - 2f) * 10f);
                }
                tickAccum += dt * 1000f * speedMul;
                int guard = 0;
                while (tickAccum >= interval() && state == STATE_RUNNING && guard++ < 40) {
                    tickAccum -= interval();
                    step();
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

    private void step() {
        if (!autoMode && mode == MODE_MANUAL) {
            boolean moved = following ? stepFollow() : stepManualPath();
            if (!moved) {
                // No route / following finished: the snake waits for the player.
                return;
            }
        }
        if (autoMode) {
            dirQueue.clear();
            int[] d = null;
            AutoPilot cycle = activePilot();
            if (mode == MODE_SHORTPATH && pathPilot != null) {
                d = pathPilot.decide(cellsToArray(), foodCell, dirX, dirY);
            } else if (cycle != null) {
                // Both cycle modes use the classic Hamiltonian walk: forward only,
                // inside the free arc between the head and the tail.
                d = cycle.decide(cellsToArray(), foodCell, dirX, dirY);
            }
            if (d != null) {
                dirX = d[0];
                dirY = d[1];
            } else if (pathPilot != null && mode == MODE_SHORTPATH) {
                // The planner could not decide: never keep a stale direction, that
                // is exactly how the snake would bite itself.
                int[] alt = pathPilot.fallbackDirection(cellsToArray());
                if (alt != null) {
                    dirX = alt[0];
                    dirY = alt[1];
                }
            }
            // SHORT PATH: if the chosen step leaves the board, turn to another
            // free direction instead of dying. Biting the body is fine (it is
            // cut off), walls are not.
            if (mode == MODE_SHORTPATH && pathPilot != null) {
                Integer headPeek = body.peekFirst();
                if (headPeek != null) {
                    int qx = headPeek % COLS + dirX;
                    int qy = headPeek / COLS + dirY;
                    if (qx < 0 || qx >= COLS || qy < 0 || qy >= rows) {
                        int[] alt = pathPilot.fallbackDirection(cellsToArray());
                        if (alt != null) {
                            dirX = alt[0];
                            dirY = alt[1];
                        }
                    }
                }
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
        prevCells = cellsToArray();
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
                    + " branch=" + (pathPilot != null ? pathPilot.lastBranch() : "-"));
            biteSelf(ni);
            registerDeath("bite");
            spawnDeathFx(nx, ny);
            haptic(true);
            return;
        }
        body.addFirst(ni);

        if (eats) {
            score += 10;
            if (score > highScore) {
                highScore = score;
            }
            spawnFood();
            spawnEatFx(nx, ny);
            popups.add(new Popup(nx, ny));
            haptic(false);
            logEat();
            onFruitEaten();
        }
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

    /**
     * Walks the planned route. Returns false when the snake must not move (no
     * waypoints left or movement not started), so the player can keep editing.
     */
    /**
     * FOLLOW: walk a path towards the snake's own tail (the tail keeps moving,
     * so this is always safe). Stops when the head reaches the body point the
     * player picked, when the tail cannot be reached any more, or on request.
     */
    private boolean stepFollow() {
        Integer headObj = body.peekFirst();
        if (headObj == null || body.size() < 2) {
            following = false;
            return false;
        }
        int head = headObj;
        if (followStop >= 0 && head == followStop) {
            following = false;
            followStop = -1;
            tickAccum = 0f;
            haptic(true);
            return false;
        }
        int tail = body.peekLast();
        // While the picked point is still covered by the body, walk after the
        // tail; once it frees up, steer straight to it and stop there.
        int target = tail;
        if (followStop >= 0 && followStop != tail && !body.contains(followStop)) {
            target = followStop;
        }
        int[] path = findPathTo(cellsToArray(), target);
        if (path == null || path.length < 2) {
            following = false;
            tickAccum = 0f;
            return false;
        }
        int hx = head % COLS;
        int hy = head / COLS;
        // path[0] is the head itself, path[1] is the first step.
        int next = path[1];
        dirX = (next % COLS) - hx;
        dirY = (next / COLS) - hy;
        if (Math.abs(dirX) + Math.abs(dirY) != 1) {
            following = false;
            return false;
        }
        return true;
    }

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
            return false;
        }
        int target = waypoints.get(0);
        int hx = head % COLS;
        int hy = head / COLS;
        int tx = target % COLS;
        int ty = target / COLS;
        int wantX = Integer.compare(tx, hx);
        int wantY = Integer.compare(ty, hy);
        boolean reverseX = wantX != 0 && dirY == 0 && dirX == -wantX;
        boolean reverseY = wantY != 0 && dirX == 0 && dirY == -wantY;
        if (wantX != 0 && !reverseX) {
            dirX = wantX;
            dirY = 0;
        } else if (wantY != 0 && !reverseY) {
            dirX = 0;
            dirY = wantY;
        } else if (wantX != 0 || wantY != 0) {
            int[] d = detourDir(hx, hy);
            dirX = d[0];
            dirY = d[1];
        }
        return true;
    }

    /** Legal perpendicular step used when the target sits exactly behind. */
    private int[] detourDir(int hx, int hy) {
        int[][] tries = dirX != 0
                ? new int[][]{{0, -1}, {0, 1}}
                : new int[][]{{-1, 0}, {1, 0}};
        for (int[] d : tries) {
            int nx = hx + d[0];
            int ny = hy + d[1];
            if (nx < 0 || nx >= COLS || ny < 0 || ny >= rows) {
                continue;
            }
            if (body.contains(ny * COLS + nx)) {
                continue;
            }
            return d;
        }
        return new int[]{dirX, dirY};
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
        if (replayMode) {
            // Any tap leaves the playback; the REPLAY button toggles it off too.
            if (!padReplayBtn.contains(x, y)) {
                stopReplay();
            } else {
                replayRoute();
            }
            return;
        }
        if (moveBtn.contains(x, y)) {
            if (manualMoving) {
                manualMoving = false;
            } else if (!waypoints.isEmpty()) {
                manualMoving = true;
                tickAccum = -120f;
                dirQueue.clear();
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
        // REPLAY: walk the whole waypoint history again.
        if (padReplayBtn.contains(x, y)) {
            replayRoute();
            return;
        }
        // FOLLOW: pick a body cell to stop at, then run along the tail.
        if (padFollowBtn.contains(x, y)) {
            if (following) {
                following = false;
                followPicking = false;
                followStop = -1;
            } else if (followPicking) {
                following = true;
                followPicking = false;
                followStop = -1;
                manualMoving = false;
                tickAccum = -120f;
            } else {
                followPicking = true;
                followStop = -1;
            }
            haptic(false);
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
        // FOLLOW: tapping a body cell chooses (or moves) the stop point.
        if (followPicking || following) {
            if (body.contains(c)) {
                followStop = c;
                following = true;
                followPicking = false;
                manualMoving = false;
                tickAccum = -120f;
                haptic(false);
            }
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
     * AUTO: replaces the route with waypoints along the shortest path from the
     * head to the fruit (only the corners are kept, so the L-shaped route between
     * two waypoints is exactly the shortest path).
     */
    private void autoRouteToFruit() {
        if (body.isEmpty() || foodCell < 0) {
            return;
        }
        int[] path = findPathTo(cellsToArray(), foodCell);
        if (path == null || path.length < 2) {
            haptic(true);
            return;
        }
        ArrayList<Integer> wps = new ArrayList<>();
        for (int i = 1; i < path.length; i++) {
            boolean corner;
            if (i == path.length - 1) {
                corner = true;
            } else {
                int d1x = (path[i] % COLS) - (path[i - 1] % COLS);
                int d1y = (path[i] / COLS) - (path[i - 1] / COLS);
                int d2x = (path[i + 1] % COLS) - (path[i] % COLS);
                int d2y = (path[i + 1] / COLS) - (path[i] / COLS);
                corner = d1x != d2x || d1y != d2y;
            }
            if (corner) {
                wps.add(path[i]);
            }
        }
        waypoints.clear();
        waypoints.addAll(wps);
        selectedWp = waypoints.isEmpty() ? -1 : 0;
        haptic(false);
    }

    /**
     * REPLAY: plays the recorded waypoint route like a video (the real snake does
     * not move). Tapping the button again - or anywhere else - leaves the playback
     * and the player continues from the same position.
     */
    private void replayRoute() {
        if (replayMode) {
            stopReplay();
            return;
        }
        if (waypoints.isEmpty() || body.isEmpty()) {
            haptic(true);
            return;
        }
        ArrayList<Integer> cells = new ArrayList<>();
        int cur = body.peekFirst();
        cells.add(cur);
        for (int wp : waypoints) {
            int cx = cur % COLS;
            int cy = cur / COLS;
            int tx = wp % COLS;
            int ty = wp / COLS;
            while (cx != tx) {
                cx += Integer.compare(tx, cx);
                cells.add(cy * COLS + cx);
            }
            while (cy != ty) {
                cy += Integer.compare(ty, cy);
                cells.add(cy * COLS + cx);
            }
            cur = wp;
        }
        replayPath = new int[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            replayPath[i] = cells.get(i);
        }
        replayPos = 0;
        replayAccum = 0f;
        replayMode = true;
        manualMoving = false;
        tickAccum = 0f;
        wpListOpen = false;
        haptic(false);
    }

    private void stopReplay() {
        replayMode = false;
        replayAccum = 0f;
        replayPath = new int[0];
        replayPos = 0;
        tickAccum = 0f;
        haptic(false);
    }

    /** Advances the playback (called from update while replaying). */
    private void updateReplay(float dt) {
        if (!replayMode) {
            return;
        }
        replayAccum += dt * 1000f;
        while (replayAccum >= REPLAY_STEP_MS && replayPos < replayPath.length - 1) {
            replayAccum -= REPLAY_STEP_MS;
            replayPos++;
        }
    }

    /** Translucent snake walking the recorded route (its head is at replayPos). */
    private void drawReplaySnake(Canvas c) {
        if (!replayMode || replayPath.length == 0) {
            return;
        }
        int len = Math.min(body.size(), replayPos + 1);
        int from = replayPos - len + 1;
        if (len < 2) {
            from = replayPos;
            len = 1;
        }
        float half = cell / 2f;
        replayBodyPaint.setStrokeWidth(cell * 0.60f);
        Path gp = new Path();
        for (int i = from; i <= replayPos; i++) {
            int rc = replayPath[i];
            float px = boardLeft + (rc % COLS) * cell + half;
            float py = boardTop + (rc / COLS) * cell + half;
            if (i == from) {
                gp.moveTo(px, py);
            } else {
                gp.lineTo(px, py);
            }
        }
        c.drawPath(gp, replayBodyPaint);
        int headCell = replayPath[replayPos];
        c.drawCircle(boardLeft + (headCell % COLS) * cell + half,
                boardTop + (headCell / COLS) * cell + half, cell * 0.40f, replayHeadPaint);
        c.drawText("PREVIEW " + replayPos + " / " + (replayPath.length - 1),
                boardLeft + (headCell % COLS) * cell + half,
                boardTop + (headCell / COLS) * cell - cell * 0.62f, replayLabelPaint);
    }

    /** BFS shortest path (cells) from the head to {@code target}, body = wall. */
    private int[] findPathTo(int[] cells, int target) {
        int total = COLS * rows;
        if (cells.length == 0 || target < 0 || target >= total) {
            return null;
        }
        boolean[] wall = new boolean[total];
        for (int c : cells) {
            wall[c] = true;
        }
        if (cells.length > 1) {
            wall[cells[cells.length - 1]] = false;   // the tail moves away
        }
        int[] parent = new int[total];
        java.util.Arrays.fill(parent, -2);
        int[] queue = new int[total];
        int qh = 0;
        int qt = 0;
        int head = cells[0];
        parent[head] = -1;
        queue[qt++] = head;
        boolean reached = false;
        while (qh < qt) {
            int cur = queue[qh++];
            if (cur == target) {
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
                if (parent[n] != -2 || (wall[n] && n != target)) {
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
        for (int c = target; c != -1; c = parent[c]) {
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
        replayMode = false;
        replayPath = new int[0];
        replayPos = 0;
        replayAccum = 0f;
        following = false;
        followPicking = false;
        followStop = -1;
    }

    private void spawnFood() {
        foodTimer = 0f;
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
                if (mode != MODE_AUTOWIN || cycle.foodReachable(cellsNow, foodCell)) {
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
        prevCells = cellsToArray();
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

    private void dumpState() {
        long now = System.currentTimeMillis();
        if (now - lastDumpMs < 1500L) {
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
            w.write("state=" + state + " auto=" + autoMode + " score=" + score
                    + " fps=" + fps
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
                    + " padReplay=" + Math.round(padReplayBtn.centerX()) + ","
                    + Math.round(padReplayBtn.centerY())
                    + " coll=" + routeCollisions().size()
                    + " list=" + (wpListOpen ? 1 : 0)
                    + " follow=" + (following ? 1 : 0) + "/" + (followPicking ? 1 : 0)
                    + "/" + followStop
                    + " replay=" + (replayMode ? 1 : 0) + "/" + replayPos
                    + "/" + replayPath.length
                    + " listRect=" + Math.round(listPanel.left) + ","
                    + Math.round(listPanel.top) + ","
                    + Math.round(listPanel.right) + ","
                    + Math.round(listPanel.bottom)
                    + " rowH=" + Math.round(listRowH)
                    + " selRow=" + listDragIndex
                    + "\nstart=" + Math.round(startBtn.centerX()) + ","
                    + Math.round(startBtn.centerY())
                    + " autoBtn=" + Math.round(autoBtn.centerX()) + ","
                    + Math.round(autoBtn.centerY())
                    + " pathBtn=" + Math.round(pathBtn.centerX()) + ","
                    + Math.round(pathBtn.centerY())
                    + " mangoBtn=" + Math.round(mangoBtn.centerX()) + ","
                    + Math.round(mangoBtn.centerY())
                    + " pause=" + Math.round(pauseCx) + "," + Math.round(pauseCy)
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
                    + "\n");
            w.close();
        } catch (Exception ignored) {
            // diagnostics only
        }
    }

    private void haptic(final boolean strong) {
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
                return true;
            }
            default:
                return super.onTouchEvent(e);
        }
    }

    private void onTap(float x, float y) {
        switch (state) {
            case STATE_READY:
                if (autoBtn.contains(x, y)) {
                    startGame(MODE_AUTOWIN);
                } else if (pathBtn.contains(x, y)) {
                    startGame(MODE_SHORTPATH);
                } else if (mangoBtn.contains(x, y)) {
                    startGame(MODE_MANGO);
                } else {
                    startGame(MODE_MANUAL);
                }
                return;
            case STATE_DEAD:
            case STATE_WON:
                if (autoRestartBtn.contains(x, y)) {
                    restartGame(MODE_AUTOWIN);
                } else if (pathRestartBtn.contains(x, y)) {
                    restartGame(MODE_SHORTPATH);
                } else if (mangoRestartBtn.contains(x, y)) {
                    restartGame(MODE_MANGO);
                } else {
                    restartGame(MODE_MANUAL);
                }
                return;
            case STATE_PAUSED:
                // The mode button resumes the run: same active mode -> keep going,
                // and if the player had taken over by hand the AI of that mode
                // simply switches back on for the same run.
                if (autoResumeBtn.contains(x, y)) {
                    resumeMode(MODE_AUTOWIN);
                } else if (pathResumeBtn.contains(x, y)) {
                    resumeMode(MODE_SHORTPATH);
                } else if (mangoResumeBtn.contains(x, y)) {
                    resumeMode(MODE_MANGO);
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
            if (replayMode) {
                drawReplaySnake(c);
            } else {
                drawGhostSnake(c);
            }
            drawWaypointMarkers(c);
        }
        drawParticles(c);
        drawPopups(c);
        drawHud(c);
        drawBottomBar(c);
        if (!autoMode && mode == MODE_MANUAL && state == STATE_RUNNING) {
            drawWaypointPad(c);
        }
        if (wpListOpen) {
            drawWpList(c);
        }
        if (state != STATE_RUNNING) {
            drawOverlay(c);
        }
    }

    /** Faint dashed preview of the planned route (drawn under the snake). */
    private void drawRoute(Canvas c) {
        if (waypoints.isEmpty() || body.isEmpty()) {
            return;
        }
        float half = cell / 2f;
        int head = body.peekFirst();
        float sx = boardLeft + (head % COLS) * cell + half;
        float sy = boardTop + (head / COLS) * cell + half;
        Path path = new Path();
        path.moveTo(sx, sy);
        float cy = sy;
        for (int wp : waypoints) {
            float tx = boardLeft + (wp % COLS) * cell + half;
            float ty = boardTop + (wp / COLS) * cell + half;
            path.lineTo(tx, cy);   // horizontal leg first, like the movement rule
            path.lineTo(tx, ty);
            cy = ty;
        }
        c.drawPath(path, previewPaint);
    }

    /** Waypoint dots with their order numbers (drawn on top of the snake/ghost). */
    private void drawWaypointMarkers(Canvas c) {
        if (waypoints.isEmpty() && followStop < 0) {
            return;
        }
        float half = cell / 2f;
        if (followStop >= 0) {
            // Stop point chosen for FOLLOW.
            float fx = boardLeft + (followStop % COLS) * cell + half;
            float fy = boardTop + (followStop / COLS) * cell + half;
            c.drawCircle(fx, fy, cell * 0.34f, wpSelPaint);
        }
        if (waypoints.isEmpty()) {
            return;
        }
        boolean[] broken = brokenWaypoints();
        for (int i = 0; i < waypoints.size(); i++) {
            int wp = waypoints.get(i);
            float tx = boardLeft + (wp % COLS) * cell + half;
            float ty = boardTop + (wp / COLS) * cell + half;
            boolean sel = i == selectedWp;
            float r = cell * (sel ? 0.36f : 0.27f);
            c.drawCircle(tx, ty, r, wpFillPaint);
            c.drawCircle(tx, ty, r, sel ? wpSelPaint : wpStrokePaint);
            c.drawText(String.valueOf(i + 1), tx, ty + dp(3.4f), wpTextPaint);
            if (broken[i]) {
                // The route runs over this point before its turn: mark it broken.
                float k = cell * 0.42f;
                c.drawLine(tx - k, ty - k, tx + k, ty + k, padDelPaint);
                c.drawLine(tx + k, ty - k, tx - k, ty + k, padDelPaint);
            }
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
     * Marks the waypoints that the planned route runs over out of order (a leg
     * passes through a waypoint that is not its own start/end), which the player
     * must fix by reordering.
     */
    private boolean[] brokenWaypoints() {
        int n = waypoints.size();
        boolean[] broken = new boolean[n];
        if (n < 2 || body.isEmpty()) {
            return broken;
        }
        int cur = body.peekFirst();
        for (int i = 0; i < n; i++) {
            int to = waypoints.get(i);
            int cx = cur % COLS;
            int cy = cur / COLS;
            int tx = to % COLS;
            int ty = to / COLS;
            while (cx != tx) {
                cx += Integer.compare(tx, cx);
                markCrossed(broken, cy * COLS + cx, i);
            }
            while (cy != ty) {
                cy += Integer.compare(ty, cy);
                markCrossed(broken, cy * COLS + cx, i);
            }
            cur = to;
        }
        return broken;
    }

    private void markCrossed(boolean[] broken, int cell, int leg) {
        for (int j = 0; j < waypoints.size(); j++) {
            if (j != leg && waypoints.get(j) == cell) {
                broken[j] = true;
            }
        }
    }

    /** Labels of the broken waypoints (1-based) for the hint line. */
    private String brokenLabel() {
        boolean[] broken = brokenWaypoints();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < broken.length; i++) {
            if (broken[i]) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(i + 1);
            }
        }
        return sb.toString();
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

    /** Cells from the head to the given waypoint, following the L-shaped route. */
    private ArrayList<Integer> routeCellsTo(int wpIndex) {
        ArrayList<Integer> out = new ArrayList<>();
        if (body.isEmpty() || wpIndex < 0 || wpIndex >= waypoints.size()) {
            return out;
        }
        int cur = body.peekFirst();
        out.add(cur);
        for (int i = 0; i <= wpIndex; i++) {
            int target = waypoints.get(i);
            int cx = cur % COLS;
            int cy = cur / COLS;
            int tx = target % COLS;
            int ty = target / COLS;
            while (cx != tx) {
                cx += Integer.compare(tx, cx);
                out.add(cy * COLS + cx);
            }
            while (cy != ty) {
                cy += Integer.compare(ty, cy);
                out.add(cy * COLS + cx);
            }
            cur = target;
        }
        return out;
    }

    /** Arrow pad (+ delete) shown while a waypoint is selected. */
    private void drawWaypointPad(Canvas c) {
        // The strip background is painted by drawBottomBar (before the hint,
        // MOVE and pause button) so those stay on top of it.
        // FOLLOW (bottom-left cell): pick a body point to stop at, then walk
        // along the tail.
        boolean followOn = following || followPicking;
        c.drawRoundRect(padFollowBtn, dp(12f), dp(12f),
                followOn ? wpFillPaint : padBtnPaint);
        c.drawRoundRect(padFollowBtn, dp(12f), dp(12f), padBtnStrokePaint);
        float oldSize = padLabelPaint.getTextSize();
        padLabelPaint.setTextSize(dp(9f));
        padLabelPaint.setColor(followOn ? 0xFFFFD479 : 0xFFC8FFDD);
        c.drawText(following ? "STOP" : "FOLLOW",
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
            drawPadButton(c, padAutoBtn, 6);
        }
        // REPLAY pill under the pad
        boolean has = !waypoints.isEmpty();
        c.drawRoundRect(padReplayBtn, dp(10f), dp(10f),
                replayMode ? wpFillPaint : padBtnPaint);
        c.drawRoundRect(padReplayBtn, dp(10f), dp(10f),
                replayMode ? replayHeadPaint : padBtnStrokePaint);
        float old = padLabelPaint.getTextSize();
        padLabelPaint.setTextSize(dp(11f));
        padLabelPaint.setColor(has ? 0xFFC8FFDD : 0x6693A7C4);
        String label;
        if (replayMode) {
            label = "THOÁT PREVIEW";
        } else {
            label = "PREVIEW  (" + waypoints.size() + ")";
        }
        c.drawText(label, padReplayBtn.centerX(), padReplayBtn.centerY() + dp(4f),
                padLabelPaint);
        padLabelPaint.setTextSize(old);
        padLabelPaint.setColor(0xE6C8FFDD);
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
        float fx = boardLeft + ((foodCell % COLS) + 0.5f) * cell;
        float fy = boardTop + ((foodCell / COLS) + 0.5f) * cell;
        float pulse = 1f + 0.07f * (float) Math.sin(animTime * 5.2f);
        float r = cell * 0.31f;

        c.save();
        c.translate(fx, fy);
        c.scale(pulse, pulse);
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
    }

    private float interpolation() {
        if (state != STATE_RUNNING && state != STATE_PAUSED) {
            return 1f;
        }
        float iv = interval();
        if (iv <= 0f) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, tickAccum / iv));
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
        int m = prevCells.length;
        int newHead = body.peekFirst() == null ? 0 : body.peekFirst();
        if (m == 0) {
            if (drawnPts.length < 2) {
                drawnPts = new float[2];
            }
            drawnPts[0] = cellPx(newHead);
            drawnPts[1] = cellPy(newHead);
            drawnCount = 1;
            return;
        }
        int count = m + 1;
        if (drawnPts.length < count * 2) {
            drawnPts = new float[count * 2];
        }

        float hx = cellPx(prevCells[0]);
        float hy = cellPy(prevCells[0]);
        drawnPts[0] = hx + (cellPx(newHead) - hx) * t;
        drawnPts[1] = hy + (cellPy(newHead) - hy) * t;

        int idx = 2;
        for (int i = 0; i < m - 1; i++) {
            drawnPts[idx++] = cellPx(prevCells[i]);
            drawnPts[idx++] = cellPy(prevCells[i]);
        }

        // Growing (ate this tick): the tail stays where it is.
        boolean grown = (m == n - 1);
        if (!grown && m >= 2) {
            float tx = cellPx(prevCells[m - 1]);
            float ty = cellPy(prevCells[m - 1]);
            float bx = cellPx(prevCells[m - 2]);
            float by = cellPy(prevCells[m - 2]);
            drawnPts[idx++] = tx + (bx - tx) * t;
            drawnPts[idx++] = ty + (by - ty) * t;
        } else {
            drawnPts[idx++] = cellPx(prevCells[m - 1]);
            drawnPts[idx++] = cellPy(prevCells[m - 1]);
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

        computeDrawnPoints(n, t);

        // One flat-coloured stroke through the body points. The middle cells do
        // not move during a tick, so a bend keeps exactly the same shape from
        // frame to frame; the round join keeps its thickness equal to the body.
        if (drawnCount >= 2) {
            buildCentrePath();
            bodyPaint.setAlpha((int) (255 * alpha));
            bodyPaint.setStrokeWidth(cell * 0.60f);
            c.drawPath(bodyPath, bodyPaint);
        }

        drawHead(c, drawnPts[0], drawnPts[1], alpha);
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

        float angle = (float) Math.toDegrees(Math.atan2(dirY, dirX));
        c.save();
        c.translate(hx + dirX * cell * 0.03f, hy + dirY * cell * 0.03f);
        c.rotate(angle);

        headGlowPaint.setAlpha((int) (255 * alpha));
        c.drawCircle(0f, 0f, w * 1.10f, headGlowPaint);

        tmpRect.set(-hl - dp(1.4f), -hw - dp(1.4f), hl + dp(1.4f), hw + dp(1.4f));
        headOutlinePaint.setAlpha((int) (0xE0 * alpha));
        c.drawRoundRect(tmpRect, hw + dp(1.4f), hw + dp(1.4f), headOutlinePaint);

        tmpRect.set(-hl, -hw, hl, hw);
        headFillPaint.setAlpha((int) (255 * alpha));
        c.drawRoundRect(tmpRect, hw, hw, headFillPaint);

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

        if (state == STATE_RUNNING && Math.sin(animTime * 6.5) > 0.35) {
            tonguePaint.setAlpha((int) (255 * alpha));
            tonguePaint.setStrokeWidth(Math.max(dp(1.4f), w * 0.06f));
            float x0 = hl * 0.96f;
            float x1 = hl + w * 0.30f;
            float x2 = hl + w * 0.52f;
            c.drawLine(x0, 0f, x1, 0f, tonguePaint);
            c.drawLine(x1, 0f, x2, -w * 0.16f, tonguePaint);
            c.drawLine(x1, 0f, x2, w * 0.16f, tonguePaint);
        }
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
        String label = mode == MODE_SHORTPATH ? "SHORT PATH"
                : mode == MODE_MANGO ? "MANGO" : "AUTO WIN";
        float bh = dp(24f);
        float bw = mode == MODE_SHORTPATH ? dp(96f)
                : mode == MODE_MANGO ? dp(70f) : dp(82f);
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
        String broken = "";
        boolean collision = false;
        if (autoMode) {
            hint = "AI đang chơi — chạm badge để tự điều khiển";
        } else if (mode == MODE_MANUAL) {
            if (following) {
                hint = followStop >= 0
                        ? "FOLLOW: bám đuôi, dừng ở điểm đã chọn"
                        : "FOLLOW: đang bám đuôi";
            } else if (followPicking) {
                hint = "Chạm lên thân rắn để chọn điểm dừng";
            } else if (manualMoving) {
                hint = "Bấm DỪNG để sửa route";
            } else if (waypoints.isEmpty()) {
                hint = "Chạm board đặt waypoint";
            } else {
                hint = "Bấm MOVE để chạy";
            }
            broken = brokenLabel();
            if (broken.length() > 0 && !following && !followPicking) {
                hint = "BROKEN tại #" + broken + "  •  đổi thứ tự waypoint";
            } else if (broken.isEmpty() && !following && !followPicking
                    && !waypoints.isEmpty()) {
                ArrayList<Integer> hits = routeCollisions();
                if (!hits.isEmpty()) {
                    int hitCell = hits.get(0);
                    hint = "COLLISION: route đâm thân ở (" + (hitCell % COLS) + ","
                            + (hitCell / COLS) + ")  •  sửa waypoint";
                }
            }
            collision = hint.startsWith("COLLISION");
        } else {
            hint = "Vuốt để điều khiển";
        }
        hint += "  •  Chết: " + deaths;
        hintPaint.setColor(broken.length() > 0 || collision ? 0xFFFF8FA3 : 0xFF647699);
        c.drawText(hint, boardLeft + dp(2f), hintTop, hintPaint);
        hintPaint.setColor(0xFF647699);
        if (!autoMode && mode == MODE_MANUAL) {
            drawMoveButton(c);
        }
        drawPauseButton(c);
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

    private void drawReadyOverlay(Canvas c) {
        float cx = boardRect.centerX();
        float cy = boardRect.centerY();
        float bob = (float) Math.sin(animTime * 2.1f) * dp(5f);

        readyTitlePaint.setShadowLayer(dp(18f), 0f, dp(2f), 0x8835D07F);
        c.drawText("SNAKE", cx, cy - dp(168f) + bob, readyTitlePaint);
        readyTitlePaint.clearShadowLayer();

        c.drawText("Chọn chế độ để bắt đầu", cx, cy - dp(120f), readySubPaint);

        // Four modes, one per row: CHƠI · AUTO WIN · SHORT PATH · MANGO.
        float gap = dp(10f);
        float bh = Math.min(dp(52f), Math.max(dp(34f), (boardH * 0.54f - gap * 3f) / 4f));
        float bw = Math.min(boardW * 0.86f, dp(300f));
        float bx = cx - bw / 2f;
        float top = cy - dp(96f);
        startBtn.set(bx, top, bx + bw, top + bh);
        autoBtn.set(bx, top + (bh + gap), bx + bw, top + 2f * bh + gap);
        pathBtn.set(bx, top + 2f * (bh + gap), bx + bw, top + 3f * bh + 2f * gap);
        mangoBtn.set(bx, top + 3f * (bh + gap), bx + bw, top + 4f * bh + 3f * gap);

        drawPrimaryButton(c, startBtn, "CHƠI", false);
        drawSecondaryButton(c, autoBtn, "AUTO WIN", false);
        drawSecondaryButton(c, pathBtn, "SHORT PATH", false);
        drawSecondaryButton(c, mangoBtn, "MANGO", false);

        float hintY = top + 4f * (bh + gap) + dp(20f);
        c.drawText("CHƠI: chạm board đặt waypoint rồi bấm MOVE", cx, hintY, readyHintPaint);
        c.drawText("AUTO WIN: luôn thắng · MANGO: chu trình ngẫu nhiên", cx,
                hintY + dp(22f), readyHintPaint);
        c.drawText("SHORT PATH: BFS ngắn nhất · cắn thân = cắt đuôi", cx,
                hintY + dp(44f), readyHintPaint);
    }

    private void drawPausedOverlay(Canvas c) {
        float cx = boardRect.centerX();
        float cy = boardRect.centerY();

        c.drawText("TẠM DỪNG", cx, cy - dp(110f), pausedTitlePaint);

        // 2 x 2: TIẾP TỤC · AUTO WIN / SHORT PATH · MANGO
        float gap = dp(10f);
        float bw = Math.min((boardW * 0.88f - gap) / 2f, dp(170f));
        float bh = dp(50f);
        float bx = cx - (bw * 2f + gap) / 2f;
        float by = cy - dp(70f);
        resumeBtn.set(bx, by, bx + bw, by + bh);
        autoResumeBtn.set(bx + bw + gap, by, bx + 2f * bw + gap, by + bh);
        pathResumeBtn.set(bx, by + bh + gap, bx + bw, by + 2f * bh + gap);
        mangoResumeBtn.set(bx + bw + gap, by + bh + gap, bx + 2f * bw + gap,
                by + 2f * bh + gap);

        drawPrimaryButton(c, resumeBtn, "TIẾP TỤC", false);
        drawSecondaryButton(c, autoResumeBtn, "AUTO WIN", false);
        drawSecondaryButton(c, pathResumeBtn, "SHORT PATH", false);
        drawSecondaryButton(c, mangoResumeBtn, "MANGO", false);

        String now = mode == MODE_AUTOWIN ? "AUTO WIN"
                : mode == MODE_SHORTPATH ? "SHORT PATH"
                : mode == MODE_MANGO ? "MANGO" : "THỦ CÔNG";
        c.drawText("Đang ở chế độ " + now, cx, by + 2f * bh + gap + dp(38f),
                readyHintPaint);
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

        // 2 x 2 restart grid: CHƠI LẠI · AUTO WIN / SHORT PATH · MANGO
        float gap = dp(6f);
        float bw = (cw * 0.88f - gap) / 2f;
        float bh = Math.min(dp(46f), ch * 0.16f);
        float by = top + ch * 0.62f;
        float left = cx - (bw * 2f + gap) / 2f;
        restartBtn.set(left, by, left + bw, by + bh);
        autoRestartBtn.set(left + bw + gap, by, left + 2f * bw + gap, by + bh);
        pathRestartBtn.set(left, by + bh + gap, left + bw, by + 2f * bh + gap);
        mangoRestartBtn.set(left + bw + gap, by + bh + gap, left + 2f * bw + gap,
                by + 2f * bh + gap);

        drawPrimaryButton(c, restartBtn, "CHƠI LẠI", false);
        drawSecondaryButton(c, autoRestartBtn, "AUTO WIN", false);
        drawSecondaryButton(c, pathRestartBtn, "SHORT PATH", false);
        drawSecondaryButton(c, mangoRestartBtn, "MANGO", false);

        c.drawText("hoặc chạm ra ngoài để chơi lại", cx, by + 2f * bh + gap + dp(24f),
                readyHintPaint);

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
