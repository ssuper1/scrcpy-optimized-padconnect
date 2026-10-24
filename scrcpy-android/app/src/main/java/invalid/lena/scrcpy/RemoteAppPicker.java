// Modified for padconnect (2026); see the root NOTICE for changes and upstream provenance.
package invalid.lena.scrcpy;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Insets;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.MotionEvent;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Searchable application chooser; favorite controls never launch an application. */
final class RemoteAppPicker {
    enum Mode { LIST, GRID, RECENT }

    private final Activity activity;
    private final List<RemoteApps.Entry> apps;
    private final Map<String, Bitmap> icons;
    private final Set<String> favorites;
    private final Mode mode;
    private final AppAdapter adapter = new AppAdapter();
    private List<RemoteApps.Entry> matches;
    private String query = "";

    private RemoteAppPicker(Activity activity, List<RemoteApps.Entry> apps, Map<String, Bitmap> icons,
                            Mode mode, List<String> history) {
        this.activity = activity;
        this.mode = mode;
        this.apps = mode == Mode.RECENT ? RecentApps.select(apps, history) : new ArrayList<>(apps);
        this.icons = icons;
        favorites = Settings.favoriteApps(activity);
        updateMatches();
    }

    static AlertDialog create(Activity activity, List<RemoteApps.Entry> apps,
                              Map<String, Bitmap> icons,
                              Consumer<RemoteApps.Entry> onOpen) {
        return create(activity, apps, icons, Mode.LIST, java.util.Collections.emptyList(), () -> {}, onOpen);
    }

    static AlertDialog create(Activity activity, List<RemoteApps.Entry> apps,
                              Map<String, Bitmap> icons, Mode mode, List<String> history, Runnable onClearHistory,
                              Consumer<RemoteApps.Entry> onOpen) {
        return new RemoteAppPicker(activity, apps, icons, mode, history).build(onOpen, onClearHistory);
    }

