#pragma once
#include <vector>
#include <map>
#include <string>
#include <stdexcept>
#include <cstdint>
#include <cstring>
#include <cerrno>
#include <cstdio>
#include <dirent.h>
#include <sys/stat.h>
#include <unistd.h>
#include "sha256.hpp"
namespace sb {
using Bytes=std::vector<uint8_t>;
const size_t LIMIT=16*1024*1024,MAX=2048;
struct Entry{bool dir;Bytes data;};
using Tree=std::map<std::string,Entry>;
inline std::string rootpath(const std::string&root){return root.empty()||root.back()=='/'?root:root+"/";}
inline void need(bool c,const char*m){if(!c)throw std::runtime_error(m);}
inline void put32(Bytes&v,uint32_t x){for(int i=3;i>=0;i--)v.push_back(x>>(i*8));}
inline void put64(Bytes&v,uint64_t x){for(int i=7;i>=0;i--)v.push_back(x>>(i*8));}
inline void str(Bytes&v,const std::string&s){need(s.size()<65536,"name too long");v.push_back(s.size()>>8);v.push_back(s.size());v.insert(v.end(),s.begin(),s.end());}
struct Reader{const Bytes&v;size_t p=0;uint32_t num(int n){need(n<=8&&p+n<=v.size(),"truncated package");uint32_t a=0;while(n--)a=(a<<8)|v[p++];return a;}std::string text(){size_t n=num(2);need(n<=480&&p+n<=v.size(),"bad path length");std::string s(v.begin()+p,v.begin()+p+n);p+=n;return s;}};
inline void path(const std::string&s){need(!s.empty()&&s.size()<=480&&s.front()!='/'&&s.back()!='/'&&s.find('\\')==s.npos&&s.find(':')==s.npos&&s.find('\0')==s.npos,"bad path");size_t pos=0;while(pos<s.size()){size_t end=s.find('/',pos);if(end==s.npos)end=s.size();auto x=s.substr(pos,end-pos);need(!x.empty()&&x!="."&&x!="..","bad path component");pos=end+1;}}
inline Tree decode(const Bytes&b){need(b.size()<=LIMIT,"save too large");Reader r{b};need(r.num(4)==0x53425031,"bad package magic");size_t n=r.num(4);need(n<=MAX,"too many files");Tree t;std::string prev;for(size_t i=0;i<n;i++){int kind=r.num(1);std::string p=r.text();path(p);need(kind<=1&&(i==0||prev<p),"bad entry/order");prev=p;auto slash=p.rfind('/');if(slash!=p.npos){auto it=t.find(p.substr(0,slash));need(it!=t.end()&&it->second.dir,"missing parent");}size_t len=r.num(4);need(len<=b.size()-r.p&&(kind==1||len==0),"bad entry length");Entry e{kind==0,{}};e.data.assign(b.begin()+r.p,b.begin()+r.p+len);r.p+=len;t.emplace(p,std::move(e));}need(r.p==b.size(),"trailing data");return t;}
inline Bytes encode(const Tree&t){need(t.size()<=MAX,"too many entries");Bytes b;put32(b,0x53425031);put32(b,t.size());for(auto&e:t){path(e.first);b.push_back(e.second.dir?0:1);str(b,e.first);put32(b,e.second.data.size());b.insert(b.end(),e.second.data.begin(),e.second.data.end());need(b.size()<=LIMIT,"save exceeds 16MiB");}return b;}
inline Bytes read(const std::string&p){FILE*f=fopen(p.c_str(),"rb");need(f!=nullptr,"cannot read file");Bytes b;uint8_t buf[32768];size_t n;while((n=fread(buf,1,sizeof buf,f))>0){if(b.size()+n>LIMIT){fclose(f);throw std::runtime_error("save too large");}b.insert(b.end(),buf,buf+n);}bool ok=!ferror(f);fclose(f);need(ok,"read failed");return b;}
inline void write(const std::string&p,const Bytes&b){FILE*f=fopen(p.c_str(),"wb");need(f!=nullptr,"cannot create file");bool ok=fwrite(b.data(),1,b.size(),f)==b.size();ok=(fflush(f)==0)&&ok;ok=(fclose(f)==0)&&ok;need(ok,"write failed");need(read(p)==b,"write verification failed");}
inline void scan(const std::string&root,const std::string&rel,Tree&t,size_t&size){DIR*d=opendir((root+rel).c_str());need(d!=nullptr,"cannot read directory");try{struct dirent*e;errno=0;while((e=readdir(d))){std::string n=e->d_name;if(n=="."||n=="..")continue;std::string p=rel+n;path(p);need(t.size()<MAX,"too many files");struct stat st;need(stat((root+p).c_str(),&st)==0,"stat failed");if(S_ISDIR(st.st_mode)){t.emplace(p,Entry{true,{}});scan(root,p+"/",t,size);}else{need(S_ISREG(st.st_mode),"unsupported file type");need(st.st_size>=0&&uint64_t(st.st_size)+size<=LIMIT,"total size limit");auto b=read(root+p);size+=b.size();t.emplace(p,Entry{false,std::move(b)});}errno=0;}need(errno==0,"directory read failed");closedir(d);}catch(...){closedir(d);throw;}}
inline Bytes snapshot(const std::string&root){Tree t;size_t size=0;auto r=rootpath(root);scan(r,"",t,size);return encode(t);}
inline void clear(const std::string&root){Tree t;size_t size=0;auto r=rootpath(root);scan(r,"",t,size);for(auto i=t.rbegin();i!=t.rend();++i){int rc=i->second.dir?rmdir((r+i->first).c_str()):unlink((r+i->first).c_str());need(rc==0,"clear failed");}}
inline void install(const std::string&root,const Bytes&b){auto r=rootpath(root);auto t=decode(b);for(auto&e:t){if(e.second.dir)need(mkdir((r+e.first).c_str(),0777)==0,"mkdir failed");else write(r+e.first,e.second.data);}}
}
