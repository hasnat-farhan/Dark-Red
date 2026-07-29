package com.antor.sosblue.ui;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.antor.sosblue.R;
import com.antor.sosblue.bridge.TransportMode;

import java.util.Arrays;
import java.util.List;

/**
 * A three-segment pill-style toggle bar for selecting the message transport mode.
 * <p>
 * Displays icons + short labels for SOSBlue, F2P Serverless, and SMS.
 * The active segment is highlighted with a filled background and accent colour.
 * </p>
 */
public class TransportSelectorView extends LinearLayout {

    public interface OnModeSelectedListener {
        void onModeSelected(@NonNull TransportMode mode);
    }

    private static final int SEGMENT_PADDING_V = 6;
    private static final int SEGMENT_PADDING_H = 12;

    private final List<TransportMode> modes = Arrays.asList(TransportMode.values());
    private final LinearLayout container;
    private OnModeSelectedListener listener;
    private TransportMode selectedMode;

    public TransportSelectorView(@NonNull Context context) {
        this(context, null);
    }

    public TransportSelectorView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TransportSelectorView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);

        int defaultModeOrdinal = 0;
        if (attrs != null) {
            TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.TransportSelectorView);
            defaultModeOrdinal = a.getInt(R.styleable.TransportSelectorView_defaultMode, 0);
            a.recycle();
        }
        selectedMode = modes.get(Math.min(defaultModeOrdinal, modes.size() - 1));

        container = new LinearLayout(context);
        container.setOrientation(HORIZONTAL);
        container.setGravity(Gravity.CENTER);
        LayoutParams containerLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        containerLp.gravity = Gravity.CENTER;
        container.setLayoutParams(containerLp);

        // Rounded pill background for the whole bar
        GradientDrawable barBg = new GradientDrawable();
        barBg.setShape(GradientDrawable.RECTANGLE);
        barBg.setCornerRadius(dp2px(20));
        barBg.setColor(0xFFF0F0F0);       // light grey background
        container.setBackground(barBg);

        for (int i = 0; i < modes.size(); i++) {
            final TransportMode mode = modes.get(i);
            View segment = createSegment(context, mode, i == modes.size() - 1);
            final int index = i;
            segment.setOnClickListener(v -> setSelectedMode(mode, true));
            container.addView(segment);
        }

        addView(container);
        applySelection();
    }

    // ---------------------------------------------------------------
    //  Public API
    // ---------------------------------------------------------------

    public TransportMode getSelectedMode()       { return selectedMode; }

    public void setSelectedMode(TransportMode mode) {
        setSelectedMode(mode, false);
    }

    public void setOnModeSelectedListener(OnModeSelectedListener listener) {
        this.listener = listener;
    }

    // ---------------------------------------------------------------
    //  Internal
    // ---------------------------------------------------------------

    private void setSelectedMode(TransportMode mode, boolean notify) {
        if (mode == selectedMode) return;
        this.selectedMode = mode;
        applySelection();
        if (notify && listener != null) {
            listener.onModeSelected(mode);
        }
    }

    private View createSegment(Context ctx, TransportMode mode, boolean isLast) {
        LinearLayout seg = new LinearLayout(ctx);
        seg.setOrientation(HORIZONTAL);
        seg.setGravity(Gravity.CENTER);
        seg.setPadding(dp2px(SEGMENT_PADDING_H), dp2px(SEGMENT_PADDING_V),
                dp2px(isLast ? SEGMENT_PADDING_H + 4 : SEGMENT_PADDING_H),
                dp2px(SEGMENT_PADDING_V));

        // Icon
        ImageView icon = new ImageView(ctx);
        Drawable d = ContextCompat.getDrawable(ctx, mode.getIconResId());
        if (d != null) {
            d = d.mutate();
            icon.setImageDrawable(d);
        }
        LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(dp2px(16), dp2px(16));
        iconLp.setMarginEnd(dp2px(4));
        icon.setLayoutParams(iconLp);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);

        // Label
        TextView label = new TextView(ctx);
        label.setText(mode.getLabel());
        label.setTextSize(12);
        label.setGravity(Gravity.CENTER);

        seg.addView(icon);
        seg.addView(label);

        return seg;
    }

    private void applySelection() {
        int accent = 0xFF0D80E0;          // SOSBlue accent blue
        int activeText = 0xFFFFFFFF;
        int inactiveText = 0xFF666666;
        int activeIconTint = 0xFFFFFFFF;
        int inactiveIconTint = 0xFF888888;

        for (int i = 0; i < container.getChildCount(); i++) {
            View seg = container.getChildAt(i);
            TransportMode mode = modes.get(i);
            boolean active = mode == selectedMode;

            // Background
            GradientDrawable segBg = new GradientDrawable();
            segBg.setShape(GradientDrawable.RECTANGLE);
            segBg.setCornerRadius(dp2px(16));
            segBg.setColor(active ? accent : 0x00000000);   // fully transparent when inactive
            seg.setBackground(segBg);

            // Icon + label tint
            applyTintRecursive(seg, active ? activeIconTint : inactiveIconTint, active ? activeText : inactiveText);
        }
    }

    private void applyTintRecursive(View view, int iconTint, int textColor) {
        if (view instanceof ImageView) {
            ((ImageView) view).setColorFilter(iconTint);
        }
        if (view instanceof TextView) {
            ((TextView) view).setTextColor(textColor);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                applyTintRecursive(group.getChildAt(i), iconTint, textColor);
            }
        }
    }

    private int dp2px(float dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
