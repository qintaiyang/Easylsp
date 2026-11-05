package io.github.easylsp.runtime;

import android.app.Activity;
import android.graphics.*;
import android.view.*;
import android.widget.TextView;
import org.json.*;

/** Target Activity overlay: view bounds, touch selection, properties and two-view distance. */
final class InspectorOverlay extends View {
    private final ViewGroup root;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private View selected, previous;
    private final Rect bounds = new Rect(), other = new Rect();
    InspectorOverlay(Activity activity, ViewGroup root) { super(activity); this.root = root; setWillNotDraw(false); setLayerType(View.LAYER_TYPE_SOFTWARE, null); }
    private View hit(View view, float x, float y) {
        if (view == this || view.getVisibility() != VISIBLE) return null;
        int[] location = new int[2]; view.getLocationOnScreen(location);
        if (x < location[0] || y < location[1] || x >= location[0] + view.getWidth() || y >= location[1] + view.getHeight()) return null;
        if (view instanceof ViewGroup group) for (int i = group.getChildCount() - 1; i >= 0; i--) { View child = hit(group.getChildAt(i), x, y); if (child != null) return child; }
        return view;
    }
    void select(int x, int y) { previous = selected; selected = hit(root, x, y); invalidate(); }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) { select((int) event.getRawX(), (int) event.getRawY()); return true; }
        if (event.getAction() == MotionEvent.ACTION_MOVE) { selected = hit(root, event.getRawX(), event.getRawY()); invalidate(); return true; }
        if (event.getAction() == MotionEvent.ACTION_UP) { performClick(); return true; }
        return true;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    JSONObject selection() throws JSONException {
        JSONObject data = new JSONObject(); if (selected == null) return data;
        int[] p = new int[2]; selected.getLocationOnScreen(p);
        float density = getResources().getDisplayMetrics().density;
        JSONArray inheritance = new JSONArray(); for (Class<?> type = selected.getClass(); type != null; type = type.getSuperclass()) inheritance.put(type.getName());
        JSONArray parents = new JSONArray(); for (ViewParent parent = selected.getParent(); parent != null; parent = parent.getParent()) parents.put(parent.getClass().getName());
        data.put("class", selected.getClass().getName()).put("inheritance", inheritance).put("parents", parents).put("xPx", p[0]).put("yPx", p[1]).put("widthPx", selected.getWidth()).put("heightPx", selected.getHeight()).put("widthDp", selected.getWidth() / density).put("heightDp", selected.getHeight() / density).put("density", density);
        if (selected instanceof TextView text) data.put("text", text.getText().toString());
        if (selected instanceof ViewGroup group) data.put("childCount", group.getChildCount());
        if (previous != null && previous != selected) {
            localBounds(selected, bounds); localBounds(previous, other);
            data.put("distance", new JSONObject().put("centerDxDp", (bounds.centerX() - other.centerX()) / density).put("centerDyDp", (bounds.centerY() - other.centerY()) / density)
                .put("horizontalGapPx", Math.max(0, Math.max(bounds.left - other.right, other.left - bounds.right))).put("verticalGapPx", Math.max(0, Math.max(bounds.top - other.bottom, other.top - bounds.bottom))));
        }
        if (selected.getLayoutParams() instanceof ViewGroup.MarginLayoutParams margins) data.put("marginsPx", new JSONArray(new int[] {margins.leftMargin, margins.topMargin, margins.rightMargin, margins.bottomMargin}));
        return data;
    }
    private void localBounds(View view, Rect out) { int[] p = new int[2], origin = new int[2]; view.getLocationOnScreen(p); getLocationOnScreen(origin); out.set(p[0] - origin[0], p[1] - origin[1], p[0] - origin[0] + view.getWidth(), p[1] - origin[1] + view.getHeight()); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (selected == null) return;
        localBounds(selected, bounds); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3); paint.setColor(0xff00d8bf); canvas.drawRect(bounds, paint);
        float density = getResources().getDisplayMetrics().density;
        String label = selected.getClass().getSimpleName() + " · " + selected.getWidth() + "×" + selected.getHeight() + " px / " + Math.round(selected.getWidth() / density) + "×" + Math.round(selected.getHeight() / density) + " dp";
        if (previous != null && previous != selected) { localBounds(previous, other); paint.setColor(0xffffb300); canvas.drawRect(other, paint); canvas.drawLine(bounds.centerX(), bounds.centerY(), other.centerX(), other.centerY(), paint); label += " · Δ " + Math.round((bounds.centerX() - other.centerX()) / density) + "," + Math.round((bounds.centerY() - other.centerY()) / density) + " dp"; }
        paint.setStyle(Paint.Style.FILL); paint.setColor(0xee122e2b); canvas.drawRect(0, 0, getWidth(), 72 * density, paint); paint.setColor(Color.WHITE); paint.setTextSize(12 * density);
        canvas.drawText("Easylsp · 滑动选取 View · 连续选择测量间距", 12 * density, 24 * density, paint); canvas.drawText(label, 12 * density, 48 * density, paint);
    }
}
