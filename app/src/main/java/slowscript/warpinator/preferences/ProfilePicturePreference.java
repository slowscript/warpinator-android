package slowscript.warpinator.preferences;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import slowscript.warpinator.R;
import slowscript.warpinator.Server;
import slowscript.warpinator.SettingsActivity;

public class ProfilePicturePreference extends Preference {

    ActivityResultLauncher<Intent> customImageActivityResultLauncher;
    private final Context mContext;

    public ProfilePicturePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mContext = context;
        registerForResult();
    }

    public ProfilePicturePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mContext = context;
        registerForResult();
    }

    public ProfilePicturePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
        registerForResult();
    }

    public ProfilePicturePreference(Context context) {
        super(context);
        mContext = context;
        registerForResult();
    }

    @Override
    protected void onClick() {
        View view = View.inflate(mContext, R.layout.profile_chooser_view, null);

        GridLayout layout = view.findViewById(R.id.layout1);
        ImageView imgCurrent = view.findViewById(R.id.imgCurrent);

        String picture = getSharedPreferences().getString("profile", "0");
        imgCurrent.setImageBitmap(Server.getProfilePicture(picture, mContext));

        for (int i = 0; i < 12; i++) {
            final int idx = i;
            ImageButton btn = new ImageButton(mContext);
            btn.setImageBitmap(Server.getProfilePicture(String.valueOf(idx), mContext));
            btn.setOnClickListener((v) -> {
                getSharedPreferences().edit().putString("profile", String.valueOf(idx)).apply();
                imgCurrent.setImageBitmap(Server.getProfilePicture(String.valueOf(idx), mContext));
            });
            layout.addView(btn);
        }

        MaterialAlertDialogBuilder materialAlertDialogBuilder = new MaterialAlertDialogBuilder(mContext)
                .setTitle(R.string.picture_settings_title)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.custom, (dialog, which) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("image/*");
                    i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

                    customImageActivityResultLauncher.launch(i);
                    dialog.dismiss();
                }).setView(view);
        materialAlertDialogBuilder.show();
    }

    private void registerForResult() {
        customImageActivityResultLauncher = getActivity().registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == Activity.RESULT_OK) {
                        Intent data = result.getData();
                        if (data != null) {
                            Uri u = data.getData();
                            if (u != null) {
                                try (var is = mContext.getContentResolver().openInputStream(u)) {
                                    var bmp = BitmapFactory.decodeStream(is);
                                    // Save "profilePic" to private app storage in reduced resolution
                                    int outW, outH;
                                    int maxDim = Math.min(512, Math.max(bmp.getWidth(), bmp.getHeight()));
                                    if(bmp.getWidth() > bmp.getHeight()){
                                        outW = maxDim;
                                        outH = (bmp.getHeight() * maxDim) / bmp.getWidth();
                                    } else {
                                        outH = maxDim;
                                        outW = (bmp.getWidth() * maxDim) / bmp.getHeight();
                                    }
                                    Bitmap resized = Bitmap.createScaledBitmap(bmp, outW, outH, true);
                                    try (var os = mContext.openFileOutput("profilePic.png", Context.MODE_PRIVATE)) {
                                        //quality is irrelevant for PNG
                                        resized.compress(Bitmap.CompressFormat.PNG, 100, os);
                                    }
                                    getSharedPreferences().edit().putString("profile", "profilePic.png").apply();
                                } catch (Exception e) {
                                    Log.e("ProfilePic", "Failed to save profile picture: " + u, e);
                                    Toast.makeText(mContext, "Failed to save profile picture: " + e, Toast.LENGTH_LONG).show();
                                }
                            }
                        }
                    }
                });
    }

    private SettingsActivity getActivity() {
        return (SettingsActivity) mContext;
    }
}
