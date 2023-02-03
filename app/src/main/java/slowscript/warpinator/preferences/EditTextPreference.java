package slowscript.warpinator.preferences;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.EditText;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

@SuppressWarnings("unused")
public class EditTextPreference extends androidx.preference.EditTextPreference {

    @Nullable private OnBindEditTextListener mOnBindEditTextListener;

    public EditTextPreference(Context context) {
        super(context);
    }

    public EditTextPreference(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    public EditTextPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    public void setOnBindEditTextListener(@Nullable OnBindEditTextListener onBindEditTextListener) {
        mOnBindEditTextListener = onBindEditTextListener;
    }

    @Override
    protected void onClick() {
        FrameLayout frameLayout = new FrameLayout(getContext());
        EditText editText = new EditText(getContext());

        editText.setText(getText());

        frameLayout.setPaddingRelative(40, 30, 40, 0);
        frameLayout.addView(editText);
        if (mOnBindEditTextListener != null) {
            mOnBindEditTextListener.onBindEditText(editText);
        }

        new MaterialAlertDialogBuilder(getContext()).setPositiveButton(android.R.string.ok, (dialog, which) -> {
            String newVal = editText.getText().toString();
            if (callChangeListener(newVal))
                setText(newVal);
            dialog.dismiss();
        }).setNegativeButton(android.R.string.cancel, null).setTitle(getDialogTitle()).setView(frameLayout).show();
        editText.requestFocus();
    }

    @Override
    public CharSequence getSummary() {
        return super.getText();
    }

    @Override
    public void setText(String text) {
        super.setText(text);
        this.setSummary(text);
    }
}
