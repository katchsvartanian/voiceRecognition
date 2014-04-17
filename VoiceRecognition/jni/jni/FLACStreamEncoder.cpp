/**
 * This file is part of AudioBoo, an android program for audio blogging.
 * Copyright (C) 2011 Audioboo Ltd. All rights reserved.
 *
 * Author: Jens Finkhaeuser <jens@finkhaeuser.de>
 *
 * $Id$
 **/

// Define __STDINT_LIMITS to get INT8_MAX and INT16_MAX.
#define __STDINT_LIMITS 1
#include <stdint.h>
#include <assert.h>
#include <string.h>
#include <alloca.h>
#include <limits.h>
#include <pthread.h>
#include <unistd.h>

#include "FLAC/metadata.h"
#include "FLAC/stream_encoder.h"

#include "util.h"

#include <jni.h>

namespace aj = audioboo::jni;

namespace {

/*****************************************************************************
 * Constants
 **/
static char const * const FLACStreamEncoder_classname   = "com.example.jni.FLACStreamEncoder";
static char const * const FLACStreamEncoder_mObject     = "mObject";

static char const * const IllegalArgumentException_classname  = "java.lang.IllegalArgumentException";

static char const * const LTAG                          = "FLACStreamEncoder/native";

static int COMPRESSION_LEVEL                            = 5;



/*****************************************************************************
 * Native FLACStreamEncoder representation
 *
 * FLACStreamEncoder uses a writer thread to write its internal buffer. The
 * implementation is deliberately simple, and writing functions like this:
 *
 * 1. There's a thread on which Java makes JNI calls to write some data, the
 *    JNI thread.
 *    There's also a thread on which data is written to disk via FLAC, the
 *    writer thread.
 * 2. Data is passed from the JNI thread to the writer thread via a locked
 *    singly linked list of buffers; the JNI thread appends buffers to the
 *    list, and once appended, relinquishes ownership which passes to the
 *    writer thread. The writer thread processes the list in a FIFO fashion;
 *    we'll call the list the write FIFO.
 * 3. Upon being called by Java to write data, the JNI thread writes the
 *    data to an internal buffer.
 *    If that buffer becomes full,
 *    a) it's appended to the write FIFO, and ownership is relinquished.
 *    b) a new buffer is allocated for subsequent write calls
 *    c) the writer thread is woken.
 **/

class FLACStreamEncoder
{
public:
  // Write FIFO
  struct write_fifo_t
  {
    write_fifo_t(FLAC__int32 * buf, int fillsize)
      : m_next(NULL)
      , m_buffer(buf) // Taking ownership here.
      , m_buffer_fill_size(fillsize)
    {
    }


    ~write_fifo_t()
    {
      // We have ownership!
      delete [] m_buffer;
      delete m_next;
    }


    write_fifo_t * last() volatile
    {
      volatile write_fifo_t * last = this;
      while (last->m_next) {
        last = last->m_next;
      }
      return (write_fifo_t *) last;
    }


    write_fifo_t *  m_next;
    FLAC__int32 *   m_buffer;
    int             m_buffer_fill_size;
  };

  // Thread trampoline arguments
  struct trampoline
  {
    typedef void * (FLACStreamEncoder::* func_t)(void * args);

    FLACStreamEncoder * m_encoder;
    func_t              m_func;
    void *              m_args;

    trampoline(FLACStreamEncoder * encoder, func_t func, void * args)
      : m_encoder(encoder)
      , m_func(func)
      , m_args(args)
    {
    }
  };


  /**
   * Takes ownership of the outfile.
   **/
  FLACStreamEncoder(char * outfile, int sample_rate, int channels,
      int bits_per_sample)
    : m_outfile(outfile)
    , m_sample_rate(sample_rate)
    , m_channels(channels)
    , m_bits_per_sample(bits_per_sample)
    , m_encoder(NULL)
    , m_max_amplitude(0)
    , m_average_sum(0)
    , m_average_count(0)
    , m_write_buffer(NULL)
    , m_write_buffer_size(0)
    , m_write_buffer_offset(0)
    , m_fifo(NULL)
    , m_kill_writer(false)
  {
  }


