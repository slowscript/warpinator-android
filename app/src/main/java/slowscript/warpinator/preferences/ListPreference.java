package slowscript.warpinator.preferences;

import android.content.Context;
import android.util.AttributeSet;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class ListPreference extends androidx.preference.ListPreference {
    public ListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ListPreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        int selected = super.findIndexOfValue((String) super.getValue());

        new MaterialAlertDialogBuilder(getContext())
                .setNegativeButton(android.R.string.cancel, null)
                .setTitle(getDialogTitle()).setSingleChoiceItems(
                getEntries(),
                selected,
                (dialog, which) -> {
                    super.setValueIndex(which);
                    getOnPreferenceChangeListener().onPreferenceChange(this, super.getValue());
                    dialog.dismiss();
                }
        ).show();
    }
}
