#include "seq/Processor.h"
#include <array>
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>

using namespace seq;
int main() {
  std::array<Band,MAX_BANDS> empty{};
  Processor p;
  p.configure(empty,48000,false);
  const float input[]={.0125f,-.0125f,.67f,-.67f,1.0f,-1.0f};
  float original[6];
  for(int i=0;i<6;i++) original[i]=input[i];
  p.process(original,3);
  for(int i=0;i<6;i++) assert(original[i]==input[i]);
  assert(p.clippedSamples()==0 && p.pregainDb()==0.0);

  p.configure(empty,48000,true);
  float neutral[6];
  for(int i=0;i<6;i++) neutral[i]=input[i];
  p.process(neutral,3);
  for(int i=0;i<6;i++) assert(std::abs(neutral[i]-input[i])<2e-6);
  assert(p.clippedSamples()==0);

  empty[0]=Band{FilterType::Peak,1000.0,6.0,1.0,true};
  p.configure(empty,48000,true);
  assert(p.pregainDb()< -5.7 && p.pregainDb()> -6.2);
  std::vector<float> wave(96000*2);
  for(std::size_t i=0;i<wave.size()/2;i++) {
    const float s=0.8f*std::sin(2.0*3.141592653589793*1000.0*i/48000.0);
    wave[2*i]=s; wave[2*i+1]=-s;
  }
  p.process(wave.data(),wave.size()/2);
  assert(p.clippedSamples()==0);
  for(float s : wave) assert(std::isfinite(s) && std::abs(s)<=1.0f);
  for(std::size_t i=20000;i<wave.size()/2;i+=203)
    assert(std::abs(wave[2*i]+wave[2*i+1]) < 2e-5f); // no inter-channel crosstalk
  bool threw=false;
  try { p.configure(empty,0,true); } catch(const std::invalid_argument&) { threw=true; }
  assert(threw);

  Biquad shelf;
  shelf.configure(Band{FilterType::LowShelf,200,3.0,0.707,true},48000);
  assert(shelf.responseDb(30,48000)>2.5);
  assert(std::abs(shelf.responseDb(16000,48000))<0.2);
  shelf.configure(Band{FilterType::HighShelf,3000,-2.0,0.707,true},48000);
  assert(shelf.responseDb(15000,48000)<-1.5);

  for (const double rate : {44100.0,48000.0,96000.0}) {
    Biquad stable;
    stable.configure(Band{FilterType::Peak,1200.0,10.0,6.0,true},rate);
    double sum=0;
    for(int i=0;i<25000;i++) {
      const float v=stable.process(i==0?1.0f:0.0f);
      assert(std::isfinite(v));
      sum+=std::abs(v);
    }
    assert(sum<100.0);
    assert(std::abs(stable.responseDb(1200.0,rate)-10.0)<0.2);
  }
  std::cout << "SEQ reference DSP: all tests passed\n";
}