  /**
   * There are no exceptions here, so we need to "construct" outside the ctor.
   * Returns NULL on success, else an error message
   **/
  char const * const init()
  {
    if (!m_outfile) {
      return "No file name given!";
    }


    // Try to create the encoder instance
    m_encoder = FLAC__stream_encoder_new();
    if (!m_encoder) {
      return "Could not create FLAC__StreamEncoder!";
    }

    // Try to initialize the encoder.
    FLAC__bool ok = true;
    ok &= FLAC__stream_encoder_set_sample_rate(m_encoder, 1.0f * m_sample_rate);
    ok &= FLAC__stream_encoder_set_channels(m_encoder, m_channels);
    ok &= FLAC__stream_encoder_set_bits_per_sample(m_encoder, m_bits_per_sample);
    ok &= FLAC__stream_encoder_set_verify(m_encoder, true);
    ok &= FLAC__stream_encoder_set_compression_level(m_encoder, COMPRESSION_LEVEL);
    if (!ok) {
      return "Could not set up FLAC__StreamEncoder with the given parameters!";
    }

    // Try initializing the file stream.
    FLAC__StreamEncoderInitStatus init_status = FLAC__stream_encoder_init_file(
        m_encoder, m_outfile, NULL, NULL);

    if (FLAC__STREAM_ENCODER_INIT_STATUS_OK != init_status) {
      return "Could not initialize FLAC__StreamEncoder for the given file!";
    }

    // Allocate write buffer. Based on observations noted down in issue #106, we'll
    // choose this to be 32k in size. Actual allocation happens lazily.
    m_write_buffer_size = 32768;

    // The write FIFO gets created lazily. But we'll initialize the mutex for it
    // here.
    int err = pthread_mutex_init(&m_fifo_mutex, NULL);
    if (err) {
      return "Could not initialize FIFO mutex!";
    }

    // Similarly, create the condition variable for the writer thread.
    err = pthread_cond_init(&m_writer_condition, NULL);
    if (err) {
      return "Could not initialize writer thread condition!";
    }

    // Start thread!
    err = pthread_create(&m_writer, NULL, &FLACStreamEncoder::trampoline_func,
        new trampoline(this, &FLACStreamEncoder::writer_thread, NULL));
    if (err) {
      return "Could not start writer thread!";
    }

    return NULL;
  }



  /**
   * Destroys encoder instance, releases outfile
   **/
  ~FLACStreamEncoder()
  {
    // Flush thread.
    flush_to_fifo();

    pthread_mutex_lock(&m_fifo_mutex);
    m_kill_writer = true;
    pthread_mutex_unlock(&m_fifo_mutex);

    pthread_cond_broadcast(&m_writer_condition);

    // Clean up thread related stuff.
    void * retval = NULL;
    pthread_join(m_writer, &retval);
    pthread_cond_destroy(&m_writer_condition);
    pthread_mutex_destroy(&m_fifo_mutex);

    // Clean up FLAC stuff
    if (m_encoder) {
      FLAC__stream_encoder_finish(m_encoder);
      FLAC__stream_encoder_delete(m_encoder);
      m_encoder = NULL;
    }

    if (m_outfile) {
      free(m_outfile);
      m_outfile = NULL;
    }
  }



  /**
   * Flushes internal buffers to disk.
   **/
  int flush()
  {
    //aj::log(ANDROID_LOG_DEBUG, LTAG, "flush() called.");
    flush_to_fifo();

    // Signal writer to wake up.
    pthread_cond_signal(&m_writer_condition);
  }