    private AlertDialog build(Consumer<RemoteApps.Entry> onOpen, Runnable onClearHistory) {
        View content = activity.getLayoutInflater().inflate(R.layout.remote_app_picker, null);
        AlertDialog dialog = mode == Mode.RECENT ? new RecentDialog(activity, content)
                : new AlertDialog.Builder(activity).create();
        dialog.setView(content, 0, 0, 0, 0);
        if (mode == Mode.RECENT) {
            dialog.setCanceledOnTouchOutside(true);
        }
        content.findViewById(R.id.close_app_picker).setOnClickListener(view -> dialog.dismiss());

        EditText search = content.findViewById(R.id.app_search);
        boolean grid = mode != Mode.LIST;
        content.findViewById(R.id.remote_app_list).setVisibility(grid ? View.GONE : View.VISIBLE);
        content.findViewById(R.id.remote_app_grid).setVisibility(grid ? View.VISIBLE : View.GONE);
        ((TextView) content.findViewById(R.id.app_picker_heading)).setText(mode == Mode.GRID
                ? R.string.app_grid : mode == Mode.RECENT ? R.string.recent_apps : R.string.app_picker_title);
        TextView empty = content.findViewById(R.id.no_matching_apps);
        if (mode == Mode.RECENT && apps.isEmpty()) empty.setText(R.string.no_recent_apps);
        View clear = content.findViewById(R.id.clear_recent_apps);
        clear.setVisibility(mode == Mode.RECENT ? View.VISIBLE : View.GONE);
        clear.setEnabled(!apps.isEmpty());
        clear.setOnClickListener(view -> {
            onClearHistory.run();
            apps.clear();
            empty.setText(R.string.no_recent_apps);
            clear.setEnabled(false);
            search.setText("");
            refresh();
            content.requestFocus();
        });
        AbsListView list = content.findViewById(grid ? R.id.remote_app_grid : R.id.remote_app_list);
        if (list instanceof ListView) ((ListView) list).setItemsCanFocus(true);
        list.setEmptyView(empty);
        list.setAdapter(adapter);
        if (mode == Mode.RECENT) {
            content.setOnClickListener(view -> dialog.cancel());
            empty.setOnClickListener(view -> dialog.cancel());
            dismissOnEmptyListTap(dialog, list);
        }
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                query = s.toString();
                refresh();
                list.setSelection(0);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        search.setOnEditorActionListener((view, action, event) -> {
            if (action != EditorInfo.IME_ACTION_DONE) return false;
            if (view.getWindowInsetsController() != null) {
                view.getWindowInsetsController().hide(WindowInsets.Type.ime());
            }
            content.requestFocus();
            return true;
        });
        adapter.onOpen = app -> {
            onOpen.accept(app);
            dialog.dismiss();
        };
        dialog.setOnShowListener(ignored -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawableResource(android.R.color.transparent);
            // Insets-based immersive mode still allows the keyboard to resize the dialog.
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            resize(dialog);
            content.requestFocus();
        });
        content.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> resize(dialog));
        return dialog;
    }

    /** OEM dialog decor can extend beyond the visible custom panel. */
    private static final class RecentDialog extends AlertDialog {
        private final View panel;
        private final int touchSlop;
        private final int[] panelLocation = new int[2];
        private boolean outsideGesture;
        private boolean outsideTap;
        private float downX, downY;

        RecentDialog(Activity activity, View panel) {
            super(activity);
            this.panel = panel;
            touchSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                panel.getLocationOnScreen(panelLocation);
                downX = event.getRawX();
                downY = event.getRawY();
                outsideGesture = downX < panelLocation[0] || downY < panelLocation[1]
                        || downX >= panelLocation[0] + panel.getWidth()
                        || downY >= panelLocation[1] + panel.getHeight();
                outsideTap = outsideGesture;
            }
            if (!outsideGesture) return super.dispatchTouchEvent(event);
            if (event.getPointerCount() > 1
                    || Math.abs(event.getRawX() - downX) > touchSlop
                    || Math.abs(event.getRawY() - downY) > touchSlop) {
                outsideTap = false;
            }
            // Consume the whole gesture so dismissing cannot click the tablet.
            if (action == MotionEvent.ACTION_UP) {
                outsideGesture = false;
                if (outsideTap) cancel();
            } else if (action == MotionEvent.ACTION_CANCEL) {
                outsideGesture = false;
            }
            return true;
        }
    }

    private void dismissOnEmptyListTap(AlertDialog dialog, AbsListView list) {
        int slop = ViewConfiguration.get(activity).getScaledTouchSlop();
        list.setOnTouchListener(new View.OnTouchListener() {
            private boolean captured;
            private boolean tap;
            private float downX, downY;

            @Override public boolean onTouch(View view, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    // The list fills a fixed-height panel. Space below its
                    // rows is inside the window, so outside-touch cancellation
                    // alone cannot close it. Leave real rows to the ListView.
                    captured = list.pointToPosition((int) event.getX(), (int) event.getY())
                            == AbsListView.INVALID_POSITION;
                    tap = captured;
                    downX = event.getX();
                    downY = event.getY();
                }
                if (!captured) return false;
                if (event.getPointerCount() > 1
                        || Math.abs(event.getX() - downX) > slop
                        || Math.abs(event.getY() - downY) > slop) {
                    tap = false;
                }
                if (action == MotionEvent.ACTION_UP) {
                    captured = false;
                    if (tap) {
                        view.performClick();
                        dialog.cancel();
                    }
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    captured = false;
                }
                return true;
            }
        });
    }

    private void resize(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null || !dialog.isShowing()) return;
        WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets cutout = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.max(1, Math.min(Math.round((mode == Mode.GRID ? 720 : 480) * density),
                bounds.width() - cutout.left - cutout.right - Math.round(32 * density)));
        int maxHeightDp = mode == Mode.RECENT ? 340 : mode == Mode.GRID ? 900 : 660;
        float heightFraction = mode == Mode.RECENT ? 0.5f : mode == Mode.GRID ? 0.90f : 0.82f;
        int height = Math.max(1, Math.min(Math.round(maxHeightDp * density),
                Math.round((bounds.height() - cutout.top - cutout.bottom) * heightFraction)));
        if (window.getAttributes().width != width || window.getAttributes().height != height) {
            window.setLayout(width, height);
        }
    }

    private void refresh() {
        updateMatches();
        adapter.notifyDataSetChanged();
    }

    private void updateMatches() {
        matches = mode == Mode.RECENT ? RemoteApps.filter(apps, query)
                : RemoteApps.filterAndPin(apps, query, favorites);
    }

    private void toggleFavorite(RemoteApps.Entry app) {
        boolean pinned = !favorites.contains(app.packageName);
        Settings.setAppFavorite(activity, app.packageName, pinned);
        if (pinned) favorites.add(app.packageName);
        else favorites.remove(app.packageName);
        refresh();
        if (mode != Mode.LIST) {
            Toast.makeText(activity, pinned ? R.string.app_favorited : R.string.app_not_favorited,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private final class AppAdapter extends BaseAdapter {
        private Consumer<RemoteApps.Entry> onOpen;

        @Override public int getCount() { return matches.size(); }
        @Override public RemoteApps.Entry getItem(int position) { return matches.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
            if (mode != Mode.LIST) return getTile(position, convertView, parent);
            View row = convertView;
            if (row == null) {
                row = activity.getLayoutInflater().inflate(R.layout.remote_app_row, parent, false);
                row.setTag(new AppRow(row));
            }
            AppRow holder = (AppRow) row.getTag();
            RemoteApps.Entry app = getItem(position);
            boolean favorite = favorites.contains(app.packageName);
            boolean startOfGroup = position == 0
                    || favorite != favorites.contains(getItem(position - 1).packageName);
            holder.section.setVisibility(startOfGroup ? View.VISIBLE : View.GONE);
            if (mode == Mode.RECENT) {
                holder.section.setVisibility(View.GONE);
            } else {
                holder.section.setText(favorite ? R.string.favorite_apps : R.string.all_apps);
            }
            holder.label.setText(app.label);
            holder.packageName.setText(app.packageName);
            Bitmap icon = icons.get(app.packageName);
            // Always rebind recycled rows, including failures/missing icons.
            if (icon != null) holder.icon.setImageBitmap(icon);
            else holder.icon.setImageResource(R.drawable.ic_app_placeholder);
            holder.favorite.setImageResource(favorite ? R.drawable.ic_star_filled : R.drawable.ic_star_outline);
            String favoriteAction = activity.getString(
                    favorite ? R.string.unfavorite_app : R.string.favorite_app, app.label);
            holder.favorite.setContentDescription(favoriteAction);
            holder.favorite.setTooltipText(favoriteAction);
            holder.favorite.setStateDescription(activity.getString(
                    favorite ? R.string.app_favorited : R.string.app_not_favorited));
            holder.favorite.setOnClickListener(view -> {
                toggleFavorite(app);
            });
            holder.open.setOnClickListener(view -> onOpen.accept(app));
            return row;
        }

        private View getTile(int position, View recycled, ViewGroup parent) {
            View tile = recycled;
            if (tile == null) tile = activity.getLayoutInflater().inflate(R.layout.remote_app_tile, parent, false);
            RemoteApps.Entry app = getItem(position);
            ((TextView) tile.findViewById(R.id.remote_app_label)).setText(app.label);
            ImageView icon = tile.findViewById(R.id.remote_app_icon);
            Bitmap image = icons.get(app.packageName);
            if (image != null) icon.setImageBitmap(image);
            else icon.setImageResource(R.drawable.ic_app_placeholder);
            boolean pinned = favorites.contains(app.packageName);
            tile.findViewById(R.id.app_pinned_badge).setVisibility(pinned ? View.VISIBLE : View.GONE);
            tile.setContentDescription(app.label);
            tile.setTooltipText(activity.getString(pinned ? R.string.unfavorite_app : R.string.favorite_app, app.label));
            tile.setStateDescription(activity.getString(pinned ? R.string.app_favorited : R.string.app_not_favorited));
            tile.setOnClickListener(view -> onOpen.accept(app));
            tile.setOnLongClickListener(view -> { toggleFavorite(app); return true; });
            return tile;
        }
    }

    private static final class AppRow {
        final TextView section, label, packageName;
        final ImageView icon;
        final View open;
        final ImageButton favorite;

        AppRow(View row) {
            section = row.findViewById(R.id.app_section);
            label = row.findViewById(R.id.remote_app_label);
            packageName = row.findViewById(R.id.remote_app_package);
            icon = row.findViewById(R.id.remote_app_icon);
            open = row.findViewById(R.id.open_app);
            favorite = row.findViewById(R.id.favorite_app);
        }
    }
}
