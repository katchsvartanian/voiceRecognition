/**
 * This file is part of Audioboo, an android program for audio blogging.
 * Copyright (C) 2011 Audioboo Ltd.
 * Copyright (C) 2010,2011 Audioboo Ltd.
 * All rights reserved.
 *
 * Author: Jens Finkhaeuser <jens@finkhaeuser.de>
 *
 * $Id$
 **/

package com.example.voicerecognition;

import java.nio.ByteBuffer;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

import com.example.jni.FLACStreamDecoder;

/**
 * Plays FLAC audio files.
 **/
public class FLACPlayer extends Thread
{
  /***************************************************************************
   * Private constants
   **/

  // Sleep time (in msec) when playback is paused. Will be interrupted, so
  // this can be arbitrarily large.
  private static final int PAUSED_SLEEP_TIME  = 10 * 60 * 1000;


  /***************************************************************************
   * Public data
   **/
  // Flag that keeps the thread running when true.
  public volatile boolean mShouldRun;


  /***************************************************************************
   * Listener that informs the user of errors and end of playback.
   **/
  public static abstract class PlayerListener
  {
    public abstract void onError();
    public abstract void onFinished();
  }


  /***************************************************************************
   * Private data
   **/
  // Context in which this object was created
  private Context           mContext;

  // Stream decoder.
  private FLACStreamDecoder mDecoder;

  // Audio track
  private AudioTrack        mAudioTrack;

  // File path for the output file.
  private String            mPath;

  // Flag; determines whether playback is paused or not.
  private volatile boolean  mPaused;

  // Seek & play position, in msec.
  private volatile long     mSeekPos = -1;
  private volatile long     mPlayPos = 0;

  // Listener.
  private PlayerListener    mListener;


  /***************************************************************************
   * Implementation
   **/
  public FLACPlayer(Context context, String path)
  {
    mContext = context;
    mPath = path;

    mShouldRun = true;
    mPaused = false;
  }



  public void pausePlayback()
  {
    mPaused = true;
    interrupt();
  }



  public void resumePlayback()
  {
    mPaused = false;
    interrupt();
  }



  public void seekTo(long position)
  {
    mSeekPos = position;
    interrupt();
  }



  public long currentPosition()
  {
    return mPlayPos;
  }



  public void setListener(PlayerListener listener)
  {
    mListener = listener;
  }



  public void run()
  {
    // Try to initialize the decoder.
    try {
      mDecoder = new FLACStreamDecoder(mPath);
    } catch (IllegalArgumentException ex) {
      
      if (null != mListener) {
        mListener.onError();
      }
      return;
    }

    // Map channel config & format
    int sampleRate = mDecoder.sampleRate();    
    int channelConfig = mapChannelConfig(mDecoder.channels());
    int format = mapFormat(mDecoder.bitsPerSample());

    // Determine buffer size
    int decoder_bufsize = mDecoder.minBufferSize();
    //Samsung galaxy S2 doesn't work here
    //int playback_bufsize = AudioTrack.getMinBufferSize(sampleRate, channelConfig, format); 
    //int bufsize = Math.max(playback_bufsize, decoder_bufsize);   
    int bufsize = decoder_bufsize; 
    // Create AudioTrack.
    try {
      mAudioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate,
          channelConfig, format, bufsize, AudioTrack.MODE_STREAM);
      mAudioTrack.play();

      ByteBuffer buffer = ByteBuffer.allocateDirect(bufsize);
      byte[] tmpbuf = new byte[bufsize];
      while (mShouldRun) {
        try {
          // If we're paused, just sleep the thread
          if (mPaused) {
            sleep(PAUSED_SLEEP_TIME);
            continue;
          }

          // Seek, if required.
          long seekPos = mSeekPos;
          if (seekPos > 0) {
            int sample = (int) (seekPos / 1000f * sampleRate);
            mDecoder.seekTo(sample);
            mSeekPos = -1;
          }

          // Otherwise, play back a chunk.
          int read = mDecoder.read(buffer, bufsize);
          if (read <= 0) {
            // We're done with playing back!
            break;
          }

          buffer.rewind();
          buffer.get(tmpbuf, 0, read);
          mAudioTrack.write(tmpbuf, 0, read);

          // Also record the current playback position.
          mPlayPos = (long) (mDecoder.position() * 1000f / sampleRate);
        } catch (InterruptedException ex) {
          // We'll pass through to the next iteration. If mShouldRun has
          // been set to false, the thread will terminate. If mPause has
          // been set to true, we'll sleep in the next interation.
        }
      }

      mAudioTrack.stop();
      mAudioTrack.release();
      mAudioTrack = null;
      mDecoder.release();
      mDecoder = null;

      if (null != mListener) {
        mListener.onFinished();
      }

    } catch (IllegalArgumentException ex) {
     
      if (null != mListener) {
        mListener.onError();
      }
    }
  }



  private int mapChannelConfig(int channels)
  {
    switch (channels) {
      case 1:
        return AudioFormat.CHANNEL_CONFIGURATION_MONO;

      case 2:
        return AudioFormat.CHANNEL_CONFIGURATION_STEREO;

      default:
        throw new IllegalArgumentException("Only supporting one or two channels!");
    }
  }



  private int mapFormat(int bits_per_sample)
  {
    switch (bits_per_sample) {
      case 8:
        return AudioFormat.ENCODING_PCM_8BIT;

      case 16:
        return AudioFormat.ENCODING_PCM_16BIT;

      default:
        throw new IllegalArgumentException("Only supporting 8 or 16 bit samples!");
    }
  }
}
