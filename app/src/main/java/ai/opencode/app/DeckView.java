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
 * P10 DeckView — the vertical "credit-card wallet".
 *
 * Two fixes over the P8/P9 version, straight from user feedback:
 *
 *  1. CARDS STAY TOGETHER. Pages used to be one full viewport tall, so two
 *     cards were a whole screen apart ("we don't need a whole page for 2
 *     cards"). Now the per-card stride is just cardHeight + a small gap:
 *     neighbors visibly peek above and below the active card — a wallet,
 *     not a slideshow.
 *
 *  2. FAST FLINGS WORK. The old snap projected velocity*0.14s and rounded,
 *     so a quick flick could round back to the starting card ("it needs to
 *     be slide very slowly or it goes back"). Now the fling rule is the
 *     same one ViewPager uses: a genuine fling advances exactly one card
 *     FROM THE PAGE WHERE THE GESTURE STARTED, in the flick's direction —
 *     velocity projection and rounding can't vote it back.
 *
 * Still framework-only, still one deliberate card per gesture (±1 clamp),
 * tap/long-press via GestureDetector, per-child scale/alpha/elevation by
 * distance from center.
 */
public class DeckView extends ViewGroup {

    public interface Callback {
        void onSettled(int page);
        void onTap(int page);
        void onLongPress(int page);
    }

    private static final int FLING_VEL  = 750;   // px/s — a real flick commits
    private static final float FLING_MIN_TRAVEL = 0.06f; // of one card
    private static final int MAX_SNAP_MS = 340;

    private final Scroller scroller;
    private final GestureDetector gestures;
    private VelocityTracker vt;

    private float lastY;
    private boolean dragging;
    private int lastSettled = -1;
    private int gestureStartPage;   // P10: anchor page at gesture start
    private float flingVy;          // P10: onFling backup velocity
    private boolean flingSeen;
    private Callback cb;
    private int sidePad;
    private int cardH;              // measured in onMeasure

    public DeckView(Context c) {
        super(c);
        scroller = new Scroller(c, new DecelerateInterpolator(1.5f));
        gestures = new GestureDetector(c, new Gest());
        sidePad = Theme.dp(c, 18);
        touchSlop = android.view.ViewConfiguration.get(c).getScaledTouchSlop();
        setClipToPadding(false);
        setClipChildren(false);
    }

    public void setCallback(Callback c) { cb = c; }
    public void setSidePad(int px) { sidePad = px; requestLayout(); }

    // ---- geometry ------------------------------------------------------

    private int viewportH() {
        return Math.max(1, getHeight() - getPaddingTop() - getPaddingBottom());
    }

    /** P10: stacked-wallet stride — card height + a small gap. */
    private int stride() {
        return cardH > 0 ? cardH + Theme.dp(getContext(), 16)
                : Math.max(1, viewportH());
    }

    private int maxScroll() { return Math.max(0, (getChildCount() - 1) * stride()); }

    public int page() {
        int st = stride();
        return st <= 0 ? 0 : Math.round(getScrollY() / (float) st);
    }

    public void setCurrent(int page, boolean smooth) {
        page = Math.max(0, Math.min(page, getChildCount() - 1));
        int y = page * stride();
        if (smooth) smoothTo(y); else { scrollTo(0, y); applyTransforms(); }
        lastSettled = page;
    }

    private void smoothTo(int y) {
        int dist = Math.abs(y - getScrollY());
        int st = stride();
        int dur = Math.max(150, Math.min(MAX_SNAP_MS,
                (int) (dist / (float) Math.max(1, st) * 240) + 110));
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
        // P27 clipping fix (the field shot: the deck card's "last opened ·
        // 2 min ago / Open →" footer clipped mid-line when the path wrapped
        // to two lines). The old code forced a FIXED card height (EXACTLY
        // spec); at larger font scales the content grew past it and the
        // card's own clip cut the footer. Now the cards measure NATURALLY
        // first and the shared height grows to fit the tallest card (still
        // clamped so the wallet keeps its peek room) — one uniform height,
        // every card fits, at any font scale.
        // The cap uses the INCOMING spec height, not getHeight() — during
        // the first measure getHeight() is still 0, which would collapse
        // the cap to the 168dp floor (caught by the P27UiTest pin).
        int vh = Math.max(1, MeasureSpec.getSize(hSpec)
                - getPaddingTop() - getPaddingBottom());
        int cws = MeasureSpec.makeMeasureSpec(childW, MeasureSpec.EXACTLY);
        int natural = Theme.dp(getContext(), 168);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cws, MeasureSpec.makeMeasureSpec(0,
                    MeasureSpec.UNSPECIFIED));
            natural = Math.max(natural, getChildAt(i).getMeasuredHeight());
        }
        int cap = (int) Math.min(vh * 0.72f, childW * 0.90f);
        cardH = Math.min(natural, Math.max(cap, Theme.dp(getContext(), 168)));

        int chs = MeasureSpec.makeMeasureSpec(cardH, MeasureSpec.EXACTLY);
        for (int i = 0; i < getChildCount(); i++) {
            getChildAt(i).measure(cws, chs);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int st = stride();
        int centerBase = stripTop();
        int left = getPaddingLeft() + sidePad;
        int w = r - l - getPaddingLeft() - getPaddingRight() - sidePad * 2;
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            int top = centerBase + i * st;
            ch.layout(left, top, left + w, top + ch.getMeasuredHeight());
        }
        applyTransforms();
        clampScroll();
    }

    /** P10: the active card sits slightly ABOVE center — dead space goes
     *  below (where neighbors peek), not above (where there's nothing). */
    private int stripTop() {
        return getPaddingTop() + (int) ((viewportH() - cardH) * 0.40f);
    }

    private int stripCenter() { return stripTop() + cardH / 2; }

    private void clampScroll() {
        int y = getScrollY();
        int max = maxScroll();
        if (y < 0) scrollTo(0, 0);
        else if (y > max) scrollTo(0, max);
    }

    // ---- transforms (the deck feel) --------------------------------------

    private void applyTransforms() {
        int st = stride();
        if (st <= 0) return;
        int center = stripCenter();
        for (int i = 0; i < getChildCount(); i++) {
            View ch = getChildAt(i);
            int chCenter = ch.getTop() + ch.getHeight() / 2 - getScrollY();
            float d = Math.min(1f, Math.abs(chCenter - center) / (float) st);
            float scale = 1f - 0.10f * d;
            ch.setPivotX(ch.getWidth() / 2f);
            ch.setPivotY(ch.getHeight() / 2f);
            ch.setScaleX(scale);
            ch.setScaleY(scale);
            ch.setAlpha(1f - 0.45f * d);
            ch.setElevation((1f - d) * Theme.dp(getContext(), 10));
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
                gestureStartPage = page();
                flingSeen = false;
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
                gestureStartPage = page();
                flingSeen = false;
                break;
            case MotionEvent.ACTION_MOVE:
                if (!dragging) {
                    // post-intercept start: the early part of the gesture
                    // went to a card — anchor the fling HERE, not at 0.
                    dragging = true;
                    lastY = ev.getY();
                    gestureStartPage = page();
                }
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

    /**
     * P10 snap rule:
     *   • genuine fling (velocity above threshold AND the page actually
     *     moved a little) → advance exactly one card from the gesture's
     *     anchor page, in the fling's direction — never back;
     *   • slow drag → commit past the halfway point, else settle back.
     */
    private void snapAfterDrag(float vy) {
        int st = stride();
        if (st <= 0 || getChildCount() == 0) return;
        int start = Math.max(0, Math.min(gestureStartPage, getChildCount() - 1));
        int last = getChildCount() - 1;
        float v = (flingSeen && Math.abs(flingVy) > Math.abs(vy)) ? flingVy : vy;
        float traveled = (getScrollY() - start * st) / (float) st; // in cards

        int target;
        if (Math.abs(v) > FLING_VEL && Math.abs(traveled) > FLING_MIN_TRAVEL) {
            int dir = v < 0 ? 1 : -1;   // finger up → next card
            target = start + dir;       // one card per gesture (±1 clamp)
        } else if (traveled > 0.5f) {
            target = start + 1;
        } else if (traveled < -0.5f) {
            target = start - 1;
        } else {
            target = start;
        }
        target = Math.max(0, Math.min(target, last));
        smoothTo(target * st);
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
        @Override public boolean onFling(MotionEvent e1, MotionEvent e2,
                                         float vx, float vy) {
            flingVy = vy; flingSeen = true;   // backup signal for snapAfterDrag
            return true;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (vt != null) { vt.recycle(); vt = null; }
        super.onDetachedFromWindow();
    }
}
