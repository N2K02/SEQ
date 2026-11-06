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
  maxBoostDb_=maxBoost;
  userPreampDb_=userPreampDb;
  enabled_=enabled;
  updatePregain();
  clipped_=0;
  history_={};
  firCursor_=0;
  guardGain_=1.0;
  observedPeak_=0.0;
  delay_={}; required_.fill(1.0); tail_={}; delayCursor_=0;
}
void Processor::updatePregain() noexcept {
  // Full headroom without a limiter (never clips); half headroom with the limiter.
  const double headroom=peakGuard_ ? maxBoostDb_*0.5 : maxBoostDb_;
  pregainDb_=std::min(-headroom, userPreampDb_);
  scale_=static_cast<float>(std::pow(10.0,pregainDb_/20.0));
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
  // Normalize by the peak of the magnitude response (not L1): no needless loudness loss,
  // while no frequency can be amplified above unity.
  for(std::size_t c=0;c<2 && taps>0;c++) {
    for(int k=0;k<=512;k++) {
      const double w=3.141592653589793*k/512.0;
      double re=0.0, im=0.0;
      for(std::size_t i=0;i<taps;i++) {
        re+=impulse_[c][i]*std::cos(w*static_cast<double>(i));
        im-=impulse_[c][i]*std::sin(w*static_cast<double>(i));
      }
      worst=std::max(worst,std::hypot(re,im));
    }
  }
  firScale_=1.0/worst;
}
void Processor::configurePeakGuard(bool enabled,double ceilingDb) {
  if(!std::isfinite(ceilingDb) || ceilingDb> -0.1 || ceilingDb< -12.0)
    throw std::invalid_argument("peak ceiling out of range");
  peakGuard_=enabled;
  ceilingLinear_=std::pow(10.0,ceilingDb/20.0);
  guardGain_=1.0;
  observedPeak_=0.0;
  // Attack reaches 99% within the lookahead window; ~80 ms release (rate-independent enough
  // for 44.1–96 kHz; derived per sample from a 48 kHz reference).
  attack_=1.0-std::exp(-5.0/static_cast<double>(LOOKAHEAD));
  release_=1.0-std::exp(-1.0/(0.080*48000.0));
  delay_={}; required_.fill(1.0); tail_={}; delayCursor_=0;
  updatePregain();
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
  if(peakGuard_ && frames>0) limit(pcm,frames);
  for(std::size_t i=0;i<frames*2;i++) {
    if(pcm[i]>1.0f || pcm[i]<-1.0f) {
      ++clipped_;
      pcm[i]=std::clamp(pcm[i],-1.0f,1.0f);
    }
  }
}
void Processor::limit(float* pcm,std::size_t frames) noexcept {
  for(std::size_t i=0;i<frames;i++) {
    // 4x cubic intersample peak of the newest frame, continuous across block boundaries.
    double peak=0.0;
    for(std::size_t c=0;c<2;c++) {
      const double a=tail_[c], b=tail_[2+c], cc=tail_[4+c], d=pcm[2*i+c];
      for(int phase=0;phase<4;phase++)
        peak=std::max(peak,std::abs(cubic(a,b,cc,d,phase*0.25)));
      peak=std::max(peak,std::abs(d));
      tail_[c]=tail_[2+c]; tail_[2+c]=tail_[4+c]; tail_[4+c]=pcm[2*i+c];
    }
    observedPeak_=std::max(observedPeak_,peak);
    const std::size_t slot=delayCursor_;
    const double needed=peak>ceilingLinear_ ? ceilingLinear_/peak : 1.0;
    // Smallest gain needed by the outgoing sample, every sample still in the window and the
    // newest one, so the envelope is already down when a peak leaves the delay line.
    double target=needed;
    for(double r : required_) target=std::min(target,r);
    required_[slot]=needed;
    guardGain_+=(target-guardGain_)*(target<guardGain_ ? attack_ : release_);
    const double gain=guardGain_;
    for(std::size_t c=0;c<2;c++) {
      const float delayed=delay_[c][slot];
      delay_[c][slot]=pcm[2*i+c];
      const double out=delayed*gain;
      // Safety net: the envelope already holds peaks below the ceiling; this only
      // guarantees the bound if a transient outruns the attack.
      pcm[2*i+c]=static_cast<float>(std::clamp(out,-ceilingLinear_,ceilingLinear_));
    }
    delayCursor_=(delayCursor_+1)%LOOKAHEAD;
  }
}
}
