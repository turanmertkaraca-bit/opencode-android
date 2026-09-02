package ai.opencode.app;

import android.content.Context;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Scroller;
import android.view.animation.DecelerateInterpolator;

/**
 * P8 DeckView — a vertical, one-card-per-swipe snap carousel (the
 * "credit-card wallet" the user asked for: screen goes up and down,
 * neighbors peek and shrink).
 *
 * Framework-only (no androidx): a ViewGroup that lays its children in a
 * vertical strip, each page exactly one viewport tall, with a Scroller
 * snap, velocity-based page projection (capped at one page per gesture —
 * deliberate, snappy, never overshoots three cards), tap/long-press via
 * GestureDetector, and per-child scale/alpha/elevation transforms driven
 * by distance from center.
 */
public class DeckView extends ViewGroup {

    public interface Callback {
        void onSettled(int page);
        void onTap(int page);
        void onLongPress(int page);
    }

    private static final int MIN_FLING_VEL = 350;   // px/s
    private static final int MAX_SNAP_MS   = 320;

    private final Scroller scroller;
    private final GestureDetector gestures;
    private VelocityTracker vt;

    private float lastY;
    private boolean dragging;
    private int lastSettled = -1;
    private Callback cb;
    private int sidePad;

    public DeckView(Context c) {
        super(c);
        scroller = new Scroller(c, new DecelerateInterpolator(1.4f));
        gestures = new GestureDetector(c, new Gest());
        sidePad = Theme.dp(c, 18);
        touchSlop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        setClipToPadding(false);
        setClipChildren(false);
    }

    public void setCallback(Callback c) { cb = c; }
    public void setSidePad(int px) { sidePad = px; requestLayout(); }

    // ---- geometry ------------------------------------------------------

