package com.sjtu.karaoke;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;
import android.widget.Space;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.AppCompatImageButton;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.constraintlayout.widget.Group;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.arthenica.mobileffmpeg.FFmpeg;
import com.dreamfish.record.AudioRecorder;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.sjtu.karaoke.component.LoadingDialog;
import com.sjtu.karaoke.data.Chord;
import com.sjtu.karaoke.data.PlayChordRecord;
import com.sjtu.karaoke.data.Score;
import com.sjtu.pianorater.PianoRater;
import com.sunty.droidparticle.ParticleSystem;
import com.sunty.droidparticle.ParticleSystemConfig;
import com.sunty.droidparticle.ParticleSystemView;

import org.apache.commons.io.FileUtils;
import org.sang.lrcview.LrcView;
import org.sang.lrcview.bean.LrcBean;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

import me.grantland.widget.AutofitTextView;

import static com.dreamfish.record.AudioRecorder.PCM_SPLIT_INTERVAL;
import static com.sjtu.karaoke.singrater.RatingUtil.getScore;
import static com.sjtu.karaoke.singrater.RatingUtil.init;
import static com.sjtu.karaoke.util.Constants.ASSET_DIRECTORY;
import static com.sjtu.karaoke.util.Constants.PROGRESS_UPDATE_INTERVAL;
import static com.sjtu.karaoke.util.Constants.RECORD_DELAY_LB;
import static com.sjtu.karaoke.util.Constants.RECORD_DELAY_UB;
import static com.sjtu.karaoke.util.FileUtil.deleteOneFile;
import static com.sjtu.karaoke.util.MediaPlayerUtil.loadAudioFileAndPrepareExoPlayer;
import static com.sjtu.karaoke.util.MediaPlayerUtil.terminateExoPlayer;
import static com.sjtu.karaoke.util.MiscUtil.clearTemporaryPcmAndWavFiles;
import static com.sjtu.karaoke.util.MiscUtil.mergeNotesToChord;
import static com.sjtu.karaoke.util.MiscUtil.parseScore;
import static com.sjtu.karaoke.util.MiscUtil.showLoadingDialog;
import static com.sjtu.karaoke.util.PathUtil.getAccompanyFullPath;
import static com.sjtu.karaoke.util.PathUtil.getAssetFullPath;
import static com.sjtu.karaoke.util.PathUtil.getChordTransFullPath;
import static com.sjtu.karaoke.util.PathUtil.getLyricInstrumentFullPath;
import static com.sjtu.karaoke.util.PathUtil.getRateFullPath;
import static com.sjtu.karaoke.util.PathUtil.getUserPlayFullPath;


/*
 * @ClassName: InstrumentSingActivity
 * @Author: guozh
 * @Date: 2021/3/28
 * @Version: v1.2
 * @Description: 自弹自唱界面。本类中包含了如下功能：
 *                  1. 各个组件的初始化、设置点击事件
 *                  2. 根据传入的歌曲信息，初始化伴奏播放器、歌词滚动器、音效池
 *                  3. 在播放时监控进度、更新得分（不以得分条的形式显示）
 *                  4. 录音
 */

public class InstrumentSingActivity extends AppCompatActivity {
    private static final float chordVolume = (float) 0.6;
    private static Integer HINT_DURATION = 3000;

    SimpleExoPlayer accompanyPlayer;
    LrcView lrcView;
    SoundPool chordPlayer;
    AudioRecorder voiceRecorder;
    ImageButton pauseButton;

    Semaphore mutex = new Semaphore(1);
    Handler handler = new Handler();

    // 监听进度
    Runnable progressMonitor;
    // 监听结束
    Runnable completionListener;
    // 监听录音切分
    Runnable recordMonitor;
    // 监听按钮提示
    Runnable hintMonitor;
    // 粒子特效容器
    ParticleSystemView particleSystemView;
    // 当前亮的进度条
    ProgressBar currentHint;
    // 当前保持在顶部的粒子特效
    ParticleSystem currentPs;

