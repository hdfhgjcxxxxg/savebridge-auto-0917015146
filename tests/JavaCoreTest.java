package com.savebridge.threeDS;
import java.io.*;
import java.nio.file.*;
import java.util.*;
public class JavaCoreTest {
 public static void main(String[]args)throws Exception {
  byte[]b=Files.readAllBytes(Paths.get(args[1]));
  if(args[0].equals("hash")){System.out.print(Bundle.hex(Bundle.hash(b)));return;}
  if(args[0].equals("validate")){if(!Arrays.equals(b,Bundle.decode(b).encode()))throw new AssertionError("canonical");return;}
  if(args[0].equals("decide")){
   String[][]cases={{"","a","b","conflict"},{"a","b","c","conflict"},{"a","b","a","push"},{"a","a","b","pull"},{"a","b","b","same"},{"","a","a","same"}};
   for(String[]c:cases)if(!Bundle.decide(c[0],c[1],c[2]).equals(c[3]))throw new AssertionError(Arrays.toString(c));return;
  }
  if(args[0].equals("peer")){Peer p=new Peer("127.0.0.1","12345678");List<Peer.Game>games=p.list();if(games.size()!=2)throw new AssertionError("multiple games");for(Peer.Game g:games){byte[]raw=p.get(g);Bundle.decode(raw);p.put(g,Bundle.hash(raw),b);}return;}
 }
}
