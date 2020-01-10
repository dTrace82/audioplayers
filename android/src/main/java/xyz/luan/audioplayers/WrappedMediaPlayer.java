package xyz.luan.audioplayers;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.rtp.AudioStream;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import java.io.IOException;

import java.io.IOException;

public class WrappedMediaPlayer extends Player implements MediaPlayer.OnPreparedListener, MediaPlayer.OnCompletionListener {

    private String playerId;

    private String url;
    private double volume = 1.0;
    private boolean respectSilence;
    private boolean stayAwake;
    private ReleaseMode releaseMode = ReleaseMode.RELEASE;

    private boolean released = true;
    private boolean prepared = false;
    private boolean playing = false;

    private int shouldSeekTo = -1;

    private MediaPlayer player;
    private AudioplayersPlugin ref;

    WrappedMediaPlayer(AudioplayersPlugin ref, String playerId) {
        this.ref = ref;
        this.playerId = playerId;
    }

    /**
     * Setter methods
     */

    @Override
    void setUrl(String url, boolean isLocal) {
        if (!objectEquals(this.url, url)) {
            this.url = url;
            if (this.released) {
                this.player = createPlayer();
                this.released = false;
            } else if (this.prepared) {
                this.player.reset();
                this.prepared = false;
            }

            this.setSource(url);
            this.player.setVolume((float) volume, (float) volume);
            this.player.setLooping(this.releaseMode == ReleaseMode.LOOP);
            this.player.prepareAsync();
        }
    }

    @Override
    void setVolume(double volume) {
        if (this.volume != volume) {
            this.volume = volume;
            if (!this.released) {
                this.player.setVolume((float) volume, (float) volume);
            }
        }
    }

    @Override
    void configAttributes(boolean respectSilence, boolean stayAwake, Context context) {
        if (this.respectSilence != respectSilence) {
            this.respectSilence = respectSilence;
            if (!this.released) {
                setAttributes(player);
            }
        }
        if (this.stayAwake != stayAwake) {
            this.stayAwake = stayAwake;
            if (!this.released && this.stayAwake) {
                this.player.setWakeMode(context, PowerManager.PARTIAL_WAKE_LOCK);
            }
        }
    }

    @Override
    void setReleaseMode(ReleaseMode releaseMode) {
        if (this.releaseMode != releaseMode) {
            this.releaseMode = releaseMode;
            if (!this.released) {
                this.player.setLooping(releaseMode == ReleaseMode.LOOP);
            }
        }
    }

    /**
     * Getter methods
     */

    @Override
    int getDuration() {
        return this.player.getDuration();
    }

    @Override
    int getCurrentPosition() {
        return this.player.getCurrentPosition();
    }

    @Override
    String getPlayerId() {
        return this.playerId;
    }

    @Override
    boolean isActuallyPlaying() {
        return this.playing && this.prepared;
    }

    /**
     * Playback handling methods
     */

    public void onAudioFocusChange(int focusChange) {
    }

    private AudioFocusRequest audioFocusRequest;
    private int savedAudioMode;


    @Override
    void play() {
        AudioManager audioManager = (AudioManager)this.ref.getActivity().getSystemService(Context.AUDIO_SERVICE);

        this.savedAudioMode = audioManager.getMode();
        if (!this.playing) {

            // CHECK VERSIONS
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                AudioAttributes mPlaybackAttributes = new AudioAttributes.Builder()
                                                          .setUsage(AudioAttributes.USAGE_MEDIA)
                                                          .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                                          .build();
                this.audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                                             .setAudioAttributes(mPlaybackAttributes)
                                             .setAcceptsDelayedFocusGain(true)
                                             .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                                                 @Override
                                                 public void onAudioFocusChange(int i) {
                                                 }
                                             })
                                             .build();
                int res = audioManager.requestAudioFocus(this.audioFocusRequest);
            } else {
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
            }

            this.playing = true;
            if (this.released) {
                this.released = false;
                this.player = createPlayer();
                this.setSource(url);
                this.player.prepareAsync();
            } else if (this.prepared) {
                this.player.start();
                this.ref.handleIsPlaying(this);
            }
        }
    }

    @Override
    void stop() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioManager audioManager = (AudioManager)this.ref.getActivity().getSystemService(Context.AUDIO_SERVICE);
            audioManager.abandonAudioFocusRequest(this.audioFocusRequest);
        } else {
            AudioManager audioManager = (AudioManager)this.ref.getActivity().getSystemService(Context.AUDIO_SERVICE);
            audioManager.setMode(this.savedAudioMode);
            audioManager.abandonAudioFocusRequest(null);
        }
        if (this.released) {
            return;
        }

        if (releaseMode != ReleaseMode.RELEASE) {
            if (this.playing) {
                this.playing = false;
                this.player.pause();
                this.player.seekTo(0);
            }
        } else {
            this.release();
        }
    }

    @Override
    void release() {
        if (this.released) {
            return;
        }

        if (this.playing) {
            this.player.stop();
        }
        this.player.reset();
        this.player.release();
        this.player = null;

        this.prepared = false;
        this.released = true;
        this.playing = false;
    }

    @Override
    void pause() {
        if (this.playing) {
            this.playing = false;
            this.player.pause();
        }
    }

    // seek operations cannot be called until after
    // the player is ready.
    @Override
    void seek(int position) {
        if (this.prepared)
            this.player.seekTo(position);
        else
            this.shouldSeekTo = position;
    }

    /**
     * MediaPlayer callbacks
     */

    @Override
    public void onPrepared(final MediaPlayer mediaPlayer) {
        this.prepared = true;
        ref.handleDuration(this);
        if (this.playing) {
            this.player.start();
            ref.handleIsPlaying(this);
        }
        if (this.shouldSeekTo >= 0) {
            this.player.seekTo(this.shouldSeekTo);
            this.shouldSeekTo = -1;
        }
    }

    @Override
    public void onCompletion(final MediaPlayer mediaPlayer) {
        if (releaseMode != ReleaseMode.LOOP) {
            this.stop();
        }
        ref.handleCompletion(this);
    }

    /**
     * Internal logic. Private methods
     */

    private MediaPlayer createPlayer() {
        MediaPlayer player = new MediaPlayer();
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        setAttributes(player);
        player.setVolume((float) volume, (float) volume);
        player.setLooping(this.releaseMode == ReleaseMode.LOOP);
        return player;
    }

    private void setSource(String url) {
        try {
            this.player.setDataSource(url);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to access resource", ex);
        }
    }

    @SuppressWarnings("deprecation")
    private void setAttributes(MediaPlayer player) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(respectSilence ? AudioAttributes.USAGE_NOTIFICATION_RINGTONE : AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            );
        } else {
            // This method is deprecated but must be used on older devices
            // player.setAudioStreamType(respectSilence ? AudioManager.STREAM_MUSIC : AudioManager.STREAM_MUSIC);
            //TEST
            player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        }
    }

}
