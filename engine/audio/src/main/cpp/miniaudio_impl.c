/*
 * Single translation unit that pulls in miniaudio's implementation (miniaudio.h is
 * header-only/declarations-only unless MA_IMPLEMENTATION is defined before including it once).
 * jni_audio.cpp includes miniaudio.h without that define, so it only sees declarations.
 */
#define MA_IMPLEMENTATION
#include "miniaudio.h"