    // 状态量
    // 当前歌的id
    int id;
    // 当前歌名
    String songName;
    // 当前播放进度
    int currentPosition;
    // 下一次切分pcm的时间
    int nextPcmSplitTime;
    // 当前的播放状态，未开始、正在播放、暂停
    State state;
    // 每句话的歌词
    List<LrcBean> lrcs;
    // 当前歌词迭代器
    ListIterator<LrcBean> lrcIterator;
    // 当前歌词
    LrcBean currentLrc;
    // 得分
    Score score;
    // 演奏片段开始时间（文件中）
    int startTime;
    // 程序中的伴奏开始时间
    int playbackStartTime;
    // 演奏片段结束时间
    int finishTime;
    // 和弦/按键个数
    int chordNum;
    // 和弦对象
    List<Chord> chords;
    // 标准的每个和弦的时间
    List<PlayChordRecord> standardSequence;
    // 用户弹的每个和弦的时间
    List<PlayChordRecord> userSequence;
    // 打分线程
    List<Thread> ratingThread;
    // 当前还没有被release的粒子系统
    ConcurrentHashMap<ParticleSystem, Boolean> unreleasedParticleSystems;
    // 将和弦名映射到和弦对象
    HashMap<String, Chord> nameToChord;
    // 将和弦映射到按钮
    HashMap<Chord, ProgressBar> chordToBtn;

