#include <jni.h>
#include <array>
#include <new>
#include <exception>
#include <cmath>
#include "seq/Processor.h"

using seq::Band;
using seq::FilterType;
using seq::Processor;
using seq::MAX_BANDS;

extern "C" JNIEXPORT jlong JNICALL
Java_com_n2k02_seq_audio_NativeDsp_nCreate(JNIEnv*, jobject) {
  return reinterpret_cast<jlong>(new(std::nothrow) Processor());
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_n2k02_seq_audio_NativeDsp_nConfigure(JNIEnv* env, jobject, jlong handle,
    jint sampleRate, jdoubleArray filterSpecs, jdouble preamp, jboolean enabled) {
  auto* p=reinterpret_cast<Processor*>(handle);
  if (!p || !filterSpecs) return JNI_FALSE;
  const jsize size=env->GetArrayLength(filterSpecs);
  if (size<0 || size%4!=0 || size>static_cast<jsize>(MAX_BANDS*4)) return JNI_FALSE;
  std::array<Band,MAX_BANDS> bands{};
  std::array<jdouble,MAX_BANDS*4> data{};
  env->GetDoubleArrayRegion(filterSpecs,0,size,data.data());
  if (env->ExceptionCheck()) return JNI_FALSE;
  for(jsize i=0;i<size;i+=4) {
    const auto type=data[i], hz=data[i+1], gain=data[i+2], q=data[i+3];
    if(!std::isfinite(type) || !std::isfinite(hz) || !std::isfinite(gain) ||
       !std::isfinite(q) || (type!=0.0 && type!=1.0 && type!=2.0) ||
       hz<20.0 || hz>20000.0 || gain < -12.0 || gain>12.0 || q<0.15 || q>12.0)
       return JNI_FALSE;
    const auto kind=type==0.0 ? FilterType::Peak :
      (type==1.0 ? FilterType::LowShelf : FilterType::HighShelf);
    bands[static_cast<std::size_t>(i/4)]=Band{kind,hz,gain,q,true};
  }
  try {
    p->configure(bands,static_cast<double>(sampleRate),enabled!=JNI_FALSE,preamp);
    return JNI_TRUE;
  } catch(const std::exception&) { return JNI_FALSE; }
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_n2k02_seq_audio_NativeDsp_nConfigureFir(JNIEnv* env,jobject,jlong handle,
    jfloatArray left,jfloatArray right) {
  auto* p=reinterpret_cast<Processor*>(handle);
  if(!p || !left || !right) return JNI_FALSE;
  const jsize taps=env->GetArrayLength(left);
  if(taps!=env->GetArrayLength(right) || taps>static_cast<jsize>(seq::MAX_FIR_TAPS)) return JNI_FALSE;
  std::array<float,seq::MAX_FIR_TAPS> a{},b{};
  env->GetFloatArrayRegion(left,0,taps,a.data());
  env->GetFloatArrayRegion(right,0,taps,b.data());
  if(env->ExceptionCheck()) return JNI_FALSE;
  try { p->configureFir(a.data(),b.data(),static_cast<std::size_t>(taps));return JNI_TRUE; }
  catch(const std::exception&) {return JNI_FALSE;}
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_n2k02_seq_audio_NativeDsp_nConfigurePeakGuard(JNIEnv*,jobject,jlong handle,
    jboolean enabled,jdouble ceiling) {
  auto* p=reinterpret_cast<Processor*>(handle);
  if(!p) return JNI_FALSE;
  try {p->configurePeakGuard(enabled!=JNI_FALSE,ceiling);return JNI_TRUE;}
  catch(const std::exception&) {return JNI_FALSE;}
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
