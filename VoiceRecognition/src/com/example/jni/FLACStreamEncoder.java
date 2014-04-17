/**
 * This file is part of Audioboo, an android program for audio blogging.
 * Copyright (C) 2011 Audioboo Ltd. All rights reserved.
 *
 * Author: Jens Finkhaeuser <jens@finkhaeuser.de>
 *
 * $Id$
 **/

package com.example.jni;

import java.nio.ByteBuffer;


/**
 * This is *not* a full JNI wrapper for the FLAC codec, but merely exports
 * the minimum of functions necessary for the purposes of the Audioboo client.
 **/
public class FLACStreamEncoder
{
  /***************************************************************************
   * Interface
   **/

  /**
   * channels must be either 1 (mono) or 2 (stereo)
   * bits_per_sample must be either 8 or 16
   **/
  public FLACStreamEncoder(String outfile, int sample_rate, int channels,
      int bits_per_sample)
  {
    init(outfile, sample_rate, channels, bits_per_sample);
  }



  public void release()
  {
    deinit();
  }



  public void reset(String outfile, int sample_rate, int channels,
      int bits_per_sample)
  {
    deinit();
    init(outfile, sample_rate, channels, bits_per_sample);
  }



  protected void finalize() throws Throwable
  {
    try {
      deinit();
    } finally {
      super.finalize();
    }
  }



  /***************************************************************************
   * JNI Implementation
   **/

  // Pointer to opaque data in C
  private long  mObject;

  /**
   * Constructor equivalent
   **/
  native private void init(String outfile, int sample_rate, int channels,
      int bits_per_sample);

  /**
   * Destructor equivalent, but can be called multiple times.
   **/
  native private void deinit();

  /**
   * Returns the maximum amplitude written to the file since the last call
   * to this function.
   **/
  native public float getMaxAmplitude();

  /**
   * Returns the average amplitude written to the file since the last call
   * to this function.
   **/
  native public float getAverageAmplitude();

  /**
   * Writes data to the encoder. The provided buffer must be at least as long
   * as the provided buffer size.
   * Returns the number of bytes actually written.
   **/
  native public int write(ByteBuffer buffer, int bufsize);

  /**
   * Flushes internal buffers to FIFO.
   **/
  native public void flush();

  // Load native library
  static {
    System.loadLibrary("audioboo-native");
  }
}
