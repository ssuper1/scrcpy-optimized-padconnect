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
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Searchable application chooser; favorite controls never launch an application. */
final class RemoteAppPicker {
    private final Activity activity;
    private final List<RemoteApps.Entry> apps;
    private final Map<String, Bitmap> icons;
    private final Set<String> favorites;
    private final AppAdapter adapter = new AppAdapter();
    private List<RemoteApps.Entry> matches;
    private String query = "";

    private RemoteAppPicker(Activity activity, List<RemoteApps.Entry> apps, Map<String, Bitmap> icons) {
        this.activity = activity;
        this.apps = new ArrayList<>(apps);
        this.icons = icons;
        favorites = Settings.favoriteApps(activity);
        matches = RemoteApps.filterAndPin(this.apps, query, favorites);
    }

    static AlertDialog create(Activity activity, List<RemoteApps.Entry> apps,
                              Map<String, Bitmap> icons,
                              Consumer<RemoteApps.Entry> onOpen) {
        return new RemoteAppPicker(activity, apps, icons).build(onOpen);
    }

    private AlertDialog build(Consumer<RemoteApps.Entry> onOpen) {
        View content = activity.getLayoutInflater().inflate(R.layout.remote_app_picker, null);
        AlertDialog dialog = new AlertDialog.Builder(activity).create();
        dialog.setView(content, 0, 0, 0, 0);
        content.findViewById(R.id.close_app_picker).setOnClickListener(view -> dialog.dismiss());

        EditText search = content.findViewById(R.id.app_search);
        ListView list = content.findViewById(R.id.remote_app_list);
        list.setItemsCanFocus(true);
        list.setEmptyView(content.findViewById(R.id.no_matching_apps));
        list.setAdapter(adapter);
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

    private void resize(AlertDialog dialog) {
        Window window = dialog.getWindow();
        if (window == null || !dialog.isShowing()) return;
        WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
        Rect bounds = metrics.getBounds();
        Insets cutout = metrics.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.displayCutout());
        float density = activity.getResources().getDisplayMetrics().density;
        int width = Math.max(1, Math.min(Math.round(480 * density),
                bounds.width() - cutout.left - cutout.right - Math.round(32 * density)));
        int height = Math.max(1, Math.min(Math.round(660 * density),
                Math.round((bounds.height() - cutout.top - cutout.bottom) * 0.82f)));
        if (window.getAttributes().width != width || window.getAttributes().height != height) {
            window.setLayout(width, height);
        }
    }

    private void refresh() {
        matches = RemoteApps.filterAndPin(apps, query, favorites);
        adapter.notifyDataSetChanged();
    }

    private final class AppAdapter extends BaseAdapter {
        private Consumer<RemoteApps.Entry> onOpen;

        @Override public int getCount() { return matches.size(); }
        @Override public RemoteApps.Entry getItem(int position) { return matches.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override public View getView(int position, View convertView, ViewGroup parent) {
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
            holder.section.setText(favorite ? R.string.favorite_apps : R.string.all_apps);
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
                boolean pinned = !favorites.contains(app.packageName);
                Settings.setAppFavorite(activity, app.packageName, pinned);
                if (pinned) favorites.add(app.packageName);
                else favorites.remove(app.packageName);
                refresh();
            });
            holder.open.setOnClickListener(view -> onOpen.accept(app));
            return row;
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
