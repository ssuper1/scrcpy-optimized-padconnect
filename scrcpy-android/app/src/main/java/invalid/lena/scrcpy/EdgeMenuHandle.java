package invalid.lena.scrcpy;

import android.content.SharedPreferences;
import android.graphics.Insets;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import java.util.Collections;

/** A draggable edge handle. Only its small hit area intercepts remote touches. */
final class EdgeMenuHandle implements View.OnTouchListener {
    private static final String LEFT = "menu_handle_left";
    private static final String HEIGHT = "menu_handle_height";
    private final View handle;
    private final FrameLayout parent;
    private final SharedPreferences preferences;
    private final int slop;
    private boolean left, dragging, touching;
    private int heightPermille, startX, startY;
    private float downX, downY;
    private Insets cutout = Insets.NONE;
    private final Runnable fade;

    EdgeMenuHandle(FrameLayout parent, View handle) {
        this.parent = parent;
        this.handle = handle;
        preferences = Settings.prefs(handle.getContext());
        left = preferences.getBoolean(LEFT, false);
        heightPermille = preferences.getInt(HEIGHT, 500);
        slop = ViewConfiguration.get(handle.getContext()).getScaledTouchSlop();
        fade = () -> handle.animate().alpha(0.3f).setDuration(250).start();
        handle.setOnTouchListener(this);
        handle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                v.setSystemGestureExclusionRects(Collections.singletonList(
                        new Rect(0, 0, v.getWidth(), v.getHeight()))));
        parent.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (!touching) snap();
        });
        handle.setOnApplyWindowInsetsListener((v, insets) -> {
            cutout = insets.getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
            if (!touching) snap();
            return insets;
        });
        handle.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {
                view.requestApplyInsets();
                fadeLater();
            }
            @Override public void onViewDetachedFromWindow(View view) { dispose(); }
        });
        handle.requestApplyInsets();
        handle.post(this::snap);
        fadeLater();
    }

    private EdgeMenuPosition bounds() {
        return new EdgeMenuPosition(parent.getWidth(), parent.getHeight(),
                handle.getLayoutParams().width, handle.getLayoutParams().height,
                cutout.left, cutout.top, cutout.right, cutout.bottom);
    }

    private void place(int x, int y) {
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) handle.getLayoutParams();
        if (params.leftMargin == x && params.topMargin == y
                && params.gravity == (Gravity.TOP | Gravity.LEFT)) return;
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.setMargins(x, y, 0, 0);
        handle.setLayoutParams(params);
    }

    private void snap() {
        if (parent.getWidth() == 0 || parent.getHeight() == 0) return;
        EdgeMenuPosition bounds = bounds();
        place(bounds.x(left), bounds.y(heightPermille));
    }

    void wake() {
        handle.removeCallbacks(fade);
        handle.animate().cancel();
        handle.setAlpha(1f);
    }

    void fadeLater() {
        handle.removeCallbacks(fade);
        handle.postDelayed(fade, 2500);
    }

    void dispose() {
        handle.removeCallbacks(fade);
        handle.animate().cancel();
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                wake();
                touching = true;
                dragging = false;
                downX = event.getRawX();
                downY = event.getRawY();
                startX = view.getLeft();
                startY = view.getTop();
                parent.requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                if (dx * dx + dy * dy > (float) slop * slop) dragging = true;
                if (dragging) {
                    EdgeMenuPosition bounds = bounds();
                    place(EdgeMenuPosition.clamp(startX + Math.round(dx), bounds.minX, bounds.maxX),
                            EdgeMenuPosition.clamp(startY + Math.round(dy), bounds.minY, bounds.maxY));
                }
                return true;
            case MotionEvent.ACTION_UP:
                touching = false;
                if (dragging) {
                    EdgeMenuPosition bounds = bounds();
                    FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) view.getLayoutParams();
                    left = bounds.nearestLeft(params.leftMargin);
                    heightPermille = bounds.permille(params.topMargin);
                    preferences.edit().putBoolean(LEFT, left).putInt(HEIGHT, heightPermille).apply();
                    snap();
                } else {
                    view.performClick();
                }
                parent.requestDisallowInterceptTouchEvent(false);
                fadeLater();
                return true;
            case MotionEvent.ACTION_CANCEL:
                touching = false;
                snap();
                parent.requestDisallowInterceptTouchEvent(false);
                fadeLater();
                return true;
            default:
                return true;
        }
    }
}
