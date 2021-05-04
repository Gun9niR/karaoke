package com.sjtu.karaoke;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.MenuItem;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.concurrent.TimeUnit;

import static com.sjtu.karaoke.util.Constants.PROGRESS_UPDATE_INTERVAL;
import static com.sjtu.karaoke.util.Constants.RECORD_DIRECTORY;
import static com.sjtu.karaoke.util.FileUtil.deleteOneFile;
import static com.sjtu.karaoke.util.MediaPlayerUtil.loadAudioFileAndPrepareExoPlayer;
import static com.sjtu.karaoke.util.MediaPlayerUtil.terminateExoPlayer;
import static com.sjtu.karaoke.util.MiscUtil.getAccompanyFullPath;
import static com.sjtu.karaoke.util.MiscUtil.getRecordName;
import static com.sjtu.karaoke.util.MiscUtil.getTrimmedAccompanyFullPath;
import static com.sjtu.karaoke.util.MiscUtil.getVoiceFullPath;
import static com.sjtu.karaoke.util.MiscUtil.showLoadingDialog;
import static com.sjtu.karaoke.util.MiscUtil.showToast;
import static com.sjtu.karaoke.util.WavUtil.getWAVDuration;
import static com.sjtu.karaoke.util.WavUtil.mergeWAVs;
import static com.sjtu.karaoke.util.WavUtil.trimWav;

/*
 * @ClassName: SingResultActivity
 * @Author: guozh
 * @Date: 2021/3/28
 * @Version: v1.2
 * @Description: 演唱结果界面。本类中包括了如下功能：
 *                  1. 各个组件的初始化、设置点击事件
 *                  2. 根据传入的歌曲信息、用户录音，初始化伴奏播放器、录音播放器
 *                  3. 在播放时监控进度、更新进度条
 *                  4. 在用户拖动音量条时修改录音或伴奏的音量
 */

public class SingResultActivity extends AppCompatActivity {
    final int INITIAL_OFFSET = 700;

    Toolbar toolbar;
    BottomNavigationView bottomNavbarResult;
    TextView titleText, playerPosition, playerDuration;
    SeekBar seekBarResultProgress;
    SeekBar seekbarTuneVoice;
    SeekBar seekbarTuneAccompany;
    SeekBar seekbarAlignVoice;
    ImageView btnPlay, btnPause;
    SimpleExoPlayer accompanyPlayer;
    SimpleExoPlayer voicePlayer;
    Handler handler = new Handler();
    Runnable progressUpdater;

    float voiceVolume = 1;
    float accompanyVolume = 1;

    boolean isFileSaved = false;
    State state;
    int voiceOffset;

    Integer id;
    String songName;
    int voiceDuration;

