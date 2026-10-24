// Modified for padconnect (2026); see the root NOTICE for changes and upstream provenance.
package invalid.lena.scrcpy;

import android.app.Activity;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowMetrics;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.Space;
import android.widget.TextView;

import java.util.function.Consumer;

/** Compact, modal controls: dismissing outside the panel never clicks the tablet. */
final class MirrorMenu extends PopupWindow {
    enum Action {
        APPS, RECENT, BACK, PHONE_MODE, TABLET_MODE, ROTATE,
        TEXT_SIZE, NAVIGATION, KEEP_AWAKE, WAKE, DISCONNECT
    }

    private final Activity activity;
    private final Consumer<Action> onAction;
    private final ScrollView panel;
    private final LinearLayout sections;
    private final int columns;
    private LinearLayout row;
    private int itemCount;

    MirrorMenu(Activity activity, boolean connected, boolean waking, Consumer<Action> onAction) {
        super(activity);
        this.activity = activity;
        this.onAction = onAction;
        Rect safe = safeBounds();
        int width = Math.max(1, Math.min(dp(252), safe.width() - dp(24)));
        columns = width < dp(228) ? 2 : 3;

        panel = new ScrollView(activity);
        panel.setBackgroundResource(R.drawable.bg_mirror_menu);
        panel.setPadding(dp(8), dp(6), dp(8), dp(6));
        panel.setClipToOutline(true);
        sections = new LinearLayout(activity);
        sections.setOrientation(LinearLayout.VERTICAL);
        panel.addView(sections, new ScrollView.LayoutParams(-1, -2));
        setContentView(panel);
        setWidth(width);
        setBackgroundDrawable(new ColorDrawable(android.graphics.Color.TRANSPARENT));
        setElevation(dp(12));
        setFocusable(true);
        setOutsideTouchable(true);
        setTouchModal(true);
        setInputMethodMode(INPUT_METHOD_NOT_NEEDED);

        section(R.string.menu_group_common);
        item(Action.RECENT, R.string.menu_recent_short, R.string.recent_apps,
                R.drawable.ic_nav_recent, connected, false, false);
        item(Action.APPS, R.string.menu_apps_short, R.string.menu_apps,
                R.drawable.ic_menu_apps, connected, false, false);
        item(Action.BACK, R.string.remote_back, R.string.remote_back,
                R.drawable.ic_menu_back, connected, false, false);

        section(R.string.menu_group_display);
        item(Action.PHONE_MODE, R.string.menu_phone_short, R.string.menu_phone_mode,
                R.drawable.ic_menu_phone, true, true, Settings.phoneMode(activity));
        item(Action.TABLET_MODE, R.string.menu_tablet_short, R.string.menu_original_mode,
                R.drawable.ic_menu_tablet, true, true, !Settings.phoneMode(activity));
        item(Action.ROTATE, R.string.menu_rotate_short, R.string.rotate_screen,
                R.drawable.ic_menu_rotate, true, false, false);
        item(Action.TEXT_SIZE, R.string.menu_text_short, R.string.phone_text_size,
                R.drawable.ic_menu_text_size, true, false, false);
        item(Action.NAVIGATION, R.string.menu_navigation_short, R.string.show_floating_navigation,
                R.drawable.ic_nav_home, true, true, Settings.floatingNavigation(activity));
        item(Action.KEEP_AWAKE, R.string.menu_awake_short, R.string.keep_tablet_awake,
                R.drawable.ic_menu_keep_awake, true, true, Settings.keepTabletAwake(activity));

        section(R.string.menu_group_device);
        item(Action.WAKE, R.string.menu_wake_short, R.string.wake_tablet,
                R.drawable.ic_menu_wake, connected && !waking, false, false);
        item(Action.DISCONNECT, R.string.menu_disconnect_short, R.string.leave_mirror,
                R.drawable.ic_menu_disconnect, true, false, false);
        finishRow();
    }

    void show(View anchor, View root) {
        Rect safe = safeBounds();
        int margin = dp(12);
        int availableHeight = Math.max(1, safe.height() - 2 * margin);
        panel.measure(View.MeasureSpec.makeMeasureSpec(getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(availableHeight, View.MeasureSpec.AT_MOST));
        setHeight(Math.min(panel.getMeasuredHeight(), availableHeight));
        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        boolean leftEdge = location[0] + anchor.getWidth() / 2 < safe.centerX();
        int x = leftEdge ? safe.left + margin : safe.right - margin - getWidth();
        int y = Math.max(safe.top + margin, Math.min(
                location[1] + anchor.getHeight() / 2 - getHeight() / 2,
                safe.bottom - margin - getHeight()));
        showAtLocation(root, Gravity.TOP | Gravity.LEFT, x, y);
        WindowInsetsController controller = panel.getWindowInsetsController();
        if (controller != null) {
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsets.Type.systemBars());
        }
    }

    private Rect safeBounds() {
        WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets cutout = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
        return new Rect(cutout.left, cutout.top, bounds.width() - cutout.right, bounds.height() - cutout.bottom);
    }

    private void section(int title) {
        finishRow();
        TextView heading = new TextView(activity);
        heading.setText(title);
        heading.setTextSize(11);
        heading.setTextColor(activity.getColor(R.color.on_surface_muted));
        heading.setAccessibilityHeading(true);
        heading.setPadding(dp(2), sections.getChildCount() == 0 ? 0 : dp(6), 0, dp(3));
        sections.addView(heading, new LinearLayout.LayoutParams(-1, -2));
        row = null;
        itemCount = 0;
    }

    private void item(Action action, int label, int description, int icon,
                      boolean enabled, boolean checkable, boolean checked) {
        if (itemCount % columns == 0) {
            row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setBaselineAligned(false);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
            if (itemCount > 0) params.topMargin = dp(3);
            sections.addView(row, params);
        }
        View tile = activity.getLayoutInflater().inflate(R.layout.mirror_menu_tile, row, false);
        TextView caption = tile.findViewById(R.id.menu_tile_label);
        caption.setText(label);
        Drawable image = activity.getDrawable(icon).mutate();
        image.setBounds(0, 0, dp(20), dp(20));
        image.setTintList(activity.getColorStateList(R.color.menu_tile_foreground));
        caption.setCompoundDrawables(null, image, null, null);
        tile.findViewById(R.id.menu_tile_checked).setVisibility(checked ? View.VISIBLE : View.GONE);
        tile.setSelected(checked);
        tile.setEnabled(enabled);
        tile.setAlpha(enabled ? 1f : 0.4f);
        tile.setContentDescription(activity.getString(description));
        tile.setTooltipText(activity.getString(description));
        if (checkable) tile.setStateDescription(activity.getString(
                checked ? R.string.menu_option_selected : R.string.menu_option_unselected));
        tile.setOnClickListener(view -> {
            dismiss();
            onAction.accept(action);
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        if (itemCount % columns != 0) params.leftMargin = dp(4);
        row.addView(tile, params);
        itemCount++;
    }

    private void finishRow() {
        if (row == null) return;
        while (itemCount % columns != 0) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, 0, 1f);
            params.leftMargin = dp(4);
            row.addView(new Space(activity), params);
            itemCount++;
        }
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
