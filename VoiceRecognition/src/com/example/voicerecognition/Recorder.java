/**
 * This file is part of Audioboo, an android program for audio blogging.
 * Copyright (C) 2010,2011 Audioboo Ltd.
 * All rights reserved.
 *
 * Author: Jens Finkhaeuser <jens@finkhaeuser.de>
 *
 * $Id$
 **/

package com.example.voicerecognition;

import java.lang.ref.WeakReference;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;



/**
 * Records Boos. This class uses FLACRecorder to record individual FLAC files
 * for a Boo.
 *
 * Recorder is a leaky abstraction of FLACRecorder; FLACRecorder's message
 * codes are re-used and so is FLACRecorder.Amplitudes.
 **/
public class Recorder
{
  /***************************************************************************
   * Public constants
   **/
  // Message ID for end of recording; at this point stats are finalized.
  // XXX Note that the message ID must be at least one higher than the highest
  // FLACRecorder message ID.
  public static final int MSG_END_OF_RECORDING  = FLACRecorder.MSG_AMPLITUDES + 1;

  //For recording FLAC files.
  public FLACRecorder            mFLACRecorder;

  
  /***************************************************************************
   * Private data
   **/
  // Context
  private WeakReference<Context>  mContext;


  // Handler for messages sent by Recorder
  private Handler                 mUpchainHandler;
  // Internal handler to hand to FLACRecorder.
  private Handler                 mInternalHandler;  

  // Overall recording metadata
  private FLACRecorder.Amplitudes mAmplitudes;
  private FLACRecorder.Amplitudes mLastAmplitudes;


  /***************************************************************************
   * Implementation
   **/
  public Recorder(Context context, Handler handler)
  {
    mContext = new WeakReference<Context>(context);
    
    mUpchainHandler = handler;
    mInternalHandler = new Handler(new Handler.Callback()
    {
      public boolean handleMessage(Message m)
      {
        switch (m.what) {
          case FLACRecorder.MSG_AMPLITUDES:
            FLACRecorder.Amplitudes amp = (FLACRecorder.Amplitudes) m.obj;

            // Create a copy of the amplitude in mLastAmplitudes; we'll use
            // that when we restart recording to calculate the position
            // within the Boo.
            mLastAmplitudes = new FLACRecorder.Amplitudes(amp);

            if (null != mAmplitudes) {
              amp.mPosition += mAmplitudes.mPosition;
            }
            mUpchainHandler.obtainMessage(FLACRecorder.MSG_AMPLITUDES,
                amp).sendToTarget();
            return true;


          case MSG_END_OF_RECORDING:
            // Update stats - at this point, mLastAmp should really be the last set
            // of amplitudes we got from the recorder.
            if (null == mAmplitudes) {
              mAmplitudes = mLastAmplitudes;
            }
            else {
              mAmplitudes.accumulate(mLastAmplitudes);
            }


            mUpchainHandler.obtainMessage(MSG_END_OF_RECORDING).sendToTarget();
            return true;


          default:
            mUpchainHandler.obtainMessage(m.what, m.obj).sendToTarget();
            return true;
        }
      }
    });
  }



  public void start(String fileName)
  {
    // Every time we start recording, we create a new recorder instance, and
    // record to a new file.
    // That means if there's still a recorder instance running (shouldn't
    // happen!), we'll kill it.
    if (mFLACRecorder != null) {
      stop();
    }

    // Start recording!
    mFLACRecorder = new FLACRecorder( fileName, mInternalHandler);
    mFLACRecorder.start();
    mFLACRecorder.resumeRecording();
  }



  public void stop()
  {
    if (mFLACRecorder == null ) {
      // We're done.
      return;
    }
    // Pause recording & kill recorder
    mFLACRecorder.pauseRecording();
    //Free memory!
    mFLACRecorder.mShouldRun = false;
    mFLACRecorder.interrupt();
    try {
    	mFLACRecorder.join();
    } catch (InterruptedException ex) {
      // pass
    }
    mFLACRecorder = null;
  }



  public boolean isRecording()
  {
    if (null == mFLACRecorder) {
      return false;
    }
    return mFLACRecorder.isRecording();
  }



  public double getDuration()
  {
    if (null == mAmplitudes) {
      return 0f;
    }
    return mAmplitudes.mPosition / 1000.0;
  }



  public FLACRecorder.Amplitudes getAmplitudes()
  {
    return mAmplitudes;
  }
}
