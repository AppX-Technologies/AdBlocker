package app.adbuster.java;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import app.adbuster.BuildConfig;
import app.adbuster.R;
import app.adbuster.databinding.ActivitySettingsBinding;

public class SettingsActivity extends AppCompatActivity {

    ActivitySettingsBinding binding;
    SharedPreManager manager;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        initUI();
    }

    private void initUI() {
        manager = new SharedPreManager(this);
        binding.ivBack.setOnClickListener(v -> finish());

        boolean autoStartEnabled = manager.getBoolean(getString(R.string.vpn_enabled_key), false);
        binding.cvAutoStart.setChecked(autoStartEnabled);

        binding.cvAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> manager.putBoolean(getString(R.string.vpn_enabled_key), isChecked));

        binding.llFeedback.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"purutalapps@gmail.com"});
            intent.putExtra(Intent.EXTRA_SUBJECT, "Feedback for AdBlocker android app");
            intent.setType("text/html");
            startActivity(Intent.createChooser(intent, "Send feedback using..."));
        });

        binding.tvVersion.setText(BuildConfig.VERSION_NAME);
    }
}
