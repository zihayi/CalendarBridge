package local.calendar.readonly;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.LinearLayout;

/** Keep the same readable column on a phone, in landscape and on a tablet. */
public final class BridgeColumn extends LinearLayout {
    public BridgeColumn(Context context, AttributeSet attrs) { super(context, attrs); }

    @Override protected void onMeasure(int width, int height) {
        int limit = Math.round(600 * getResources().getDisplayMetrics().density);
        if (MeasureSpec.getSize(width) > limit) width = MeasureSpec.makeMeasureSpec(limit, MeasureSpec.EXACTLY);
        super.onMeasure(width, height);
    }
}
