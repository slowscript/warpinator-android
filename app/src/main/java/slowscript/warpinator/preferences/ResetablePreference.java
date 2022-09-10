package slowscript.warpinator.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceViewHolder;

import slowscript.warpinator.R;

public class ResetablePreference extends androidx.preference.Preference {
    private Button resetBtn;
    private boolean resetEnabled = true;
    private View.OnClickListener onResetListener;

    public ResetablePreference(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.resetable_preference);
    }

    public void setOnResetListener(View.OnClickListener listener) {
        onResetListener = listener;
        if (resetBtn != null)
            resetBtn.setOnClickListener(listener);
    }

    public void setResetEnabled(boolean enabled) {
        resetEnabled = enabled;
        if (resetBtn != null)
            resetBtn.setEnabled(enabled);
    }

    @Override
    public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        resetBtn = (Button)holder.itemView.findViewById(R.id.resetButton);
        resetBtn.setOnClickListener(onResetListener);
        resetBtn.setEnabled(resetEnabled);
    }
}
