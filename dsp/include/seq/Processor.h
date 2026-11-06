#pragma once
#include "seq/Biquad.h"
#include <array>
#include <cstddef>
#include <cstdint>

namespace seq {
constexpr std::size_t MAX_BANDS=16;
constexpr std::size_t MAX_FIR_TAPS=256;
/** Limiter lookahead: 64 frames (1.3 ms at 48 kHz). */
constexpr std::size_t LOOKAHEAD=64;

/**
 * Offline/test-reference DSP for interleaved stereo float PCM.
 * Configure/reset only when processing is stopped. No allocations or locks in process().
 */
class Processor {
public:
  void configure(const std::array<Band, MAX_BANDS>& bands, double sampleRate, bool enabled, double userPreampDb = 0.0);
  /** Offline FIR, convolution only when enabled; at most 256 taps to bound CPU cost. */
  void configureFir(const float* left, const float* right, std::size_t taps);
  /**
   * Optional lookahead limiter with 4x interpolated intersample peak detection (not an
   * ITU-certified meter). When enabled, EQ headroom is halved and the limiter catches the rare
   * remaining peaks, so boosts no longer cost the full loudness of the boost.
   */
  void configurePeakGuard(bool enabled, double ceilingDb=-1.0);
  void process(float* interleaved, std::size_t stereoFrames) noexcept;
  [[nodiscard]] double observedPeak() const noexcept { return observedPeak_; }
  [[nodiscard]] std::size_t firTaps() const noexcept { return firTaps_; }
  [[nodiscard]] double pregainDb() const noexcept { return pregainDb_; }
  [[nodiscard]] std::uint64_t clippedSamples() const noexcept { return clipped_; }
  [[nodiscard]] bool enabled() const noexcept { return enabled_; }
private:
  void updatePregain() noexcept;
  void limit(float* interleaved, std::size_t stereoFrames) noexcept;
  std::array<std::array<Biquad, 2>,MAX_BANDS> filters_{};
  double maxBoostDb_=0.0, userPreampDb_=0.0;
  double pregainDb_=0.0;
  float scale_=1.0f;
  bool enabled_=false;
  std::uint64_t clipped_=0;
  std::array<std::array<float,MAX_FIR_TAPS>,2> impulse_{};
  std::array<std::array<float,MAX_FIR_TAPS>,2> history_{};
  std::size_t firTaps_=0, firCursor_=0;
  double firScale_=1.0;
  bool peakGuard_=false;
  double ceilingLinear_=0.8912509381337456;
  double guardGain_=1.0, observedPeak_=0.0;
  double attack_=0.0, release_=0.0;
  std::array<std::array<float,LOOKAHEAD>,2> delay_{};
  std::array<double,LOOKAHEAD> required_{};
  std::array<float,6> tail_{}; // last 3 frames for continuous interpolation across blocks
  std::size_t delayCursor_=0;
};
}