  /**
   * Writes bufsize elements from buffer to the stream. Returns the number of
   * bytes actually written.
   **/
  int write(char * buffer, int bufsize)
  {
    //aj::log(ANDROID_LOG_DEBUG, LTAG, "Asked to write buffer of size %d", bufsize);

    // We have 8 or 16 bit pcm in the buffer, but FLAC expects 32 bit samples,
    // where some of the 32 bits are unused.
    int bufsize32 = bufsize / (m_bits_per_sample / 8);
    //aj::log(ANDROID_LOG_DEBUG, LTAG, "Required size: %d", bufsize32);

    // Protect from overly large buffers on the JNI side.
    if (bufsize32 > m_write_buffer_size) {
      // The only way we can handle this sanely without fragmenting buffers and
      // so forth is to use a separate code path here. In this, we'll flush the
      // current write buffer to the FIFO, and immediately append a new
      // FIFO entry that's as large as bufsize32.
      flush_to_fifo();

      m_write_buffer = new FLAC__int32[bufsize32];
      m_write_buffer_offset = 0;

      int ret = copyBuffer(buffer, bufsize, bufsize32);
      flush_to_fifo();

      // Signal writer to wake up.
      pthread_cond_signal(&m_writer_condition);

      return ret;
    }


    // If the current write buffer cannot hold the amount of data we've
    // got, push it onto the write FIFO and create a new buffer.
    if (m_write_buffer && m_write_buffer_offset + bufsize32 > m_write_buffer_size) {
      aj::log(ANDROID_LOG_DEBUG, LTAG, "JNI buffer is full, pushing to FIFO");
      flush_to_fifo();

      // Signal writer to wake up.
      pthread_cond_signal(&m_writer_condition);
    }

    // If we need to create a new buffer, do so now.
    if (!m_write_buffer) {
      //aj::log(ANDROID_LOG_DEBUG, LTAG, "Need new buffer.");
      m_write_buffer = new FLAC__int32[m_write_buffer_size];
      m_write_buffer_offset = 0;
    }

    // At this point we know that there's a write buffer, and we know that
    // there's enough space in it to write the data we've received.
    return copyBuffer(buffer, bufsize, bufsize32);
  }



  /**
   * Writer thread function.
   **/
  void * writer_thread(void * args)
  {
    // Loop while m_kill_writer is false.
    pthread_mutex_lock(&m_fifo_mutex);
    do {
      //aj::log(ANDROID_LOG_DEBUG, LTAG, "Going to sleep...");
      pthread_cond_wait(&m_writer_condition, &m_fifo_mutex);
      //aj::log(ANDROID_LOG_DEBUG, LTAG, "Wakeup: should I die after this? %s", (m_kill_writer ? "yes" : "no"));

      // Grab ownership over the current FIFO, and release the lock again.
      write_fifo_t * fifo = (write_fifo_t *) m_fifo;
      while (fifo) {
        m_fifo = NULL;
        pthread_mutex_unlock(&m_fifo_mutex);

        // Now we can take all the time we want to iterate over the FIFO's
        // contents. We just need to make sure to grab the lock again before
        // going into the next iteration of this loop.
        int retry = 0;

        write_fifo_t * current = fifo;
        while (current) {
          //aj::log(ANDROID_LOG_DEBUG, LTAG, "Encoding current entry %p, buffer %p, size %d",
          //    current, current->m_buffer, current->m_buffer_fill_size);

          // Encode!
          FLAC__bool ok = FLAC__stream_encoder_process_interleaved(m_encoder,
              current->m_buffer, current->m_buffer_fill_size);
          if (ok) {
            retry = 0;
          }
          else {
            // We don't really know how much was written, we have to assume it was
            // nothing.
            if (++retry > 3) {
              aj::log(ANDROID_LOG_ERROR, LTAG, "Giving up on writing current FIFO!");
              break;
            }
            else {
              // Sleep a little before retrying.
              aj::log(ANDROID_LOG_ERROR, LTAG, "Writing FIFO entry %p failed; retrying...");
              usleep(5000); // 5msec
            }
            continue;
          }

          current = current->m_next;
        }

        // Once we've written everything, delete the fifo and grab the lock again.
        delete fifo;
        pthread_mutex_lock(&m_fifo_mutex);
        fifo = (write_fifo_t *) m_fifo;
      }

      //aj::log(ANDROID_LOG_DEBUG, LTAG, "End of wakeup, or should I die? %s", (m_kill_writer ? "yes" : "no"));
    } while (!m_kill_writer);

    pthread_mutex_unlock(&m_fifo_mutex);

    //aj::log(ANDROID_LOG_DEBUG, LTAG, "Writer thread dies.");
	for (long i=0;i<50; i++){
		aj::log(ANDROID_LOG_DEBUG, LTAG,".");
		usleep(5000);
	}
    aj::log(ANDROID_LOG_DEBUG, LTAG, "slept.");
    return NULL;
  }



  float getMaxAmplitude()
  {
    float result = m_max_amplitude;
    m_max_amplitude = 0;
    return result;
  }