    // 需要将伴奏裁减成和录音一样长的音频
    String trimmedAccompanyFullPath;
    // 上一个activity中录制的录音
    String voiceFullPath;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sing_result);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent intent = getIntent();
        id = intent.getIntExtra("id", 0);
        songName = intent.getStringExtra("songName");
        getFilePaths();

        voicePlayer = new SimpleExoPlayer.Builder(this).build();
        loadAudioFileAndPrepareExoPlayer(voicePlayer, voiceFullPath);
        voiceDuration = (int) getWAVDuration(voiceFullPath);
        // trim accompany
        trimWav(getAccompanyFullPath(songName), trimmedAccompanyFullPath, 0, voiceDuration);

        accompanyPlayer = new SimpleExoPlayer.Builder(this).build();
        loadAudioFileAndPrepareExoPlayer(accompanyPlayer, trimmedAccompanyFullPath);
        voiceOffset = INITIAL_OFFSET;
        this.state = State.UNSTARTED;

        initProgressUpdater();
        initToolBar();
        initTitle();
        initBottomNavbar();
        initPlaySeekbar();
        initButtonControl();
        initTuneSeekbar();
        initAlignSeekbar();
        btnPlay.callOnClick();
        initFab();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void initAlignSeekbar() {
        seekbarAlignVoice = findViewById(R.id.seekbarAlignVoice);
        // Tune forward or backward by 1s
        seekbarAlignVoice.setMin(0);
        seekbarAlignVoice.setMax(1000);
        seekbarAlignVoice.setProgress(INITIAL_OFFSET);
        seekbarAlignVoice.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                voiceOffset = progress;
                stopAllPlayers();
                voicePlayer.seekTo(0);
                accompanyPlayer.seekTo(0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                btnPlay.callOnClick();
            }
        });
    }

    private void stopAllPlayers() {
        this.state = State.UNSTARTED;
        voicePlayer.pause();
        accompanyPlayer.pause();
        handler.removeCallbacks(progressUpdater);
    }

    private void getFilePaths() {
        trimmedAccompanyFullPath = getTrimmedAccompanyFullPath(songName);
        voiceFullPath = getVoiceFullPath(songName);
    }

    private void initFab() {
        FloatingActionButton fabSave = findViewById(R.id.fabSave);
        fabSave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (isFileSaved) {
                    showToast(getApplicationContext(), "文件已经保存");
                }
                // merge two .wav files, and put under .../Karaoke/record/
                Dialog loadingDialog = showLoadingDialog(SingResultActivity.this, "正在生成作品...");
                new Thread(new Runnable() {
                    @RequiresApi(api = Build.VERSION_CODES.O)
                    @Override
                    public void run() {
                        Looper.prepare();
                        String resultFileName = getRecordName(id, songName);
                        mergeWAVs(RECORD_DIRECTORY + resultFileName, trimmedAccompanyFullPath, voiceFullPath, accompanyVolume, voiceVolume, voiceOffset);
                        loadingDialog.dismiss();
                        showToast(getApplicationContext(), "录音已成功保存");
                        Looper.loop();
                        isFileSaved = true;
                    }
                }).start();
            }
        });
    }

    private void initProgressUpdater() {
        progressUpdater = new Runnable() {
            @Override
            public void run() {
                seekBarResultProgress.setProgress((int) voicePlayer.getCurrentPosition());
                handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
            }
        };
    }

    private void initToolBar() {
        toolbar = findViewById(R.id.toolbarResult);

        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setHomeButtonEnabled(true);
    }

    private void initTitle() {
        titleText = findViewById(R.id.toolbarResultTitle);

        Spannable songName = new SpannableString(this.songName);
        songName.setSpan(new ForegroundColorSpan(Color.WHITE), 0, songName.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleText.setText(songName);

        // todo: change rank
        Spannable rank = new SpannableString("  SS");
        rank.setSpan(new ForegroundColorSpan(Color.YELLOW), 0, rank.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        titleText.append(rank);
    }

    private void initBottomNavbar() {
        bottomNavbarResult = findViewById(R.id.bottomNavigationViewResult);
        bottomNavbarResult.setBackground(null);
        bottomNavbarResult.getMenu().getItem(1).setEnabled(false);

        bottomNavbarResult.setOnNavigationItemSelectedListener(new BottomNavigationView.OnNavigationItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                int id = item.getItemId();
                switch(id) {
                    case R.id.resultRetry:
                        deleteOneFile(trimmedAccompanyFullPath);
                        deleteOneFile(voiceFullPath);
                        handler.removeCallbacks(progressUpdater);
                        terminateExoPlayer(voicePlayer);
                        terminateExoPlayer(accompanyPlayer);
                        onBackPressed();
                        break;
                    case R.id.resultShare:
                        break;
                }
                return false;
            }
        });
    }


    private void initPlaySeekbar() {
        // seekbar text
        playerPosition = findViewById(R.id.playerPosition);
        playerDuration = findViewById(R.id.playerDuration);
        String sDuration = convertFormat(voiceDuration);
        playerDuration.setText(sDuration);

        // seekbar
        seekBarResultProgress = findViewById(R.id.seekbarResultProgress);
        seekBarResultProgress.setMax(voiceDuration);
        seekBarResultProgress.setProgress(0);

        seekBarResultProgress.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    voicePlayer.seekTo(progress);
                    accompanyPlayer.seekTo(Math.max(progress - voiceOffset, 0));
                }

                playerPosition.setText(convertFormat(((int) voicePlayer.getCurrentPosition())));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
    }

    private void initButtonControl() {

        btnPlay = findViewById(R.id.resultPlay);
        btnPause = findViewById(R.id.resultPause);

        voicePlayer.addListener(new Player.EventListener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    SingResultActivity.this.state = State.UNSTARTED;
                    btnPause.setVisibility(View.GONE);
                    btnPlay.setVisibility(View.VISIBLE);
                    accompanyPlayer.pause();
                    accompanyPlayer.seekTo(0);
                    voicePlayer.pause();
                    voicePlayer.seekTo(0);
                    seekBarResultProgress.setProgress(0);
                    handler.removeCallbacks(progressUpdater);
                }
            }
        });

        btnPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnPlay.setVisibility(View.GONE);
                btnPause.setVisibility(View.VISIBLE);
                if (state == State.UNSTARTED) {
                    startAllPlayers(voiceOffset);
                } else {
                    startAllPlayers(0);
                }
            }
        });

        btnPause.setOnClickListener((new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                btnPause.setVisibility(View.GONE);
                btnPlay.setVisibility(View.VISIBLE);
                pauseAllPlayers();
            }
        }));
    }

    private void pauseAllPlayers() {
        this.state = State.PAUSE;
        voicePlayer.pause();
        accompanyPlayer.pause();
        handler.removeCallbacks(progressUpdater);
    }

    private void initTuneSeekbar() {

        seekbarTuneVoice = findViewById(R.id.seekbarTuneVoice);
        seekbarTuneVoice.setMax(100);
        seekbarTuneVoice.setProgress(100);
        voicePlayer.setVolume(1);
        seekbarTuneAccompany = findViewById(R.id.seekbarTuneAccompany);
        seekbarTuneAccompany.setMax(100);
        seekbarTuneAccompany.setProgress(100);
        accompanyPlayer.setVolume(1);

        seekbarTuneVoice.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                voiceVolume = (float)progress / 100;
                if (fromUser)
                    voicePlayer.setVolume(voiceVolume);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });

        seekbarTuneAccompany.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                accompanyVolume = (float)progress / 100;
                if (fromUser)
                    accompanyPlayer.setVolume(accompanyVolume);
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {

            }
        });
    }

    private void startAllPlayers(int voiceOffset) {

        this.state = State.PLAYING;

        handler.postDelayed(progressUpdater, 0);

        voicePlayer.play();
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                accompanyPlayer.play();
            }
        }, voiceOffset);

    }

    @Override
    protected void onStop() {
        super.onStop();

        btnPause.callOnClick();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        deleteOneFile(trimmedAccompanyFullPath);
        deleteOneFile(voiceFullPath);
        handler.removeCallbacks(progressUpdater);
        terminateExoPlayer(voicePlayer);
        terminateExoPlayer(accompanyPlayer);
    }

    @SuppressLint("DefaultLocale")
    private String convertFormat(int duration) {
        return String.format("%02d:%02d",
                TimeUnit.MILLISECONDS.toMinutes(duration),
                TimeUnit.MILLISECONDS.toSeconds(duration)
                        - TimeUnit.MINUTES.toSeconds(TimeUnit.MILLISECONDS.toMinutes(duration)));
    }

    private enum State {
        PLAYING, PAUSE, UNSTARTED
    }
}