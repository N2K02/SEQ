#pragma once
#include "seq/Biquad.h"
#include <array>
#include <cstddef>
#include <cstdint>

namespace seq {
constexpr std::size_t MAX_BANDS=16;

/**
 * Offline/test-reference DSP for interleaved stereo float PCM.
 * Configure/reset only when processing is stopped. No allocations or locks in process().
 */
class Processor {
public:
  void configure(const std::array<Band, MAX_BANDS>& bands, double sampleRate, bool enabled, double userPreampDb = 0.0);
  void process(float* interleaved, std::size_t stereoFrames) noexcept;
  [[nodiscard]] double pregainDb() const noexcept { return pregainDb_; }
  [[nodiscard]] std::uint64_t clippedSamples() const noexcept { return clipped_; }
  [[nodiscard]] bool enabled() const noexcept { return enabled_; }
private:
  std::array<std::array<Biquad, 2>,MAX_BANDS> filters_{};
  double pregainDb_=0.0;
  float scale_=1.0f;
  bool enabled_=false;
  std::uint64_t clipped_=0;
};
}
