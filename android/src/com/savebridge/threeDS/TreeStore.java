package com.savebridge.multisync;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.util.*;

final class TreeStore {
 final Context c;final ContentResolver cr;final Uri grant;
 TreeStore(Context c,Uri grant){this.c=c;this.cr=c.getContentResolver();this.grant=grant;}
 Uri root(){return DocumentsContract.buildDocumentUriUsingTree(grant,DocumentsContract.getTreeDocumentId(grant));}
 static class Doc {Uri uri;String name;boolean dir;Doc(Uri u,String n,boolean d){uri=u;name=n;dir=d;}}
 List<Doc> children(Uri p)throws Exception{
  List<Doc>out=new ArrayList<>();Uri q=DocumentsContract.buildChildDocumentsUriUsingTree(grant,DocumentsContract.getDocumentId(p));
  try(Cursor r=cr.query(q,new String[]{"document_id","_display_name","mime_type"},null,null,null)){
   if(r==null)throw new IOException("フォルダを読めません");while(r.moveToNext())out.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(grant,r.getString(0)),r.getString(1),DocumentsContract.Document.MIME_TYPE_DIR.equals(r.getString(2))));}
  return out;
 }
 Map<String,Uri> scan()throws Exception{Map<String,Uri>out=new TreeMap<>();int[]count={0};walkScan(root(),"",0,count,out);return out;}
 void walkScan(Uri p,String path,int depth,int[] count,Map<String,Uri>out)throws Exception{
  if(depth>10||++count[0]>10000)throw new IOException("フォルダが広すぎます。Azaharのユーザーフォルダを選択してください");
  java.util.regex.Matcher m=java.util.regex.Pattern.compile("(?:^|/)title/(00040000)/([0-9a-f]{8})/([0-9a-f]{8})/data/00000001$").matcher(path.toLowerCase(Locale.ROOT));
  if(m.find()){String tid=(m.group(1)+m.group(2)+m.group(3)).toUpperCase(Locale.ROOT);if(out.containsKey(tid))throw new IOException("同一ゲームの保存先が複数あります。対象のユーザーフォルダへ絞ってください: "+tid);out.put(tid,p);return;}
  for(Doc d:children(p))if(d.dir&&!Arrays.asList("cache","shaders","load","dump","log","states").contains(d.name.toLowerCase(Locale.ROOT)))walkScan(d.uri,path+"/"+d.name,depth+1,count,out);
 }
 byte[] snapshot(Uri root)throws Exception{Bundle b=new Bundle();long[]size={0};walk(root,"",b,size);return b.encode();}
 void walk(Uri p,String base,Bundle b,long[]size)throws Exception{
  for(Doc d:children(p)){String path=base+d.name;Bundle.path(path);if(b.files.containsKey(path)||b.files.size()>=Bundle.MAX)throw new IOException("重複または件数上限");
   if(d.dir){b.files.put(path,null);walk(d.uri,path+"/",b,size);}else{
    ByteArrayOutputStream o=new ByteArrayOutputStream();try(InputStream in=cr.openInputStream(d.uri)){if(in==null)throw new IOException("読込失敗");byte[]buf=new byte[32768];int n;while((n=in.read(buf))!=-1){size[0]+=n;if(size[0]>Bundle.LIMIT)throw new IOException("16MiB上限");o.write(buf,0,n);}}b.files.put(path,o.toByteArray());}
  }
 }
 void clear(Uri root)throws Exception{for(Doc d:children(root))if(!DocumentsContract.deleteDocument(cr,d.uri))throw new IOException("削除失敗: "+d.name);}
 void write(Uri root,byte[]raw)throws Exception{
  Bundle b=Bundle.decode(raw);Map<String,Uri>dirs=new HashMap<>();dirs.put("",root);
  for(Map.Entry<String,byte[]> e:b.files.entrySet()){String path=e.getKey();int slash=path.lastIndexOf('/');Uri parent=dirs.get(slash<0?"":path.substring(0,slash));String name=path.substring(slash+1);byte[]data=e.getValue();
   Uri u=DocumentsContract.createDocument(cr,parent,data==null?DocumentsContract.Document.MIME_TYPE_DIR:"application/octet-stream",name);if(u==null)throw new IOException("書込先作成失敗");if(data==null)dirs.put(path,u);else try(OutputStream o=cr.openOutputStream(u,"wt")){if(o==null)throw new IOException("書込失敗");o.write(data);}
  }
 }
 static void durable(File p,byte[]data)throws Exception{try(FileOutputStream o=new FileOutputStream(p)){o.write(data);o.getFD().sync();}}
 void replace(String tid,Uri root,byte[]data,String expected)throws Exception{
  Bundle b=Bundle.decode(data);if(!b.hasFile())throw new IOException("空の受信セーブは反映しません");
  byte[]old=snapshot(root);if(!Bundle.hex(Bundle.hash(old)).equals(expected))throw new IOException("Androidセーブが変化しました。再試行してください");
  File dir=new File(c.getFilesDir(),"backups/"+tid);if(!dir.isDirectory()&&!dir.mkdirs())throw new IOException("バックアップ作成失敗");
  File undo=new File(dir,System.currentTimeMillis()+".sbpk");durable(undo,old);
  File journal=new File(c.getFilesDir(),"recovery.json");if(journal.exists())throw new IOException("未復旧の記録があります");
  JSONObject j=new JSONObject();j.put("grant",grant.toString());j.put("root",root.toString());j.put("backup",undo.getAbsolutePath());durable(journal,j.toString().getBytes("UTF-8"));
  try{clear(root);write(root,data);if(!Arrays.equals(Bundle.hash(snapshot(root)),Bundle.hash(data)))throw new IOException("反映後の検証失敗");if(!journal.delete())throw new IOException("記録の確定失敗");}
  catch(Exception e){try{clear(root);write(root,old);if(!Arrays.equals(Bundle.hash(snapshot(root)),Bundle.hash(old)))throw new IOException("復旧検証失敗");journal.delete();}catch(Exception rollback){throw new IOException("自動復旧に失敗。アプリを再起動して復旧してください。バックアップは保存済み",rollback);}throw e;}
 }
 static byte[] read(File f)throws Exception{if(f.length()>Bundle.LIMIT)throw new IOException("容量超過");try(InputStream i=new FileInputStream(f)){ByteArrayOutputStream o=new ByteArrayOutputStream();byte[]b=new byte[32768];int n;while((n=i.read(b))!=-1)o.write(b,0,n);return o.toByteArray();}}
 static void recover(Context c)throws Exception{
  File j=new File(c.getFilesDir(),"recovery.json");if(!j.exists())return;JSONObject o=new JSONObject(new String(read(j),"UTF-8"));TreeStore t=new TreeStore(c,Uri.parse(o.getString("grant")));Uri root=Uri.parse(o.getString("root"));byte[]data=read(new File(o.getString("backup")));Bundle.decode(data);t.clear(root);t.write(root,data);if(!Arrays.equals(Bundle.hash(t.snapshot(root)),Bundle.hash(data)))throw new IOException("復旧検証失敗");if(!j.delete())throw new IOException("復旧記録の解除失敗");
 }
}
