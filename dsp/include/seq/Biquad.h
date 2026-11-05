#pragma once
#include <cmath>

namespace seq {
enum class FilterType { Peak, LowShelf, HighShelf };
struct Band {
  FilterType type = FilterType::Peak;
  double hz = 1000.0;
  double gainDb = 0.0;
  double q = 0.70710678118;
  bool enabled = false;
};

/** Normalized Direct Form II Transposed, two channel-independent state instances. */
class Biquad {
public:
  void configure(const Band& band, double sampleRate);
  void reset() noexcept { z1_ = z2_ = 0.0; }
  float process(float input) noexcept;
  double responseDb(double hz, double sampleRate) const noexcept;
private:
  double b0_ = 1.0, b1_ = 0.0, b2_ = 0.0;
  double a1_ = 0.0, a2_ = 0.0;
  double z1_ = 0.0, z2_ = 0.0;
};
}