    // 下一次提示时间和对应和弦
    int nextHintTime;
    PlayChordRecord nextHintChord;


    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_instrument_sing);
        initFullScreen();

        initSongName();
        initState();
        initProgressMonitor();
        initRecordMonitor();
        initHintMonitor();
        initPauseButton();
        initParticleSystem();
    }

    private void initFullScreen() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    protected void onStart() {
        super.onStart();

        if (state == State.UNSTARTED) {
            LoadingDialog loadingDialog = showLoadingDialog(
                    this,
                    getString(R.string.initialize_hint),
                    true
            );
            loadingDialog.setCancelable(false);
            initFullScreen();

            new Thread(() -> {
                parseChordFile();
                loadingDialog.setProgress(15);

                initSoundPool();
                loadingDialog.setProgress(25);

                initInstrumentButtons();
                loadingDialog.setProgress(40);

                nextPcmSplitTime = PCM_SPLIT_INTERVAL + playbackStartTime;
                initAccompanyPlayer();
                loadingDialog.setProgress(50);

                initRatingSystem();
                loadingDialog.setProgress(65);

                initOnCompleteListener();
                initLrcView();
                loadingDialog.setProgress(80);

                clearTemporaryPcmAndWavFiles();
                initVoiceRecorder();
                initScore();
                loadingDialog.setProgress(90);

                nextHintChord = standardSequence.remove(0);
                nextHintTime = getHintTime(nextHintChord.getTime());
                loadingDialog.setProgress(100);
                loadingDialog.dismiss();

                InstrumentSingActivity.this.runOnUiThread(this::start);

            }).start();
        }
    }


    @Override
    protected void onStop() {
        super.onStop();
        if (this.state == State.PLAYING) {
            pauseButton.callOnClick();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (voiceRecorder.getStatus() == AudioRecorder.Status.STATUS_PAUSE || voiceRecorder.getStatus() == AudioRecorder.Status.STATUS_START) {
            voiceRecorder.stopRecord(false);
        }
        lrcView.alertPlayerReleased();
        terminateExoPlayer(this, accompanyPlayer);
        chordPlayer.release();
    }

    private void initSongName() {
        Intent intent = getIntent();
        id = intent.getIntExtra("id", 0);
        songName = intent.getStringExtra("songName");
    }

    private void initState() {
        state = State.UNSTARTED;
    }

    private void initProgressMonitor() {
        progressMonitor = new Runnable() {
            @Override
            public void run() {
                currentPosition = (int) accompanyPlayer.getCurrentPosition();
                handler.postDelayed(this, PROGRESS_UPDATE_INTERVAL);
            }
        };
    }

    private void initRecordMonitor() {
        recordMonitor = new Runnable() {
            @Override
            public void run() {
                if (currentPosition >= nextPcmSplitTime) {
                    voiceRecorder.setLastPcmStartTime(nextPcmSplitTime - PCM_SPLIT_INTERVAL);
                    voiceRecorder.setShouldStartNewPcm(true);
                    nextPcmSplitTime += PCM_SPLIT_INTERVAL;

                    if (lrcIterator.hasNext() && currentPosition > currentLrc.getEnd()) {
                        if (currentLrc.shouldRate()) {
                            rate((int) currentLrc.getStart(), (int) currentLrc.getEnd());
                        }
                        currentLrc = lrcIterator.next();
                    }
                }
                handler.postDelayed(this, 10);
            }
        };
    }

    private void initHintMonitor() {
        hintMonitor = new Runnable() {
            @Override
            public void run() {
                if (currentPosition > nextHintTime) {
                    displayHint(nextHintTime, nextHintChord);

                    if (!standardSequence.isEmpty()) {
                        nextHintChord = standardSequence.remove(0);
                        nextHintTime = getHintTime(nextHintChord.getTime());
                    } else {
                        handler.removeCallbacks(this);
                        return;
                    }
                }
                handler.postDelayed(this, 10);
            }
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void initPauseButton() {

        pauseButton = findViewById(R.id.instrumentPauseBtn);

        pauseButton.setOnClickListener(view -> {

            pauseButton.setEnabled(false);

            Dialog instrumentPauseDialog = new Dialog(this, R.style.PauseDialog);
            instrumentPauseDialog.setContentView(R.layout.dialog_instrument_pause);
            instrumentPauseDialog.getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);

            ImageButton backBtn = instrumentPauseDialog.findViewById(R.id.instrumentBackBtn);
            ImageButton resumeBtn = instrumentPauseDialog.findViewById(R.id.instrumentResumeBtn);
            ImageButton retryBtn = instrumentPauseDialog.findViewById(R.id.instrumentRetryBtn);
            ImageButton finishBtn = instrumentPauseDialog.findViewById(R.id.instrumentFinishBtn);

            ConstraintLayout backClickable = instrumentPauseDialog.findViewById(R.id.instrumentBack);
            ConstraintLayout resumeClickable = instrumentPauseDialog.findViewById(R.id.instrumentResume);
            ConstraintLayout retryClickable = instrumentPauseDialog.findViewById(R.id.instrumentRetry);
            ConstraintLayout finishClickable = instrumentPauseDialog.findViewById(R.id.instrumentFinish);

            backBtn.setOnClickListener(view0 -> {
                backBtn.setEnabled(false);
                instrumentPauseDialog.dismiss();
                if (this.state != State.UNSTARTED) {
                    stopActivity(false);
                }
                onBackPressed();
            });

            resumeBtn.setOnClickListener(view1 -> {
                resumeBtn.setEnabled(false);
                instrumentPauseDialog.dismiss();

                // 在渐隐动画结束后继续游戏
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        start();
                    }
                }, 300);
            });

            retryBtn.setOnClickListener(view2 -> {
                retryBtn.setEnabled(false);
                instrumentPauseDialog.dismiss();
                retry();
            });

            finishBtn.setOnClickListener(view3 -> {
                finishBtn.setEnabled(false);
                instrumentPauseDialog.dismiss();
                finishInstrumentSing();
            });

            backClickable.setOnClickListener(view00 -> {
                backClickable.setClickable(false);
                backBtn.callOnClick();
            });

            resumeClickable.setOnClickListener(view00 -> {
                resumeClickable.setClickable(false);
                resumeBtn.callOnClick();
            });

            retryClickable.setOnClickListener(view00 -> {
                retryClickable.setClickable(false);
                retryBtn.callOnClick();
            });

            finishClickable.setOnClickListener(view00 -> {
                finishClickable.setClickable(false);
                finishBtn.callOnClick();
            });

            pause();
            instrumentPauseDialog.show();

        });
    }

    private void initParticleSystem() {
        particleSystemView = findViewById(R.id.particleSystemView);
        particleSystemView.setTranslationZ(300);
        unreleasedParticleSystems = new ConcurrentHashMap<>();
    }

    private void parseChordFile() {
        chords = new ArrayList<>();
        standardSequence = new ArrayList<>();
        userSequence = new ArrayList<>();
        nameToChord = new HashMap<>();
        chordNum = 0;

        File chordFile = new File(getChordTransFullPath(songName));
        Scanner scanner;
        String line;

        try {
            scanner = new Scanner(chordFile);

            // skip useless data
            scanner.nextDouble();
            scanner.nextInt();
            scanner.nextInt();

            // read start time and finish time
            startTime = scanner.nextInt();
            playbackStartTime = startTime - HINT_DURATION;
            finishTime = scanner.nextInt();

            // skip empty line
            do {
                line = scanner.nextLine();
            } while (line.equals(""));

            // read chord info
            for (; !line.equals(""); line = scanner.nextLine()) {
                // get chord name
                String[] params = line.split(" ");
                String chordName = params[0];
                List<String> notes = new ArrayList<>();

                // increment number of chords
                ++chordNum;

                // extract get path to note files
                for (int i = 1; i < params.length; ++i) {
                    notes.add(getAssetFullPath(this, params[i] + ".wav"));
                }

                // // merge notes to a chord file and create chord object
                Chord chord = new Chord(chordName, mergeNotesToChord(chordName, notes));
                chords.add(chord);
                nameToChord.put(chordName, chord);
            }

            // clear assets temporary file
            FileUtils.cleanDirectory(new File(ASSET_DIRECTORY));

            // generate sequence in which buttons display animation
            Integer time = startTime;
            HashMap<Chord, Integer> lastChordTime = new HashMap<>();

            while (scanner.hasNext()) {
                line = scanner.nextLine();
                String[] params = line.split(" ");

                Chord chord  = nameToChord.get(params[0]);
                int duration = Integer.parseInt(params[1]);

                if (lastChordTime.containsKey(chord)) {
                    if (time - lastChordTime.get(chord) < HINT_DURATION) {
                        HINT_DURATION = time - lastChordTime.get(chord);
                    }
                }
                lastChordTime.put(chord, time);

                standardSequence.add(new PlayChordRecord(chord, time, duration));
                time += duration;
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initSoundPool() {
        chordPlayer = new SoundPool.Builder()
                .setMaxStreams(chordNum)
                .build();

        for (Chord chord: chords) {
            chord.setSoundId(chordPlayer.load(chord.getFilePath(), 1));
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void initInstrumentButtons() {
        this.runOnUiThread(() -> {
            LinearLayout btnContainer = findViewById(R.id.instrumentButtonsContainer);

            btnContainer.removeAllViews();

            ListIterator<Chord> chordIt = chords.listIterator();
            chordToBtn = new HashMap<>();

            while (chordIt.hasNext()) {
                Chord chord = chordIt.next();
                // add relative layout
                RelativeLayout relativeLayout = new RelativeLayout(InstrumentSingActivity.this);
                LinearLayout.LayoutParams params1 = new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (float) 1.0);
                relativeLayout.setGravity(Gravity.CENTER);
                relativeLayout.setLayoutParams(params1);

                // add progress bar
                ProgressBar instrumentBtn = new ProgressBar(InstrumentSingActivity.this, null, android.R.attr.progressBarStyleHorizontal);
                RelativeLayout.LayoutParams params2 = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                );
                params2.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                instrumentBtn.setIndeterminateDrawable(ContextCompat.getDrawable(InstrumentSingActivity.this, R.drawable.custom_instrument_button));
                instrumentBtn.setProgressDrawable(ContextCompat.getDrawable(InstrumentSingActivity.this, R.drawable.custom_instrument_button));
                instrumentBtn.setLayoutParams(params2);
                instrumentBtn.setMax(100);
                instrumentBtn.setProgress(0);
                // fixme: probably set in progress monitor?
                instrumentBtn.setOnClickListener(v -> {
                            chordPlayer.play(chord.getSoundId(), chordVolume, chordVolume, 1, 0, 1);
                            userSequence.add(new PlayChordRecord(chord, currentPosition));
                        }
                );
                chordToBtn.put(chord, instrumentBtn);
                relativeLayout.addView(instrumentBtn);

                // add text view
                AutofitTextView chordLabel = new AutofitTextView(InstrumentSingActivity.this);
                RelativeLayout.LayoutParams params3 = new RelativeLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                params3.addRule(RelativeLayout.CENTER_IN_PARENT, RelativeLayout.TRUE);
                chordLabel.setText(chord.getName());
                chordLabel.setTextColor(getColor(R.color.instrument_chord_label));
                chordLabel.setTypeface(Typeface.DEFAULT_BOLD);
                chordLabel.setGravity(Gravity.CENTER);
                chordLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
                chordLabel.setMaxLines(1);
                chordLabel.setSizeToFit();

                chordLabel.setLayoutParams(params3);
                relativeLayout.addView(chordLabel);

                btnContainer.addView(relativeLayout);

                // add space
                if (chordIt.hasNext()) {
                    Space space = new Space(InstrumentSingActivity.this);
                    LinearLayout.LayoutParams params4 = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1.0f
                    );
                    space.setLayoutParams(params4);
                    btnContainer.addView(space);
                }
            }
        });

    }

    private void initAccompanyPlayer() {
        accompanyPlayer = new SimpleExoPlayer.Builder(this).build();
        loadAudioFileAndPrepareExoPlayer(this, accompanyPlayer, getAccompanyFullPath(songName));

        runOnUiThread(() -> accompanyPlayer.seekTo(playbackStartTime));
    }

    private Integer getHintTime(Integer time) {
        return time - HINT_DURATION;
    }

    private void initRatingSystem() {
        ratingThread = new ArrayList<>();
        init(getRateFullPath(songName), PCM_SPLIT_INTERVAL, RECORD_DELAY_LB, RECORD_DELAY_UB);
    }

    private void initOnCompleteListener() {
        completionListener = new Runnable() {
            @Override
            public void run() {
                if (currentPosition > finishTime) {
                    finishInstrumentSing();
                    return;
                }
                handler.postDelayed(this, 200);
            }
        };
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void initLrcView() {
        lrcView = findViewById(R.id.lrcRoller);
        lrcView.setHighLineColor(ContextCompat.getColor(getApplicationContext(), R.color.instrument_lyric));
        try {
            InputStream is = new FileInputStream(getLyricInstrumentFullPath(songName));

            String lrc = new BufferedReader(new InputStreamReader(is))
                    .lines().collect(Collectors.joining("\n"));

            lrcs = lrcView.setLrc(lrc);
            lrcIterator = lrcs.listIterator();
            currentLrc = lrcIterator.next();

            lrcView.setPlayer(accompanyPlayer);
            lrcView.init();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void initVoiceRecorder() {
        voiceRecorder = AudioRecorder.getInstance();
        voiceRecorder.createDefaultAudio(songName, playbackStartTime % PCM_SPLIT_INTERVAL);
    }

    private void initScore() {
        score = new Score();
    }

    private void displayHint(int startTime, PlayChordRecord hintChord) {

        new Thread(() -> {
            ProgressBar progressBar = chordToBtn.get(hintChord.getChord());
            ParticleSystem particleSystem = particleSystemView.createParticleSystem();

            int[] btnLocation = new int[2];
            int[] containerLocation = new int[2];
            final int height = progressBar.getMeasuredHeight();
            final int width = progressBar.getMeasuredWidth();
            progressBar.getLocationOnScreen(btnLocation);
            particleSystemView.getLocationOnScreen(containerLocation);
            final int x = btnLocation[0] - containerLocation[0] + width / 2;
            final int y = btnLocation[1] - containerLocation[1] + height;

            initParticleSystem(particleSystem, x, y);

            int hintFinishTime = hintChord.getTime();
            particleSystem.start();
            unreleasedParticleSystems.put(particleSystem, true);

            while (currentPosition < hintFinishTime && !(state == State.UNSTARTED)) {
                int percentage = (currentPosition - startTime) / (HINT_DURATION / 100);

                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                progressBar.setProgress(percentage);
                particleSystem.setPtcPosition(x, y - height * percentage / 100);
            }

            if (currentHint != null) {
                currentHint.setProgress(0);
                currentPs.stop();

                ParticleSystem psSnapshot = currentPs;
                handler.postDelayed(() -> {
                    mutex.acquireUninterruptibly();
                    if (unreleasedParticleSystems.contains(psSnapshot)) {
                        unreleasedParticleSystems.remove(psSnapshot);
                        particleSystemView.releaseParticleSystem(psSnapshot);
                    }
                    mutex.release();
                }, 1500);
            }

            if (state != State.UNSTARTED) {
                progressBar.setProgress(100);

                currentHint = progressBar;
                currentPs = particleSystem;
            }

        }).start();
    }

    private void initParticleSystem(ParticleSystem ptcSys, int x, int y) {

        BitmapDrawable drawable = (BitmapDrawable) ResourcesCompat.getDrawable(Karaoke.getRes(),
                R.drawable.note, null);

        Bitmap img = drawable.getBitmap();

        ptcSys.setPtcBlend(1);
        ptcSys.setFps(35);
        ptcSys.setPps(10);
        ptcSys.setPtcImage(img);
        ptcSys.setPtcPosition(x, y);

        ParticleSystemConfig config = new ParticleSystemConfig();
        config.duration.set(1000, 0);
        config.theta.set(270, 17);
        config.startVelocity.set(480, 200);
        config.endVelocity.set(480, 200);
        config.startAngularRate.set(0, 0);
        config.endAngularRate.set(0, 0);
        config.startSpinRate.set(360, 0);
        config.endSpinRate.set(360, 0);
        config.startAlpha.set(1, 0);
        config.endAlpha.set(0.3f, 0);
        config.gravx.set(0, 0);
        config.gravy.set(1000, 0);
        config.startScale.set(0.22f, 0);
        config.endScale.set(0.33f, 0);
        config.startRed.set(0, 0);
        config.endRed.set(0.6f, 0.15f);
        config.startGreen.set(0, 0);
        config.endGreen.set(0.05f, 0.05f);
        config.startBlue.set(1, 0.2f);
        config.endBlue.set(1, 0.2f);

        ptcSys.setConfig(config);
    }

    private void startAllPlayers() {
        accompanyPlayer.play();
        chordPlayer.autoResume();
    }

    private void start() {
        state = State.PLAYING;

        handler.postDelayed(progressMonitor, 0);
        handler.postDelayed(completionListener, 0);
        handler.postDelayed(recordMonitor, 0);
        handler.postDelayed(hintMonitor, 0);

        voiceRecorder.startRecord(null);
        pauseButton.setEnabled(true);
        startAllPlayers();
    }

    private void pauseAllPlayers() {
        accompanyPlayer.pause();
        chordPlayer.autoPause();
    }

    private void pause() {
        state = State.PAUSE;

        handler.removeCallbacks(progressMonitor);
        handler.removeCallbacks(completionListener);
        handler.removeCallbacks(recordMonitor);
        handler.removeCallbacks(hintMonitor);

        voiceRecorder.pauseRecord();
        pauseAllPlayers();
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void retry() {
        stopActivity(false);
        onStart();
    }

    private void finishInstrumentSing() {

        LoadingDialog loadingDialog = showLoadingDialog(this, getString(R.string.process_record_hint));
        int len = userSequence.size();

        new Thread(() -> {
            stopActivity(true);

            double[] userTimeSequence = new double[len];
            String[] userChordNameSequence = new String[len];
            for (int i = 0; i < len; ++i) {
                PlayChordRecord r = userSequence.get(i);
                userTimeSequence[i] = (r.getTime() - HINT_DURATION);
                userChordNameSequence[i] = r.getChord().getName();
            }

            for (Thread thread: ratingThread) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            score.computeFinalScore();
            Intent intent = new Intent(InstrumentSingActivity.this,
                    SingResultActivity.class);
            intent.putExtra("id", id);
            intent.putExtra("songName", songName);
            intent.putExtra("score", score);
            intent.putExtra("pianoScore",
                    PianoRater.getScore(
                            getChordTransFullPath(songName),
                            len,
                            userTimeSequence,
                            userChordNameSequence
                    )
            );
            startActivityForResult(intent, 0);
            loadingDialog.dismiss();

        }).start();
    }

    private void stopActivity(boolean shouldMergePcm) {
        // 在stopActivity中不删除临时的pcm和wav文件，在onStart中删除
        this.state = State.UNSTARTED;

        // 释放所有粒子系统
        mutex.acquireUninterruptibly();
        for (ParticleSystem particleSystem : unreleasedParticleSystems.keySet()) {
            unreleasedParticleSystems.remove(particleSystem);
            particleSystemView.releaseParticleSystem(particleSystem);
        }
        mutex.release();

        handler.removeCallbacks(completionListener);
        handler.removeCallbacks(progressMonitor);
        handler.removeCallbacks(recordMonitor);
        handler.removeCallbacks(hintMonitor);
        voiceRecorder.stopRecord(shouldMergePcm);

        terminateExoPlayer(this, accompanyPlayer);
        if (shouldMergePcm) {
            mergeUserChords(userSequence);
        }
        lrcView.alertPlayerReleased();
        resetProgressBar();
    }

    private void resetProgressBar() {
        for (ProgressBar progressBar: chordToBtn.values()) {
            progressBar.setProgress(0);
        }
    }

    private void mergeUserChords(List<PlayChordRecord> userSequence) {
        int duration = finishTime - playbackStartTime;
        String destPath = getUserPlayFullPath(songName);
        String silencePath = getUserPlayFullPath("silence");
        int labelId = 1;
        int volumeMultiply = userSequence.size() + 1;
        // generate silent audio
        StringBuilder command = new StringBuilder("-f lavfi -i anullsrc -t ");

        command.append(duration).append("ms ").append(silencePath);
        FFmpeg.execute(command.toString());

        command = new StringBuilder("-y ");

        command.append("-i ").append(silencePath).append(" ");

        for (PlayChordRecord userRecord: userSequence) {
            userRecord.decrementTime(playbackStartTime);
            command.append("-i ").append(userRecord.getChord().getFilePath()).append(" ");
        }

        command.append("-filter_complex \"");
        command.append(String.format(Locale.CHINA, "[0]volume=%d[00];", volumeMultiply));
        for (PlayChordRecord userRecord: userSequence) {
            int time = userRecord.getTime();
            command.append(String.format(Locale.CHINA, "[%d]volume=%d,adelay=%d|%d[0%d];",
                    labelId, volumeMultiply, time, time, labelId));
            ++labelId;
        }
        for (int i = 0; i < labelId; ++i) {
            command.append(String.format(Locale.CHINA, "[0%d]", i));
        }
        command.append("amix=inputs=").append(userSequence.size() + 1);
        command.append(":duration=longest:dropout_transition=1000000\" ");
        command.append(destPath);

        FFmpeg.execute(command.toString());

        deleteOneFile(silencePath);
    }

    /**
     * Rate the record in a given interval
     * @param startTime Starting time of the line (ms)
     * @param endTime End time of the line (ms)
     */
    private void rate(int startTime, int endTime) {
        Thread thread = new Thread(() -> {
            while (!voiceRecorder.isf0AnalysisComplete(startTime, endTime)) {
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
            String scoreStr = getScore(startTime, endTime);
            Integer[] scores = parseScore(scoreStr);
            score.update(scores);
        });

        ratingThread.add(thread);
        thread.start();
    }

    private enum State {
        PAUSE, PLAYING, UNSTARTED
    }
}