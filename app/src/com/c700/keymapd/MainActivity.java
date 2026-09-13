package com.c700.keymapd;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * Landscape 16:9 configurator.
 *
 * Navigation is a hand-rolled cursor state machine instead of Android's focus
 * search: rows are grouped into columns, a (column,row) cursor is moved by the
 * d-pad / left stick, A activates, B backs out, and the cursor ring is painted
 * by us - so it is always obvious what is selected and nothing is unreachable.
 */
public class MainActivity extends Activity {

    static final int BG = 0xFF0B0E13;
    static final int PANEL = 0xFF12161D;
    static final int CARD = 0xFF1C222C;
    static final int ACCENT = 0xFF29D6FF;        // text, ticks, logo
    static final int ACCENT_FILL = 0xFF1F9FC9;   // activated row fill (one step deeper)
    static final int ACCENT_DARK = 0xFF06222B;
    static final int TEXT = 0xFFE8F1F7;
    static final int SUB = 0xFF77879A;
    static final int DANGER = 0xFFFF6B6B;
    static final int RING = 0xFFE8F1F7;

    // order shown in the UI: back keys first, custom key last
    static final String[] TRIG_NAMES = { "左背键", "右背键", "自定义键" };
    static final String[] TRIG_CFG = { "F11", "F12", "F10" };
    static final int[] TRIG_CODES = { 141, 142, 140 }; // KEYCODE_F11/F12/F10

    // picker rows (index 0 = 不映射, at the top outside the groups)
    static final String[] PICK_NAME = {
            "不映射",
            "手柄按键",
            "A", "B", "X", "Y", "LB", "RB", "LT", "RT", "LS", "RS",
            "\u2191", "\u2193", "\u2190", "\u2192", "View", "Menu", "Home",
            "系统按键",
            "截屏", "返回", "主页", "最近任务", "通知栏",
            "锁屏", "音量+", "音量-", "静音",
    };
    static final String[] PICK_CFG = {
            "0",
            "",
            "BUTTON_A", "BUTTON_B", "BUTTON_X", "BUTTON_Y", "BUTTON_L1",
            "BUTTON_R1", "BUTTON_L2", "BUTTON_R2", "BUTTON_THUMBL",
            "BUTTON_THUMBR", "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT",
            "DPAD_RIGHT", "BUTTON_SELECT", "BUTTON_START", "BUTTON_MODE",
            "",
            "SYSRQ", "BACK", "HOME", "APP_SWITCH", "NOTIFICATION",
            "LOCK", "VOLUME_UP", "VOLUME_DOWN", "VOLUME_MUTE",
    };
    static final boolean[] PICK_HDR = {
            false, true,
            false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false,
            true,
            false, false, false, false, false, false, false, false, false,
    };

    static {
        if (PICK_NAME.length != PICK_CFG.length || PICK_NAME.length != PICK_HDR.length) {
            throw new ExceptionInInitializerError("picker arrays length mismatch");
        }
    }

    private static final String[] NAV_ICON = { "\u2261", "\u26A1" };
    private static final String[] NAV_LABEL = { "按键映射", "服务状态" };
    private static final int NAV_COUNT = 2;

    /** one selectable row of the state machine */
    private static final class Row {
        final View v;
        final Runnable paint;
        final Runnable action;

        Row(View v, Runnable paint, Runnable action) {
            this.v = v;
            this.paint = paint;
            this.action = action;
        }
    }

    private LinearLayout mMid, mRight, mMidHeader, mRightHeader;
    private TextView mTargetVal, mTurboBtn, mRateVal, mRateLbl;
    private LinearLayout mRateRow;
    private final List<View[]> mNavCell = new ArrayList<>(); // per nav item: {row}

    // ---- cursor state machine ----
    private final List<List<Row>> mCols = new ArrayList<>();
    private final List<Runnable> mPainters = new ArrayList<>();  // paint != selectability
    private final List<Row> mReg0 = new ArrayList<>();
    private final List<Row> mReg1 = new ArrayList<>();
    private final List<Row> mReg2 = new ArrayList<>();
    private int mCol = 1, mRow = 0, mPage = 0;

