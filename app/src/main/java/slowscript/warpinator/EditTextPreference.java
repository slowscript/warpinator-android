package slowscript.warpinator;

import android.content.Context;
import android.util.AttributeSet;

public class EditTextPreference extends androidx.preference.EditTextPreference {

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
    public CharSequence getSummary() {
        return super.getText();
    }

    @Override
    public void setText(String text) {
        super.setText(text);
        this.setSummary(text);
    }
}
