package com.savebridge.multisync;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Deterministic protocol container, not a 3DS encrypted save image. */
public final class Bundle {
 public static final int LIMIT=16*1024*1024, MAX=2048;
 public static final Comparator<String> ORDER=(a,b)->{byte[]x=a.getBytes(StandardCharsets.UTF_8),y=b.getBytes(StandardCharsets.UTF_8);for(int i=0;i<Math.min(x.length,y.length);i++){int d=(x[i]&255)-(y[i]&255);if(d!=0)return d;}return x.length-y.length;};
 public final TreeMap<String,byte[]> files=new TreeMap<>(ORDER); // null = directory
 public static void path(String p)throws IOException{
  if(p.isEmpty()||p.getBytes(StandardCharsets.UTF_8).length>480||p.startsWith("/")||p.endsWith("/")||p.contains("\\")||p.indexOf(0)>=0||p.contains(":"))throw new IOException("不正なセーブパス");
  for(String s:p.split("/",-1))if(s.isEmpty()||s.equals(".")||s.equals(".."))throw new IOException("不正なセーブパス");
 }
 public byte[] encode()throws IOException{
  if(files.size()>MAX)throw new IOException("ファイル数上限");
  ByteArrayOutputStream b=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(b);d.writeInt(0x53425031);d.writeInt(files.size());
  for(Map.Entry<String,byte[]>e:files.entrySet()){path(e.getKey());byte[]p=e.getKey().getBytes(StandardCharsets.UTF_8),v=e.getValue();d.writeByte(v==null?0:1);d.writeShort(p.length);d.write(p);d.writeInt(v==null?0:v.length);if(v!=null)d.write(v);if(b.size()>LIMIT)throw new IOException("16MiBを超えるセーブは未対応");}
  return b.toByteArray();
 }
 public static Bundle decode(byte[]b)throws IOException{
  if(b.length>LIMIT)throw new IOException("容量超過");DataInputStream d=new DataInputStream(new ByteArrayInputStream(b));if(d.readInt()!=0x53425031)throw new IOException("形式不一致");int n=d.readInt();if(n<0||n>MAX)throw new IOException("件数不正");Bundle r=new Bundle();String previous=null;
  for(int i=0;i<n;i++){int kind=d.readUnsignedByte(),len=d.readUnsignedShort();if(len<1||len>480)throw new IOException("名前長不正");byte[]p=new byte[len];d.readFully(p);String name;
   try{name=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(p)).toString();}catch(CharacterCodingException e){throw new IOException("UTF8不正");}
   path(name);if(previous!=null&&ORDER.compare(previous,name)>=0)throw new IOException("重複または順序不正");previous=name;
   int slash=name.lastIndexOf('/');if(slash>=0&&(!r.files.containsKey(name.substring(0,slash))||r.files.get(name.substring(0,slash))!=null))throw new IOException("親フォルダ不正");
   int size=d.readInt();if(kind>1||size<0||size>d.available()||(kind==0&&size!=0))throw new IOException("サイズ不正");byte[]v=null;if(kind==1){v=new byte[size];d.readFully(v);}r.files.put(name,v);
  }
  if(d.available()!=0)throw new IOException("余剰データ");return r;
 }
 public boolean hasFile(){for(byte[]v:files.values())if(v!=null)return true;return false;}
 public static byte[] hash(byte[]b){try{return MessageDigest.getInstance("SHA-256").digest(b);}catch(Exception e){throw new IllegalStateException(e);}}
 public static String hex(byte[]b){StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.ROOT,"%02x",x&255));return s.toString();}
 public static String decide(String base,String local,String remote){if(local.equals(remote))return "same";if(base.isEmpty())return "conflict";if(local.equals(base))return "pull";if(remote.equals(base))return "push";return "conflict";}
}
