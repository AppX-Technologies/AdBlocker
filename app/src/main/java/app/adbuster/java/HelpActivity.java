package app.adbuster.java;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import app.adbuster.R;
import app.adbuster.databinding.ActivityHelpBinding;

public class HelpActivity extends AppCompatActivity {

    ActivityHelpBinding binding;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityHelpBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        initUI();

    }

    private void initUI() {
        binding.ivBack.setOnClickListener(v -> finish());

        binding.llReport.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{"purutalapps@gmail.com"});
            intent.putExtra(Intent.EXTRA_SUBJECT, "Report an issue for AdBlocker android app");
            intent.setType("text/html");
            startActivity(Intent.createChooser(intent, "Send report using..."));
        });

        binding.llAdsInfo.setOnClickListener(v -> UIHelper.createAlertDialog(this, getLayoutInflater().inflate(R.layout.dialog_ads_help, null)).show());
    }
}
