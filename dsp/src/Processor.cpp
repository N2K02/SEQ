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
}
void Processor::process(float* pcm, std::size_t frames) noexcept {
  if (!pcm || !enabled_) return; // bit-identical bypass at the PCM buffer boundary
  for (std::size_t i=0; i<frames; ++i) {
    for (std::size_t c=0; c<2; ++c) {
      float v=pcm[2*i+c]*scale_;
      for (auto& band : filters_) v=band[c].process(v);
      if (v>1.0f || v< -1.0f) {
        ++clipped_;
        v=std::clamp(v,-1.0f,1.0f); // last-resort saturation, measured in tests
      }
      pcm[2*i+c]=v;
    }
  }
}
}
