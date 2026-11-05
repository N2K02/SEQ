#include "seq/Processor.h"
#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace seq {
void Processor::configure(const std::array<Band, MAX_BANDS>& bands,
                          double sampleRate, bool enabled, double userPreampDb) {
  if (!std::isfinite(userPreampDb) || userPreampDb > 0.0 || userPreampDb < -24.0)
    throw std::invalid_argument("unsafe preamp");
  if (!std::isfinite(sampleRate) || sampleRate<8000 || sampleRate>384000)
    throw std::invalid_argument("invalid sample rate");
  for (std::size_t i=0; i<MAX_BANDS; ++i)
    for (auto& filter : filters_[i]) filter.configure(bands[i], sampleRate);
  double maxBoost=0;
  if (enabled) {
    for (int i=0;i<=512;i++) {
      const double hz=20.0*std::pow((sampleRate*0.475)/20.0, i/512.0);
      double db=0.0;
      for (const auto& f : filters_) db += f[0].responseDb(hz,sampleRate);
      maxBoost=std::max(maxBoost, db);
    }
  }
  pregainDb_=std::min(-maxBoost, userPreampDb);
  scale_=static_cast<float>(std::pow(10.0,pregainDb_/20.0));
  enabled_=enabled;
  clipped_=0;
  history_={};
  firCursor_=0;
  guardGain_=1.0;
  observedPeak_=0.0;
}
void Processor::configureFir(const float* left,const float* right,std::size_t taps) {
  if(taps>MAX_FIR_TAPS || (taps>0 && (!left || !right)))
    throw std::invalid_argument("invalid FIR");
  firTaps_=taps;
  impulse_={};history_={};firCursor_=0;
  double worst=1.0;
  for(std::size_t c=0;c<2;c++) {
    double sum=0.0;
    for(std::size_t i=0;i<taps;i++) {
      const float sample=c==0 ? left[i] : right[i];
      if(!std::isfinite(sample) || std::abs(sample)>4.0f)
        throw std::invalid_argument("invalid FIR coefficient");
      impulse_[c][i]=sample;
      sum+=std::abs(sample);
    }
    worst=std::max(worst,sum);
  }
  firScale_=1.0/worst; // conservative L1 attenuation for arbitrary IR gain.
}
void Processor::configurePeakGuard(bool enabled,double ceilingDb) {
  if(!std::isfinite(ceilingDb) || ceilingDb> -0.1 || ceilingDb< -12.0)
    throw std::invalid_argument("peak ceiling out of range");
  peakGuard_=enabled;
  ceilingLinear_=std::pow(10.0,ceilingDb/20.0);
  guardGain_=1.0;
  observedPeak_=0.0;
}
static double cubic(double a,double b,double c,double d,double t) noexcept {
  return b + 0.5*t*(c-a+t*(2*a-5*b+4*c-d+t*(3*(b-c)+d-a)));
}
void Processor::process(float* pcm,std::size_t frames) noexcept {
  if(!pcm || !enabled_) return;
  for(std::size_t i=0;i<frames;i++) {
    const std::size_t cursor=firCursor_;
    for(std::size_t c=0;c<2;c++) {
      float v=pcm[2*i+c]*scale_;
      for(auto& band:filters_) v=band[c].process(v);
      if(firTaps_>0) {
        history_[c][cursor]=v;
        double sum=0.0;
        for(std::size_t t=0;t<firTaps_;t++)
          sum+=impulse_[c][t]*history_[c][(cursor+MAX_FIR_TAPS-t)%MAX_FIR_TAPS];
        v=static_cast<float>(sum*firScale_);
      }
      pcm[2*i+c]=v;
    }
    firCursor_=(firCursor_+1)%MAX_FIR_TAPS;
  }
  if(peakGuard_ && frames>0) {
    double peak=0.0;
    for(std::size_t i=0;i<frames;i++) {
      for(std::size_t c=0;c<2;c++) {
        const auto pos=[&](std::size_t j)->double { return pcm[2*j+c]; };
        const double a=pos(i>0?i-1:0);
        const double b=pos(i);
        const double cc=pos(std::min(i+1,frames-1));
        const double dd=pos(std::min(i+2,frames-1));
        for(int phase=0;phase<4;phase++)
          peak=std::max(peak,std::abs(cubic(a,b,cc,dd,phase*0.25)));
      }
    }
    observedPeak_=std::max(observedPeak_,peak);
    // Instant block attenuation and slow release. Conservative, not certified true peak.
    const double allowed=peak>0 ? std::min(1.0,ceilingLinear_/peak) : 1.0;
    if(allowed<guardGain_) guardGain_=allowed;
    else guardGain_=std::min(allowed,guardGain_+0.001);
    for(std::size_t i=0;i<frames*2;i++)
      pcm[i]=static_cast<float>(pcm[i]*guardGain_);
  }
  for(std::size_t i=0;i<frames*2;i++) {
    if(pcm[i]>1.0f || pcm[i]<-1.0f) {
      ++clipped_;
      pcm[i]=std::clamp(pcm[i],-1.0f,1.0f);
    }
  }
}
}
