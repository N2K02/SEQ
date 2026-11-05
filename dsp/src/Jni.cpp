#include <jni.h>
#include <array>
#include <new>
#include <exception>
#include "seq/Processor.h"

using seq::Band;
using seq::FilterType;
using seq::Processor;
using seq::MAX_BANDS;

extern "C" JNIEXPORT jlong JNICALL
Java_com_n2k02_seq_audio_NativeDsp_nCreate(JNIEnv*, jobject) {
  auto* p=new(std::nothrow) Processor();
  return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_n2k02_seq_audio_NativeDsp_nConfigure(JNIEnv*, jobject, jlong handle,
    jint sampleRate, jfloat bass, jfloat mid, jfloat treble, jboolean enabled) {
  auto* p=reinterpret_cast<Processor*>(handle);
  if (!p) return JNI_FALSE;
  try {
    std::array<Band,MAX_BANDS> bands{};
    bands[0] = Band{FilterType::LowShelf, 110.0, bass, 0.7071, true};
    bands[1] = Band{FilterType::Peak, 1100.0, mid, 0.80, true};
    bands[2] = Band{FilterType::HighShelf, 6000.0, treble, 0.7071, true};
    p->configure(bands, static_cast<double>(sampleRate), enabled != JNI_FALSE);
    return JNI_TRUE;
  } catch (const std::exception&) { return JNI_FALSE; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_n2k02_seq_audio_NativeDsp_nProcess(JNIEnv* env, jobject,
    jlong handle, jfloatArray buffer, jint frames) {
  auto* p=reinterpret_cast<Processor*>(handle);
  if (!p || !buffer || frames<0 || static_cast<jsize>(frames*2) > env->GetArrayLength(buffer))
    return JNI_FALSE;
  jfloat* pcm=env->GetFloatArrayElements(buffer,nullptr);
  if (!pcm) return JNI_FALSE;
  p->process(pcm,static_cast<std::size_t>(frames));
  env->ReleaseFloatArrayElements(buffer,pcm,0);
  return JNI_TRUE;
}
extern "C" JNIEXPORT void JNICALL
Java_com_n2k02_seq_audio_NativeDsp_nDestroy(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<Processor*>(handle);
}
