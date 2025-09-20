package slowscript.warpinator;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;
import android.text.Html;
import android.text.method.LinkMovementMethod;
import android.view.MenuItem;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;

public class AboutActivity extends AppCompatActivity {

    TextView versionView, warrantyView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        Utils.setEdgeToEdge(getWindow());
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }
        Utils.setToolbarInsets(toolbar);
        Utils.setContentInsets(findViewById(R.id.scrollView));

        versionView = findViewById(R.id.versionText);
        warrantyView = findViewById(R.id.warrantyText);

        versionView.setText(getString(R.string.version, BuildConfig.VERSION_NAME));
        warrantyView.setMovementMethod(LinkMovementMethod.getInstance());
        warrantyView.setText(Html.fromHtml(getResources().getString(R.string.warranty_html)));

    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Respond to the action bar's Up/Home button
        if (item.getItemId() == android.R.id.home) {
            super.onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}