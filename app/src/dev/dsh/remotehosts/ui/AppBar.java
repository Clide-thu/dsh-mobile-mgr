package dev.dsh.remotehosts.ui;

import android.app.Activity;
import android.view.View;
import android.widget.PopupMenu;
import android.widget.TextView;

import dev.dsh.remotehosts.R;

/**
 * Small facade over the reusable in-layout top bar ({@code layout/view_app_bar.xml}).
 *
 * v0.2.0: the app no longer uses the platform ActionBar. Every activity has the
 * same vertical structure (top bar then a weight=1 content area), which cannot
 * overlap its content even when Android 15 forces edge-to-edge.
 */
final class AppBar {

    private AppBar() {
    }

    /** Wire the back arrow (it simply finishes the activity) and hide unused actions. */
    static void bind(final Activity activity) {
        View back = activity.findViewById(R.id.appbar_back);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View ignored) {
                activity.finish();
            }
        });
        back.setVisibility(View.GONE);
        View more = activity.findViewById(R.id.appbar_more);
        more.setVisibility(View.GONE);
        View collapse = activity.findViewById(R.id.appbar_collapse);
        if (collapse != null) collapse.setVisibility(View.GONE);
    }

    static void setTitle(Activity activity, CharSequence title) {
        TextView view = (TextView) activity.findViewById(R.id.appbar_title);
        if (view != null) view.setText(title == null ? "" : title);
    }

    static void setTitle(Activity activity, int titleRes) {
        setTitle(activity, activity.getString(titleRes));
    }

    static void setSubtitle(Activity activity, CharSequence subtitle) {
        TextView view = (TextView) activity.findViewById(R.id.appbar_subtitle);
        if (view == null) return;
        if (subtitle == null || subtitle.length() == 0) {
            view.setText("");
            view.setVisibility(View.GONE);
        } else {
            view.setText(subtitle);
            view.setVisibility(View.VISIBLE);
        }
    }

    static void setBackVisible(Activity activity, boolean visible) {
        View view = activity.findViewById(R.id.appbar_back);
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    static void setMoreVisible(Activity activity, boolean visible) {
        View view = activity.findViewById(R.id.appbar_more);
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /**
     * v0.2.5: show/hide the optional "collapse top bar" action. Only the built-in
     * WebView screen enables it, and only while the global setting is on.
     */
    static void setCollapseVisible(Activity activity, boolean visible) {
        View view = activity.findViewById(R.id.appbar_collapse);
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    /** Show the "more" button and anchor a {@link PopupMenu} built from {@code menuRes}. */
    static void showMoreMenu(final Activity activity, int menuRes,
                             final PopupMenu.OnMenuItemClickListener listener) {
        final View more = activity.findViewById(R.id.appbar_more);
        if (more == null) return;
        more.setVisibility(View.VISIBLE);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View anchor) {
                PopupMenu popup = new PopupMenu(activity, anchor);
                popup.getMenuInflater().inflate(menuRes, popup.getMenu());
                popup.setOnMenuItemClickListener(listener);
                popup.show();
            }
        });
    }
}