    // ---- config state ----
    private int mSel = 0;
    private final int[] mSelTarget = new int[TRIG_CODES.length];
    private final boolean[] mTurbo = new boolean[TRIG_CODES.length];
    private final int[] mRate = new int[TRIG_CODES.length];
    private final TextView[] mCardVals = new TextView[TRIG_CODES.length];
    private String mExtraRaw = "";
    private long mLastStick;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        android.app.ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setTitle("C700 KeyMap");
        }
        final View rootView = buildRoot();
        setContentView(rootView);
        // this ROM draws the ActionBar as an overlay, so reserve its height
        rootView.post(() -> {
            android.app.ActionBar bar = getActionBar();
            int h = bar != null ? bar.getHeight() : 0;
            if (h > 0) rootView.setPadding(0, h, 0, rootView.getPaddingBottom());
        });
        seedDefaultsIfEmpty();
        loadConfig();
        refresh();
        probeDaemon();
        autoHeal();
    }

    // ================= layout =================

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);


        LinearLayout body = row(root, 0);
        ((LinearLayout.LayoutParams) body.getLayoutParams()).weight = 1;

        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.VERTICAL);
        nav.setBackgroundColor(PANEL);
        nav.setPadding(dp(8), dp(18), dp(8), dp(10));
        body.addView(nav, new LinearLayout.LayoutParams(dp(158), -1));
        for (int i = 0; i < NAV_COUNT; i++) {
            final int idx = i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.HORIZONTAL);
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setPadding(dp(12), 0, 0, 0);
            nav.addView(item, new LinearLayout.LayoutParams(-1, dp(56)));
            TextView icon = new TextView(this);
            icon.setText(NAV_ICON[i]);
            icon.setTextSize(14);
            icon.setTextColor(SUB);
            icon.setGravity(Gravity.CENTER);
            item.addView(icon, new LinearLayout.LayoutParams(dp(26), -2));
            TextView lab = new TextView(this);
            lab.setText(NAV_LABEL[i]);
            lab.setTextSize(14);
            lab.setTextColor(SUB);
            lab.setAllCaps(false);
            item.addView(lab, new LinearLayout.LayoutParams(-2, -2));
            mNavCell.add(new View[]{ item, icon, lab });
            reg(0, item, () -> paintNav(),
                    () -> { mPage = idx; mCol = 1; mRow = 0; refresh(); });
            item.setOnClickListener(v -> {
                mPage = idx; mCol = 1; mRow = 0; refresh();
            });
        }

        LinearLayout midCol = new LinearLayout(this);
        midCol.setOrientation(LinearLayout.VERTICAL);
        body.addView(midCol, new LinearLayout.LayoutParams(0, -1, 1.28f));
        mMidHeader = new LinearLayout(this);
        mMidHeader.setOrientation(LinearLayout.HORIZONTAL);
        midCol.addView(mMidHeader, new LinearLayout.LayoutParams(-1, dp(62)));
        ScrollView midScroll = new ScrollView(this);
        midScroll.setBackgroundColor(BG);
        midScroll.setFocusable(false);
        midScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mMid = new LinearLayout(this);
        mMid.setOrientation(LinearLayout.VERTICAL);
        mMid.setPadding(dp(14), dp(2), dp(14), dp(10));
        midScroll.addView(mMid, svl());
        midCol.addView(midScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.setBackgroundColor(PANEL);
        body.addView(rightCol, new LinearLayout.LayoutParams(0, -1, 1f));
        mRightHeader = new LinearLayout(this);
        mRightHeader.setOrientation(LinearLayout.HORIZONTAL);
        rightCol.addView(mRightHeader, new LinearLayout.LayoutParams(-1, dp(62)));
        ScrollView rightScroll = new ScrollView(this);
        rightScroll.setFocusable(false);
        rightScroll.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        mRight = new LinearLayout(this);
        mRight.setOrientation(LinearLayout.VERTICAL);
        mRight.setPadding(dp(20), dp(6), dp(20), dp(10));
        rightScroll.addView(mRight, svl());
        rightCol.addView(rightScroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(buildLegend());
        return root;
    }

    /** gamepad legend: only the three controls that are actually required */
    private View buildLegend() {
        LinearLayout bar = row(null, dp(54));
        bar.setBackgroundColor(PANEL);
        bar.setPadding(dp(18), dp(14), dp(14), dp(20)); // PANEL reaches the screen edge
        TextView t = tv(bar, "\u271A 移动选择      \u24B6 确认      \u24B7 返回",
                13, SUB, -1, dp(20));
        t.setGravity(Gravity.TOP | Gravity.START);
        return bar;
    }

    // ================= cursor state machine =================

    private void reg(int col, View v, Runnable paint, Runnable action) {
        Row r = new Row(v, paint, action);
        if (paint != null) mPainters.add(paint);
        if (col == 0) mReg0.add(r);
        else if (col == 1) mReg1.add(r);
        else mReg2.add(r);
        v.setOnClickListener(x -> {
            setCursorTo(v);
            if (action != null) action.run();
        });
    }

    /** assemble the column lists after (re)building a page, keeping the cursor sane */
    private void commitRows() {
        mCols.clear();
        mCols.add(new ArrayList<>(mReg0));
        mCols.add(new ArrayList<>(mReg1));
        mCols.add(new ArrayList<>(mReg2));
        if (mCol >= mCols.size()) mCol = mCols.size() - 1;
        List<Row> c = mCols.get(mCol);
        if (c.isEmpty()) {
            for (int i = mCols.size() - 1; i >= 0; i--) {
                if (!mCols.get(i).isEmpty()) { mCol = i; c = mCols.get(i); break; }
            }
        }
        if (mRow >= c.size()) mRow = c.size() - 1;
        if (mRow < 0) mRow = 0;
        paintAll();
    }

    private boolean isCursor(View v) {
        if (mCols.isEmpty()) return false;
        List<Row> c = mCols.get(Math.min(mCol, mCols.size() - 1));
        return mRow < c.size() && c.get(mRow).v == v;
    }

    private void setCursorTo(View v) {
        for (int ci = 0; ci < mCols.size(); ci++) {
            List<Row> c = mCols.get(ci);
            for (int ri = 0; ri < c.size(); ri++) {
                if (c.get(ri).v == v) {
                    mCol = ci;
                    mRow = ri;
                    paintAll();
                    return;
                }
            }
        }
    }

    /** Paint-only row: rendered like any other row but never selectable. */
    private void paintOnly(View v, int baseColor, int radiusPx) {
        Runnable p = () -> applyBg(v, baseColor, radiusPx, false);
        mPainters.add(p);
        p.run();
    }

    private void paintAll() {
        for (Runnable p : mPainters) p.run();
    }

    private void moveCursor(int dCol, int dRow) {
        Log.i("KeyMapUI", "move dCol=" + dCol + " dRow=" + dRow
                + " from col=" + mCol + " row=" + mRow + " ncols=" + mCols.size());
        if (mCols.isEmpty()) return;
        if (dCol < 0) {                            // left: skip empty columns
            int t = prevNonEmptyCol(mCol);
            if (t >= 0) { mCol = t; mRow = t == 0 ? mPage : (t == 1 ? clampRow(1, mSel) : 0); }
        } else if (dCol > 0) {                     // right: skip empty columns too
            int t = nextNonEmptyCol(mCol);
            if (t >= 0) { mCol = t; mRow = t == 1 ? clampRow(1, mSel) : 0; }
        } else if (dRow != 0) {
            List<Row> c = mCols.get(mCol);
            if (!c.isEmpty()) {
                int r = mRow + dRow;
                if (r < 0) r = 0;
                if (r >= c.size()) r = c.size() - 1;
                mRow = r;
            }
        }
        Log.i("KeyMapUI", "cursor col=" + mCol + " row=" + mRow
                + " page=" + mPage + " size=" + mCols.get(mCol).size());
        paintAll();
    }

    private int clampSize(int col) {
        return col < mCols.size() ? mCols.get(col).size() : 0;
    }

    private int clampRow(int col, int row) {
        if (col >= mCols.size()) return 0;
        int n = mCols.get(col).size();
        if (n == 0) return 0;
        return Math.max(0, Math.min(row, n - 1));
    }

    private void activateCursor() {
        if (mCols.isEmpty()) return;
        List<Row> c = mCols.get(mCol);
        if (mRow >= c.size()) return;
        Row r = c.get(mRow);
        Log.i("KeyMapUI", "activate col=" + mCol + " row=" + mRow
                + " has=" + (r.action != null));
        if (r.action != null) r.action.run();
    }

    /** go one column towards the left-hand nav: right -> middle -> nav -> exit */
    private void backOut() {
        int target = prevNonEmptyCol(mCol);
        if (target < 0) {
            finish();
            return;
        }
        mCol = target;
        mRow = target == 0 ? mPage : (target == 1 ? Math.max(0, mSel) : 0);
        Log.i("KeyMapUI", "back -> col=" + mCol + " row=" + mRow);
        paintAll();
    }

    /** nearest non-empty column strictly left of {@code col}, or -1 */
    private int prevNonEmptyCol(int col) {
        for (int c = col - 1; c >= 0; c--) if (clampSize(c) > 0) return c;
        return -1;
    }

    /** nearest non-empty column strictly right of {@code col}, or -1 */
    private int nextNonEmptyCol(int col) {
        for (int c = col + 1; c < mCols.size(); c++) if (clampSize(c) > 0) return c;
        return -1;
    }

    // ================= gamepad / keys =================

    @Override
    public boolean dispatchKeyEvent(KeyEvent ev) {
        final int code = ev.getKeyCode();
        final int action = ev.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            Log.i("KeyMapUI", "key " + KeyEvent.keyCodeToString(code));
            switch (code) {
                case KeyEvent.KEYCODE_DPAD_UP:    moveCursor(0, -1); return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:  moveCursor(0, 1);  return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:  moveCursor(-1, 0); return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT: moveCursor(1, 0);  return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:      activateCursor();  return true;
                case KeyEvent.KEYCODE_BUTTON_X:
                    pickTarget();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_B:   backOut();         return true;
                case KeyEvent.KEYCODE_BUTTON_L1:
                    mPage = (mPage + NAV_COUNT - 1) % NAV_COUNT;
                    mCol = 1; mRow = 0; refresh(); return true;
                case KeyEvent.KEYCODE_BUTTON_R1:
                    mPage = (mPage + 1) % NAV_COUNT;
                    mCol = 1; mRow = 0; refresh(); return true;
                default:
                    break;
            }
        }
        return super.dispatchKeyEvent(ev);
    }

    /** left stick / hat -> cursor movement, with deadzone and repeat throttle */
    @Override
    public boolean onGenericMotionEvent(android.view.MotionEvent ev) {
        float x = ev.getAxisValue(android.view.MotionEvent.AXIS_X);
        float y = ev.getAxisValue(android.view.MotionEvent.AXIS_Y);
        float hx = ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_X);
        float hy = ev.getAxisValue(android.view.MotionEvent.AXIS_HAT_Y);
        if (Math.abs(hx) > 0.5f) x = hx;
        if (Math.abs(hy) > 0.5f) y = hy;
        if (Math.abs(x) < 0.55f && Math.abs(y) < 0.55f) return true;
        long now = SystemClock.uptimeMillis();
        if (now - mLastStick < 220) return true;
        mLastStick = now;
        if (Math.abs(x) >= Math.abs(y)) moveCursor(x > 0 ? 1 : -1, 0);
        else moveCursor(0, y > 0 ? 1 : -1);
        return true;
    }

    // ================= pages =================

    private void refresh() {
        mReg0.clear();
        mReg1.clear();
        mReg2.clear();
        mPainters.clear();
        buildNavRegs();
        if (mPage == 0) buildKeyPane();
        else buildStatusPane();
        commitRows();
        paintNav();
    }

    private void buildNavRegs() {
        for (int i = 0; i < NAV_COUNT; i++) {
            View item = mNavCell.get(i)[0];
            final int idx = i;
            reg(0, item, () -> paintNav(),
                    () -> { mPage = idx; mCol = 1; mRow = 0; refresh(); });
        }
    }

    private void paintNav() {
        for (int i = 0; i < NAV_COUNT; i++) {
            View item = mNavCell.get(i)[0];
            boolean sel = i == mPage;
            applyBg(item, sel ? CARD : PANEL, dp(8), isCursor(item));
            TextView icon = (TextView) mNavCell.get(i)[1];
            TextView lab = (TextView) mNavCell.get(i)[2];
            icon.setTextColor(sel ? ACCENT : SUB);
            lab.setTextColor(sel ? ACCENT : SUB);
            lab.setTypeface(null, sel ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void buildKeyPane() {
        sectionHeader(mMidHeader, "按键映射", "选中后在右侧修改");
        mMid.removeAllViews();
        loadConfig();
        for (int i = 0; i < TRIG_NAMES.length; i++) {
            final int idx = i;
            LinearLayout r = row(mMid, -2);
            r.setPadding(dp(14), dp(14), dp(14), dp(14));
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
            rp.topMargin = dp(8);
            r.setLayoutParams(rp);
            TextView nm = tv(r, TRIG_NAMES[i], 15, TEXT, -2, -1);
            nm.setTypeface(null, Typeface.BOLD);
            nm.setAllCaps(false);
            TextView val = tv(r, "", 13, SUB, -1, -1);
            val.setGravity(Gravity.END);
            mCardVals[idx] = val;
            reg(1, r, () -> paintCard(idx), () -> {
                mSel = idx;
                paintCards();
                buildEditor(null);      // fresh right pane for this trigger
                mCol = 2;               // ...and put the cursor in it
                mRow = 0;
                commitRows();
            });
        }
        paintCards();
        buildEditor(null);
    }

    private void paintCard(int idx) {
        View r = mCols.isEmpty() || mCols.get(1).size() <= idx ? null : mCols.get(1).get(idx).v;
        // fall back to the registered view when commit has not happened yet
        if (r == null && mReg1.size() > idx) r = mReg1.get(idx).v;
        if (r == null) return;
        boolean sel = idx == mSel;
        applyBg(r, sel ? ACCENT_FILL : CARD, dp(10), isCursor(r));
        if (((LinearLayout) r).getChildCount() > 0) {
            ((TextView) ((LinearLayout) r).getChildAt(0))
                    .setTextColor(sel ? ACCENT_DARK : TEXT);
        }
        TextView val = mCardVals[idx];
        if (val != null) {
            val.setTextColor(sel ? 0xFF0A4557 : SUB);
            val.setText(targetLabel(idx));
        }
    }

    private void paintCards() {
        for (int i = 0; i < TRIG_NAMES.length; i++) paintCard(i);
    }

    private String targetLabel(int trig) {
        if (mSelTarget[trig] <= 0) return "不映射";
        String n = PICK_NAME[mSelTarget[trig]];
        return mTurbo[trig] ? n + " ·连发" : n;
    }

    /** right-hand editor for the current trigger */
    private void buildEditor(String ignored) {
        mReg2.clear();          // drop rows of the previous right-pane build
        sectionHeader(mRightHeader, TRIG_NAMES[mSel], "");
        mRight.removeAllViews();
        mRateRow = null;
        mRateVal = null;
        mRateLbl = null;

        label("按键映射");
        LinearLayout r1 = row(mRight, -2);
        ((LinearLayout.LayoutParams) r1.getLayoutParams()).topMargin = dp(6);
        LinearLayout vbox = new LinearLayout(this);
        vbox.setOrientation(LinearLayout.HORIZONTAL);
        vbox.setGravity(Gravity.CENTER_VERTICAL);
        vbox.setPadding(dp(14), 0, dp(14), 0);
        r1.addView(vbox, new LinearLayout.LayoutParams(0, dp(46), 1f));
        mTargetVal = new TextView(this);
        mTargetVal.setTextSize(14);
        mTargetVal.setTextColor(TEXT);
        mTargetVal.setAllCaps(false);
        mTargetVal.setText(mSelTarget[mSel] > 0 ? PICK_NAME[mSelTarget[mSel]] : "不映射");
        vbox.addView(mTargetVal, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView arrow = new TextView(this);
        arrow.setText("\u25BE");
        arrow.setTextSize(20);
        arrow.setTextColor(ACCENT);
        arrow.setGravity(Gravity.CENTER);
        vbox.addView(arrow, new LinearLayout.LayoutParams(-2, -2));
        reg(2, vbox, () -> applyBg(vbox, CARD, dp(10), isCursor(vbox)), this::pickTarget);

        label("连发");
        mTurboBtn = new TextView(this);
        mTurboBtn.setTextSize(14);
        mTurboBtn.setAllCaps(false);
        mTurboBtn.setGravity(Gravity.CENTER);
        mTurboBtn.setPadding(dp(12), dp(13), dp(12), dp(13));
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(-1, -2);
        tp.topMargin = dp(6);
        mRight.addView(mTurboBtn, tp);
        reg(2, mTurboBtn, () -> paintTurbo(), () -> {
            if (mSelTarget[mSel] <= 0) { toast("请先设置按键映射"); return; }
            mTurbo[mSel] = !mTurbo[mSel];
            if (mTurbo[mSel] && mRate[mSel] <= 0) mRate[mSel] = 10;
            persist();
            paintTurbo();
            paintCard(mSel);
            buildEditor(null);   // rebuilds the rate stepper too when turbo is on
            commitRows();
        });
        paintTurbo();
        if (mTurbo[mSel]) buildRateRow();
    }

    private void buildRateRow() {
        label("连发频率");
        mRateLbl = (TextView) mRight.getChildAt(mRight.getChildCount() - 1);
        mRateRow = row(mRight, -2);
        ((LinearLayout.LayoutParams) mRateRow.getLayoutParams()).topMargin = dp(6);
        TextView minus = stepBtn("\u2212");
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        mRateRow.addView(minus, mp);
        mRateVal = new TextView(this);
        mRateVal.setTextSize(15);
        mRateVal.setAllCaps(false);
        mRateVal.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(0, dp(46), 1f);
        vp.leftMargin = dp(8);
        vp.rightMargin = dp(8);
        mRateRow.addView(mRateVal, vp);
        TextView plus = stepBtn("+");
        mRateRow.addView(plus, new LinearLayout.LayoutParams(0, dp(46), 1f));
        reg(2, minus, () -> applyBg(minus, CARD, dp(10), isCursor(minus)), () -> {
            mRate[mSel] = Math.max(1, mRate[mSel] - 1);
            persist(); updateRateLabel(); paintCard(mSel);
        });
        reg(2, plus, () -> applyBg(plus, CARD, dp(10), isCursor(plus)), () -> {
            mRate[mSel] = Math.min(30, mRate[mSel] + 1);
            persist(); updateRateLabel(); paintCard(mSel);
        });
        updateRateLabel();
    }

    private void updateRateLabel() {
        if (mRateVal != null) mRateVal.setText(mRate[mSel] + " 次/秒");
    }

    private void paintTurbo() {
        boolean on = mTurbo[mSel];
        mTurboBtn.setText(on ? "开启" : "关闭");
        mTurboBtn.setTextColor(on ? ACCENT_DARK : TEXT);
        applyBg(mTurboBtn, on ? ACCENT_FILL : CARD, dp(10), isCursor(mTurboBtn));
    }

    // ================= picker =================

    private void pickTarget() {
        final int cur = Math.max(0, mSelTarget[mSel]);
        HEADER_HPX = dp(38);
        ITEM_HPX = dp(45);

        // ---- build our own list: full-width rows, no ListView/theme quirks ----
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        final java.util.List<TextView> rows = new ArrayList<>();
        final java.util.List<Integer> rowsIdx = new ArrayList<>();
        for (int i = 0; i < PICK_NAME.length; i++) {
            TextView t = new TextView(this);
            t.setText(PICK_NAME[i]);
            t.setAllCaps(false);
            if (PICK_HDR[i]) {
                t.setTextSize(12);
                t.setTextColor(SUB);
                t.setTypeface(null, Typeface.BOLD);
                t.setPadding(dp(20), dp(14), dp(20), dp(6));
                t.setLayoutParams(new LinearLayout.LayoutParams(-1, HEADER_HPX));
            } else {
                t.setTextSize(15);
                t.setPadding(dp(24), dp(12), dp(20), dp(12));
                final int pick = i;
                rows.add(t);
                rowsIdx.add(pick);
                t.setOnClickListener(v -> {
                    applyPick(pick);
                    if (mPickDialog != null) mPickDialog.dismiss();
                });
            }
            if (PICK_HDR[i]) list.addView(t);
            else list.addView(t, new LinearLayout.LayoutParams(-1, ITEM_HPX));
        }
        list.setPadding(0, dp(6), 0, dp(6));
        ScrollView sv = new ScrollView(this);
        sv.setFocusable(false);
        sv.addView(list, new ScrollView.LayoutParams(-1, -2));
        // invisible until the first scroll is applied -> the list never appears
        // at the top and then slides, the very first visible frame is final
        sv.setAlpha(0f);

        final android.app.AlertDialog d = dlg().setTitle("按键映射")
                .setView(sv).create();
        // fixed content height: the dialog can no longer re-measure after the
        // first layout, so its centred position stays put
        sv.setLayoutParams(new LinearLayout.LayoutParams(-1, dp(420)));
        Window dw = d.getWindow();
        dw.setWindowAnimations(0);
        dw.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN);
        dw.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_UNCHANGED);
        dw.setGravity(Gravity.CENTER);
        if (android.os.Build.VERSION.SDK_INT >= 30) dw.setDecorFitsSystemWindows(false);

        // ---- cursor over our own rows (same idea as the main screen) ----
        int startRow = 0;
        for (int k = 0; k < rowsIdx.size(); k++) if (rowsIdx.get(k) == cur) startRow = k;
        final int[] cursor = { startRow };
        Runnable paint = () -> {
            for (int k = 0; k < rows.size(); k++) {
                TextView t = rows.get(k);
                boolean onCursor = k == cursor[0];
                boolean isCurrent = rowsIdx.get(k) == cur;
                t.setBackground(onCursor ? selectorDrawable() : null);
                t.setTextColor(onCursor ? ACCENT : (isCurrent ? ACCENT : TEXT));
                t.setTypeface(null, (onCursor || isCurrent) ? Typeface.BOLD : Typeface.NORMAL);
            }
        };
        Runnable reveal = () -> sv.post(() -> {
            int top = offsetForRow(rowsIdxOf(cursor[0]));
            int view = sv.getHeight();
            int y = sv.getScrollY();
            if (top < y) sv.scrollTo(0, Math.max(0, top - dp(6)));
            else if (top + ITEM_HPX > y + view) sv.scrollTo(0, top + ITEM_HPX - view + dp(6));
        });

        d.setOnKeyListener((dlg, keyCode, kev) -> {
            if (kev.getAction() != KeyEvent.ACTION_DOWN) return false;
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    if (cursor[0] > 0) { cursor[0]--; paint.run(); reveal.run(); }
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    if (cursor[0] < rows.size() - 1) { cursor[0]++; paint.run(); reveal.run(); }
                    return true;
                case KeyEvent.KEYCODE_BUTTON_A:
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    applyPick(rowsIdx.get(cursor[0]));
                    dlg.dismiss();
                    return true;
                case KeyEvent.KEYCODE_BUTTON_B:
                    dlg.dismiss();
                    return true;
                default:
                    return false;
            }
        });
        mPickDialog = d;
        d.setOnDismissListener(x -> mPickDialog = null);
        paint.run();
        d.show();
        // the theme re-applies its window animation at show time, so clear it again
        d.getWindow().setWindowAnimations(0);
        final ScrollView fsv = sv;
        final LinearLayout flist = list;
        final java.util.List<TextView> frows = rows;
        final int[] fcur = cursor;
        fsv.getViewTreeObserver().addOnPreDrawListener(
                new android.view.ViewTreeObserver.OnPreDrawListener() {
                    @Override
                    public boolean onPreDraw() {
                        fsv.getViewTreeObserver().removeOnPreDrawListener(this);
                        int top = offsetForRow(rowsIdxOf(fcur[0]));
                        int centre = top - (fsv.getHeight() - ITEM_HPX) / 2;
                        fsv.scrollTo(0, Math.max(0, centre));
                        fsv.setAlpha(1f);
                        return true;   // draw the already-scrolled frame
                    }
                });
    }

    private android.app.AlertDialog mPickDialog;

    private int HEADER_HPX = 0, ITEM_HPX = 0;

    /** pixel offset of the n-th selectable row, from the fixed row heights */
    private int offsetForRow(int pickTarget) {
        int off = 0;
        for (int i = 0; i < PICK_NAME.length; i++) {
            if (!PICK_HDR[i] && i == pickTarget) break;
            off += PICK_HDR[i] ? HEADER_HPX : ITEM_HPX;
        }
        return Math.max(0, off - dp(8));
    }

    /** pick index of the n-th selectable row */
    private int rowsIdxOf(int row) {
        int seen = 0;
        for (int i = 0; i < PICK_NAME.length; i++) {
            if (PICK_HDR[i]) continue;
            if (seen++ == row) return i;
        }
        return 0;
    }

    private void applyPick(int pick) {
        if (PICK_HDR[pick]) return;
        mSelTarget[mSel] = pick;
        if (PICK_CFG[pick].equals("0")) mTurbo[mSel] = false;
        persist();
        paintCard(mSel);
        buildEditor(null);
        mCol = 2;
        mRow = 0;
        commitRows();
    }

    // ================= service page =================

    private void buildStatusPane() {
        sectionHeader(mMidHeader, "服务状态", "运行组件自检");
        mMid.removeAllViews();
        boolean first = isA11yFirst();
        boolean daemonOk = mDaemonOk;
        addStatusRow("无障碍服务", RemapService.sConnected ? "已绑定 ●" : "未绑定 ○",
                RemapService.sConnected ? 100 : 101);
        addStatusRow("拦截优先级", first ? "队列首位（先于厂商）" : "非首位 — 右侧开启",
                first ? 100 : 101);
        addStatusRow("注入守护", daemonOk ? "运行中 ●" : "未运行 ○",
                daemonOk ? 100 : 101);
        addStatusRow("模块版本", "1.0.0", 103);
        buildServicePane();
    }

    private void addStatusRow(String k, String v, int kind) {
        LinearLayout r = row(mMid, -2);
        r.setPadding(dp(12), dp(14), dp(12), dp(14));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, -2);
        rp.topMargin = dp(8);
        r.setLayoutParams(rp);
        tv(r, k, 13, SUB, dp(110), -1);
        TextView v2 = tv(r, v, 13,
                kind == 100 ? ACCENT : kind == 101 ? DANGER : TEXT, -1, -1);
        v2.setGravity(Gravity.END);
        // read-only info: rendered, but not part of the cursor state machine
        paintOnly(r, CARD, dp(8));
    }

    private void buildServicePane() {
        mReg2.clear();
        sectionHeader(mRightHeader, "服务配置", "通过 Magisk 提权");
        mRight.removeAllViews();
        final boolean a11yOn = RemapService.sConnected && isA11yFirst();
        LinearLayout r1 = row(mRight, -2);
        ((LinearLayout.LayoutParams) r1.getLayoutParams()).topMargin = dp(12);
        TextView b1 = button(r1, a11yOn ? "无障碍服务：开启中，点击停用"
                : "无障碍服务：已停用，点击开启", a11yOn ? ACCENT_FILL : CARD, 1f);
        reg(2, b1, () -> applyBg(b1, a11yOn ? ACCENT_FILL : CARD, dp(10), isCursor(b1)),
                () -> new Thread(() -> {
                    boolean nowOn = RemapService.sConnected && isA11yFirst();
                    boolean ok = nowOn
                            ? RootUtil.disableA11y(oursComponent(), currentA11yList())
                            : RootUtil.enableA11y(oursComponent(), currentA11yList());
                    runOnUiThread(() -> {
                        toast(ok ? "已提交，等几秒生效" : "失败：请检查 Magisk 授权");
                        refresh();
                    });
                }).start());
        desc("无障碍服务负责截获并吞掉背键事件，是全部映射的前提；停用后背键恢复系统原生行为。");

        final boolean modOn = RootUtil.moduleEnabled();
        LinearLayout r2 = row(mRight, -2);
        ((LinearLayout.LayoutParams) r2.getLayoutParams()).topMargin = dp(14);
        TextView b2 = button(r2, modOn ? "Magisk 模块：已启用，点击停用"
                : "Magisk 模块：已停用，点击启用", modOn ? ACCENT_FILL : CARD, 1f);
        reg(2, b2, () -> applyBg(b2, modOn ? ACCENT_FILL : CARD, dp(10), isCursor(b2)),
                () -> new Thread(() -> {
                    boolean nowOn = RootUtil.moduleEnabled();
                    boolean ok = RootUtil.setModuleEnabled(!nowOn);
                    if (ok && !nowOn) RootUtil.startDaemon();
                    runOnUiThread(() -> {
                        toast(ok ? (nowOn ? "已写入停用标记，重启后生效" : "已启用，守护已拉起")
                                : "失败：请检查 Magisk 授权");
                        refresh();
                        probeDaemon();
                    });
                }).start());
        desc("模块负责开机自动注入守护进程、同步配置并在 ROM 更新后随模块重装 App；停用需重启生效。");
    }

    private void desc(String t) {
        TextView d = new TextView(this);
        d.setText(t);
        d.setTextSize(12);
        d.setTextColor(SUB);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(8);
        mRight.addView(d, p);
    }

    // ================= config =================

    /** bare-APK install (no module): start from the documented defaults */
    private void seedDefaultsIfEmpty() {
        SharedPreferences p = getSharedPreferences("keymap", MODE_PRIVATE);
        if (!p.getString("raw", "").trim().isEmpty()) return;
        p.edit().putString("raw",
                "F10=SYSRQ\nF11=BUTTON_THUMBL\nF12=BUTTON_THUMBR\n").commit();
    }

    private void loadConfig() {
        for (int i = 0; i < TRIG_CODES.length; i++) {
            mSelTarget[i] = -1;
            mTurbo[i] = false;
            mRate[i] = 0;
        }
        SharedPreferences p = getSharedPreferences("keymap", MODE_PRIVATE);
        String raw = p.getString("raw", "");
        StringBuilder extra = new StringBuilder();
        // tolerate ';'-separated payloads (the module seeds config that way)
        if (raw.indexOf('\n') < 0 && raw.indexOf(';') >= 0) raw = raw.replace(';', '\n');
        String[] lines = raw.split("\n");
        int i = 0;
        while (i < lines.length) {
            String line = lines[i].trim();
            boolean isGlobal = false;
            if (line.equals("[PACKAGE]") && i + 1 < lines.length) {
                extra.append("\n[PACKAGE]\n").append(lines[++i]).append('\n');
                while (i + 1 < lines.length && lines[i + 1].startsWith("    ")) {
                    i++;
                    extra.append(lines[i]).append('\n');
                }
                i++;
                continue;
            }
            if (!line.isEmpty() && !line.startsWith("#")) {
                String[] kv = line.split("=", 2);
                if (kv.length == 2) {
                    int src = RemapService.keyCodeFromName(kv[0].trim().toUpperCase());
                    for (int t = 0; t < TRIG_CODES.length; t++) {
                        if (src == TRIG_CODES[t]) {
                            String valraw = kv[1];
                            int hash = valraw.indexOf('#');
                            if (hash >= 0) valraw = valraw.substring(0, hash);
                            String tv2 = valraw.trim().toUpperCase();
                            int hz = 0;
                            int bang = tv2.indexOf('!');
                            if (bang >= 0) {
                                hz = 10;
                                String rs = tv2.substring(bang + 1).trim();
                                if (!rs.isEmpty()) {
                                    try {
                                        hz = Integer.parseInt(rs);
                                    } catch (NumberFormatException ignored) {
                                    }
                                }
                                tv2 = tv2.substring(0, bang);
                            }
                            int idx = idxOfCode(RemapService.keyCodeFromName(tv2));
                            if (idx >= 0) {
                                mSelTarget[t] = idx;
                                mTurbo[t] = hz > 0;
                                mRate[t] = hz;
                            }
                            isGlobal = true;
                            break;
                        }
                    }
                    if (!isGlobal) extra.append(line).append('\n');
                }
            }
            i++;
        }
        mExtraRaw = extra.toString();
    }

    private void persist() {
        SharedPreferences p = getSharedPreferences("keymap", MODE_PRIVATE);
        StringBuilder keep = new StringBuilder();
        boolean wroteTrigs = false;
        for (String line : p.getString("raw", "").split("\n")) {
            String t = line.trim();
            boolean isTrig = false;
            if (t.contains("=")) {
                int src = RemapService.keyCodeFromName(
                        t.substring(0, t.indexOf('=')).trim().toUpperCase());
                for (int trig : TRIG_CODES) if (src == trig) isTrig = true;
            }
            if (!isTrig && !t.startsWith("#")) {
                keep.append(line).append('\n');
            } else if (isTrig && !wroteTrigs) {
                for (int t2 = 0; t2 < TRIG_CODES.length; t2++) {
                    keep.append(TRIG_CFG[t2]).append('=');
                    if (mSelTarget[t2] > 0) {
                        keep.append(PICK_CFG[mSelTarget[t2]]);
                        if (mTurbo[t2]) keep.append('!').append(mRate[t2] > 0 ? mRate[t2] : 10);
                    } else {
                        keep.append('0');
                    }
                    keep.append('\n');
                }
                wroteTrigs = true;
            }
        }
        if (!wroteTrigs) {
            for (int t2 = 0; t2 < TRIG_CODES.length; t2++) {
                keep.append(TRIG_CFG[t2]).append('=');
                if (mSelTarget[t2] > 0) {
                    keep.append(PICK_CFG[mSelTarget[t2]]);
                    if (mTurbo[t2]) keep.append('!').append(mRate[t2] > 0 ? mRate[t2] : 10);
                } else {
                    keep.append('0');
                }
                keep.append('\n');
            }
        }
        p.edit().putString("raw", keep.toString()).commit();
    }

    private int idxOfCode(int code) {
        for (int i = 0; i < PICK_CFG.length; i++) {
            if (PICK_HDR[i] || PICK_CFG[i].isEmpty()) continue;
            if (RemapService.keyCodeFromName(PICK_CFG[i]) == code) return i;
        }
        if (code == 82) { // legacy SYSRQ configs
            for (int i = 0; i < PICK_CFG.length; i++)
                if (PICK_CFG[i].equals("SYSRQ")) return i;
        }
        return -1;
    }

    // ================= small helpers =================

    private LinearLayout row(ViewGroup parent, int h) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        if (parent != null) parent.addView(l, new LinearLayout.LayoutParams(-1, h));
        return l;
    }

    private TextView tv(ViewGroup parent, String s, float sp, int color, int w, int h) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (parent != null) parent.addView(t, new LinearLayout.LayoutParams(w, h));
        return t;
    }

    private ScrollView.LayoutParams svl() {
        return new ScrollView.LayoutParams(-1, -2);
    }

    private void label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(SUB);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(14);
        mRight.addView(t, p);
    }

    private void sectionHeader(LinearLayout header, String title, String hint) {
        header.removeAllViews();
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(12), 0, dp(12));
        View tick = new View(this);
        GradientDrawable tg = new GradientDrawable();
        tg.setColor(ACCENT);
        tg.setCornerRadius(dp(2));
        tick.setBackground(tg);
        header.addView(tick, new LinearLayout.LayoutParams(dp(4), dp(18)));
        ((LinearLayout.LayoutParams) tick.getLayoutParams()).gravity = Gravity.CENTER_VERTICAL;
        TextView t = tv(header, "  " + title, 15, TEXT, -2, -1);
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setTypeface(null, Typeface.BOLD);
        TextView h = tv(header, "   " + hint, 11, SUB, -2, -1);
        h.setGravity(Gravity.CENTER_VERTICAL);
    }

    private TextView stepBtn(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(20);
        t.setTextColor(TEXT);
        t.setGravity(Gravity.CENTER);
        applyBg(t, CARD, dp(10), false);
        return t;
    }

    /** service-config button: enabled via weight */
    private TextView button(LinearLayout parent, String label, int color, double weight) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTypeface(null, Typeface.BOLD);
        t.setAllCaps(false);
        t.setTextColor((color == ACCENT || color == ACCENT_FILL) ? ACCENT_DARK : TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        applyBg(t, (color == ACCENT || color == ACCENT_FILL) ? ACCENT_FILL : CARD, dp(10), false);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, (float) weight);
        p.leftMargin = dp(6);
        p.rightMargin = dp(6);
        parent.addView(t, p);
        return t;
    }

    /** top-bar pill (touch only: never in the cursor graph) */
    private TextView btn(ViewGroup parent, String label, int color, View.OnClickListener c) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(16), dp(8), dp(16), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(8));
        bg.setStroke(dp(1), color);
        t.setBackground(bg);
        t.setOnClickListener(c);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
        p.gravity = Gravity.CENTER_VERTICAL;
        p.leftMargin = dp(8);
        parent.addView(t, p);
        return t;
    }

    /** full-width rounded highlight used by the picker rows */
    private GradientDrawable selectorDrawable() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(8));
        g.setColor(0x3329D6FF);
        g.setStroke(dp(2), ACCENT);
        return g;
    }

    /** paint a row background; ring when the cursor is on it */
    /**
     * Fill stays exactly as it is when unselected (same size, same colour), and
     * the cursor ring is drawn as a separate foreground overlay - so putting the
     * cursor on a row never changes that row's style, it only adds the ring.
     */
    private void applyBg(View v, int baseColor, int radiusPx, boolean ring) {
        GradientDrawable fill = new GradientDrawable();
        fill.setCornerRadius(radiusPx);
        fill.setColor(baseColor);
        v.setBackground(fill);
        if (!ring) {
            v.setForeground(null);
            return;
        }
        GradientDrawable outline = new GradientDrawable();
        outline.setCornerRadius(radiusPx);
        outline.setColor(0x00000000);
        // always the light ring: the activated fill is a deeper tone now, so the
        // ring stands out against both the fill and the dark background
        outline.setStroke(dp(3), RING);
        // no inset: the ring sits exactly on the row's bounds, so putting the
        // cursor on a row only changes colours, never the row's size
        v.setForeground(outline);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private android.app.AlertDialog.Builder dlg() {
        return new android.app.AlertDialog.Builder(this,
                android.R.style.Theme_DeviceDefault_Dialog_Alert);
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    // ================= status / heal =================

    private String oursComponent() {
        return getPackageName() + "/" + RemapService.class.getName();
    }

    private String currentA11yList() {
        return Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
    }

    private boolean isA11yFirst() {
        String s = currentA11yList();
        if (s == null) return false;
        for (String part : s.split(":")) {
            if (part.isEmpty()) continue;
            return part.contains(getPackageName());
        }
        return false;
    }

    private volatile boolean mDaemonOk = false;
    private boolean mHealTried = false;

    private void autoHeal() {
        if (mHealTried) return;
        mHealTried = true;
        new Thread(() -> {
            try {
                boolean a11yBad = !RemapService.sConnected || !isA11yFirst();
                boolean daemonBad;
                try (java.net.Socket s = new java.net.Socket()) {
                    s.connect(new java.net.InetSocketAddress("127.0.0.1", 57521), 400);
                    daemonBad = false;
                } catch (Exception e) {
                    daemonBad = true;
                }
                if (daemonBad) RootUtil.startDaemon();
                if (a11yBad) RootUtil.enableA11y(oursComponent(), currentA11yList());
            } catch (Exception ignored) {
            }
        }).start();
    }

    private void probeDaemon() {
        new Thread(() -> {
            boolean ok;
            try (java.net.Socket s = new java.net.Socket()) {
                s.connect(new java.net.InetSocketAddress("127.0.0.1", 57521), 400);
                ok = true;
            } catch (Exception e) {
                ok = false;
            }
            final boolean f = ok;
            mDaemonOk = f;
            runOnUiThread(() -> { if (mPage == 1) refresh(); });
        }).start();
    }
}

