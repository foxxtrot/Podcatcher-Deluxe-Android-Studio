/**
 * Copyright 2012-2015 Kevin Hausmann
 *
 * This file is part of Podcatcher Deluxe.
 *
 * Podcatcher Deluxe is free software: you can redistribute it
 * and/or modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * Podcatcher Deluxe is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Podcatcher Deluxe. If not, see <http://www.gnu.org/licenses/>.
 */

package com.podcatcher.deluxe.services;

import com.podcatcher.deluxe.Podcatcher;
import com.podcatcher.deluxe.SettingsActivity;
import com.podcatcher.deluxe.listeners.OnChangePlaylistListener;
import com.podcatcher.deluxe.listeners.PlayServiceListener;
import com.podcatcher.deluxe.model.EpisodeManager;
import com.podcatcher.deluxe.model.ParserUtils;
import com.podcatcher.deluxe.model.PodcastManager;
import com.podcatcher.deluxe.model.types.Episode;
import com.podcatcher.deluxe.model.types.Podcast;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.AudioManager.OnAudioFocusChangeListener;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnBufferingUpdateListener;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnInfoListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.format.DateFormat;
import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.TimeUnit;

import static android.support.v4.media.session.PlaybackStateCompat.STATE_BUFFERING;
import static android.support.v4.media.session.PlaybackStateCompat.STATE_ERROR;
import static android.support.v4.media.session.PlaybackStateCompat.STATE_PAUSED;
import static android.support.v4.media.session.PlaybackStateCompat.STATE_PLAYING;
import static android.support.v4.media.session.PlaybackStateCompat.STATE_STOPPED;
import static com.podcatcher.deluxe.Podcatcher.AUTHORIZATION_KEY;

/**
 * <p>
 * Play an episode service, wraps media player. This class implements an Android
 * service. It can be used to play back podcast episodes and tries to hide away
 * the complexity of the media player support in Android. All methods should
 * fail gracefully.
 * </p>
 * <p>
 * To use this service, either issue a start command and/or connect (bind) to
 * the service from your activity/fragment. You can also send intent actions
 * to use it. For even more interaction, implement the {@link PlayServiceListener}.
 * This service will never stop itself.
 * </p>
 */