  float getAverageAmplitude()
  {
    float result = m_average_sum / m_average_count;
    m_average_sum = 0;
    m_average_count = 0;
    return result;
  }


private:
  /**
   * Append current write buffer to FIFO, and clear it.
   **/
  inline void flush_to_fifo()
  {
    if (!m_write_buffer) {
      return;
    }

    //aj::log(ANDROID_LOG_DEBUG, LTAG, "Flushing to FIFO.");

    write_fifo_t * next = new write_fifo_t(m_write_buffer,
        m_write_buffer_offset);
    m_write_buffer = NULL;

    pthread_mutex_lock(&m_fifo_mutex);
    if (m_fifo) {
      write_fifo_t * last = m_fifo->last();
      last->m_next = next;
    }
    else {
      m_fifo = next;
    }
    //aj::log(ANDROID_LOG_DEBUG, LTAG, "FIFO: %p, new entry: %p", m_fifo, next);
    pthread_mutex_unlock(&m_fifo_mutex);
  }



  /**
   * Wrapper around templatized copyBuffer that writes to the current write
   * buffer at the current offset.
   **/
  inline int copyBuffer(char * buffer, int bufsize, int bufsize32)
  {
    FLAC__int32 * buf = m_write_buffer + m_write_buffer_offset;

    //aj::log(ANDROID_LOG_DEBUG, LTAG, "Writing at %p[%d] = %p", m_write_buffer, m_write_buffer_offset, buf);
    if (8 == m_bits_per_sample) {
      copyBuffer<int8_t>(buf, buffer, bufsize);
      m_write_buffer_offset += bufsize32;
    }
    else if (16 == m_bits_per_sample) {
      copyBuffer<int16_t>(buf, buffer, bufsize);
      m_write_buffer_offset += bufsize32;
    }
    else {
      // XXX should never happen, just exit.
      return 0;
    }

    return bufsize;
  }



  /**
   * Copies inbuf to outpuf, assuming that inbuf is really a buffer of
   * sized_sampleT.
   * As a side effect, m_max_amplitude, m_average_sum and m_average_count are
   * modified.
   **/
  template <typename sized_sampleT>
  void copyBuffer(FLAC__int32 * outbuf, char * inbuf, int inbufsize)
  {
    sized_sampleT * inbuf_sized = reinterpret_cast<sized_sampleT *>(inbuf);
    for (int i = 0 ; i < inbufsize / sizeof(sized_sampleT) ; ++i) {
      sized_sampleT cur = inbuf_sized[i];

      // Convert sized sample to int32
      outbuf[i] = cur;

      // Convert to float on a range from 0..1
      if (cur < 0) {
        // Need to lose precision here, the positive value range is lower than
        // the negative value range in a signed integer.
        cur = -(cur + 1);
      }
      float amp = static_cast<float>(cur) / aj::type_traits<sized_sampleT>::MAX;

      // Store max amplitude
      if (amp > m_max_amplitude) {
        m_max_amplitude = amp;
      }

      // Sum average.
      if (!(i % m_channels)) {
        m_average_sum += amp;
        ++m_average_count;
      }
    }
  }


  // Thread trampoline
  static void * trampoline_func(void * args)
  {
    trampoline * tramp = static_cast<trampoline *>(args);
    FLACStreamEncoder * encoder = tramp->m_encoder;
    trampoline::func_t func = tramp->m_func;

    void * result = (encoder->*func)(tramp->m_args);

    // Ownership tor tramp is passed to us, so we'll delete it here.
    delete tramp;
    return result;
  }




  // Configuration values passed to ctor
  char *  m_outfile;
  int     m_sample_rate;
  int     m_channels;
  int     m_bits_per_sample;

  // FLAC encoder instance
  FLAC__StreamEncoder * m_encoder;

  // Max amplitude measured
  float   m_max_amplitude;
  float   m_average_sum;
  int     m_average_count;

  // JNI thread's buffer.
  FLAC__int32 * m_write_buffer;
  int           m_write_buffer_size;
  int           m_write_buffer_offset;

  // Write FIFO
  volatile write_fifo_t * m_fifo;
  pthread_mutex_t         m_fifo_mutex;

