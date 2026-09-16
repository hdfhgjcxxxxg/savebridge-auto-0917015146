#include <3ds.h>
#include <3ds/archive.h>
#include <3ds/util/utf.h>
#include <sys/socket.h>
#include <arpa/inet.h>
#include <poll.h>
#include <malloc.h>
#include <algorithm>
#include <memory>
#include "package.hpp"
using namespace sb;
static constexpr uint64_t OWN=0x000400000ECDB100ULL;
struct Game{uint64_t id;uint32_t media;std::string name;};
std::vector<Game> catalog;
std::string pin;int authFailures=0;bool locked=false;
const std::string BASE="sdmc:/3ds/SaveBridgeMulti/";
void check(Result r,const char*msg){if(R_FAILED(r)){char b[128];snprintf(b,sizeof b,"%s (%08lX)",msg,(unsigned long)r);throw std::runtime_error(b);}}
std::string label(uint64_t id){char s[20];snprintf(s,sizeof s,"%016llX",(unsigned long long)id);return s;}
std::string prefix(Game g){return BASE+label(g.id)+"-"+std::to_string(g.media);}
struct Mount{Mount(Game g){uint32_t p[]={g.media,uint32_t(g.id),uint32_t(g.id>>32)};check(archiveMount(ARCHIVE_USER_SAVEDATA,{PATH_BINARY,sizeof p,p},"sbgame"),"open save");}~Mount(){archiveUnmount("sbgame");}};
void secure(Game g){if(g.media==MEDIATYPE_SD){uint64_t v=(uint64_t(SECUREVALUE_SLOT_SD)<<32)|(uint32_t(g.id)&0xffffff00);u8 out=0;check(FSUSER_ControlSecureSave(SECURESAVE_ACTION_DELETE,&v,sizeof v,&out,sizeof out),"secure value");}}
std::string gameName(Game g){uint32_t ap[]={uint32_t(g.id),uint32_t(g.id>>32),g.media,0};uint32_t fp[]={0,0,2,0x6e6f6369,0};Handle h;Result r=FSUSER_OpenFileDirectly(&h,ARCHIVE_SAVEDATA_AND_CONTENT,{PATH_BINARY,sizeof ap,ap},{PATH_BINARY,sizeof fp,fp},FS_OPEN_READ,0);if(R_FAILED(r))return label(g.id);std::array<uint8_t,0x36c0> smdh{};u32 n=0;r=FSFILE_Read(h,&n,0,smdh.data(),smdh.size());FSFILE_Close(h);if(R_FAILED(r)||n!=smdh.size())return label(g.id);uint16_t title[65]={};memcpy(title,smdh.data()+8,128);uint8_t out[260]={};auto size=utf16_to_utf8(out,title,259);if(size<=0)return label(g.id);out[size]=0;return reinterpret_cast<char*>(out);}
void enumerate(){catalog.clear();for(auto media:{MEDIATYPE_SD,MEDIATYPE_GAME_CARD}){u32 n=0;if(R_FAILED(AM_GetTitleCount(media,&n))||n>2048)continue;std::vector<u64>ids(n);u32 got=0;if(n&&R_FAILED(AM_GetTitleList(&got,media,n,ids.data())))continue;for(u32 i=0;i<got;i++){if((ids[i]>>32)!=0x00040000||ids[i]==OWN)continue;Game g{ids[i],uint32_t(media),""};try{Mount m(g);}catch(...){continue;}g.name=gameName(g);catalog.push_back(g);}}}
bool exists(const std::string&p){struct stat st;return stat(p.c_str(),&st)==0;}
void recover(Game g){std::string p=prefix(g);if(!exists(p+".pending"))return;auto old=read(p+".undo");decode(old);Mount m(g);clear("sbgame:/");install("sbgame:/",old);check(archiveCommitSaveData("sbgame"),"recovery commit");secure(g);need(snapshot("sbgame:/")==old,"recovery verification failed");need(unlink((p+".pending").c_str())==0,"recovery journal removal failed");}
void restore(Game g,const Bytes&data,const std::array<uint8_t,32>&expected){need(!locked,"recovery required; restart CIA");auto t=decode(data);bool files=false;for(auto&e:t)files|=!e.second.dir;need(files,"empty save rejected");std::string p=prefix(g);need(!exists(p+".pending"),"pending recovery");Mount m(g);auto old=snapshot("sbgame:/");need(sha256(old)==expected,"CONFLICT: save changed on 3DS");
 write(p+".undo",old);write(p+"-"+std::to_string(osGetTime())+".sbpk",old);write(p+".pending",Bytes{1});
 try{clear("sbgame:/");install("sbgame:/",data);check(archiveCommitSaveData("sbgame"),"commit");secure(g);need(snapshot("sbgame:/")==data,"restore verification failed");need(unlink((p+".pending").c_str())==0,"journal completion failed");}
 catch(...){try{clear("sbgame:/");install("sbgame:/",old);check(archiveCommitSaveData("sbgame"),"rollback commit");secure(g);need(snapshot("sbgame:/")==old,"rollback verification failed");need(unlink((p+".pending").c_str())==0,"rollback journal failed");}catch(...){locked=true;}throw;}
}
void transfer(int fd,void*buf,size_t size,bool writing){auto*p=static_cast<uint8_t*>(buf);uint64_t deadline=osGetTime()+120000;while(size){need(osGetTime()<deadline,"network timeout");pollfd f{fd,short(writing?POLLOUT:POLLIN),0};int ready=poll(&f,1,1000);need(ready>=0,"socket poll failed");if(!ready)continue;int n=writing?send(fd,p,std::min(size,size_t(32768)),0):recv(fd,p,std::min(size,size_t(32768)),0);need(n>0,"connection closed");p+=n;size-=n;}}
uint32_t number(int fd){uint8_t b[4];transfer(fd,b,4,false);return uint32_t(b[0])<<24|uint32_t(b[1])<<16|uint32_t(b[2])<<8|b[3];}
void reply(int fd,uint32_t status,Bytes b){Bytes h;put32(h,status);put32(h,b.size());transfer(fd,h.data(),h.size(),true);if(!b.empty())transfer(fd,b.data(),b.size(),true);}
void client(int fd){try{need(number(fd)==0x53425333,"wrong protocol");uint8_t len[2];transfer(fd,len,2,false);size_t n=(len[0]<<8)|len[1];need(n==8,"PIN must be 8 digits");char p[8];transfer(fd,p,8,false);int diff=0;for(int i=0;i<8;i++)diff|=p[i]^pin[i];if(diff){authFailures++;throw std::runtime_error("wrong PIN");}authFailures=0;uint32_t op=number(fd);need(op>=1&&op<=3,"unknown operation");
 if(op==1){Bytes b;put32(b,catalog.size());for(auto&g:catalog){put64(b,g.id);put32(b,g.media);str(b,g.name);}reply(fd,0,std::move(b));return;}
 uint64_t id=uint64_t(number(fd))<<32;id|=number(fd);uint32_t media=number(fd);auto it=std::find_if(catalog.begin(),catalog.end(),[&](const Game&g){return g.id==id&&g.media==media;});need(it!=catalog.end(),"game not available");Game g=*it;need(!locked,"restore recovery required");
 if(op==2){Mount m(g);reply(fd,0,snapshot("sbgame:/"));return;}
 std::array<uint8_t,32>expected{};transfer(fd,expected.data(),32,false);n=number(fd);need(n<=LIMIT,"16MiB limit");Bytes data(n);transfer(fd,data.data(),n,false);auto hash=sha256(data);restore(g,data,expected);reply(fd,0,Bytes(hash.begin(),hash.end()));printf("Restored %s\n",label(id).c_str());
 }catch(const std::exception&e){printf("%s\n",e.what());try{std::string m=e.what();reply(fd,1,Bytes(m.begin(),m.end()));}catch(...){}}}