    private int pageH() { return Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom()); }

    private int maxScroll() { return Math.max(0, (getChildCount() - 1) * pageH()); }

    public int page() {
        int ph = pageH();
        return ph <= 0 ? 0 : Math.round(getScrollY() / (float) ph);
    }

    public void setCurrent(int page, boolean smooth) {
        page = Math.max(0, Math.min(page, getChildCount() - 1));
        int y = page * pageH();
        if (smooth) smoothTo(y); else { scrollTo(0, y); applyTransforms(); }
        lastSettled = page;
    }

    private void smoothTo(int y) {
        int dist = Math.abs(y - getScrollY());
        int dur = Math.max(160, Math.min(MAX_SNAP_MS,
                (int) (dist / (float) Math.max(1, pageH()) * MAX_SNAP_MS) + 120));
        scroller.abortAnimation();
        scroller.startScroll(0, getScrollY(), 0, y - getScrollY(), dur);
        postInvalidateOnAnimation();
    }

    // ---- measure / layout ----------------------------------------------

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        setMeasuredDimension(w, h);

        int childW = w - getPaddingLeft() - getPaddingRight() - sidePad * 2;
        int pageH = Math.max(1, h - getPaddingTop() - getPaddingBottom());
        // credit-card proportions: height ≈ 0.62 × card width, capped by page
        int cardH = (int) Math.min(pageH * 0.74f, childW * 0.62f);
        cardH = Math.max(cardH, Theme.dp(getContext(), 170));

        int cws = MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY);
        int chs = MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cws, chs);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int pageH = pageH();
        int left = getPaddingLeft() + sidePad;
        int w = r - l - getPaddingLeft() - getPaddingRight() - sidePad * 2;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            int chH = ch.getMeasuredHeight();
            int top = getPaddingTop() + i * pageH + (pageH - chH) / 2;
            ch.layout(left, top, left + w, top + chH);
        }
        applyTransforms();
        clampScroll();
    }

    private void clampScroll() {
        int y = getScrollY();
        int max = maxScroll();
        if (y < 0) scrollTo(0, 0);
        else if (y > max) scrollTo(0, max);
    }

    // ---- transforms (the deck feel) --------------------------------------

    private void applyTransforms() {
        int ph = pageH();
        if (ph <= 0) return;
        int center = getHeight() / 2;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            int chCenter = ch.getTop() + ch.getHeight() / 2 - getScrollY();
            float d = Math.min(1f, Math.abs(chCenter - center) / (float) ph);
            float scale = 1f - 0.13f * d;
            ch.setPivotX(ch.getWidth() / 2f);
            ch.setPivotY(ch.getHeight() / 2f);
            ch.setScaleX(scale);
            ch.setScaleY(scale);
            ch.setAlpha(1f - 0.55f * d);
            ch.setElevation((1f - d) * Theme.dp(getContext(), 12));
        }
    }

    @Override
    protected void onScrollChanged(int x, int oldl, int oldy, int newy) {
        super.onScrollChanged(x, oldl, oldy, newy);
        applyTransforms();
    }

    @Override
    public void computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(0, scroller.getCurrY());
            postInvalidateOnAnimation();
        } else {
            settleCheck(); // snap animation done → notify exactly on change
        }
    }

    private void settleCheck() {
        int p = page();
        if (p != lastSettled) {
            lastSettled = p;
            if (cb != null) cb.onSettled(p);
        }
    }

    // ---- touch -----------------------------------------------------------

    private float downY;
    private int touchSlop;

    /** Intercept once the finger moves past the slop — this is what lets
     *  clickable cards coexist with dragging: taps go to the card, drags
     *  come to the deck (child gets ACTION_CANCEL, click is suppressed). */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                scroller.abortAnimation();
                return false;
            case MotionEvent.ACTION_MOVE:
                return Math.abs(ev.getY() - downY) > touchSlop;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        gestures.onTouchEvent(ev);
        if (vt == null) vt = VelocityTracker.obtain();
        vt.addMovement(ev);

        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                scroller.abortAnimation();
                lastY = ev.getY();
                dragging = true;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) { dragging = true; lastY = ev.getY(); } // post-intercept start
                float dy = ev.getY() - lastY;
                lastY = ev.getY();
                int y = getScrollY() - (int) dy;
                // overscroll resistance at the edges
                if (y < 0) y /= 3;
                else if (y > maxScroll()) y = maxScroll() + (y - maxScroll()) / 3;
                scrollTo(0, y);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    if (vt != null) {
                        vt.computeCurrentVelocity(1000);
                        float vy = vt.getYVelocity();
                        snapAfterDrag(vy);
                        vt.recycle();
                        vt = null;
                    }
                }
                break;
        }
        return true;
    }

    /** One card per gesture: project the fling, clamp to ±1 page. */
    private void snapAfterDrag(float vy) {
        int ph = pageH();
        if (ph <= 0 || getChildCount() == 0) return;
        int cur = page();
        int target = cur;
        if (Math.abs(vy) > MIN_FLING_VEL) {
            int proj = Math.round((getScrollY() + vy * 0.14f) / (float) ph);
            target = Math.max(cur - 1, Math.min(cur + 1, proj));
        } else {
            target = Math.round(getScrollY() / (float) ph);
        }
        target = Math.max(0, Math.min(target, getChildCount() - 1));
        smoothTo(target * ph);
        if (target == lastSettled) settleCheck(); // tapped and stayed → still notify
    }

    // ---- gestures ----------------------------------------------------------

    private final class Gest extends GestureDetector.SimpleOnGestureListener {
        @Override public boolean onDown(MotionEvent e) { return true; }
        @Override public boolean onSingleTapConfirmed(MotionEvent e) {
            if (cb != null && getChildCount() > 0) cb.onTap(page());
            return true;
        }
        @Override public void onLongPress(MotionEvent e) {
            if (cb != null && getChildCount() > 0) cb.onLongPress(page());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (vt != null) { vt.recycle(); vt = null; }
        super.onDetachedFromWindow();
    }
}
