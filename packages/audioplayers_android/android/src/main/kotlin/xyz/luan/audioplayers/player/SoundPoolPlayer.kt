package xyz.luan.audioplayers.player

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import xyz.luan.audioplayers.AudioContextAndroid
import xyz.luan.audioplayers.Logger
import xyz.luan.audioplayers.source.Source
import xyz.luan.audioplayers.source.UrlSource
import java.util.Collections.synchronizedMap

// TODO(luan): make this configurable
const val MAX_STREAMS = 100

class SoundPoolPlayer(
    val wrappedPlayer: WrappedPlayer,
    private val soundPoolWrapper: SoundPoolWrapper,
) : Player {

    /** The id of the sound of source which will be played */
    var soundId: Int? = null

    /** The id of the stream / player */
    private var streamId: Int? = null
    
    private var audioContext = wrappedPlayer.context

    val urlSource: UrlSource?
        get() = wrappedPlayer.source as? UrlSource

    /** The android sound pool */
    private lateinit var soundPool: SoundPool

    init {
        var tmpSoundPool = soundPoolWrapper.soundPools[audioContext]
        if (tmpSoundPool == null) {
            soundPoolWrapper.createSoundPool(MAX_STREAMS, audioContext)
            tmpSoundPool = soundPoolWrapper.soundPools[audioContext]
        }
        if (tmpSoundPool != null) {
            soundPool = tmpSoundPool
        }
    }

    override fun stop() {
        streamId?.let {
            soundPool.stop(it)
            streamId = null
        }
    }

    override fun release() {
        stop()
        val soundId = this.soundId ?: return
        val urlSource = this.urlSource ?: return

        synchronized(soundPoolWrapper.urlToPlayers) {
            val playersForSoundId = soundPoolWrapper.urlToPlayers[urlSource] ?: return
            if (playersForSoundId.singleOrNull() === this) {
                soundPoolWrapper.urlToPlayers.remove(urlSource)
                soundPool.unload(soundId)
                soundPoolWrapper.soundIdToPlayer.remove(soundId)
                this.soundId = null
                Logger.info("unloaded soundId $soundId")
            } else {
                // This is not the last player using the soundId, just remove it from the list.
                playersForSoundId.remove(this)
            }

        }
    }

    override fun pause() {
        streamId?.let { soundPool.pause(it) }
    }

    override fun updateContext(context: AudioContextAndroid) {
        audioContext = context
        soundPoolWrapper.createSoundPool(MAX_STREAMS, context)
        val tmpSoundPool = soundPoolWrapper.soundPools[audioContext]
        if (tmpSoundPool != null) {
            soundPool = tmpSoundPool
        }
    }

    override fun setSource(source: Source) {
        source.setForSoundPool(this)
    }

    fun setUrlSource(urlSource: UrlSource) {
        if (soundId != null) {
            release()
        }
        synchronized(soundPoolWrapper.urlToPlayers) {
            val urlPlayers = soundPoolWrapper.urlToPlayers.getOrPut(urlSource) { mutableListOf() }
            val originalPlayer = urlPlayers.firstOrNull()

            if (originalPlayer != null) {
                // Sound has already been loaded - reuse the soundId.
                val prepared = originalPlayer.wrappedPlayer.prepared
                wrappedPlayer.prepared = prepared
                soundId = originalPlayer.soundId
                Logger.info("Reusing soundId $soundId for $urlSource is prepared=$prepared $this")
            } else {
                // First one for this URL - load it.
                val start = System.currentTimeMillis()

                wrappedPlayer.prepared = false
                Logger.info("Fetching actual URL for $urlSource")
                val actualUrl = urlSource.getAudioPathForSoundPool()
                Logger.info("Now loading $actualUrl")
                val intSoundId = soundPool.load(actualUrl, 1)
                soundPoolWrapper.soundIdToPlayer[intSoundId] = this
                soundId = intSoundId

                Logger.info("time to call load() for $urlSource: ${System.currentTimeMillis() - start} player=$this")
            }
            urlPlayers.add(this)
        }
    }

    override fun setVolume(volume: Float) {
        streamId?.let { soundPool.setVolume(it, volume, volume) }
    }

    override fun setRate(rate: Float) {
        streamId?.let { soundPool.setRate(it, rate) }
    }

    override fun setLooping(looping: Boolean) {
        streamId?.let { soundPool.setLoop(it, looping.loopModeInteger()) }
    }

    // Cannot get duration for Sound Pool
    override fun getDuration() = null

    // Cannot get current position for Sound Pool
    override fun getCurrentPosition() = null

    override fun isActuallyPlaying() = false

    override fun seekTo(position: Int) {
        if (position == 0) {
            streamId?.let {
                stop()
                if (wrappedPlayer.playing) {
                    soundPool.resume(it)
                }
            }
        } else {
            unsupportedOperation("seek")
        }
    }

    override fun start() {
        val streamId = streamId
        val soundId = soundId

        if (streamId != null) {
            soundPool.resume(streamId)
        } else if (soundId != null) {
            this.streamId = soundPool.play(
                soundId,
                wrappedPlayer.volume,
                wrappedPlayer.volume,
                0,
                wrappedPlayer.isLooping.loopModeInteger(),
                wrappedPlayer.rate,
            )
        }
    }

    override fun prepare() {
        // sound pool automatically prepares when source URL is set
    }

    override fun reset() {
        // TODO(luan): what do I do here?
    }

    override fun isLiveStream() = false

    /** Integer representation of the loop mode used by Android */
    private fun Boolean.loopModeInteger(): Int = if (this) -1 else 0

    private fun unsupportedOperation(message: String): Nothing {
        throw UnsupportedOperationException("LOW_LATENCY mode does not support: $message")
    }
}

