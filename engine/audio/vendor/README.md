# vendor/miniaudio.h

Vendored from https://raw.githubusercontent.com/mackron/miniaudio/master/miniaudio.h
(v0.11.25, single-header, public domain / MIT-0 -- see the license block at the end of the
file). Not modified.

`src/main/cpp/miniaudio_impl.c` is the one translation unit that defines `MA_IMPLEMENTATION`
before including this header, so the actual implementation is compiled exactly once;
`src/main/cpp/jni_audio.cpp` only sees the declarations.

To update: re-download the file from the URL above and overwrite this one. miniaudio does not
guarantee ABI compatibility between versions (even patch releases), so rebuild
`dncity_audio` after updating.
