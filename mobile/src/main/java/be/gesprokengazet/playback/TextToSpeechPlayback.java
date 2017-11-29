package be.gesprokengazet.playback;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.TextUtils;

import be.gesprokengazet.MusicService;
import be.gesprokengazet.R;
import be.gesprokengazet.model.MusicProvider;
import be.gesprokengazet.model.MusicProviderSource;
import be.gesprokengazet.news.Article;
import be.gesprokengazet.news.DownloadManager;
import be.gesprokengazet.news.NewsSiteConfiguration;
import be.gesprokengazet.news.Util;
import be.gesprokengazet.news.WebResourceCompleted;
import be.gesprokengazet.utils.LogHelper;
import be.gesprokengazet.utils.MediaIDHelper;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static android.support.v4.media.session.MediaSessionCompat.QueueItem;

/**
 * A class that implements local media playback based on text-to-speech. It is heavily inspired on
 * LocalPlayback.
 */
public final class TextToSpeechPlayback implements Playback {

    private static final String TAG = LogHelper.makeLogTag(TextToSpeechPlayback.class);

    // we don't have audio focus, and can't duck (play at a low volume)
    private static final int AUDIO_NO_FOCUS_NO_DUCK = 0;
    // we don't have focus, but can duck (play at a low volume)
    private static final int AUDIO_NO_FOCUS_CAN_DUCK = 1;
    // we have full audio focus
    private static final int AUDIO_FOCUSED = 2;

    private final Context mContext;
    private boolean mPlayOnFocusGain;
    private Callback mCallback;
    private final MusicProvider mMusicProvider;
    private boolean mAudioNoisyReceiverRegistered;
    private String mCurrentMediaId;

    private int mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
    private final AudioManager mAudioManager;

