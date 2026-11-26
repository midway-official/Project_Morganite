#include "VGraphicsLane.h"
#include <algorithm>
#include <array>
#include <cassert>
#include <cmath>
#include <cstring>
#include <deque>
#include <iostream>
#include <random>

struct Sample {unsigned fn,a,b,expected; double ref;};
static float asFloat(unsigned v) {float f;std::memcpy(&f,&v,4);return f;}
static unsigned blend(unsigned a,unsigned b) {
    unsigned out=0,alpha=a>>24;
    for(unsigned i=0;i<4;i++){
        unsigned src=i==3?255:(a>>(8*i))&255, dst=(b>>(8*i))&255;
        out|=((src*alpha+dst*(255-alpha)+127)/255)<<(8*i);
    }
    return out;
}
int main() {
    VGraphicsLane d;
    std::mt19937 random(753);
    std::deque<Sample> pipe;
    std::array<double,2> worst{};
    std::array<unsigned,2> tested{};
    auto send=[&](Sample s) {
        pipe.push_back(s);d.io_op=s.fn;d.io_a=s.a;d.io_b=s.b;d.io_c=0;
        d.clock=0;d.eval();d.clock=1;d.eval();
        if(pipe.size()<6)return;
        auto p=pipe.front();pipe.pop_front();
        if(p.fn>=9)++tested[p.fn-9];
        if(p.ref==0){assert(d.io_result==p.expected);return;}
        double err=std::abs(asFloat(d.io_result)/p.ref-1);
        assert(std::isfinite(err) && err<(p.fn==9?0.004:0.0021));
        worst[p.fn-9]=std::max(worst[p.fn-9],err);
    };
    auto sfu=[&](unsigned fn,unsigned bits) {
        unsigned e=(bits>>23)&255, m=bits&0x7fffff, sign=bits&0x80000000;
        double ref=0;
        unsigned expected=0;
        if(e==0)expected=sign|0x7f800000;
        else if((fn==10 && sign) || (e==255 && m))expected=0x7fc00000;
        else if(e==255)expected=sign;
        else {
            double x=asFloat(bits);
            double v=fn==9?1/x:1/std::sqrt(x);
            if(std::abs(v)<std::ldexp(1.0,-126))expected=sign;
            else if(!m && (fn==9 || e%2==1)){
                float rounded=v;std::memcpy(&expected,&rounded,4);
            }else ref=v;
        }
        send({fn,bits,0,expected,ref});
    };
    for(unsigned fn:{9u,10u}){
        for(unsigned v:{0u,1u,0x007fffffu,0x00800000u,0x7f7fffffu,0x7f800000u,0x7fa00001u,0x7fc00000u})
            for(unsigned sign:{0u,0x80000000u})sfu(fn,v|sign);
        for(unsigned e=1;e<255;e++)for(unsigned i=0;i<128;i++)
            for(unsigned end:{0u,65535u})sfu(fn,(e<<23)|(i<<16)|end);
        for(unsigned i=0;i<100000;i++)sfu(fn,random());
    }
    for(unsigned i=0;i<20000;i++){
        unsigned a=random(),b=random();
        send({7,a,b,unsigned(asFloat(a)<asFloat(b)),0});
        send({8,a,b,blend(a,b),0});
    }
    for(unsigned i=0;i<6;i++)send({8,0,0,0,0});
    std::cout<<"{\"rcp_cases\":"<<tested[0]<<",\"rsqrt_cases\":"<<tested[1]
             <<",\"max_rcp_relative\":"<<worst[0]<<",\"max_rsqrt_relative\":"<<worst[1]<<"}\n";
}
