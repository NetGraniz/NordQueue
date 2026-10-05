package com.nordfjell.nordqueue;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public final class BanStorageTest {
    private static int passed;
    private static final List<Path> directories=new ArrayList<>();
    interface Checked {void run() throws Exception;}
    private static void test(String name,Checked operation)throws Exception{operation.run();passed++;System.out.println("PASS: "+name);}
    private static Path file()throws IOException{Path dir=Files.createTempDirectory("nordqueue-store-test-");directories.add(dir);return dir.resolve("bans.properties");}
    private static SuspendedBan ban(String name){return new SuspendedBan(name,System.currentTimeMillis()+3600000,"Тестовый бан");}
    private static BanInbox.Change put(SuspendedBan ban){return new BanInbox.Change(ban.playerName(),ban);}
    private static BanInbox.Change remove(String name){return new BanInbox.Change(name,null);}
    private static String encode(String value){return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));}
    private static String line(SuspendedBan ban){return ban.expiresAtMillis()+"|"+encode(ban.playerName())+"|"+encode(ban.reason());}
    private static void ioFails(Checked operation)throws Exception{try{operation.run();throw new AssertionError("IOException expected");}catch(IOException expected){}}
    public static void main(String[] args)throws Exception {
        try {
            test("legacy file, case-insensitive lookups, Unicode roundtrip",()->{
                Path p=file();SuspendedBan b=ban("Player");Files.writeString(p,"player="+line(b)+"\n");
                SuspendedBanStore store=new SuspendedBanStore(p);store.load();assert store.active("PLAYER").orElseThrow().equals(b);
                store.remove("Player");SuspendedBanStore reload=new SuspendedBanStore(p);reload.load();assert reload.active("player").isEmpty();
            });
            test("failed BAN and UNBAN do not publish partial state",()->{
                AtomicBoolean fail=new AtomicBoolean();AtomicInteger writes=new AtomicInteger();
                SuspendedBanStore store=new SuspendedBanStore(file(),p->{if(fail.get())throw new IOException("injected");writes.incrementAndGet();});
                SuspendedBan b=ban("One");store.put(b);fail.set(true);ioFails(()->store.remove("One"));assert store.active("One").orElseThrow().equals(b);
                ioFails(()->store.put(ban("Two")));assert store.active("Two").isEmpty();assert writes.get()==1;
            });
            test("failed batch is all-or-nothing",()->{
                AtomicBoolean fail=new AtomicBoolean();SuspendedBanStore store=new SuspendedBanStore(file(),p->{if(fail.get())throw new IOException("injected");});
                SuspendedBan original=ban("Old");store.put(original);fail.set(true);
                ioFails(()->store.apply(List.of(remove("Old"),put(ban("New"))),false));
                assert store.active("Old").isPresent();assert store.active("New").isEmpty();
            });
            test("identical BAN and absent UNBAN are disk-idempotent",()->{
                AtomicInteger writes=new AtomicInteger();SuspendedBanStore store=new SuspendedBanStore(file(),p->writes.incrementAndGet());
                SuspendedBan b=ban("Player");store.put(b);store.put(b);store.remove("Absent");assert writes.get()==1;
            });
            test("corrupt load does not publish even valid earlier rows",()->{
                Path p=file();SuspendedBanStore store=new SuspendedBanStore(p);store.load();SuspendedBan b=ban("Original");store.put(b);
                Files.writeString(p,"valid="+line(ban("Valid"))+"\ninvalid=broken\n");ioFails(store::load);
                assert store.active("Original").isPresent();assert store.active("Valid").isEmpty();
            });
            test("duplicate property keys fail strict load",()->{
                Path p=file();String row="player="+line(ban("Player"))+"\n";Files.writeString(p,row+row);ioFails(()->new SuspendedBanStore(p).load());
            });
            test("normalized duplicate accounts fail strict load",()->{
                Path p=file();String value=line(ban("Player"));Files.writeString(p,"Player="+value+"\nplayer="+value+"\n");ioFails(()->new SuspendedBanStore(p).load());
            });
            test("mismatched key/name fails load",()->{
                Path p=file();Files.writeString(p,"wrong="+line(ban("Right"))+"\n");ioFails(()->new SuspendedBanStore(p).load());
            });
            test("invalid UTF-8 and invalid escaped properties fail load",()->{
                Path p=file();Files.writeString(p,"player=123|"+encode("Player")+"|_w\n");ioFails(()->new SuspendedBanStore(p).load());
                Files.writeString(p,"player=\\uQQQQ\n");ioFails(()->new SuspendedBanStore(p).load());
            });
            test("expired corrupt records are still validated",()->{
                Path p=file();Files.writeString(p,"bad=1|invalid*|invalid*\n");ioFails(()->new SuspendedBanStore(p).load());
            });
            test("control characters and excessive reason rejected",()->{
                SuspendedBanStore store=new SuspendedBanStore(file());store.load();
                ioFails(()->store.put(new SuspendedBan("Player",1,"bad\nlog")));
                ioFails(()->store.put(new SuspendedBan("Player",1,"a".repeat(257))));
            });
            test("strict file-size bound",()->{
                Path p=file();try(RandomAccessFile f=new RandomAccessFile(p.toFile(),"rw")){f.setLength(16*1024*1024+1L);}
                ioFails(()->new SuspendedBanStore(p).load());
            });
            test("atomic batch replacement and no leftover temp files",()->{
                Path p=file();SuspendedBanStore store=new SuspendedBanStore(p);store.load();
                store.apply(List.of(put(ban("One")),put(ban("Two"))),false);store.remove("One");
                SuspendedBanStore reload=new SuspendedBanStore(p);reload.load();assert reload.active("One").isEmpty();assert reload.active("Two").isPresent();
                try(var paths=Files.list(p.getParent())){assert paths.count()==1;}
            });
            test("1000 updates commit in four bounded writes; repeated baseline writes nothing",()->{
                AtomicInteger writes=new AtomicInteger();SuspendedBanStore store=new SuspendedBanStore(file(),p->writes.incrementAndGet());
                BanInbox inbox=new BanInbox(store,4096,256);List<SuspendedBan> records=new ArrayList<>();
                for(int i=0;i<1000;i++){SuspendedBan b=ban("Player"+i);records.add(b);assert inbox.offer(put(b));}
                assert !inbox.ready();for(int i=0;i<4;i++)inbox.flush(i);assert inbox.ready();assert writes.get()==4;
                for(SuspendedBan b:records)assert inbox.offer(put(b));inbox.flush(10);assert writes.get()==4;assert inbox.ready();
            });
            test("latest account value coalesces before a write",()->{
                AtomicInteger writes=new AtomicInteger();SuspendedBanStore store=new SuspendedBanStore(file(),p->writes.incrementAndGet());
                BanInbox inbox=new BanInbox(store,8,8);SuspendedBan b=ban("Player");
                inbox.offer(put(b));inbox.offer(remove("Player"));assert inbox.pendingCount()==1;inbox.flush(0);assert inbox.ready();assert writes.get()==0;
            });
            test("pending BAN restricts immediately, pending UNBAN keeps committed ban",()->{
                SuspendedBanStore store=new SuspendedBanStore(file(),p->{});BanInbox inbox=new BanInbox(store,8,8);SuspendedBan b=ban("Player");
                inbox.offer(put(b));assert inbox.active("Player").isPresent();assert store.active("Player").isEmpty();inbox.flush(0);
                inbox.offer(remove("Player"));assert inbox.active("Player").isPresent();assert !inbox.ready();inbox.flush(1);assert inbox.active("Player").isEmpty();
            });
            test("failed UNBAN holds admission, retries only after five seconds, then recovers",()->{
                AtomicBoolean fail=new AtomicBoolean();AtomicInteger calls=new AtomicInteger();
                SuspendedBanStore store=new SuspendedBanStore(file(),p->{calls.incrementAndGet();if(fail.get())throw new IOException("injected");});
                store.put(ban("Player"));BanInbox inbox=new BanInbox(store,8,8);inbox.offer(remove("Player"));fail.set(true);inbox.flush(0);
                assert !inbox.ready();assert inbox.pendingCount()==1;assert inbox.active("Player").isPresent();assert inbox.problem().equals("IOException");
                inbox.flush(4999999999L);assert calls.get()==2;fail.set(false);inbox.flush(5000000000L);
                assert inbox.ready();assert inbox.active("Player").isEmpty();assert calls.get()==3;
            });
            test("bounded overflow stays fail-closed after accepted items commit",()->{
                BanInbox inbox=new BanInbox(new SuspendedBanStore(file(),p->{}),2,2);
                assert inbox.offer(put(ban("One")));assert inbox.offer(put(ban("Two")));assert !inbox.offer(put(ban("Three")));
                assert inbox.pendingCount()==2;inbox.flush(0);assert !inbox.ready();assert inbox.problem().equals("inbox-overflow");
            });
            test("newer UNBAN arriving during slow BAN persistence is not acknowledged early",()->{
                CountDownLatch started=new CountDownLatch(1),release=new CountDownLatch(1);
                SuspendedBanStore store=new SuspendedBanStore(file(),p->{started.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new IOException("timeout");}catch(InterruptedException e){throw new IOException(e);}});
                BanInbox inbox=new BanInbox(store,8,8);inbox.offer(put(ban("Player")));
                Thread writer=new Thread(()->inbox.flush(0));writer.start();assert started.await(5,TimeUnit.SECONDS);
                try {
                    assert inbox.offer(remove("Player"));assert !inbox.ready();assert inbox.pendingCount()==1;
                }finally{release.countDown();}
                writer.join(5000);assert !writer.isAlive();assert inbox.pendingCount()==1;assert !inbox.ready();assert store.active("Player").isPresent();
                inbox.flush(1);assert inbox.ready();assert store.active("Player").isEmpty();
            });
            test("expiry reads ignore elapsed bans before cleanup persistence",()->{
                SuspendedBanStore store=new SuspendedBanStore(file(),p->{});store.put(new SuspendedBan("Expired",1,"expired"));
                assert store.active("Expired").isEmpty();BanInbox inbox=new BanInbox(store,8,8);inbox.flush(0);assert inbox.ready();
            });
            test("closed inbox never admits or accepts more updates",()->{
                BanInbox inbox=new BanInbox(new SuspendedBanStore(file(),p->{}),8,8);inbox.offer(put(ban("Player")));inbox.close();
                assert !inbox.offer(put(ban("Another")));assert !inbox.ready();inbox.flush(0);assert inbox.pendingCount()==0;
            });
            test("concurrent submissions/readers preserve bounded state and latest identity",()->{
                SuspendedBanStore store=new SuspendedBanStore(file(),p->{});BanInbox inbox=new BanInbox(store,4096,256);
                ExecutorService pool=Executors.newFixedThreadPool(4);List<Future<?>> tasks=new ArrayList<>();
                try {
                    for(int worker=0;worker<4;worker++){int base=worker*250;tasks.add(pool.submit(()->{for(int i=base;i<base+250;i++){assert inbox.offer(put(ban("Concurrent"+i)));inbox.active("Concurrent"+i);}}));}
                    for(Future<?> task:tasks)task.get(5,TimeUnit.SECONDS);
                    while(inbox.pendingCount()>0)inbox.flush(System.nanoTime());assert inbox.ready();
                    for(int i=0;i<1000;i++)assert store.active("Concurrent"+i).isPresent();
                }finally{pool.shutdownNow();}
            });
            System.out.println("ALL "+passed+" BAN STORAGE SCENARIOS PASSED");
        } finally {
            for(Path directory:directories){try(var paths=Files.list(directory)){for(Path p:paths.toList())Files.delete(p);}Files.delete(directory);}
        }
    }
}