    private final IntentFilter mAudioNoisyIntentFilter =
            new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);

    private final BroadcastReceiver mAudioNoisyReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())) {
                        LogHelper.d(TAG, "Headphones disconnected.");
                        if (isPlaying()) {
                            Intent i = new Intent(context, MusicService.class);
                            i.setAction(MusicService.ACTION_CMD);
                            i.putExtra(MusicService.CMD_NAME, MusicService.CMD_PAUSE);
                            mContext.startService(i);
                        }
                    }
                }
            };


    private int mState = PlaybackStateCompat.STATE_NONE;

    private TextToSpeech mTextToSpeech;
    private CountDownLatch mTextToSpeechReady = new CountDownLatch(1);
    private boolean mTextToSpeechInitialized = false;

    private DownloadManager<Article> mDownloadManager;

    private AtomicInteger mCurrentArticleParagraphIndex = new AtomicInteger(0);
    private final ConcurrentHashMap<String, Article> mCachedArticles = new ConcurrentHashMap<>();
    private MediaMetadataCompat mCurrentTrack;
    private Article mCurrentArticle;

    private long mPlayStartTime = -1;
    private long mLastStartedStreamPos = 0;

    private final QueueManager mQueueManager;


    public TextToSpeechPlayback(Context context, MusicProvider musicProvider, NewsSiteConfiguration config, QueueManager queueManager) {
        Context applicationContext = context.getApplicationContext();
        this.mContext = applicationContext;
        this.mMusicProvider = musicProvider;

        this.mAudioManager =
                (AudioManager) applicationContext.getSystemService(Context.AUDIO_SERVICE);

        mDownloadManager = new DownloadManager<>(mContext, config.getArticleScrapingJavascript());

        // Create the text to speech, it initializes itself in the background. Meanwhile we continue
        // with fetching the article list (see below, see initTextToSpeech).
        mTextToSpeech = new TextToSpeech(mContext, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int i) {
                mTextToSpeechReady.countDown();
            }
        });
        mQueueManager = queueManager;
    }

    public void destroy() {
        mDownloadManager.destroy();
        mTextToSpeech.shutdown();
    }

    @Override
    public void start() {

    }

    @Override
    public void stop(boolean notifyListeners) {
        setState(PlaybackStateCompat.STATE_STOPPED);

        giveUpAudioFocus();
        unregisterAudioNoisyReceiver();
        releaseResources(true);
    }

    @Override
    public void setState(int state) {
        boolean wasPlaying = isPlayingInternal();

        this.mState = state;
        if (mCallback != null ) {
            mCallback.onPlaybackStatusChanged(state);
        }

        boolean isPlaying = isPlayingInternal();

        if (!wasPlaying && isPlaying) {
            mPlayStartTime = System.nanoTime();
        }
        if (wasPlaying && !isPlaying) {
            mLastStartedStreamPos = getCurrentStreamPosition();
            mPlayStartTime = -1;
        }
    }

    @Override
    public int getState() {
        return mState;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isPlaying() {
        return isPlayingOrBuffering() ||
                mPlayOnFocusGain;
    }

    private boolean isPlayingOrBuffering() {
        return isPlayingInternal() ||
                mState == PlaybackStateCompat.STATE_BUFFERING;
    }

    private boolean isPlayingInternal() {
        return mState == PlaybackStateCompat.STATE_PLAYING;
    }

    @Override
    public long getCurrentStreamPosition() {
        long pos = mLastStartedStreamPos;

        if (mPlayStartTime >= 0 ) {
            long now = System.nanoTime();
            pos += now - mPlayStartTime;
        }
        return pos / 1000000;
    }

    @Override
    public void updateLastKnownStreamPosition() {
        // Nothing to do
    }

    private Article getArticle(MediaMetadataCompat track) {
        String url = track.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_SOURCE);
        synchronized (mCachedArticles) {
            Article article = mCachedArticles.get(url);
            if (article == null) {
                article = new Article(url);
                mCachedArticles.put(url, article);
            }
            return article;
        }
    }


    @Override
    public void play(QueueItem item) {
        mPlayOnFocusGain = true;
        tryToGetAudioFocus();
        registerAudioNoisyReceiver();
        String mediaId = item.getDescription().getMediaId();
        boolean mediaHasChanged = !TextUtils.equals(mediaId, mCurrentMediaId);
        if (mediaHasChanged) {
            // No longer a need to download the previous article
            if ( mCurrentArticle != null ) {
                mDownloadManager.removeForDownload(mCurrentArticle);
            }

            // Play from beginning when seeking to a different article
            mCurrentArticleParagraphIndex.set(0);

            // Stop talking as immediate feedback to pushing previous/next.
            mTextToSpeech.stop();

            mCurrentMediaId = mediaId;
            mCurrentTrack = mMusicProvider.getMusic(MediaIDHelper.extractMusicIDFromMediaID(mediaId));
            mCurrentArticle = getArticle(mCurrentTrack);

        } else if (mCurrentArticle != null &&
                mCurrentArticleParagraphIndex.get() >= mCurrentArticle.getContent().size()) {
            // Play from beginning when the end of the content was reached, this is really an edge case.
            mCurrentArticleParagraphIndex.set(0);
        }

        if (mediaHasChanged) {
            setState(PlaybackStateCompat.STATE_BUFFERING);
            mDownloadManager.scheduleForDownload(mCurrentArticle, new WebResourceCompleted<Article>() {
                @Override
                public void onSuccess(Article resource) {
                    // Current article may have changed meanwhile
                    if (resource.equals(mCurrentArticle)) {
                        setState(PlaybackStateCompat.STATE_PLAYING);
                        readArticle();
                    }
                }

                @Override
                public void onError(Article resource, String message) {
                    if (resource.equals(mCurrentArticle)) {
                        pause();
                    }

                    mCallback.onError(message);
                }
            });
        }
        else {
            setState(PlaybackStateCompat.STATE_PLAYING);
            readArticle();
        }

        configurePlayerState();
    }

    /**
     * Reads the current article out loud.
     */
    private void readArticle() {
        Util.assertUIThread();

        // Prefetch the next article when we start reading
        QueueItem next = mQueueManager.peekQueuePosition(1);
        if (next != null) {
            MediaMetadataCompat nextTrack = mMusicProvider.getMusic(MediaIDHelper.extractMusicIDFromMediaID(next.getDescription().getMediaId()));
            Article nextArticle = getArticle(nextTrack);

            mDownloadManager.scheduleForDownload(nextArticle, new WebResourceCompleted<Article>() {
                @Override
                public void onSuccess(Article resource) {
                }

                @Override
                public void onError(Article resource, String message) {
                }
            });
        }

        String language = mCurrentTrack.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_LANGUAGE);
        String country = mCurrentTrack.getString(MusicProviderSource.CUSTOM_METADATA_TRACK_COUNTRY);
        initTextToSpeech(language, country);

        // Start reading from the current index. This means we re-read the current paragraph
        // after going from pause to play.
        List<String> text = mCurrentArticle.getText();
        List<String> toSpeak = text.subList(mCurrentArticleParagraphIndex.get(), text.size());

        final CountDownLatch done = new CountDownLatch(toSpeak.size() + 2); //+2 for the silence at the end and the beginning
        mTextToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String s) {
            }

            @Override
            public void onError(String s) {
                onDone(s);
            }

            @Override
            public void onDone(String s) {
                done.countDown();
                mCurrentArticleParagraphIndex.incrementAndGet();

                if (done.getCount() == 0 && mCallback != null) {
                    mCallback.onCompletion();
                }
            }
        });


        int bOutcome = mTextToSpeech.playSilentUtterance(200, TextToSpeech.QUEUE_ADD, "beginningPause");
        handleTtsError(done, bOutcome);
        for (String s : toSpeak) {
            int outcome = mTextToSpeech.speak(s, TextToSpeech.QUEUE_ADD, null, s);
            handleTtsError(done, outcome);
        }
        int eOutcome = mTextToSpeech.playSilentUtterance(1500, TextToSpeech.QUEUE_ADD, "endingPause");
        handleTtsError(done, eOutcome);
    }

    private void handleTtsError(CountDownLatch done, int outcome) {
        if (outcome == TextToSpeech.ERROR) {
            onError(mContext.getResources().getString(R.string.error_tts_queue_refused));
            done.countDown();
        }
    }

    private void initTextToSpeech(String language, String country) {
        try {
            Locale locale = new Locale(language, country);

            if (!mTextToSpeechInitialized || !locale.equals(mTextToSpeech.getVoice().getLocale())) {
                mTextToSpeechInitialized = true;

                boolean success = mTextToSpeechReady.await(10, TimeUnit.SECONDS);
                if (!success) {
                    onError(mContext.getResources().getString(R.string.error_tts_timeout));
                } else {
                    int available = mTextToSpeech.setLanguage(locale);
                    if (available == TextToSpeech.LANG_NOT_SUPPORTED) {
                        onError(mContext.getResources().getString(R.string.error_lang_not_supported));
                    }
                    if (available == TextToSpeech.LANG_MISSING_DATA) {
                        onError(mContext.getResources().getString(R.string.error_lang_missing_data));
                    }
                    // Default dutch voice is a tad too fast to be pleasing for the long texts we're reading
                    mTextToSpeech.setSpeechRate(0.95f);
                    mTextToSpeech.setPitch(0.8f); // Speak a bit lower, like they do on radio and tv
                }
            }
        } catch (InterruptedException e) {
            onError(mContext.getResources().getString(R.string.error_tts_interrupted));
        }
    }

    private void onError(final String message) {
        if (mCallback != null ) {
            mCallback.onError(message);
        }
    }

    @Override
    public void pause() {
        // Pause player and cancel the 'foreground service' state.
        mPlayOnFocusGain = false;

        mTextToSpeech.stop();
        setState(PlaybackStateCompat.STATE_PAUSED);

        // While paused, retain the player instance, but give up audio focus.
        releaseResources(false); //this doesn't actually give up audio focus, but it's identical to LocalPlayback's behavior.
        unregisterAudioNoisyReceiver();
    }

    @Override
    public void seekTo(long position) {
        LogHelper.d(TAG, "seekTo called with ", position);

        throw new RuntimeException("seekTo not implemented in text-to-speech player");
    }

    @Override
    public void setCallback(Callback callback) {
        this.mCallback = callback;
    }

    @Override
    public void setCurrentMediaId(String mediaId) {
        this.mCurrentMediaId = mediaId;
    }

    @Override
    public String getCurrentMediaId() {
        return mCurrentMediaId;
    }

    private void tryToGetAudioFocus() {
        LogHelper.d(TAG, "tryToGetAudioFocus");
        int result =
                mAudioManager.requestAudioFocus(
                        mOnAudioFocusChangeListener,
                        AudioManager.STREAM_MUSIC,
                        AudioManager.AUDIOFOCUS_GAIN);
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mCurrentAudioFocusState = AUDIO_FOCUSED;
        } else {
            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
        }
    }

    private void giveUpAudioFocus() {
        LogHelper.d(TAG, "giveUpAudioFocus");
        if (mAudioManager.abandonAudioFocus(mOnAudioFocusChangeListener)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
        }
    }

    /**
     * Reconfigures the player according to audio focus settings and starts/restarts it. This method
     * starts/restarts the ExoPlayer instance respecting the current audio focus state. So if we
     * have focus, it will play normally; if we don't have focus, it will either leave the player
     * paused or set it to a low volume, depending on what is permitted by the current focus
     * settings.
     */
    private void configurePlayerState() {
        LogHelper.d(TAG, "configurePlayerState. mCurrentAudioFocusState=", mCurrentAudioFocusState);
        if (mCurrentAudioFocusState != AUDIO_FOCUSED) {
            // If we were playing when we lost focus, we need to resume playing.
            if (isPlaying()) {
                mPlayOnFocusGain = true;
            }

            // We don't do audio ducking, just pause if we loose focus.
            pause();

        } else {
            registerAudioNoisyReceiver();
        }
    }

    private final AudioManager.OnAudioFocusChangeListener mOnAudioFocusChangeListener =
            new AudioManager.OnAudioFocusChangeListener() {
                @Override
                public void onAudioFocusChange(int focusChange) {
                    LogHelper.d(TAG, "onAudioFocusChange. focusChange=", focusChange);
                    switch (focusChange) {
                        case AudioManager.AUDIOFOCUS_GAIN:
                            mCurrentAudioFocusState = AUDIO_FOCUSED;
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                            // Audio focus was lost, but it's possible to duck (i.e.: play quietly)
                            mCurrentAudioFocusState = AUDIO_NO_FOCUS_CAN_DUCK;
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                            // Lost audio focus, but will gain it back (shortly), so note whether
                            // playback should resume
                            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
                            break;
                        case AudioManager.AUDIOFOCUS_LOSS:
                            // Lost audio focus, probably "permanently"
                            mCurrentAudioFocusState = AUDIO_NO_FOCUS_NO_DUCK;
                            break;
                    }

                    // Update the player state based on the change
                    configurePlayerState();
                }
            };

    /**
     * Releases resources used by the service for playback.
     *
     * @param releasePlayer Indicates whether the player should also be released
     */
    private void releaseResources(boolean releasePlayer) {
        LogHelper.d(TAG, "releaseResources. releasePlayer=", releasePlayer);

        // Stops and releases player (if requested and available).
        if (releasePlayer) {
            mTextToSpeech.stop();

            mPlayOnFocusGain = false;
        }
    }

    private void registerAudioNoisyReceiver() {
        if (!mAudioNoisyReceiverRegistered) {
            mContext.registerReceiver(mAudioNoisyReceiver, mAudioNoisyIntentFilter);
            mAudioNoisyReceiverRegistered = true;
        }
    }

    private void unregisterAudioNoisyReceiver() {
        if (mAudioNoisyReceiverRegistered) {
            mContext.unregisterReceiver(mAudioNoisyReceiver);
            mAudioNoisyReceiverRegistered = false;
        }
    }
}
