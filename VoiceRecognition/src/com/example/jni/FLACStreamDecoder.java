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

package com.example.jni;

import java.nio.ByteBuffer;

/**
 * This is *not* a full JNI wrapper for the FLAC codec, but merely exports
 * the minimum of functions necessary for the purposes of the Audioboo client.
 *
 * Note that we're not using ByteBuffer here as we do in FLACStreamEncoder;
 * this is because AudioTrack (see FLACPlayer) doesn't support ByteBuffer.
 **/
public class FLACStreamDecoder
{
  /***************************************************************************
   * Interface
   **/

  /**
   * Initialize the stream decoder with the file given by infile.
   * TODO: Will possibly need a different constructor that accepts URLs.
   **/
  public FLACStreamDecoder(String infile)
  {
    init(infile);
  }



  public void release()
  {
    deinit();
  }



  public void reset(String infile)
  {
    deinit();
    init(infile);
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
  native private void init(String infile);

  /**
   * Destructor equivalent, but can be called multiple times.
   **/
  native private void deinit();

  /**
   * Returns the bits per sample in the infile, or -1 if that is unknown.
   **/
  native public int bitsPerSample();

  /**
   * Returns the number of channels in the infile, or -1 if that is unknown.
   **/
  native public int channels();

  /**
   * Returns the sample rate in the infile, or -1 if that is unknown.
   **/
  native public int sampleRate();

  /**
   * Returns the minimum buffer size you need to hand to read() below, or -1
   * if it's unknown.
   **/
  native public int minBufferSize();

  /**
   * Reads data from the decoder, and writes it into the provided buffer. The
   * buffer must be at least minBufferSize() in size.
   * Returns the number of bytes actually read, or -1 on fatal errors.
   **/
  native public int read(ByteBuffer buffer, int bufsize);

  /**
   * Returns the number of samples in the file.
   **/
  native public int totalSamples();

  /**
   * Seeks to a particular sample.
   **/
  native public void seekTo(int sample);

  /**
   * Returns read position, in samples.
   **/
  native public int position();

  // Load native library
  static {
    System.loadLibrary("audioboo-native");
  }
}
