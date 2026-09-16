package com.savebridge.multisync;
import java.io.*;
import java.net.*;
import java.util.*;
final class Peer {
 final String ip,pin;Peer(String ip,String pin){this.ip=ip;this.pin=pin;}
 static void str(DataOutputStream d,String s)throws Exception{byte[]b=s.getBytes("UTF-8");if(b.length>65535)throw new IOException("文字列超過");d.writeShort(b.length);d.write(b);}
 static String str(DataInputStream d)throws Exception{int n=d.readUnsignedShort();byte[]b=new byte[n];d.readFully(b);return new String(b,"UTF-8");}
 byte[] request(int op,String tid,int media,byte[]expected,byte[]payload)throws Exception{
  try(Socket s=new Socket()){s.connect(new InetSocketAddress(ip,38473),5000);s.setSoTimeout(120000);DataOutputStream out=new DataOutputStream(s.getOutputStream());out.writeInt(0x53425333);str(out,pin);out.writeInt(op);
   if(op>1){out.writeLong(Long.parseUnsignedLong(tid,16));out.writeInt(media);}if(op==3){out.write(expected);out.writeInt(payload.length);out.write(payload);}out.flush();
   DataInputStream in=new DataInputStream(s.getInputStream());int status=in.readInt(),n=in.readInt();if(n<0||n>Bundle.LIMIT)throw new IOException("受信容量不正");byte[]b=new byte[n];in.readFully(b);if(status!=0)throw new IOException("3DS: "+new String(b,"UTF-8"));return b;
  }
 }
 static class Game {String tid,name;int media;Game(String t,String n,int m){tid=t;name=n;media=m;}String id(){return tid+":"+media;}}
 List<Game> list()throws Exception{DataInputStream d=new DataInputStream(new ByteArrayInputStream(request(1,"",0,null,null)));int n=d.readInt();if(n<0||n>2048)throw new IOException("タイトル数不正");List<Game>games=new ArrayList<>();for(int i=0;i<n;i++){String t=String.format(Locale.ROOT,"%016X",d.readLong());int m=d.readInt();games.add(new Game(t,str(d),m));}if(d.available()!=0)throw new IOException("応答不正");return games;}
 byte[] get(Game g)throws Exception{byte[]b=request(2,g.tid,g.media,null,null);Bundle.decode(b);return b;}
 void put(Game g,byte[]expected,byte[]data)throws Exception{byte[]result=request(3,g.tid,g.media,expected,data);if(!Arrays.equals(result,Bundle.hash(data)))throw new IOException("3DS書込後ハッシュ不一致");}
}