  // Writer thread
  pthread_t       m_writer;
  pthread_cond_t  m_writer_condition;
  volatile bool   m_kill_writer;
};




/*****************************************************************************
 * Helper functions
 **/

/**
 * Retrieve FLACStreamEncoder instance from the passed jobject.
 **/
static FLACStreamEncoder * get_encoder(JNIEnv * env, jobject obj)
{
  assert(sizeof(jlong) >= sizeof(FLACStreamEncoder *));

  // Do the JNI dance for getting the mObject field
  jclass cls = env->FindClass(FLACStreamEncoder_classname);
  jfieldID object_field = env->GetFieldID(cls, FLACStreamEncoder_mObject, "J");
  jlong encoder_value = env->GetLongField(obj, object_field);

  env->DeleteLocalRef(cls);

  return reinterpret_cast<FLACStreamEncoder *>(encoder_value);
}


/**
 * Store FLACStreamEncoder instance in the passed jobject.
 **/
static void set_encoder(JNIEnv * env, jobject obj, FLACStreamEncoder * encoder)
{
  assert(sizeof(jlong) >= sizeof(FLACStreamEncoder *));

  // Do the JNI dance for setting the mObject field
  jlong encoder_value = reinterpret_cast<jlong>(encoder);
  jclass cls = env->FindClass(FLACStreamEncoder_classname);
  jfieldID object_field = env->GetFieldID(cls, FLACStreamEncoder_mObject, "J");
  env->SetLongField(obj, object_field, encoder_value);
  env->DeleteLocalRef(cls);
}


} // anonymous namespace



/*****************************************************************************
 * JNI Wrappers
 **/

extern "C" {

void
Java_com_example_jni_FLACStreamEncoder_init(JNIEnv * env, jobject obj,
    jstring outfile, jint sample_rate, jint channels, jint bits_per_sample)
{
  assert(sizeof(jlong) >= sizeof(FLACStreamEncoder *));

  FLACStreamEncoder * encoder = new FLACStreamEncoder(
      aj::convert_jstring_path(env, outfile), sample_rate, channels,
      bits_per_sample);

  char const * const error = encoder->init();
  if (NULL != error) {
    delete encoder;

    aj::throwByName(env, IllegalArgumentException_classname, error);
    return;
  }

  set_encoder(env, obj, encoder);
}



void
Java_com_example_jni_FLACStreamEncoder_deinit(JNIEnv * env, jobject obj)
{
  FLACStreamEncoder * encoder = get_encoder(env, obj);
  delete encoder;
  set_encoder(env, obj, NULL);
}



jint
Java_com_example_jni_FLACStreamEncoder_write(JNIEnv * env, jobject obj,
    jobject buffer, jint bufsize)
{
  FLACStreamEncoder * encoder = get_encoder(env, obj);

  if (NULL == encoder) {
    aj::throwByName(env, IllegalArgumentException_classname,
        "Called without a valid encoder instance!");
    return 0;
  }

  if (bufsize > env->GetDirectBufferCapacity(buffer)) {
    aj::throwByName(env, IllegalArgumentException_classname,
        "Asked to read more from a buffer than the buffer's capacity!");
  }

  char * buf = static_cast<char *>(env->GetDirectBufferAddress(buffer));
  return encoder->write(buf, bufsize);
}



void
Java_com_example_jni_FLACStreamEncoder_flush(JNIEnv * env, jobject obj)
{
  FLACStreamEncoder * encoder = get_encoder(env, obj);

  if (NULL == encoder) {
    aj::throwByName(env, IllegalArgumentException_classname,
        "Called without a valid encoder instance!");
    return;
  }

  encoder->flush();
}



jfloat
Java_com_example_jni_FLACStreamEncoder_getMaxAmplitude(JNIEnv * env, jobject obj)
{
  FLACStreamEncoder * encoder = get_encoder(env, obj);

  if (NULL == encoder) {
    aj::throwByName(env, IllegalArgumentException_classname,
        "Called without a valid encoder instance!");
    return 0;
  }

  return encoder->getMaxAmplitude();
}



jfloat
Java_com_example_jni_FLACStreamEncoder_getAverageAmplitude(JNIEnv * env, jobject obj)
{
  FLACStreamEncoder * encoder = get_encoder(env, obj);

  if (NULL == encoder) {
    aj::throwByName(env, IllegalArgumentException_classname,
        "Called without a valid encoder instance!");
    return 0;
  }

  return encoder->getAverageAmplitude();
}


} // extern "C"
