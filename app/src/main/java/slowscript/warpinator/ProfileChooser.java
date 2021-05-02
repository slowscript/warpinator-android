package slowscript.warpinator;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceManager;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TableLayout;

public class ProfileChooser extends AppCompatActivity {

    static final int CHOOSE_PICTURE_REQ_CODE = 10;
    GridLayout layout;
    ImageView imgCurrent;
    Button btnCustom;
    Button btnCancel;
    SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_chooser);
        layout = findViewById(R.id.layout1);
        imgCurrent = findViewById(R.id.imgCurrent);
        btnCustom = findViewById(R.id.btnCustom);
        btnCancel = findViewById(R.id.btnCancel);

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        String picture = prefs.getString("profile", "0");
        imgCurrent.setImageBitmap(Server.current.getProfilePicture(picture, this));

        for (int i = 0; i < 12; i++) {
            final int idx = i;
            ImageButton btn = new ImageButton(this);
            btn.setImageBitmap(Server.current.getProfilePicture(String.valueOf(idx), this));
            btn.setOnClickListener((v)->{
                prefs.edit().putString("profile", String.valueOf(idx)).apply();
                finish();
            });
            layout.addView(btn);
        }

        btnCustom.setOnClickListener((v)->{
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);

            startActivityForResult(i, CHOOSE_PICTURE_REQ_CODE);
        });

        btnCancel.setOnClickListener((v)->finish());
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CHOOSE_PICTURE_REQ_CODE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri u = data.getData();
                getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                prefs.edit().putString("profile", u.toString()).apply();
                finish();
            }
        }
    }
}