int main(){gfxInitDefault();consoleInit(GFX_TOP,nullptr);printf("SaveBridge Multi 0.3 (prototype)\n\n");void*mem=nullptr;int server=-1;bool soc=false;try{
 check(amInit(),"amInit");check(psInit(),"psInit");check(acInit(),"acInit");mkdir("sdmc:/3ds",0777);mkdir(BASE.c_str(),0777);
 uint32_t rand=0;check(PS_GenerateRandomBytes(&rand,sizeof rand),"random PIN");char code[9];snprintf(code,sizeof code,"%08lu",(unsigned long)(rand%100000000));pin=code;
 enumerate();
 // Recover any interrupted restore, including a removed cartridge: block until reinserted.
 DIR*d=opendir(BASE.c_str());need(d!=nullptr,"backup directory unavailable");struct dirent*ent;std::vector<Game>pending;while((ent=readdir(d))){std::string f=ent->d_name;if(f.size()>8&&f.substr(f.size()-8)==".pending"){unsigned long long id;unsigned media;if(sscanf(f.c_str(),"%16llX-%u.pending",&id,&media)==2)pending.push_back({uint64_t(id),media,""});}}closedir(d);
 for(auto&g:pending){printf("Recovering %s...\n",label(g.id).c_str());recover(g);}
 mem=memalign(0x1000,0x100000);need(mem!=nullptr,"socket memory");check(socInit(static_cast<u32*>(mem),0x100000),"socInit");soc=true;
 server=socket(AF_INET,SOCK_STREAM,0);need(server>=0,"socket failed");sockaddr_in addr{};addr.sin_family=AF_INET;addr.sin_port=htons(38473);addr.sin_addr.s_addr=INADDR_ANY;need(bind(server,(sockaddr*)&addr,sizeof addr)==0,"bind failed");need(listen(server,2)==0,"listen failed");in_addr ip;ip.s_addr=gethostid();
 printf("Games with saves: %u\nIP: %s\nPort: 38473\nPIN: %s\n\n",unsigned(catalog.size()),inet_ntoa(ip),pin.c_str());
 printf("Keep this CIA open while syncing.\nUse the Android app to select games.\nBackups: /3ds/SaveBridgeMulti/\n\nX: rescan games   START: exit\n");aptSetSleepAllowed(false);
 while(aptMainLoop()){hidScanInput();u32 keys=hidKeysDown();if(keys&KEY_START)break;if(keys&KEY_X){enumerate();printf("Rescanned: %u games\n",unsigned(catalog.size()));}pollfd f{server,POLLIN,0};if(poll(&f,1,0)>0){int fd=accept(server,nullptr,nullptr);if(fd>=0){client(fd);close(fd);}if(authFailures>=5)throw std::runtime_error("Too many wrong PINs. Restart to retry.");}gfxFlushBuffers();gfxSwapBuffers();gspWaitForVBlank();}
 }catch(const std::exception&e){printf("\nStopped: %s\nPress START to exit.\n",e.what());while(aptMainLoop()){hidScanInput();if(hidKeysDown()&KEY_START)break;gspWaitForVBlank();}}
 if(server>=0)close(server);if(soc)socExit();free(mem);acExit();psExit();amExit();gfxExit();return 0;}
