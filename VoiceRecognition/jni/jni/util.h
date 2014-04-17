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

#include <jni.h>

#include <android/log.h>


namespace audioboo {
namespace jni {


/*****************************************************************************
 * Very simple traits for int8_t/int16_t
 **/
template <typename intT>
struct type_traits
{
};


template <>
struct type_traits<int8_t>
{
  enum {
    MAX = INT8_MAX,
  };
};


template <>
struct type_traits<int16_t>
{
  enum {
    MAX = INT16_MAX,
  };
};



/*****************************************************************************
 * Helper functions
 **/
/**
 * Convert a jstring to a UTF-8 char pointer. Ownership of the pointer goes
 * to the caller.
 **/
char * convert_jstring_path(JNIEnv * env, jstring input);


/**
 * Throws the given exception/message
 **/
void throwByName(JNIEnv * env, const char * name, const char * msg);


/**
 * Log stuff printf-style.
 **/
void log(int priority, char const * tag, char const * format, ...);

}} // namespace audioboo::jni
