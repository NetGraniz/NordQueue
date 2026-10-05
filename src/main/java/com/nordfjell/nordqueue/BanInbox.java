package com.nordfjell.nordqueue;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Bounded, latest-per-account inbox. No disk/Player calls while holding its monitor. */
final class BanInbox {
    record Change(String name,SuspendedBan ban) {}
    private final SuspendedBanStore store;
    private final int capacity,batchSize;
    private final LinkedHashMap<String,Change> pending=new LinkedHashMap<>();
    private boolean closed,flushing,overloaded;
    private Exception failure;
    private long retryAt=Long.MIN_VALUE,maintenanceAt=Long.MIN_VALUE;
    BanInbox(SuspendedBanStore store,int capacity,int batchSize) {
        if(capacity<1 || batchSize<1 || batchSize>capacity)throw new IllegalArgumentException("Invalid inbox limits");
        this.store=store;this.capacity=capacity;this.batchSize=batchSize;
    }
    synchronized boolean offer(Change change) {
        if(closed || overloaded)return false;
        String key=SuspendedBanStore.normalize(change.name());
        if(!SuspendedBanStore.validName(change.name()))throw new IllegalArgumentException("Invalid account name");
        try{if(change.ban()!=null)SuspendedBanStore.validate(change.ban());}
        catch(IOException e){throw new IllegalArgumentException("Invalid ban",e);}
        if(change.ban()!=null && !key.equals(SuspendedBanStore.normalize(change.ban().playerName())))
            throw new IllegalArgumentException("Ban name/key mismatch");
        Change old=pending.get(key);
        if(old!=null && old.equals(change))return true;
        if(old==null && store.matches(change))return true;
        if(old==null && pending.size()>=capacity){overloaded=true;return false;}
        pending.put(key,change);return true;
    }
    synchronized Optional<SuspendedBan> active(String name) {
        Change change=pending.get(SuspendedBanStore.normalize(name));
        // A pending BAN is already restrictive. A pending UNBAN cannot release a committed ban.
        if(change!=null && change.ban()!=null && change.ban().active(System.currentTimeMillis()))return Optional.of(change.ban());
        return store.active(name);
    }
    synchronized boolean ready(){return !closed && !overloaded && failure==null && !flushing && pending.isEmpty();}
    synchronized String problem(){return overloaded ? "inbox-overflow" : failure==null ? "" : failure.getClass().getSimpleName();}
    synchronized int pendingCount(){return pending.size();}
    void flush(long now) {
        List<Change> batch;boolean maintenance;
        synchronized(this) {
            if(closed || flushing || now<retryAt)return;
            maintenance=now>=maintenanceAt;
            if(pending.isEmpty() && !maintenance)return;
            batch=pending.values().stream().limit(batchSize).toList();flushing=true;
        }
        try {
            store.apply(batch,maintenance);
            synchronized(this) {
                for(Change change:batch) {
                    String key=SuspendedBanStore.normalize(change.name());
                    if(pending.get(key)==change)pending.remove(key); // Do not acknowledge a newer value for this account.
                }
                failure=null;retryAt=Long.MIN_VALUE;
                if(maintenance)maintenanceAt=now+TimeUnit.MINUTES.toNanos(1);
            }
        }catch(Exception exception) {
            synchronized(this){failure=exception;retryAt=now+TimeUnit.SECONDS.toNanos(5);}
        }finally{synchronized(this){flushing=false;}}
    }
    synchronized void close(){closed=true;pending.clear();}
}