class SoundPoolWrapper {
    var soundPools = HashMap<AudioContextAndroid, SoundPool>()

    /** For the onLoadComplete listener, track which sound id is associated with which player. An entry only exists until
     * it has been loaded.
     */
    val soundIdToPlayer: MutableMap<Int, SoundPoolPlayer> = synchronizedMap(mutableMapOf<Int, SoundPoolPlayer>())

    /** This is to keep track of the players which share the same sound id, referenced by url. When a player release()s, it
     * is removed from the associated player list. The last player to be removed actually unloads() the sound id and then
     * the url is removed from this map.
     */
    val urlToPlayers: MutableMap<UrlSource, MutableList<SoundPoolPlayer>> =
        synchronizedMap(mutableMapOf<UrlSource, MutableList<SoundPoolPlayer>>())

    fun createSoundPool(maxStreams: Int, audioContext: AudioContextAndroid) {
        if (!soundPools.containsKey(audioContext)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val attrs = audioContext.buildAttributes()
                soundPools[audioContext] = SoundPool.Builder()
                    .setAudioAttributes(attrs)
                    .setMaxStreams(maxStreams)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                soundPools[audioContext] = SoundPool(maxStreams, AudioManager.STREAM_MUSIC, 0)
            }
        }
    }

    init {
        createSoundPool(
            MAX_STREAMS,
            AudioContextAndroid(
                isSpeakerphoneOn = false,
                stayAwake = false,
                contentType = AudioAttributes.CONTENT_TYPE_MUSIC,
                usageType = AudioAttributes.USAGE_GAME,
                audioFocus = null
            ),
        )
        for (soundPoolEntry in soundPools) {
            soundPoolEntry.value.setOnLoadCompleteListener { _, sampleId, _ ->
                Logger.info("Loaded $sampleId")
                val loadingPlayer = soundIdToPlayer[sampleId]
                val urlSource = loadingPlayer?.urlSource
                if (urlSource != null) {
                    soundIdToPlayer.remove(loadingPlayer.soundId)
                    // Now mark all players using this sound as not loading and start them if necessary
                    synchronized(urlToPlayers) {
                        val urlPlayers = urlToPlayers[urlSource] ?: listOf()
                        for (player in urlPlayers) {
                            Logger.info("Marking $player as loaded")
                            player.wrappedPlayer.prepared = true
                            if (player.wrappedPlayer.playing) {
                                Logger.info("Delayed start of $player")
                                player.start()
                            }
                        }
                    }
                }
            }
        }
    }

    fun dispose() {
        for (soundPoolEntry in soundPools) {
            soundPoolEntry.value.release()
        }
        soundPools.clear()
        soundIdToPlayer.clear()
        urlToPlayers.clear()
    }
}
