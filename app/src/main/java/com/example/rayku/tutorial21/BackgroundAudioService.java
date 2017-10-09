package com.example.rayku.tutorial21;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaBrowserServiceCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.support.v7.app.NotificationCompat;
import android.text.TextUtils;

import java.io.IOException;
import java.util.List;

public class BackgroundAudioService extends MediaBrowserServiceCompat
        implements MediaPlayer.OnCompletionListener,
        AudioManager.OnAudioFocusChangeListener {

    private MediaPlayer mMediaPlayer;
    private MediaSessionCompat mMediaSessionCompat;


    private BroadcastReceiver mNoisyReceiver = new BroadcastReceiver(){
        @Override
        public void onReceive(Context context, Intent intent) {
            if(mMediaPlayer != null && mMediaPlayer.isPlaying()){
                mMediaPlayer.pause();
            }
        }
    };

    private MediaSessionCompat.Callback mMediaSessionCallback = new MediaSessionCompat.Callback() {
        @Override
        public void onPlay() {
            super.onPlay();
            if (!successfullyRetrievedAudioFocus()) {
                return;
            }

            mMediaSessionCompat.setActive(true);
            setMediaPlaybackState(PlaybackStateCompat.STATE_PLAYING);

            showPlayingNotification();
            mMediaPlayer.start();

        }

        @Override
        public void onPause() {
            super.onPause();
            if(mMediaPlayer.isPlaying()){
                mMediaPlayer.pause();
                setMediaPlaybackState(PlaybackStateCompat.STATE_PAUSED);
                showPausedNotification();

                mMediaSessionCompat.sendSessionEvent("killSeekBarTask", null);
            }
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            super.onPlayFromMediaId(mediaId, extras);

            try {
                AssetFileDescriptor afd = getResources().openRawResourceFd(Integer.valueOf(mediaId));
                if(afd == null) {
                    return;
                }

                try {
                    mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());

                } catch(IllegalStateException e){
                    mMediaPlayer.release();
                    initMediaPlayer();
                    mMediaPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                }

                afd.close();
                initMediaSessionMetadata();

            } catch (IOException e){
                return;
            }

            try {
                mMediaPlayer.prepare();
            } catch(IOException e) {}
        }

        @Override
        public void onPlayFromUri(Uri uri, Bundle extras) {
            super.onPlayFromUri(uri, extras);

            try {
                try {
                    mMediaPlayer.setDataSource(getApplicationContext(), uri);

                } catch(IllegalStateException e){
                    mMediaPlayer.release();
                    initMediaPlayer();
                    mMediaPlayer.setDataSource(getApplicationContext(), uri);
                }

                initMediaSessionMetadata();

            } catch (IOException e){
                return;
            }

            try {
                mMediaPlayer.prepare();
            } catch(IOException e) {}

        }

        @Override
        public void onSeekTo(long pos) {
            super.onSeekTo(pos);
            mMediaPlayer.seekTo((int)pos);
        }

        @Override
        public void onSkipToPrevious() {
            super.onSkipToPrevious();
            mMediaSessionCompat.sendSessionEvent("playPrevSong", null);
        }

        @Override
        public void onSkipToNext() {
            super.onSkipToNext();
            mMediaSessionCompat.sendSessionEvent("playNextSong", null);
        }

    };

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        if(TextUtils.equals(clientPackageName, getPackageName())) {
            return new BrowserRoot(getString(R.string.app_name), null);
        }
        return null;
    }
    //Not important for general audio service, required for class
    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        result.sendResult(null);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        MediaButtonReceiver.handleIntent(mMediaSessionCompat, intent);
        return super.onStartCommand(intent, flags, startId);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initMediaPlayer();
        initMediaSession();
        initNoisyReceiver();
    }

    private void initMediaPlayer(){
        mMediaPlayer = new MediaPlayer();
        mMediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setVolume(1.0f, 1.0f);

        mMediaPlayer.setOnCompletionListener(this);
    }

    private void initMediaSession(){
        ComponentName mediaButtonReceiver = new ComponentName(getApplicationContext(), MediaButtonReceiver.class);
        mMediaSessionCompat = new MediaSessionCompat(getApplicationContext(), "Tag", mediaButtonReceiver, null);

        mMediaSessionCompat.setCallback(mMediaSessionCallback);
        mMediaSessionCompat.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setClass(this, MediaButtonReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, 0);
        mMediaSessionCompat.setMediaButtonReceiver(pendingIntent);

        setSessionToken(mMediaSessionCompat.getSessionToken());
    }

    private void initNoisyReceiver(){
        //Handles headphones coming unplugged. cannot be done through a manifest receiver
        IntentFilter filter = new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        registerReceiver(mNoisyReceiver, filter);
    }

    private boolean successfullyRetrievedAudioFocus(){
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        int result = audioManager.requestAudioFocus(this,
                AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);

        return result == AudioManager.AUDIOFOCUS_GAIN;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch( focusChange ){
            case AudioManager.AUDIOFOCUS_LOSS: {
                if(mMediaPlayer.isPlaying()){
                    mMediaPlayer.stop();

                    mMediaSessionCompat.sendSessionEvent("lostAudioFocus", null);
                    mMediaSessionCompat.sendSessionEvent("killSeekBarTask", null);

                }
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT: {
                mMediaPlayer.pause();
                break;
            }
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: {
                if(mMediaPlayer != null){
                    mMediaPlayer.setVolume(0.3f, 0.3f);
                }
                break;
            }
            case AudioManager.AUDIOFOCUS_GAIN: {
                if(mMediaPlayer != null){
                    if(!mMediaPlayer.isPlaying()){
                        mMediaPlayer.start();
                    }
                    mMediaPlayer.setVolume(1.0f, 1.0f);
                }
                break;
            }
        }
    }

    private void setMediaPlaybackState(int state){
        PlaybackStateCompat.Builder builder = new PlaybackStateCompat.Builder();

        if(state == PlaybackStateCompat.STATE_PLAYING){
            builder.setActions(
                    PlaybackStateCompat.ACTION_PAUSE |
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
        }else{
            builder.setActions(
                    PlaybackStateCompat.ACTION_PLAY |
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT);
        }

        builder.setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 0);
        mMediaSessionCompat.setPlaybackState(builder.build());
    }

    private void showPlayingNotification(){
        NotificationCompat.Builder builder = MediaStyleHelper.from(this, mMediaSessionCompat);
        if( builder == null ) {
            return;
        }

        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));

        builder.setStyle(new NotificationCompat.MediaStyle().setMediaSession(mMediaSessionCompat.getSessionToken()));

        builder.setSmallIcon(R.mipmap.ic_launcher);
        NotificationManagerCompat.from(BackgroundAudioService.this).notify(1, builder.build());
    }

    private void showPausedNotification(){
        NotificationCompat.Builder builder = MediaStyleHelper.from(this, mMediaSessionCompat);
        if( builder == null ) {
            return;
        }

        builder.addAction(new NotificationCompat.Action(android.R.drawable.ic_media_play, "Play",
                MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)));

        builder.setStyle(new NotificationCompat.MediaStyle().setMediaSession(mMediaSessionCompat.getSessionToken()));
        builder.setSmallIcon(R.mipmap.ic_launcher);
        NotificationManagerCompat.from(this).notify(1, builder.build());

    }

    @Override
    public void onCompletion(MediaPlayer mediaPlayer) {
        mMediaSessionCompat.sendSessionEvent("songFinished", null);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.abandonAudioFocus(this);
        unregisterReceiver(mNoisyReceiver);
        mMediaSessionCompat.release();
        NotificationManagerCompat.from(this).cancel(1);
    }

    private void initMediaSessionMetadata() {
        MediaMetadataCompat.Builder builder = new MediaMetadataCompat.Builder();
        //Notification icon in card
        //builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));
        //builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));

        //lock screen icon for pre lollipop
        //builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, BitmapFactory.decodeResource(getResources(), R.mipmap.ic_launcher));
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, "Display Title");
        builder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, "Display Subtitle");
        builder.putLong(MediaMetadataCompat.METADATA_KEY_TRACK_NUMBER, 1);
        builder.putLong(MediaMetadataCompat.METADATA_KEY_NUM_TRACKS, 1);

        mMediaSessionCompat.setMetadata(builder.build());
    }

}
