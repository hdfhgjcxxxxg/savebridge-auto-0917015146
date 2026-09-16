package com.savebridge.multisync;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public class MainActivity extends Activity {
 static final int ROOT=20,EXPORT=21;
 EditText host,pin;TextView state,folder;LinearLayout rows;Button choose,scan,start;Uri grant;
 final ExecutorService worker=Executors.newSingleThreadExecutor();final Handler handler=new Handler(Looper.getMainLooper());
 final List<Row> games=new ArrayList<>();final Map<String,Conflict> conflicts=new HashMap<>();final Map<String,Conflict> choices=new HashMap<>();
 volatile boolean running=false,foreground=false,busy=false;volatile long generation=0;SharedPreferences prefs;
 static class Row {Peer.Game game;Uri root;CheckBox check;TextView status;Button resolve;Row(Peer.Game g,Uri r){game=g;root=r;}}
 static class Conflict {String local,remote,action;Conflict(String l,String r,String a){local=l;remote=r;action=a;}}
 final Runnable loop=()->cycle();
 @Override public void onCreate(android.os.Bundle b){super.onCreate(b);prefs=getSharedPreferences("multi3",0);ui();String s=prefs.getString("root","");if(!s.isEmpty()){grant=Uri.parse(s);folder.setText("保存先を設定済み：自動検出を開始します");}host.setText(prefs.getString("host",""));pin.setText(prefs.getString("pin",""));worker.execute(()->{try{TreeStore.recover(this);}catch(Exception e){show("復旧が必要："+e.getMessage());}});if(!s.isEmpty()&&!host.getText().toString().trim().isEmpty()&&pin.getText().toString().matches("[0-9]{8}"))handler.postDelayed(this::discover,600);}
 @Override public void onResume(){super.onResume();foreground=true;}
 @Override public void onPause(){foreground=false;stop();super.onPause();}
 @Override public void onDestroy(){stop();worker.shutdown();super.onDestroy();}
 void ui(){ScrollView sc=new ScrollView(this);LinearLayout box=new LinearLayout(this);box.setOrientation(1);box.setPadding(24,48,24,40);box.setBackgroundColor(Color.rgb(245,245,240));sc.addView(box);
 box.addView(text("SaveBridge Multi",28));box.addView(text("3DS ↔ Azahar  ·  0.3 試作版",15));
 box.addView(text("ゲームを両端末で終了し、3DSで専用CIAを開いてください。",15));
 host=input("3DSのIPアドレス");pin=input("CIAに表示された8桁PIN");pin.setInputType(2);box.addView(host);box.addView(pin);
 choose=button("① Azaharのユーザーフォルダを選ぶ",v->{if(!busy)startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION),ROOT);});box.addView(choose);
 folder=text("一度選べばゲームのセーブを自動検索します",14);box.addView(folder);
 scan=button("② ゲームを自動検出・再検出",v->discover());box.addView(scan);
 box.addView(button("対応ゲームをすべて選択",v->{Set<String>seen=new HashSet<>();for(Row r:games)if(r.root!=null&&seen.add(r.game.tid))r.check.setChecked(true);}));
 rows=new LinearLayout(this);rows.setOrientation(1);box.addView(rows);
 start=button("③ 選択したゲームの自動同期を開始",v->{if(running){stop();return;}if(busy)return;new AlertDialog.Builder(this).setTitle("ゲームの終了を確認")
 .setMessage("Azaharでゲームを停止してください。同期中はゲームを起動しないでください。CIAとこの画面を開いている間、選択したゲームを順に同期します。初回や競合時は使う側を選びます。")
 .setNegativeButton("戻る",null).setPositiveButton("ゲーム終了済み・開始",(d,w)->begin()).show();});box.addView(start);
 box.addView(button("バックアップをZIPへ書き出す",v->{if(!busy)startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/zip").putExtra(Intent.EXTRA_TITLE,"SaveBridge-backups.zip"),EXPORT);}));
 state=text("同じWi-Fiに接続してください。独自CIA専用です。",15);box.addView(state);setContentView(sc);
 }
 boolean valid(){if(grant==null){show("保存先フォルダを選択してください");return false;}if(!host.getText().toString().trim().matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")||!pin.getText().toString().matches("[0-9]{8}")){show("CIAのIPと8桁PINを入力してください");return false;}return true;}
 void save(){prefs.edit().putString("host",host.getText().toString().trim()).putString("pin",pin.getText().toString()).apply();}
 void show(String s){handler.post(()->state.setText(s));}
 void controls(boolean on){host.setEnabled(on);pin.setEnabled(on);choose.setEnabled(on);scan.setEnabled(on);}
 void discover(){if(busy||running||!valid())return;save();busy=true;controls(false);show("保存先を検索し、CIAのゲーム名と照合しています…");final Uri g=grant;final Peer peer=new Peer(host.getText().toString().trim(),pin.getText().toString());
 worker.execute(()->{try{TreeStore.recover(this);Map<String,Uri>found=new TreeStore(this,g).scan();List<Peer.Game>remote=peer.list();handler.post(()->{games.clear();rows.removeAllViews();conflicts.clear();choices.clear();for(Peer.Game game:remote){Row row=new Row(game,found.get(game.tid));games.add(row);add(row);}show("Androidのセーブ "+found.size()+"件 / CIAのゲーム "+remote.size()+"件。両方にセーブがあるゲームを選択できます。");});}catch(Exception e){show("検出失敗："+e.getMessage());}finally{handler.post(()->{busy=false;controls(true);});}});
 }
 void add(Row r){LinearLayout b=new LinearLayout(this);b.setOrientation(1);b.setPadding(8,20,8,14);r.check=new CheckBox(this);r.check.setText(r.game.name+"\n"+r.game.tid+(r.game.media==1?" · SD":" · カートリッジ"));r.check.setEnabled(r.root!=null);r.check.setChecked(r.root!=null&&prefs.getBoolean("selected:"+r.game.id(),false));
 r.check.setOnCheckedChangeListener((v,on)->{prefs.edit().putBoolean("selected:"+r.game.id(),on).apply();if(on)for(Row o:games)if(o!=r&&o.check!=null&&o.game.tid.equals(r.game.tid))o.check.setChecked(false);});b.addView(r.check);
 r.status=text(r.root==null?"Azahar側にセーブなし：一度ゲーム内で保存して再検出":"待機",13);b.addView(r.status);
 r.resolve=button("初回・競合：使うセーブを選ぶ",v->resolve(r));r.resolve.setVisibility(View.GONE);b.addView(r.resolve);rows.addView(b);}
 void resolve(Row r){Conflict c=conflicts.get(r.game.id());if(c==null)return;new AlertDialog.Builder(this).setTitle(r.game.name).setMessage("両方のセーブが異なります。使う側を選択してください。反映先の現在のセーブはバックアップされます。")
 .setNegativeButton("保留",null).setNeutralButton("Androidを使う",(d,w)->{choices.put(r.game.id(),new Conflict(c.local,c.remote,"push"));r.status.setText("次の同期でAndroid側を使用");})
 .setPositiveButton("3DSを使う",(d,w)->{choices.put(r.game.id(),new Conflict(c.local,c.remote,"pull"));r.status.setText("次の同期で3DS側を使用");}).show();}
 void begin(){if(!valid()||games.isEmpty()){show("先にゲームを検出してください");return;}boolean selected=false;for(Row r:games)selected|=r.check.isChecked();if(!selected){show("同期するゲームを選んでください");return;}save();running=true;generation++;controls(false);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);start.setText("自動同期を停止");cycle();}
 void stop(){running=false;generation++;handler.removeCallbacks(loop);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);if(start!=null){start.setText("③ 選択したゲームの自動同期を開始");if(!busy)controls(true);}}
 boolean active(long gen){return running&&foreground&&gen==generation;}
 String baseKey(Row r,String ip,Uri root){return "baseline:"+ip+":"+root+":"+r.game.id();}
 void cycle(){if(!running||!foreground||busy)return;busy=true;final long gen=generation;final Uri rootGrant=grant;String ip=host.getText().toString().trim();final Peer peer=new Peer(ip,pin.getText().toString());final List<Row>selected=new ArrayList<>();for(Row r:games)if(r.root!=null&&r.check.isChecked())selected.add(r);final Map<String,Conflict>resolutions=new HashMap<>(choices);choices.clear();
 worker.execute(()->{try{TreeStore.recover(this);TreeStore store=new TreeStore(this,rootGrant);
 for(Row r:selected){if(!active(gen))break;try{
 byte[]local=store.snapshot(r.root),remote=peer.get(r.game);Bundle l=Bundle.decode(local),d=Bundle.decode(remote);if(!l.hasFile()||!d.hasFile())throw new IOException("空セーブを検出。両端末で一度ゲーム内セーブしてください");
 String lh=Bundle.hex(Bundle.hash(local)),rh=Bundle.hex(Bundle.hash(remote)),key=baseKey(r,ip,rootGrant),base=prefs.getString(key,"");String action=Bundle.decide(base,lh,rh);Conflict choice=resolutions.get(r.game.id());if(choice!=null&&choice.local.equals(lh)&&choice.remote.equals(rh))action=choice.action;
 final String conflictLocal=lh,conflictRemote=rh;
 if(action.equals("conflict")){handler.post(()->{conflicts.put(r.game.id(),new Conflict(conflictLocal,conflictRemote,""));r.resolve.setVisibility(View.VISIBLE);r.status.setText(base.isEmpty()?"初回：使う側を選んでください":"両側に変更：上書き停止");});continue;}
 if(!active(gen))break;
 // A second full snapshot catches concurrent writes before touching either destination.
 if(!Arrays.equals(Bundle.hash(store.snapshot(r.root)),Bundle.hash(local)))throw new IOException("Androidの保存が進行中：保留");
 if(action.equals("push")){if(!active(gen))break;peer.put(r.game,Bundle.hash(remote),local);rh=lh;}
 else if(action.equals("pull")){if(!active(gen))break;store.replace(r.game.tid,r.root,remote,lh);lh=rh;}
 final String done=lh;if(!prefs.edit().putString(key,done).commit())throw new IOException("同期記録の保存失敗");
 handler.post(()->{conflicts.remove(r.game.id());r.resolve.setVisibility(View.GONE);r.status.setText("同期済み "+new java.text.SimpleDateFormat("HH:mm:ss",Locale.JAPAN).format(new Date()));});
 }catch(Exception e){handler.post(()->r.status.setText("保留・再試行："+e.getMessage()));}}
 show("確認完了。15秒後に再確認します。");
 }catch(Exception e){show("同期停止："+e.getMessage());handler.post(this::stop);}finally{handler.post(()->{busy=false;if(active(gen))handler.postDelayed(loop,15000);else controls(true);});}});
 }
 @Override protected void onActivityResult(int req,int result,Intent data){super.onActivityResult(req,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;
 if(req==ROOT){try{grant=data.getData();getContentResolver().takePersistableUriPermission(grant,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));prefs.edit().putString("root",grant.toString()).apply();games.clear();rows.removeAllViews();folder.setText("保存先を設定しました。自動検出してください。");}catch(Exception e){show("アクセス許可を取得できません："+e.getMessage());}}
 if(req==EXPORT){Uri out=data.getData();worker.execute(()->{try(OutputStream os=getContentResolver().openOutputStream(out);ZipOutputStream z=new ZipOutputStream(os)){export(z,new File(getFilesDir(),"backups"),"");show("バックアップを書き出しました");}catch(Exception e){show("書出失敗："+e.getMessage());}});}}
 void export(ZipOutputStream z,File dir,String base)throws Exception{File[]files=dir.listFiles();if(files==null)return;for(File f:files){if(f.isDirectory())export(z,f,base+f.getName()+"/");else{z.putNextEntry(new ZipEntry(base+f.getName()));try(InputStream in=new FileInputStream(f)){byte[]b=new byte[32768];int n;while((n=in.read(b))!=-1)z.write(b,0,n);}z.closeEntry();}}}
 TextView text(String s,int size){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(Color.rgb(25,25,25));t.setPadding(4,10,4,10);return t;}
 EditText input(String hint){EditText e=new EditText(this);e.setSingleLine(true);e.setHint(hint);return e;}
 Button button(String s,View.OnClickListener listener){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setOnClickListener(listener);return b;}
}