public class PlayEpisodeService extends Service implements OnPreparedListener,
        OnCompletionListener, OnErrorListener, OnBufferingUpdateListener,
        OnInfoListener, OnAudioFocusChangeListener, OnChangePlaylistListener {

    /**
     * Action to send to service to toggle play/pause
     */
    public static final String ACTION_TOGGLE = "com.podcatcher.deluxe.action.TOGGLE";
    /**
     * Action to send to service to play (resume) episode
     */
    public static final String ACTION_PLAY = "com.podcatcher.deluxe.action.PLAY";
    /**
     * Action to send to service to pause episode
     */
    public static final String ACTION_PAUSE = "com.podcatcher.deluxe.action.PAUSE";
    /**
     * Action to send to service to restart the current episode
     */
    public static final String ACTION_PREVIOUS = "com.podcatcher.deluxe.action.PREVIOUS";
    /**
     * Action to send to service to skip to next episode
     */
    public static final String ACTION_SKIP = "com.podcatcher.deluxe.action.SKIP";
    /**
     * Action to send to service to rewind the current episode
     */
    public static final String ACTION_REWIND = "com.podcatcher.deluxe.action.REWIND";
    /**
     * Action to send to service to fast forward the current episode
     */
    public static final String ACTION_FORWARD = "com.podcatcher.deluxe.action.FORWARD";
    /**
     * Action to send to service to stop episode
     */
    public static final String ACTION_STOP = "com.podcatcher.deluxe.action.STOP";

    /**
     * Current episode
     */
    private Episode currentEpisode;
    /**
     * The episode manager handle
     */
    private EpisodeManager episodeManager;
    /**
     * Our MediaPlayer handle
     */
    private MediaPlayer player = new MediaPlayer();
    /**
     * Is the player prepared ?
     */
    private boolean prepared = false;
    /**
     * Is the player currently buffering ?
     */
    private boolean buffering = false;
    /**
     * Are we making a bulk-update to the playlist?
     */
    private boolean updatingPlaylist = false;
    /**
     * Time at which the playback has last been paused,
     * used to determine whether we should rewind on resume.
     */
    private Date lastPaused;

    /**
     * Our audio manager handle
     */
    private AudioManager audioManager;
    /**
     * Our media button broadcast receiver
     */
    private ComponentName mediaButtonReceiver;
    /**
     * Our media session
     */
    private MediaSessionCompat mediaSession;
    /**
     * Our wifi lock
     */
    private WifiLock wifiLock;
    /**
     * Our notification helper
     */
    private PlayEpisodeNotification notification;

    /**
     * Update handler for the notification
     */
    private Handler notificationUpdateHandler = new Handler();

    /**
     * Our notification id (does not really matter)
     */
    private static final int NOTIFICATION_ID = 123;
    /**
     * The amount of milli-seconds used for any fast-forward event
     */
    private static final int SKIP_AMOUNT_FF = (int) TimeUnit.SECONDS.toMillis(30);
    /**
     * The amount of milli-seconds used for any rewind event
     */
    private static final int SKIP_AMOUNT_REW = (int) TimeUnit.SECONDS.toMillis(10);
    /**
     * The amount of milli-seconds playback rewinds on resume (if triggered)
     */
    private static final int REWIND_ON_RESUME_DURATION = SKIP_AMOUNT_REW;
    /**
     * Time elapsed since pause was called that triggers resume on rewind
     */
    private static final long REWIND_ON_RESUME_TRIGGER = TimeUnit.MINUTES.toMillis(30);

    /**
     * The volume we duck playback to
     */
    private static final float DUCK_VOLUME = 0.1f;
    /**
     * Our log tag
     */
    private static final String TAG = "PlayEpisodeService";

    /**
     * The call-back set for the play service listeners
     */
    private Set<PlayServiceListener> listeners = new HashSet<>();

    /**
     * Binder given to clients
     */
    private final IBinder binder = new PlayServiceBinder();

    private List<MediaSessionCompat.QueueItem> currentQueue;

    private int currentQueueIndex;

    /**
     * The binder to return to client.
     */
    public class PlayServiceBinder extends Binder {

        /**
         * @return The service binder.
         */
        public PlayEpisodeService getService() {
            // Return the service, so clients can call public methods
            return PlayEpisodeService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // Get media button receiver
        mediaButtonReceiver = new ComponentName(this, MediaButtonReceiver.class);

        // Get the audio manager handle
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        // Create the wifi lock (not acquired yet)
        wifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                .createWifiLock(WifiManager.WIFI_MODE_FULL, null);

        // Get our episode manager handle
        episodeManager = EpisodeManager.getInstance();
        // We need to listen to playlist updates to update the notification
        episodeManager.addPlaylistListener(this);
        // Our notification helper
        notification = PlayEpisodeNotification.getInstance(this);

        // Add media player listeners
        player.setAudioStreamType(AudioManager.STREAM_MUSIC);
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
        player.setOnInfoListener(this);
        player.setOnBufferingUpdateListener(this);

        configureAudioManager();

        // Prime the Queue so we can activate on Play quickly.
        currentQueueIndex = 0;
        if (episodeManager.isPlaylistEmpty()) {
            // Add all new or downloaded episodes to the initial queue.
            List<Episode> episodes = new ArrayList<>();
            PodcastManager podcastManager = PodcastManager.getInstance();
            if (podcastManager.size() > 0) {
                for (Podcast podcast : podcastManager.getPodcastList()) {
                    episodes.addAll(podcast.getEpisodes(true, true));
                }
            }
            updateQueue(episodes);
        } else {
            updateQueue(episodeManager.getPlaylist());
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // We might have received an action to perform
        if (intent != null && intent.getAction() != null && prepared) {
            // Retrieve the action
            String action = intent.getAction();
            // Go handle the action
            switch (action) {
                case ACTION_TOGGLE:
                    if (player.isPlaying())
                        pause();
                    else
                        resume();
                    break;
                case ACTION_PLAY:
                    resume();
                    break;
                case ACTION_PAUSE:
                    pause();
                    break;
                case ACTION_PREVIOUS:
                    // Store the resume at value because we want to handle the case
                    // where the user invokes this action accidentally.
                    storeResumeAt();

                    seekTo(0);
                    break;
                case ACTION_SKIP:
                    // "Skip" can mean two things here: Move ahead in the current
                    // episode to the stored "resume at" value, or (if that is not
                    // available or actually "behind" us) go to the next item in the
                    // playlist.
                    final int resumeAt = episodeManager.getResumeAt(currentEpisode);

                    if (resumeAt > getCurrentPosition())
                        seekTo(resumeAt);
                    else
                        playNext();
                    break;
                case ACTION_REWIND:
                    rewind();
                    break;
                case ACTION_FORWARD:
                    fastForward();
                    break;
                case ACTION_STOP:
                    stop();
                    break;
            }

            // Alert listeners so the UI can adjust
            for (PlayServiceListener listener : listeners)
                listener.onPlaybackStateChanged();
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        // Unregister listener
        episodeManager.removePlaylistListener(this);
        // Stop the timer
        notificationUpdateHandler.removeCallbacksAndMessages(null);
        mediaSession.release();

        stop();
    }

    /**
     * Register a play service listener.
     *
     * @param listener Listener to add.
     */
    public void addPlayServiceListener(PlayServiceListener listener) {
        listeners.add(listener);
    }

    /**
     * Unregister a play service listener.
     *
     * @param listener Listener to remove.
     */
    public void removePlayServiceListener(PlayServiceListener listener) {
        listeners.remove(listener);
    }

    /**
     * Load and start playback for given episode. Will end any current playback.
     *
     * @param episode Episode to play (not <code>null</code>).
     */
    public void playEpisode(Episode episode) {
        if (episode != null) {
            // Stop and reset the current player and init variables
            stop();

            // Make the new episode our current source
            this.currentEpisode = episode;

            // Start playback for new episode
            try {
                // Play local file
                if (episodeManager.isDownloaded(episode)) {
                    player.setDataSource(episodeManager.getLocalPath(episode));
                }
                // Need to resort to remote file
                else {
                    // We add some request headers to overwrite the default user
                    // agent because this is blocked by some servers
                    final HashMap<String, String> headers = new HashMap<>(2);
                    headers.put(Podcatcher.USER_AGENT_KEY, Podcatcher.USER_AGENT_VALUE);

                    // Also set the authorization header data if needed
                    final String auth = episode.getPodcast().getAuthorization();
                    if (auth != null)
                        headers.put(AUTHORIZATION_KEY, auth);

                    // Actually set the remote source for the playback
                    player.setDataSource(this, Uri.parse(currentEpisode.getMediaUrl()), headers);

                    // We are streaming, so make wifi stay alive
                    wifiLock.acquire();
                    mediaSession.setActive(true);
                }

                updateMetadata();
                player.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
                player.prepareAsync(); // might take long! (for buffering, etc)
            } catch (Throwable throwable) {
                Log.w(TAG, "Prepare/Play failed for episode: " + episode, throwable);
            }
        }
    }

    private void updateMetadata() {
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentEpisode.getPodcast().getName())
                .putString(MediaMetadataCompat.METADATA_KEY_DATE, DateFormat.getDateFormat(this).format(currentEpisode.getPubDate()))
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, currentEpisode.getPodcast().getLogoUrl())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentEpisode.getName())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentEpisode.getDuration())
                .build());
    }

    /**
     * Play the next episode in the playlist. Does nothing if there is none. If
     * this is called while the current episode is still set, this episode will
     * be removed from the playlist. The current episode (if set) also
     * influences the selection of the next episode: if in the playlist (e.g. as
     * item number #4) the next episode is selected relative to the current one
     * (#5). That said, playback will jump to item #1 if the current episode is
     * either not in the playlist or is at the end of the playlist.
     */
    public void playNext() {
        Episode next = getEpisodeFromQueue(++currentQueueIndex);
        playEpisode(next);
    }


    private Episode getEpisodeFromQueue(int queueIndex) {
        String mediaId = currentQueue.get(queueIndex).getDescription().getMediaId();
        return PodcastManager.getInstance().findEpisodeForUrl(mediaId);
    }

    @Override
    public void onPlaylistChanged() {
        // Update status bar notifications
        rebuildNotification();
    }

    private MediaMetadataCompat episodeToMediaMetadata(Episode episode) {
        return new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, episode.getMediaUrl())
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, episode.getName())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, episode.getPodcast().getName())
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI,
                        episode.getPodcast().getLogoUrl())
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, episode.getDuration() * 1000)
                .build();
    }

    /**
     * Pause current playback.
     */
    public void pause() {
        if (prepared && player.isPlaying()) {
            player.pause();
            storeResumeAt();

            this.lastPaused = new Date();
            stopNotificationUpdater();
            updatePlayState(STATE_PAUSED);
            rebuildNotification();
        }
    }

    /**
     * Resume to play current episode.
     */
    public void resume() {
        if (prepared && !player.isPlaying()) {
            // We might want to rewind a bit after a long pause
            if (lastPaused != null &&
                    new Date().getTime() - lastPaused.getTime() > REWIND_ON_RESUME_TRIGGER)
                seekTo(getCurrentPosition() - REWIND_ON_RESUME_DURATION);

            player.start();

            startNotificationUpdater();
            updatePlayState(STATE_PLAYING);
            rebuildNotification();
        }
    }

    /**
     * Stop playback and reset player.
     * Will also store resume playback information if appropriate.
     */
    public void stop() {
        if (player.isPlaying())
            player.stop();

        storeResumeAt();
        // This will also take care of the notification etc.
        reset();
    }

    /**
     * Seek player to given location in media file.
     *
     * @param msecs Milliseconds from the start to seek to. Giving any
     *              value <=0 makes the player jump to the beginning.
     */
    public void seekTo(int msecs) {
        if (prepared && msecs <= getDuration()) {
            player.seekTo(msecs > 0 ? msecs : 0);

            startForeground(NOTIFICATION_ID,
                    notification.updateProgress(getCurrentPosition(), getDuration()));
        }
    }

    /**
     * Rewind the playback by XX secs.
     */
    public void rewind() {
        final int newPosition = getCurrentPosition() - SKIP_AMOUNT_REW;
        seekTo(newPosition <= 0 ? 0 : newPosition);
    }

    /**
     * Fast forward XX secs.
     */
    public void fastForward() {
        final int newPosition = getCurrentPosition() + SKIP_AMOUNT_FF;
        seekTo(newPosition < getDuration() ? newPosition : getDuration());
    }

    /**
     * @return Whether the player is currently playing.
     */
    public boolean isPlaying() {
        return player.isPlaying();
    }

    /**
     * @return Whether the service is currently preparing, i.e. buffering data
     * and will start playing asap.
     */
    public boolean isPreparing() {
        return currentEpisode != null && !prepared;
    }

    /**
     * @return Whether the service is prepared, i.e. any episode is loaded.
     */
    public boolean isPrepared() {
        return prepared;
    }

    /**
     * @return Whether the service is currently buffering data.
     */
    public boolean isBuffering() {
        return buffering || isPreparing();
    }

    /**
     * Checks whether the currently loaded episode is equal to the one given.
     * The check will be true regardless of whether the episode has been actually
     * prepared or not.
     *
     * @param episode Episode to check for.
     * @return true iff given episode is loaded (or loading), false otherwise.
     */
    public boolean isLoadedEpisode(Episode episode) {
        return currentEpisode != null && currentEpisode.equals(episode);
    }

    /**
     * @return The episode currently loaded (might be <code>null</code>).
     */
    public Episode getCurrentEpisode() {
        return currentEpisode;
    }

    public MediaSessionCompat getMediaSession() {
        return mediaSession;
    }

    /**
     * @return Current position of playback in milliseconds from media start.
     * Does not throw any exception but returns at least zero.
     */
    public int getCurrentPosition() {
        return !prepared ? 0 : player.getCurrentPosition();
    }

    /**
     * @return Duration of media element in milliseconds. Does not throw any
     * exception but returns at least zero.
     */
    public int getDuration() {
        return !prepared ? 0 : player.getDuration();
    }

    @Override
    public void onPrepared(MediaPlayer mediaPlayer) {
        this.prepared = true;
        // (Real) duration is now available, update episode metadata information
        episodeManager.updateDuration(currentEpisode,
                (int) TimeUnit.MILLISECONDS.toSeconds(getDuration()));

        // Try to get audio focus
        int result = audioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN);


        // Only start playback if focus is granted
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            // So we have audio focus and we tell the audio manager all the details
            // about our playback and that it should route media buttons to us
            updatePlayState(STATE_PLAYING);

            // Go start and show the notification
            player.seekTo(episodeManager.getResumeAt(currentEpisode) - REWIND_ON_RESUME_DURATION);
            player.start();
            startForeground(NOTIFICATION_ID, notification.build(currentEpisode, mediaSession));
            startNotificationUpdater();

            // Alert the listeners
            for (PlayServiceListener listener : listeners)
                listener.onPlaybackStarted();
        } else
            onError(mediaPlayer, 0, 0);
    }

    @Override
    public void onBufferingUpdate(MediaPlayer mp, int percent) {
        // Send buffer information to listeners
        for (PlayServiceListener listener : listeners)
            listener.onBufferUpdate(getDuration() * percent / 100);

        // This will fix the case where the media player does not send a
        // "BUFFERING_END" event via onInfo(), we will simply create our own:
        if (buffering && getDuration() > 0 &&
                percent > getCurrentPosition() / (float) getDuration() * 100)
            onInfo(player, MediaPlayer.MEDIA_INFO_BUFFERING_END, 0);
    }

    @Override
    public boolean onInfo(MediaPlayer mp, int what, int extra) {
        switch (what) {
            case MediaPlayer.MEDIA_INFO_BUFFERING_START:
                buffering = true;
                updatePlayState(STATE_BUFFERING);

                for (PlayServiceListener listener : listeners)
                    listener.onStopForBuffering();

                break;
            case MediaPlayer.MEDIA_INFO_BUFFERING_END:
                buffering = false;
                updatePlayState(player.isPlaying() ? STATE_PLAYING : STATE_PAUSED);

                for (PlayServiceListener listener : listeners)
                    listener.onResumeFromBuffering();

                break;
        }

        return false;
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        updatePlayState(STATE_STOPPED);

        // Mark the episode old (needs to be done before resetting the service!)
        episodeManager.setState(currentEpisode, true);
        episodeManager.setResumeAt(currentEpisode, null);
        // Delete download if auto delete is enabled
        if (shouldAutoDeleteCompletedEpisode())
            episodeManager.deleteDownload(currentEpisode);

        // If there is another episode on the playlist, play it.
        if (!episodeManager.isPlaylistEmptyBesides(currentEpisode))
            playNext();
            // If not, stop
        else {
            // Pop the episode off the playlist
            episodeManager.removeFromPlaylist(currentEpisode);
            // Not calling stop() because that would overwrite
            // the resume playback info and we are stopped anyway.
            reset();
        }

        // Alert listeners
        if (listeners.size() > 0)
            for (PlayServiceListener listener : listeners)
                listener.onPlaybackComplete();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        updatePlayState(STATE_ERROR);

        // If there is another downloaded episode in the playlist, play it.
        final SortedMap<Integer, Episode> playlist = episodeManager.getDownloadedPlaylist();
        if (!(playlist.isEmpty() || (playlist.size() == 1 && playlist.values().contains(
                currentEpisode)))) {
            // Find the current episode's position in the complete playlist and
            // remove it from the playlist of downloaded episodes item since we
            // will not play that one in any case and we know there is at least
            // one other episode more.
            final int currentEpisodePosition = episodeManager.getPlaylistPosition(currentEpisode);
            playlist.remove(currentEpisodePosition);

            // Play the episode with the lowest key or the one preceding the
            // current episode's position
            Episode next = playlist.get(playlist.firstKey());

            if (currentEpisodePosition > 0 && currentEpisodePosition < playlist.lastKey()) {
                SortedMap<Integer, Episode> tail = playlist.tailMap(currentEpisodePosition);
                next = tail.get(tail.firstKey());
            }

            playEpisode(next);
        }
        // If there is anybody listening, alert and let them decide what to do
        // next, if not we reset and possibly stop ourselves
        else if (listeners.size() > 0)
            for (PlayServiceListener listener : listeners)
                listener.onError();
        else
            stop();

        Log.w(TAG, "Media player send error: " + what + "/" + extra);
        return true;
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN:
                player.setVolume(1.0f, 1.0f);
                break;

            case AudioManager.AUDIOFOCUS_LOSS:
                // Lost focus for an unbounded amount of time: stop playback and
                // reset media player
                stop();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // Lost focus for a short time, but we have to stop playback.
                // We don't release the media player because playback is likely to resume
                pause();
                break;

            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                // Lost focus for a short time, but it's ok to keep playing
                // at an attenuated level
                player.setVolume(DUCK_VOLUME, DUCK_VOLUME);
                break;
        }
    }

    /**
     * Reset the service to creation state.
     */
    private void reset() {
        // Remove notification
        stopForeground(true);
        stopNotificationUpdater();

        // Reset variables
        this.currentEpisode = null;
        this.prepared = false;
        this.buffering = false;
        this.lastPaused = null;

        // Release resources
        audioManager.abandonAudioFocus(this);
        if (wifiLock.isHeld())
            wifiLock.release();

        mediaSession.setActive(false);

        // Reset player
        player.reset();
    }

    private void storeResumeAt() {
        if (currentEpisode != null) {
            final int position = player.getCurrentPosition();
            final int duration = player.getDuration();

            // Only set resume at time if it is actually interesting, i.e. not
            // at the beginning or very close to the end (position == duration
            // might not be true even after player called onCompletion)
            episodeManager.setResumeAt(currentEpisode,
                    position == 0 || position / (float) duration > 0.99 ? null : position);
        }
    }

    private void startNotificationUpdater() {
        // Remove all runnables and post a fresh one
        notificationUpdateHandler.removeCallbacksAndMessages(null);
        notificationUpdateHandler.post(new Runnable() {
            @Override
            public void run() {
                startForeground(NOTIFICATION_ID,
                        notification.updateProgress(getCurrentPosition(), getDuration()));

                notificationUpdateHandler.postDelayed(this, TimeUnit.SECONDS.toMillis(1));
            }
        });
    }

    private void stopNotificationUpdater() {
        notificationUpdateHandler.removeCallbacksAndMessages(null);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void rebuildNotification() {
        if (isPrepared() && currentEpisode != null) {
            Notification note = notification.build(currentEpisode, !player.isPlaying(), getCurrentPosition(),
                    getDuration(), mediaSession);

            startForeground(NOTIFICATION_ID, note);
        }
    }

    private void configureAudioManager() {
        // Build the PendingIntent for the remote control client
        Intent mediaButtonIntent = new Intent(Intent.ACTION_MEDIA_BUTTON);
        mediaButtonIntent.setComponent(mediaButtonReceiver);
        PendingIntent mediaPendingIntent =
                PendingIntent.getBroadcast(getApplicationContext(), 0, mediaButtonIntent, 0);

        // Create and register the MediaSession
        mediaSession = new MediaSessionCompat(this, "PlayEpisodeService", mediaButtonReceiver,
                mediaPendingIntent);
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setCallback(new MediaSessionCallbacks());
    }

    private void updatePlayState(int state) {
        updateMetadata();
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, getCurrentPosition(), 1.0f)
                .setActions(getAvailableActions())
                .build());
    }

    private long getAvailableActions() {
        long actions = PlaybackStateCompat.ACTION_PLAY
                | PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID;
        if (episodeManager.isPlaylistEmpty()) {
            return actions;
        }
        if (isPlaying()) {
            actions |= PlaybackStateCompat.ACTION_PAUSE;
        }
        if (currentQueueIndex > 0) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS;
        }
        if (currentQueueIndex < episodeManager.getPlaylistSize() - 1) {
            actions |= PlaybackStateCompat.ACTION_SKIP_TO_NEXT;
        }
        return actions;
    }

    private boolean shouldAutoDeleteCompletedEpisode() {
        return PreferenceManager.getDefaultSharedPreferences(this)
                .getBoolean(SettingsActivity.KEY_AUTO_DELETE, false);
    }

    private void updateQueue(List<Episode> episodes) {
        List<MediaSessionCompat.QueueItem> items = new ArrayList<>();
        int count = 0;
        for (Episode episode : episodes) {
            items.add(new MediaSessionCompat.QueueItem(episodeToMediaMetadata(episode).getDescription(),
                    count++));
        }
        currentQueue = items;
        mediaSession.setQueue(currentQueue);
    }

    private final class MediaSessionCallbacks extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            if (isPrepared() && !isPlaying()) {
                resume();
            } else {
                playEpisode(getEpisodeFromQueue(currentQueueIndex));
            }
        }

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            Episode episodeToPlay;
            List<Episode> newQueue;
            if (mediaId.equals(PodcastBrowserService.CURRENT_PLAYLIST_MEDIA_ID)) {
                newQueue = episodeManager.getPlaylist();
                episodeToPlay = newQueue.get(0);
                currentQueueIndex = 0;
            } else {
                boolean filter = true;
                if (mediaId.contains(PodcastBrowserService.SHOW_ALL_SUFFIX)) {
                    mediaId = mediaId.substring(0, mediaId.indexOf(PodcastBrowserService.SHOW_ALL_SUFFIX));
                    filter = false;
                }
                episodeToPlay = PodcastManager.getInstance().findEpisodeForUrl(mediaId);
                newQueue = episodeToPlay.getPodcast().getEpisodes(filter, filter);
                currentQueueIndex = newQueue.indexOf(episodeToPlay);
            }
            updateQueue(newQueue);
            playEpisode(episodeToPlay);
        }

        @Override
        public void onSkipToQueueItem(long queueId) {
            for (MediaSessionCompat.QueueItem item : currentQueue) {
                if (item.getQueueId() == queueId) {
                    currentQueueIndex = currentQueue.indexOf(item);
                    String mediaId = item.getDescription().getMediaId();
                    playEpisode(PodcastManager.getInstance().findEpisodeForUrl(mediaId));
                }
            }
        }

        @Override
        public void onPause() {
            pause();
        }

        @Override
        public void onSkipToNext() {
            if (currentQueueIndex + 1 < currentQueue.size()) {
                playEpisode(getEpisodeFromQueue(++currentQueueIndex));
            }
        }

        @Override
        public void onSkipToPrevious() {
            if (currentQueueIndex > 0) {
                playEpisode(getEpisodeFromQueue(--currentQueueIndex));
            }
        }

        @Override
        public void onFastForward() {
            fastForward();
        }

        @Override
        public void onRewind() {
            rewind();
        }

        @Override
        public void onStop() {
            stop();
        }

        @Override
        public void onSeekTo(long pos) {
            seekTo((int) pos);
        }
    }
}
