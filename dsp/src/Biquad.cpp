#include "seq/Biquad.h"
#include <algorithm>
#include <complex>
#include <stdexcept>
#include <numbers>

namespace seq {
void Biquad::configure(const Band& band, double fs) {
  if (!std::isfinite(fs) || fs < 8000 || fs > 384000)
    throw std::invalid_argument("invalid sample rate");
  if (!std::isfinite(band.hz) || !std::isfinite(band.gainDb) || !std::isfinite(band.q))
    throw std::invalid_argument("non-finite filter settings");
  const double hz = std::clamp(band.hz, 10.0, fs * 0.475);
  const double gain = std::clamp(band.gainDb, -12.0, 12.0);
  const double q = std::clamp(band.q, 0.15, 12.0);
  const double a = std::pow(10.0, gain / 40.0);
  const double w = 2.0 * std::numbers::pi * hz / fs;
  const double cw = std::cos(w), sw = std::sin(w);
  const double alpha = sw / (2.0 * q);
  const double sqrtA = std::sqrt(a);
  double b0=1,b1=0,b2=0,a0=1,a1=0,a2=0;
  if (band.enabled && std::abs(gain) > 1e-10) {
    switch (band.type) {
      case FilterType::Peak:
        b0=1+alpha*a; b1=-2*cw; b2=1-alpha*a;
        a0=1+alpha/a; a1=-2*cw; a2=1-alpha/a;
        break;
      case FilterType::LowShelf: {
        // RBJ low-shelf; slope S=1 produces smooth second-order shelf.
        const double al=sw/2 * std::sqrt(2.0);
        b0=a*((a+1)-(a-1)*cw+2*sqrtA*al);
        b1=2*a*((a-1)-(a+1)*cw);
        b2=a*((a+1)-(a-1)*cw-2*sqrtA*al);
        a0=(a+1)+(a-1)*cw+2*sqrtA*al;
        a1=-2*((a-1)+(a+1)*cw);
        a2=(a+1)+(a-1)*cw-2*sqrtA*al;
        break;
      }
      case FilterType::HighShelf: {
        const double al=sw/2 * std::sqrt(2.0);
        b0=a*((a+1)+(a-1)*cw+2*sqrtA*al);
        b1=-2*a*((a-1)+(a+1)*cw);
        b2=a*((a+1)+(a-1)*cw-2*sqrtA*al);
        a0=(a+1)-(a-1)*cw+2*sqrtA*al;
        a1=2*((a-1)-(a+1)*cw);
        a2=(a+1)-(a-1)*cw-2*sqrtA*al;
        break;
      }
    }
  }
  if (!std::isfinite(a0) || std::abs(a0) < 1e-15)
    throw std::invalid_argument("unstable normalization");
  b0_ = b0/a0; b1_=b1/a0; b2_=b2/a0;
  a1_=a1/a0; a2_=a2/a0;
  reset();
}
float Biquad::process(float x) noexcept {
  const double y=b0_*static_cast<double>(x)+z1_;
  const double z1=b1_*static_cast<double>(x)-a1_*y+z2_;
  z2_=b2_*static_cast<double>(x)-a2_*y;
  z1_=z1;
  if (std::abs(z1_)<1e-26) z1_=0.0;
  if (std::abs(z2_)<1e-26) z2_=0.0;
  return static_cast<float>(y);
}
double Biquad::responseDb(double hz, double fs) const noexcept {
  if (hz <= 0 || hz >= fs/2) return 0.0;
  using C=std::complex<double>;
  const double w=2.0*std::numbers::pi*hz/fs;
  const C z=std::polar(1.0, -w);
  const C numerator=b0_+b1_*z+b2_*z*z;
  const C denominator=1.0+a1_*z+a2_*z*z;
  const auto amp=std::abs(numerator/denominator);
  return amp>1e-15 ? 20.0*std::log10(amp) : -300.0;
}
}
