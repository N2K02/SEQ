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
  empty[0]=Band{}; // Isolate FIR tests from the earlier +6 dB EQ filter.
  std::array<float,2> impulseL{1.0f,0.0f},impulseR{1.0f,0.0f};
  p.configure(empty,48000,true);
  p.configureFir(impulseL.data(),impulseR.data(),2);
  assert(p.firTaps()==2);
  float raw[6]{.1f,-.1f,.2f,-.2f,.3f,-.3f};
  p.process(raw,3);
  assert(std::abs(raw[0]-.1f)<1e-5f && std::abs(raw[3]+.2f)<1e-5f);
  const float half[2]{.5f,.5f};
  p.configure(empty,48000,true);
  p.configureFir(half,half,2);
  float impulse[8]{1.f,1.f,0,0,0,0,0,0};
  p.process(impulse,4);
  assert(std::abs(impulse[0]-.5f)<1e-5f);
  assert(std::abs(impulse[2]-.5f)<1e-5f);
  p.configure(empty,48000,true);
  p.configurePeakGuard(true,-1.0);
  std::vector<float> hot(4096,1.0f);
  p.process(hot.data(),hot.size()/2);
  for(float v:hot) assert(v<0.9f && v>=0.0f);
  assert(p.observedPeak()>=1.0);
  p.configure(empty,48000,false);
  p.configurePeakGuard(true,-1.0);
  float still[2]{1.f,-1.f};
  p.process(still,1);
  assert(still[0]==1.f && still[1]==-1.f); // Pure is identity.
  // Limiter: half headroom keeps loudness, lookahead envelope keeps peaks under the ceiling.
  {
    std::array<Band,MAX_BANDS> bass{};
    bass[0]=Band{FilterType::LowShelf,110.0,6.0,0.7071,true};
    Processor safe, loud;
    safe.configure(bass,48000,true);
    loud.configure(bass,48000,true);
    loud.configurePeakGuard(true,-1.0);
    assert(loud.pregainDb()>safe.pregainDb()+2.5);
    std::vector<float> a(48000*2), b;
    for(std::size_t i=0;i<a.size()/2;i++) {
      const float v=std::sin(2.0*3.141592653589793*60.0*i/48000.0)*(i%9600<960 ? 0.9f:0.45f);
      a[2*i]=v; a[2*i+1]=v;
    }
    b=a;
    safe.process(a.data(),a.size()/2);
    for(std::size_t off=0;off<b.size();off+=2*480) // odd block sizes across boundaries
      loud.process(b.data()+off,std::min<std::size_t>(480,(b.size()-off)/2));
    const float ceiling=std::pow(10.0f,-1.0f/20.0f);
    double rmsA=0, rmsB=0;
    for(std::size_t i=0;i<a.size();i++) {
      assert(std::isfinite(b[i]) && std::abs(b[i])<=ceiling+1e-6f);
      rmsA+=a[i]*a[i]; rmsB+=b[i]*b[i];
    }
    assert(loud.clippedSamples()==0 && safe.clippedSamples()==0);
    assert(rmsB>rmsA*1.35); // louder than full-headroom path, yet bounded
  }
  // FIR normalization never amplifies any frequency above unity.
  {
    std::array<Band,MAX_BANDS> flat{};
    Processor fir;
    fir.configure(flat,48000,true);
    const float l[3]{.6f,.6f,-.6f}, r[3]{.6f,.6f,-.6f};
    fir.configureFir(l,r,3);
    std::vector<float> noise(20000);
    unsigned x=12345;
    for(auto& v:noise) { x=x*1664525u+1013904223u; v=((x>>9)/8388608.0f-0.5f)*1.8f; }
    fir.process(noise.data(),noise.size()/2);
    for(float v:noise) assert(std::abs(v)<=1.0f);
  }
  std::cout << "SEQ reference DSP: all tests passed\n";
}
